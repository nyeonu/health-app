package com.ny.my.health

import com.ny.my.health.core.*
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistorySessionState(
    val read: HistoryRead,
    val operation: HistoryOperation,
    val deleteDialog: Boolean,
    val deleteFailed: Boolean,
    val retainedAfterDeleteFailure: Boolean,
    val error: HistoryError?,
)
enum class HistoryOperation { IDLE, LOADING, WRITING, DELETING }
enum class HistoryError { READ, WRITE, DELETE, RECOVERY_WRITE }

/** 단일 세션의 도착 시점 검사를 직렬화한다. 다중 프로세스 잠금을 제공하지 않는다. */
internal class HistorySession(
    private val store: HistoryStore,
    private val scope: CoroutineScope,
    private val invalidateCredentials: () -> Unit,
) {
    private val guard = Any()
    private val mutableState = MutableStateFlow(HistorySessionState(
        HistoryRead(HistoryReadStatus.UNAVAILABLE, null), HistoryOperation.IDLE, false, false, false, null,
    ))
    val state: StateFlow<HistorySessionState> = mutableState.asStateFlow()
    private var restored = false
    private var generation = 0L
    private var livePhase: HistoryPhase? = null

    // 모든 자격 판단은 저장소 호출 밖에서 도착 즉시 끝낸다.
    private fun idle() = scope.isActive && state.value.operation == HistoryOperation.IDLE
    private fun readable() = state.value.read.status in setOf(HistoryReadStatus.READY, HistoryReadStatus.ABSENT)
    private fun allowed() = restored && readable() && state.value.error == null && !state.value.deleteFailed && !state.value.deleteDialog
    private fun current(ticket: Long, phase: HistoryPhase) = idle() && allowed() && ticket == generation && livePhase == phase
    private fun deletable() = idle() && livePhase !in setOf(HistoryPhase.AUTHORIZING, HistoryPhase.RUNNING)
    private fun update(change: (HistorySessionState) -> HistorySessionState) { mutableState.value = change(state.value) }
    private fun history() = state.value.read.history ?: SyncHistory(SyncHistoryCodec.VERSION, null, null)

    suspend fun restore() {
        synchronized(guard) {
            if (!idle() || restored) return
            update { it.copy(operation = HistoryOperation.LOADING) }
        }
        load()
    }

    suspend fun reload() {
        synchronized(guard) {
            if (!deletable() || state.value.deleteFailed) return
            invalidateCredentials()
            generation++
            livePhase = null
            update { it.copy(operation = HistoryOperation.LOADING, deleteDialog = false) }
        }
        load()
    }

    private suspend fun load() {
        try {
            val read = store.load().frozen()
            val recovered = read.history?.recover()
            synchronized(guard) {
                restored = true
                update { it.copy(read = read.copy(history = recovered), error = if (read.status in setOf(HistoryReadStatus.READY, HistoryReadStatus.ABSENT)) null else HistoryError.READ) }
            }
            if (recovered != null && recovered != read.history) {
                try { store.replace(recovered) } catch (error: Exception) {
                    synchronized(guard) { update { it.copy(error = HistoryError.RECOVERY_WRITE) } }
                    if (error is CancellationException) throw error
                }
            }
        } catch (error: Exception) {
            synchronized(guard) {
                restored = true
                update { it.copy(read = HistoryRead(HistoryReadStatus.UNAVAILABLE, null), error = HistoryError.READ) }
            }
            if (error is CancellationException) throw error
        } finally {
            synchronized(guard) { update { it.copy(operation = HistoryOperation.IDLE) } }
        }
    }

    suspend fun beginAuthorization(date: LocalDate, at: Instant): Long? {
        val pair = synchronized(guard) {
            if (!idle() || !allowed() || livePhase != null) return null
            generation++
            update { it.copy(operation = HistoryOperation.WRITING) }
            generation to history().copy(attempt = HistoryAttempt(date, at, null, null, HistoryPhase.AUTHORIZING, emptySet(), null, null))
        }
        return if (write(pair.second, HistoryPhase.AUTHORIZING)) pair.first else null
    }

    suspend fun authorizationReturned(ticket: Long): Boolean {
        val next = synchronized(guard) {
            if (!current(ticket, HistoryPhase.AUTHORIZING)) return false
            update { it.copy(operation = HistoryOperation.WRITING) }
            history().copy(attempt = history().attempt!!.copy(phase = HistoryPhase.AWAITING_UPLOAD))
        }
        return write(next, HistoryPhase.AWAITING_UPLOAD)
    }

    suspend fun authorizationFailed(ticket: Long, failure: AuthorizationDiagnostics.Failure, at: Instant) {
        val next = synchronized(guard) {
            if (!current(ticket, HistoryPhase.AUTHORIZING)) return
            update { it.copy(operation = HistoryOperation.WRITING) }
            history().complete(history().attempt!!.copy(phase = HistoryPhase.AUTHORIZATION_FAILED, finishedAt = at, failureCode = failure.name))
        }
        write(next, null)
    }

    suspend fun beginExecution(ticket: Long, date: LocalDate, at: Instant): Boolean {
        val next = synchronized(guard) {
            if (!current(ticket, HistoryPhase.AWAITING_UPLOAD) || history().attempt?.date != date) return false
            update { it.copy(operation = HistoryOperation.WRITING) }
            history().copy(attempt = history().attempt!!.copy(phase = HistoryPhase.RUNNING, startedAt = at))
        }
        return write(next, HistoryPhase.RUNNING)
    }

    suspend fun finishExecution(ticket: Long, result: SyncResult, coverage: Set<DataType>, at: Instant) {
        val next = synchronized(guard) {
            if (!current(ticket, HistoryPhase.RUNNING)) return
            val phase = when (result.status) {
                SyncStatus.SUCCESS -> HistoryPhase.SUCCESS
                SyncStatus.EMPTY -> HistoryPhase.EMPTY
                SyncStatus.PERMISSION_REQUIRED -> HistoryPhase.PERMISSION_REQUIRED
                SyncStatus.FAILED -> HistoryPhase.FAILED
            }
            val code = if (result.status == SyncStatus.FAILED) result.errorCode?.takeIf {
                it in setOf("READ_FAILED", "NORMALIZATION_FAILED", "EXPORT_FAILED", "UPLOAD_FAILED")
            } ?: "SYNC_FAILED" else null
            val completed = history().complete(history().attempt!!.copy(
                phase = phase, finishedAt = at, coverage = immutableCoverage(coverage), confirmedFiles = result.uploadedFiles, failureCode = code,
            ))
            update { it.copy(operation = HistoryOperation.WRITING) }
            completed
        }
        write(next, null)
    }

    suspend fun interruptExecution(ticket: Long, at: Instant) {
        val next = synchronized(guard) {
            if (!current(ticket, HistoryPhase.RUNNING)) return
            val completed = history().complete(history().attempt!!.copy(phase = HistoryPhase.INTERRUPTED, finishedAt = at, confirmedFiles = null))
            update { it.copy(operation = HistoryOperation.WRITING) }
            completed
        }
        write(next, null)
    }

    private suspend fun write(next: SyncHistory, phase: HistoryPhase?): Boolean {
        try {
            store.replace(next)
            synchronized(guard) {
                livePhase = phase
                update { it.copy(read = HistoryRead(HistoryReadStatus.READY, next), error = null) }
            }
            return true
        } catch (error: Exception) {
            synchronized(guard) {
                livePhase = null
                update { it.copy(error = HistoryError.WRITE) }
            }
            if (error is CancellationException) throw error
            return false
        } finally {
            synchronized(guard) { update { it.copy(operation = HistoryOperation.IDLE) } }
        }
    }

    fun requestDelete(): Boolean = synchronized(guard) {
        if (!deletable() || state.value.deleteFailed || state.value.deleteDialog) return false
        update { it.copy(deleteDialog = true) }
        true
    }

    fun cancelDelete() = synchronized(guard) {
        if (idle()) update { it.copy(deleteDialog = false) }
    }

    suspend fun confirmDelete() {
        synchronized(guard) {
            if (!deletable() || !state.value.deleteDialog) return
            prepareReset()
        }
        reset()
    }

    suspend fun retryDelete() {
        synchronized(guard) {
            if (!deletable() || !state.value.deleteFailed) return
            prepareReset()
        }
        reset()
    }

    private fun prepareReset() {
        // 인증과 준비된 작업은 첫 저장소 중단점보다 먼저 폐기한다.
        invalidateCredentials()
        generation++
        livePhase = null
        update { it.copy(operation = HistoryOperation.DELETING, deleteDialog = false) }
    }

    private suspend fun reset() {
        try {
            store.reset()
            synchronized(guard) {
                restored = true
                update { HistorySessionState(HistoryRead(HistoryReadStatus.READY, SyncHistory(1, null, null)), HistoryOperation.DELETING, false, false, false, null) }
            }
        } catch (error: Exception) {
            synchronized(guard) {
                update { it.copy(deleteFailed = true, retainedAfterDeleteFailure = readable() && (it.read.history?.attempt != null || it.read.history?.success != null), error = HistoryError.DELETE) }
            }
            if (error is CancellationException) throw error
        } finally {
            synchronized(guard) { update { it.copy(operation = HistoryOperation.IDLE) } }
        }
    }

    suspend fun keepExistingAfterDeleteFailure() {
        synchronized(guard) {
            if (!idle() || !state.value.deleteFailed) return
            generation++
            livePhase = null
            update { it.copy(operation = HistoryOperation.LOADING) }
        }
        load()
        synchronized(guard) {
            if (state.value.error == null) update { it.copy(deleteFailed = false, retainedAfterDeleteFailure = false) }
        }
    }
}

private fun immutableCoverage(coverage: Set<DataType>): Set<DataType> = Collections.unmodifiableSet(coverage.toSet())
private fun HistoryRead.frozen(): HistoryRead = copy(history = history?.let { value ->
    value.copy(attempt = value.attempt?.let { it.copy(coverage = immutableCoverage(it.coverage)) },
        success = value.success?.let { it.copy(coverage = immutableCoverage(it.coverage)) })
})

/** 실제 화면 모델이 사용하는 외부 효과 경계. 저장 실패 시 읽기와 업로드를 호출하지 않는다. */
internal class HistoryEffects(private val session: HistorySession) {
    suspend fun authorize(date: LocalDate, at: Instant, onReady: (Long) -> Unit): Boolean {
        val ticket = session.beginAuthorization(date, at) ?: return false
        onReady(ticket)
        return true
    }

    suspend fun acceptAuthorization(ticket: Long, acceptCredential: () -> Unit): Boolean {
        if (!session.authorizationReturned(ticket)) return false
        acceptCredential()
        return true
    }

    suspend fun execute(
        ticket: Long, date: LocalDate, at: Instant, now: () -> Instant,
        foreground: () -> Boolean,
        remoteConfirmed: (HistoryExecutionResult) -> Unit,
        effect: suspend (LocalDate) -> HistoryExecutionResult,
    ): HistoryExecutionResult? {
        // 시작 커밋과 종결 커밋은 전경 작업의 취소와 독립적으로 끝낸다.
        var started = false
        try {
            withContext(NonCancellable) { started = session.beginExecution(ticket, date, at) }
            if (!started) return null
            currentCoroutineContext().ensureActive()
            if (!foreground()) throw CancellationException("전경 실행 중단")
            val outcome = effect(date)
            remoteConfirmed(outcome)
            withContext(NonCancellable) {
                session.finishExecution(ticket, outcome.result, outcome.coverage, now())
            }
            return outcome
        } catch (cancelled: CancellationException) {
            if (started) withContext(NonCancellable) { session.interruptExecution(ticket, now()) }
            throw cancelled
        } catch (_: Exception) {
            val outcome = HistoryExecutionResult(SyncResult(SyncStatus.FAILED, errorCode = "SYNC_FAILED"), emptySet())
            if (started) withContext(NonCancellable) { session.finishExecution(ticket, outcome.result, outcome.coverage, now()) }
            return outcome
        }
    }
}

internal data class HistoryExecutionResult(val result: SyncResult, val coverage: Set<DataType>)


/** 외부 동의 결과의 상관관계는 같은 화면 모델의 메모리에만 존재한다. */
internal class AuthorizationResolutionTickets {
    private var pending: Long? = null

    fun register(ticket: Long): Boolean {
        if (pending != null) return false
        pending = ticket
        return true
    }

    fun consume(): Long? = pending.also { pending = null }
    fun clear() { pending = null }
}

/** 렌더러의 버튼 상태와 별개로 사용자 이벤트가 도착한 순간 검사한다. */
internal data class HistoryEntryGate(
    val pendingConsent: Boolean,
    val refreshing: Boolean,
    val syncing: Boolean,
    val history: HistorySessionState,
) {
    fun allowsDeletion(): Boolean = !pendingConsent && !refreshing && !syncing &&
        history.operation == HistoryOperation.IDLE

    fun allowsAuthorization(existingTicket: Long?): Boolean = allowsDeletion() && existingTicket == null &&
        history.error == null && !history.deleteFailed && !history.deleteDialog &&
        history.read.status in setOf(HistoryReadStatus.READY, HistoryReadStatus.ABSENT)
}
