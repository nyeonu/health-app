package com.ny.my.health

import android.content.Context
import com.ny.my.health.core.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class SyncSessionPhase { BEFORE_START, STARTED, RECEIPT_QUEUED, ENGINE_RETURNED, CLEANUP }

/** UI 수명과 무관한 경로별 admission이며 원격 쓰기의 취소 완료까지 소유권을 유지한다. */
internal class SyncExecutionSession(
    private val store: SqliteSyncStateStore,
    private val reader: ScopedHealthReader,
    private val engine: DestinationSync,
    private val clock: Clock,
    private val newId: () -> String,
    private val observer: suspend (SyncSessionPhase) -> Unit = {},
) {
    private class Owner(val generation: DestinationGeneration?) {
        var attemptId: String? = null
        var closing = false
    }
    private class Entry {
        val gate = Mutex()
        val control = Mutex()
        var owner: Owner? = null
        var recoveryNeeded = true
        val receipts = mutableMapOf<SyncTrigger, CompletableDeferred<BatchOutcome>>()
    }
    private val entry = synchronized(entries) {
        entries.getOrPut(store.database.databaseFile.canonicalPath) { Entry() }
    }

    suspend fun detectPending(): CoverageSummary {
        if (!entry.gate.tryLock()) return ready().coverage
        try {
            recover()
            val state = ready()
            val config = state.configuration ?: return state.coverage
            val access = reader.access(if (config.automatic) SyncTrigger.CATCH_UP else SyncTrigger.MANUAL)
            return selected(state.generation, access).coverage
        } finally { entry.gate.unlock() }
    }

    suspend fun run(trigger: SyncTrigger, generation: DestinationGeneration): BatchOutcome {
        if (!entry.gate.tryLock()) return merge(trigger, generation)
        val owner = Owner(generation)
        var outcome = result(BatchStatus.BLOCKED)
        var cleanupSucceeded = false
        var failure: Throwable? = null
        try {
            entry.control.withLock { entry.owner = owner }
            recover()
            val initial = ready()
            if (initial.generation != generation) return result(BatchStatus.STALE).also { outcome = it }
            val config = initial.configuration ?: return outcome
            val access = reader.access(trigger)
            val state = selected(generation, access)
            val key = SyncPlanner.scopeKey(config, access)
            if (key == null || key != state.scopeKey) return outcome
            val batch = SyncPlanner.plan(state, today(), access)
            if (batch.scopeKey != key) return outcome
            if (batch.dates.isEmpty()) {
                outcome = BatchOutcome(if (batch.blocked.isEmpty()) BatchStatus.PENDING else BatchStatus.BLOCKED, 0, state.coverage)
                return outcome
            }
            observer(SyncSessionPhase.BEFORE_START)
            currentCoroutineContext().ensureActive()
            entry.control.withLock {
                val now = clock.instant()
                val attempt = AttemptState(newId(), key, state.requestedScopes, trigger, now, now, null,
                    batch.dates, retryKey(generation, key, batch.dates), 0, entry.receipts.keys + trigger)
                val commit = store.transact(generation, mutation(attempt.id, start = attempt))
                if (commit.status != CommitStatus.COMMITTED) {
                    outcome = result(if (commit.status == CommitStatus.STALE) BatchStatus.STALE else BatchStatus.BLOCKED)
                    return outcome
                }
                owner.attemptId = attempt.id
                entry.receipts.values.forEach { it.complete(result(BatchStatus.MERGED)) }
                entry.receipts.clear()
            }
            observer(SyncSessionPhase.STARTED)
            outcome = engine.run(trigger, generation, requireNotNull(owner.attemptId))
            observer(SyncSessionPhase.ENGINE_RETURNED)
            return outcome
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    observer(SyncSessionPhase.CLEANUP)
                    entry.control.withLock {
                        owner.closing = true
                        // engine이 이미 종료한 시도에는 finish를 중복 기록하지 않는다.
                        val state = ready()
                        if (state.generation == generation && state.attempt?.id == owner.attemptId &&
                            state.attempt?.finishedAt == null) recoverAttempt(state)
                        val latestAttempt = ready().attempt
                        val unresolved = latestAttempt != null && latestAttempt.finishedAt == null
                        entry.recoveryNeeded = unresolved
                        cleanupSucceeded = !unresolved
                    }
                } catch (_: Exception) {
                    entry.recoveryNeeded = true
                    if (failure == null) error("동기화 저장소를 사용할 수 없습니다")
                } finally {
                    entry.control.withLock {
                        if (!cleanupSucceeded) entry.recoveryNeeded = true
                        entry.receipts.values.forEach { it.complete(result(
                            if (outcome.status == BatchStatus.STALE) BatchStatus.STALE else BatchStatus.BLOCKED)) }
                        entry.receipts.clear()
                        entry.owner = null
                    }
                    entry.gate.unlock()
                }
            }
        }
    }

    suspend fun invalidate(generation: DestinationGeneration): SyncCommit = store.invalidate(generation)

    private suspend fun merge(trigger: SyncTrigger, generation: DestinationGeneration): BatchOutcome {
        var receipt: CompletableDeferred<BatchOutcome>? = null
        val immediate = entry.control.withLock {
            val owner = entry.owner ?: return@withLock result(BatchStatus.PENDING)
            if (owner.generation != generation) return@withLock result(BatchStatus.STALE)
            if (owner.closing) return@withLock result(BatchStatus.PENDING)
            val state = ready()
            if (state.generation != generation) return@withLock result(BatchStatus.STALE)
            val id = owner.attemptId
            if (id == null) {
                receipt = entry.receipts.getOrPut(trigger) { CompletableDeferred() }
                return@withLock null
            }
            val attempt = state.attempt
            if (attempt?.id != id) return@withLock result(BatchStatus.STALE)
            val commit = store.transact(generation, mutation(id, pending = attempt.pendingTriggers + trigger))
            result(when (commit.status) {
                CommitStatus.COMMITTED -> BatchStatus.MERGED
                CommitStatus.STALE -> BatchStatus.STALE
                else -> BatchStatus.BLOCKED
            })
        }
        if (immediate != null) return immediate
        observer(SyncSessionPhase.RECEIPT_QUEUED)
        return requireNotNull(receipt).await()
    }

    private suspend fun recover() {
        if (!entry.recoveryNeeded) return
        val state = ready()
        try {
            recoverAttempt(state)
            entry.recoveryNeeded = false
        } catch (cancel: CancellationException) {
            entry.recoveryNeeded = true
            throw cancel
        } catch (_: Exception) {
            entry.recoveryNeeded = true
            error("동기화 저장소를 사용할 수 없습니다")
        }
    }

    private suspend fun recoverAttempt(state: SyncState) {
        val attempt = state.attempt?.takeIf { it.finishedAt == null } ?: return
        check(attempt.startedAt != null) { "동기화 저장소를 사용할 수 없습니다" }
        for (date in attempt.dates) {
            val day = store.loadDay(state.generation, date, attempt.scopeKey) ?: continue
            val recovered = day.afterInterruption()
            if (recovered != day) requireCommit(store.transact(state.generation, mutation(attempt.id, day = recovered)))
        }
        requireCommit(store.transact(state.generation, mutation(attempt.id, finish = clock.instant())))
    }

    private suspend fun ready(): SyncState = requireReady(store.load())
    private suspend fun selected(generation: DestinationGeneration, access: ReadAccess): SyncState =
        requireReady(store.loadForScope(generation, today(), access))
    private fun requireReady(read: SyncStateRead): SyncState {
        val state = read.state
        if (read.status == StateStatus.ABSENT) error("동기화가 설정되지 않았습니다")
        if (read.status != StateStatus.READY || state == null) error("동기화 저장소를 사용할 수 없습니다")
        return state
    }
    private fun requireCommit(commit: SyncCommit) {
        check(commit.status == CommitStatus.COMMITTED) { "동기화 저장소를 사용할 수 없습니다" }
    }
    private suspend fun retryKey(generation: DestinationGeneration, key: ReadScopeKey, dates: List<LocalDate>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val components = listOf(generation.value, key.value) + dates.sorted().flatMap {
            listOf(it.toString(), store.loadDay(generation, it, key)?.revision?.digest ?: "UNREAD")
        }
        for (component in components) {
            val bytes = component.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun mutation(id: String, start: AttemptState? = null, day: DayState? = null,
        pending: Set<SyncTrigger>? = null, finish: java.time.Instant? = null) = SyncMutation(id, start, day, null, pending, finish)
    private fun result(status: BatchStatus) = BatchOutcome(status, 0, null)

    companion object {
        private val entries = mutableMapOf<String, Entry>()
        private val sessions = java.util.WeakHashMap<Context, SyncExecutionSession>()
        fun get(context: Context, factory: () -> SyncExecutionSession): SyncExecutionSession = synchronized(sessions) {
            sessions.getOrPut(context.applicationContext) { factory() }
        }
    }
}
