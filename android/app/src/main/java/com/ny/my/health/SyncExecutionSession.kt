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
    private val engine: DestinationSync?,
    private val clock: Clock,
    private val newId: () -> String,
    private val observer: suspend (SyncSessionPhase) -> Unit = {},
    private val automaticSession: Boolean = false,
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

    suspend fun detectPending(trigger: SyncTrigger = SyncTrigger.MANUAL): CoverageSummary {
        require(if (automaticSession) trigger != SyncTrigger.MANUAL else trigger == SyncTrigger.MANUAL)
        if (!entry.gate.tryLock()) return ready().coverage
        try {
            recover()
            val state = ready()
            state.configuration ?: return state.coverage
            val access = reader.access(trigger)
            return selected(state.generation, access).coverage
        } finally { entry.gate.unlock() }
    }

    suspend fun run(trigger: SyncTrigger, generation: DestinationGeneration): BatchOutcome =
        runPrepared(trigger, generation) { checkNotNull(engine) { "동기화 실행 준비가 필요합니다" } }

    suspend fun runPrepared(
        trigger: SyncTrigger,
        generation: DestinationGeneration,
        prepare: suspend () -> DestinationSync,
    ): BatchOutcome {
        require(!automaticSession)
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
            if (config.automatic && trigger != SyncTrigger.MANUAL) return outcome
            // 실행 credential을 포함하는 engine은 gate 소유자의 지역 변수로만 유지한다.
            val preparedEngine = prepare()
            currentCoroutineContext().ensureActive()
            if (ready().generation != generation) return result(BatchStatus.STALE).also { outcome = it }
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
            outcome = preparedEngine.run(trigger, generation, requireNotNull(owner.attemptId))
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

    suspend fun runAutomaticPrepared(trigger: SyncTrigger, generation: DestinationGeneration,
        prepare: suspend () -> DestinationSync): BatchOutcome {
        require(automaticSession && trigger != SyncTrigger.MANUAL)
        if (!entry.gate.tryLock()) return merge(trigger, generation)
        val owner = Owner(generation)
        var claimed: AutomaticOwner? = null
        var outcome = result(BatchStatus.BLOCKED)
        var failure: Throwable? = null
        try {
            entry.control.withLock { entry.owner = owner }
            recover()
            val initial = ready()
            if (initial.generation != generation) return result(BatchStatus.STALE).also { outcome = it }
            val config = initial.configuration ?: return outcome
            if (!config.automatic) return outcome
            when (store.preflightAutomaticAdmission(generation, clock.instant())) {
                AutomaticPreflight.Stale -> return result(BatchStatus.STALE).also { outcome = it }
                is AutomaticPreflight.Deferred -> return result(BatchStatus.RETRYABLE).also { outcome = it }
                AutomaticPreflight.RecoveryRequired -> return outcome
                AutomaticPreflight.Ready -> Unit
            }
            val access = reader.access(trigger)
            val state = selected(generation, access)
            val key = SyncPlanner.scopeKey(config, access) ?: return outcome
            if (state.scopeKey != key || !config.categories.containsAll(state.requestedScopes.keys)) return outcome
            val budget = when (val read = store.loadAutomaticBudget(generation, key)) {
                AutomaticBudgetRead.Stale -> return result(BatchStatus.STALE).also { outcome = it }
                is AutomaticBudgetRead.Ready -> read.budget
            }
            if (budget?.status == RetryBudgetStatus.BLOCKED || budget?.status == RetryBudgetStatus.EXHAUSTED) return outcome
            val batch = SyncPlanner.plan(state, today(), access)
            val dates = budget?.dates ?: batch.dates
            if (dates.isEmpty()) return result(if (batch.blocked.isEmpty()) BatchStatus.PENDING else BatchStatus.BLOCKED).also { outcome = it }
            if (budget != null && (budget.requestedScopes != state.requestedScopes ||
                    dates.any { it < today().minusDays(30) || it < config.enrolledDay || it >= today() })) return outcome
            val id = newId()
            val proposal = if (budget == null) InitialAutomaticProposal.validated(generation, key, state.requestedScopes,
                dates, id, trigger, clock.instant(), AutomaticRetryModel.accessFingerprint(access), config.enrolledDay, today()) else null
            observer(SyncSessionPhase.BEFORE_START)
            currentCoroutineContext().ensureActive()
            val claim = entry.control.withLock {
                val value = store.claimAutomaticAttempt(generation, budget?.lineageId, budget?.claimVersion, proposal,
                    key, state.requestedScopes, dates, id, trigger, clock.instant())
                if (value is RetryClaim.Claimed) {
                    claimed = AutomaticOwner(value.budget.lineageId, id, value.budget.claimVersion)
                    owner.attemptId = id
                    if (entry.receipts.isNotEmpty()) requireCommit(store.transact(generation,
                        mutation(id, pending = value.attempt.pendingTriggers + entry.receipts.keys)))
                    entry.receipts.values.forEach { it.complete(result(BatchStatus.MERGED)) }
                    entry.receipts.clear()
                }
                value
            }
            when (claim) {
                is RetryClaim.Claimed -> Unit
                is RetryClaim.Deferred -> return result(BatchStatus.RETRYABLE).also { outcome = it }
                RetryClaim.Stale -> return result(BatchStatus.STALE).also { outcome = it }
                else -> return outcome
            }
            observer(SyncSessionPhase.STARTED)
            val fresh = reader.access(trigger)
            if (AutomaticRetryModel.accessFingerprint(fresh) != AutomaticRetryModel.accessFingerprint(access)) {
                finishAutomatic(generation, requireNotNull(claimed), AutomaticFinishOutcome.BLOCKED)
                claimed = null
                return outcome
            }
            val prepared = try { prepare() } catch (cancel: CancellationException) { throw cancel }
            catch (_: BoundDriveAuthorizationException) {
                finishAutomatic(generation, requireNotNull(claimed), AutomaticFinishOutcome.ACCOUNT_AUTHORIZATION_BLOCKED)
                claimed = null
                return outcome
            }
            val afterPrepare = ready()
            if (afterPrepare.generation != generation) return result(BatchStatus.STALE).also { outcome = it }
            if (afterPrepare.configuration?.automatic != true) return outcome
            outcome = prepared.run(trigger, generation, id, requireNotNull(claimed), finishAttempt = false)
            observer(SyncSessionPhase.ENGINE_RETURNED)
            val finish = when (outcome.status) {
                BatchStatus.COMPLETED -> AutomaticFinishOutcome.COMPLETED
                BatchStatus.RETRYABLE -> AutomaticFinishOutcome.RETRYABLE
                else -> AutomaticFinishOutcome.BLOCKED
            }
            finishAutomatic(generation, requireNotNull(claimed), finish)
            claimed = null
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
                        claimed?.let {
                            val recovered = store.recoverAutomaticAttempt(generation, it, clock.instant())
                            if (recovered.status != CommitStatus.STALE) requireCommit(recovered)
                        }
                        entry.recoveryNeeded = false
                    }
                } catch (_: Exception) {
                    entry.recoveryNeeded = true
                    if (failure == null) error("동기화 저장소를 사용할 수 없습니다")
                } finally {
                    entry.control.withLock {
                        entry.receipts.values.forEach { it.complete(result(if (outcome.status == BatchStatus.STALE) BatchStatus.STALE else BatchStatus.BLOCKED)) }
                        entry.receipts.clear(); entry.owner = null
                    }
                    entry.gate.unlock()
                }
            }
        }
    }

    private suspend fun finishAutomatic(generation: DestinationGeneration, owner: AutomaticOwner, outcome: AutomaticFinishOutcome) {
        requireCommit(store.finishAutomaticAttempt(generation, owner.lineageId, owner.attemptId, owner.claimVersion, outcome, clock.instant()))
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
        val budget = (store.loadAutomaticBudget(state.generation, attempt.scopeKey) as? AutomaticBudgetRead.Ready)?.budget
        if (budget?.ownerAttemptId == attempt.id) {
            requireCommit(store.recoverAutomaticAttempt(state.generation,
                AutomaticOwner(budget.lineageId, attempt.id, budget.claimVersion), clock.instant()))
            return
        }
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
