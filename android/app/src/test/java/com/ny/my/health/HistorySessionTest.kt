package com.ny.my.health

import com.ny.my.health.core.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** 가짜 저장소는 순서와 실패만 모델링한다. 실제 내구성은 SQLite 통합 테스트가 검증한다. */
class HistorySessionTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val at = Instant.parse("2026-10-30T12:00:00Z")
    private val success = HistorySuccess(date, at, setOf(DataType.STEPS), 1)
    private fun history(phase: HistoryPhase? = null) = SyncHistory(1, phase?.let {
        HistoryAttempt(date, at, if (it == HistoryPhase.RUNNING) at else null, null, it, emptySet(), null, null)
    }, success)

    private class Store(initial: HistoryRead) : HistoryStore {
        var saved = initial
        var failLoad = false
        var failWrite = false
        var failReset = false
        var writes = 0
        var resets = 0
        var loads = 0
        var gate: CompletableDeferred<Unit>? = null
        var onReset: () -> Unit = {}
        override suspend fun load(): HistoryRead {
            loads++
            gate?.await()
            check(!failLoad) { "합성 읽기 오류" }
            return saved
        }
        override suspend fun replace(history: SyncHistory) {
            writes++
            gate?.await()
            check(!failWrite) { "합성 쓰기 오류" }
            saved = HistoryRead(HistoryReadStatus.READY, history)
        }
        override suspend fun reset() {
            resets++
            onReset()
            gate?.await()
            check(!failReset) { "합성 삭제 오류" }
            saved = HistoryRead(HistoryReadStatus.READY, SyncHistory(1, null, null))
        }
    }
    private fun store(value: SyncHistory = history()) = Store(HistoryRead(HistoryReadStatus.READY, value))
    private class Credentials {
        var valid = true
        var invalidations = 0
        fun invalidate() { valid = false; invalidations++ }
    }
    private fun session(store: Store, scope: CoroutineScope, credentials: Credentials = Credentials()) =
        HistorySession(store, scope, credentials::invalidate)
    private suspend fun prepared(session: HistorySession): Long {
        val ticket = requireNotNull(session.beginAuthorization(date, at))
        assertTrue(session.authorizationReturned(ticket))
        return ticket
    }

    @Test
    fun `미완료 실행을 복원하면 재시도 없이 결과 미확정 중단을 저장한다`() = runBlocking {
        val store = store(history(HistoryPhase.RUNNING))
        val credentials = Credentials().apply { valid = false }
        val session = session(store, this, credentials)
        assertEquals(0, store.loads)
        session.restore()
        assertEquals(HistoryPhase.INTERRUPTED, session.state.value.read.history!!.attempt!!.phase)
        assertNull(session.state.value.read.history!!.attempt!!.finishedAt)
        assertEquals(success, store.saved.history!!.success)
        assertEquals(1, store.writes)
        assertFalse(credentials.valid)
        assertEquals(0, credentials.invalidations)
    }

    @Test
    fun `중복 시작과 지연 완료는 확인된 삭제를 덮어쓰지 못한다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        assertNull(session.beginAuthorization(date, at))
        assertTrue(session.beginExecution(ticket, date, at))
        assertFalse(session.beginExecution(ticket, date, at))
        session.interruptExecution(ticket, at)
        assertTrue(session.requestDelete())
        session.confirmDelete()
        val writes = store.writes
        session.finishExecution(ticket, SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS), at)
        assertEquals(writes, store.writes)
        assertNull(store.saved.history!!.success)
        assertNull(store.saved.history!!.attempt)
    }

    @Test
    fun `확인된 로컬 삭제는 인증을 무효화하고 다시 열면 빈 기록이다`() = runBlocking {
        val store = store()
        val credentials = Credentials()
        val session = session(store, this, credentials)
        session.restore()
        prepared(session)
        assertTrue(session.requestDelete())
        session.confirmDelete()
        assertFalse(credentials.valid)
        val reopened = session(store, this, credentials)
        reopened.restore()
        assertEquals(SyncHistory(1, null, null), reopened.state.value.read.history)
        assertFalse(credentials.valid)
        assertEquals(1, credentials.invalidations)
        assertEquals(1, store.resets)
    }

    @Test
    fun `삭제 확인을 취소하면 인증과 영속 기록을 보존한다`() = runBlocking {
        val store = store()
        val credentials = Credentials()
        val session = session(store, this, credentials)
        session.restore()
        assertTrue(session.requestDelete())
        session.cancelDelete()
        session.confirmDelete()
        assertTrue(credentials.valid)
        assertEquals(history(), store.saved.history)
        assertEquals(0, store.resets)
    }

    @Test
    fun `진행 중 세션의 삭제 요청과 시작은 대기열 없이 거부된다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        store.gate = CompletableDeferred()
        val start = async(start = CoroutineStart.UNDISPATCHED) { session.beginAuthorization(date, at) }
        assertEquals(HistoryOperation.WRITING, session.state.value.operation)
        assertFalse(session.requestDelete())
        assertNull(session.beginAuthorization(date, at))
        session.reload()
        assertEquals(1, store.loads)
        store.gate!!.complete(Unit)
        val ticket = requireNotNull(start.await())
        assertFalse(session.requestDelete())
        assertTrue(session.authorizationReturned(ticket))
        assertTrue(session.beginExecution(ticket, date, at))
        assertFalse(session.requestDelete())
        assertFalse(session.state.value.deleteDialog)
    }

    @Test
    fun `삭제 확인은 첫 저장소 중단점 전에 인증을 무효화한다`() = runBlocking {
        val store = store()
        val credentials = Credentials()
        val session = session(store, this, credentials)
        session.restore()
        prepared(session)
        store.gate = CompletableDeferred()
        store.onReset = { assertFalse(credentials.valid) }
        assertTrue(session.requestDelete())
        val deletion = async(start = CoroutineStart.UNDISPATCHED) { session.confirmDelete() }
        assertFalse(credentials.valid)
        assertEquals(HistoryOperation.DELETING, session.state.value.operation)
        assertNull(session.beginAuthorization(date, at))
        assertFalse(session.requestDelete())
        store.gate!!.complete(Unit)
        deletion.await()
    }

    @Test
    fun `삭제 실패는 완료로 주장하지 않고 인증 복원 없이 재시도를 허용한다`() = runBlocking {
        val store = store().apply { failReset = true }
        val credentials = Credentials()
        val session = session(store, this, credentials)
        session.restore()
        assertTrue(session.requestDelete())
        session.confirmDelete()
        assertTrue(session.state.value.deleteFailed)
        assertEquals(HistoryError.DELETE, session.state.value.error)
        assertEquals(success, session.state.value.read.history!!.success)
        assertFalse(credentials.valid)
        store.failReset = false
        session.retryDelete()
        assertFalse(session.state.value.deleteFailed)
        assertNull(session.state.value.read.history!!.success)
        assertFalse(credentials.valid)
        assertNotNull(session.beginAuthorization(date, at))
    }

    @Test
    fun `삭제 실패는 읽을 수 있는 기록만 유지로 표시하고 읽기 불가는 미확정으로 남긴다`() = runBlocking {
        for (status in listOf(HistoryReadStatus.READY, HistoryReadStatus.CORRUPT, HistoryReadStatus.UNSUPPORTED, HistoryReadStatus.UNAVAILABLE)) {
            val store = Store(HistoryRead(status, if (status == HistoryReadStatus.READY) history() else null)).apply { failReset = true }
            val session = session(store, this)
            session.restore()
            assertTrue(session.requestDelete())
            session.confirmDelete()
            assertEquals(status == HistoryReadStatus.READY, session.state.value.retainedAfterDeleteFailure)
            assertEquals(status, session.state.value.read.status)
            assertTrue(session.state.value.deleteFailed)
        }
    }

    @Test
    fun `삭제 실패는 명시적 유지와 성공한 재읽기 전까지 새 승인을 차단한다`() = runBlocking {
        val store = store().apply { failReset = true }
        val credentials = Credentials()
        val session = session(store, this, credentials)
        session.restore()
        assertTrue(session.requestDelete())
        session.confirmDelete()
        assertNull(session.beginAuthorization(date, at))
        session.reload()
        assertEquals(1, store.loads)
        store.failLoad = true
        session.keepExistingAfterDeleteFailure()
        assertTrue(session.state.value.deleteFailed)
        assertNull(session.beginAuthorization(date, at))
        store.failLoad = false
        session.keepExistingAfterDeleteFailure()
        assertFalse(session.state.value.deleteFailed)
        assertFalse(credentials.valid)
        assertEquals(success, session.state.value.read.history!!.success)
        assertNotNull(session.beginAuthorization(date, at))
    }

    @Test
    fun `복구 후 이전 세대 콜백은 확인된 초기화를 다시 쓰지 못한다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        session.reload()
        assertEquals(HistoryPhase.UPLOAD_NOT_STARTED, session.state.value.read.history!!.attempt!!.phase)
        assertTrue(session.requestDelete())
        session.confirmDelete()
        val writes = store.writes
        assertFalse(session.authorizationReturned(ticket))
        session.authorizationFailed(ticket, AuthorizationDiagnostics.Failure.UNKNOWN, at)
        assertFalse(session.beginExecution(ticket, date, at))
        session.finishExecution(ticket, SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS), at)
        session.interruptExecution(ticket, at)
        assertEquals(writes, store.writes)
        assertEquals(SyncHistory(1, null, null), store.saved.history)
    }

    @Test
    fun `빈 기록의 반복 삭제는 로컬이며 영속화 실패를 성공으로 바꾸지 않는다`() = runBlocking {
        val store = store(SyncHistory(1, null, null))
        val session = session(store, this)
        session.restore()
        repeat(2) {
            assertTrue(session.requestDelete())
            session.confirmDelete()
            assertEquals(SyncHistory(1, null, null), session.state.value.read.history)
        }
        store.failReset = true
        assertTrue(session.requestDelete())
        session.confirmDelete()
        assertTrue(session.state.value.deleteFailed)
        assertEquals(HistoryError.DELETE, session.state.value.error)
        assertEquals(0, store.writes)
        assertEquals(3, store.resets)
    }

    @Test
    fun `읽기 불가 기록은 명시적 재읽기 또는 초기화 성공 전까지 업로드를 차단한다`() = runBlocking {
        for (reset in listOf(false, true)) {
            val store = Store(HistoryRead(HistoryReadStatus.CORRUPT, null))
            val session = session(store, this)
            session.restore()
            assertNull(session.beginAuthorization(date, at))
            assertFalse(session.beginExecution(0, date, at))
            assertEquals(HistoryError.READ, session.state.value.error)
            if (reset) {
                assertTrue(session.requestDelete())
                session.confirmDelete()
            } else {
                store.saved = HistoryRead(HistoryReadStatus.ABSENT, null)
                session.reload()
            }
            assertNotNull(session.beginAuthorization(date, at))
        }
    }

    @Test
    fun `복구 저장 실패는 파생 중단과 로컬 저장 오류를 표시한다`() = runBlocking {
        val store = store(history(HistoryPhase.RUNNING)).apply { failWrite = true }
        val session = session(store, this)
        session.restore()
        assertEquals(HistoryPhase.INTERRUPTED, session.state.value.read.history!!.attempt!!.phase)
        assertEquals(HistoryError.RECOVERY_WRITE, session.state.value.error)
        assertEquals(HistoryPhase.RUNNING, store.saved.history!!.attempt!!.phase)
        assertNull(session.beginAuthorization(date, at))
        store.failWrite = false
        session.reload()
        assertNull(session.state.value.error)
        assertEquals(HistoryPhase.INTERRUPTED, store.saved.history!!.attempt!!.phase)
    }

    @Test
    fun `종결 저장 실패는 새 실행을 막고 명시적 초기화를 허용한다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        assertTrue(session.beginExecution(ticket, date, at))
        store.failWrite = true
        session.finishExecution(ticket, SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS), at)
        assertEquals(HistoryError.WRITE, session.state.value.error)
        assertEquals(success, session.state.value.read.history!!.success)
        assertNull(session.beginAuthorization(date, at))
        assertTrue(session.requestDelete())
        session.confirmDelete()
        assertNull(session.state.value.error)
        assertEquals(SyncHistory(1, null, null), store.saved.history)
    }

    @Test
    fun `승인 및 실행 시작 저장이 끝나기 전에는 외부 작업을 허용하지 않는다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        store.gate = CompletableDeferred()
        val execution = async(start = CoroutineStart.UNDISPATCHED) { session.beginExecution(ticket, date, at) }
        assertFalse(execution.isCompleted)
        session.finishExecution(ticket, SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS), at)
        assertEquals(HistoryPhase.AWAITING_UPLOAD, store.saved.history!!.attempt!!.phase)
        store.gate!!.complete(Unit)
        assertTrue(execution.await())
        assertEquals(HistoryPhase.RUNNING, store.saved.history!!.attempt!!.phase)
        session.finishExecution(ticket, SyncResult(SyncStatus.FAILED, 1, "민감한 공급자 진단"), setOf(DataType.STEPS), at)
        assertEquals("SYNC_FAILED", store.saved.history!!.attempt!!.failureCode)
        assertEquals(success, store.saved.history!!.success)
    }

    @Test
    fun `세션 수명이 종료되면 새 승인과 삭제를 거부한다`() = runBlocking {
        val job = Job()
        val store = store()
        val session = session(store, CoroutineScope(coroutineContext + job))
        session.restore()
        job.cancel()
        assertNull(session.beginAuthorization(date, at))
        assertFalse(session.requestDelete())
        session.reload()
        assertEquals(1, store.loads)
    }

    @Test
    fun `승인 시작 저장 실패는 SDK 실행을 막고 취소 결과는 요청 시각만 기록한다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        val effects = HistoryEffects(session)
        session.restore()
        var launches = 0
        store.failWrite = true
        effects.authorize(date, at) { launches++ }
        assertEquals(0, launches)
        store.failWrite = false
        session.reload()
        effects.authorize(date, at) { ticket ->
            assertEquals(HistoryPhase.AUTHORIZING, store.saved.history!!.attempt!!.phase)
            launches++
            launch { session.authorizationFailed(ticket, AuthorizationDiagnostics.Failure.CONSENT_CANCELED, at) }
        }
        yield()
        val attempt = store.saved.history!!.attempt!!
        assertEquals(1, launches)
        assertEquals(at, attempt.authorizationRequestedAt)
        assertNull(attempt.startedAt)
        assertEquals(HistoryPhase.AUTHORIZATION_FAILED, attempt.phase)
    }

    @Test
    fun `실행 시작 저장 실패는 실제 효과 경계의 읽기와 업로드를 모두 막는다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        store.failWrite = true
        var reads = 0
        var uploads = 0
        val outcome = HistoryEffects(session).execute(ticket, date, at, { at }, { true }, { fail("원격 결과 없음") }) {
            reads++
            uploads++
            HistoryExecutionResult(SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS))
        }
        assertNull(outcome)
        assertEquals(0, reads)
        assertEquals(0, uploads)
        assertEquals(success, store.saved.history!!.success)
    }

    @Test
    fun `계속 대기 저장 이후에만 토큰을 받고 중복 계속은 한 번만 정확한 날짜를 실행한다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        val effects = HistoryEffects(session)
        session.restore()
        val ticket = requireNotNull(session.beginAuthorization(date, at))
        var credentials = 0
        effects.acceptAuthorization(ticket) {
            assertEquals(HistoryPhase.AWAITING_UPLOAD, store.saved.history!!.attempt!!.phase)
            credentials++
        }
        assertFalse(effects.acceptAuthorization(ticket) { credentials++ })
        var reads = 0
        var uploads = 0
        repeat(2) {
            effects.execute(ticket, date, at, { at }, { true }, {}) { selected ->
                assertEquals(date, selected)
                assertEquals(HistoryPhase.RUNNING, store.saved.history!!.attempt!!.phase)
                reads++
                uploads++
                HistoryExecutionResult(SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS))
            }
        }
        assertEquals(1, credentials)
        assertEquals(1, reads)
        assertEquals(1, uploads)
        assertEquals(HistoryPhase.SUCCESS, store.saved.history!!.attempt!!.phase)
    }

    @Test
    fun `원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        var remote: HistoryExecutionResult? = null
        HistoryEffects(session).execute(ticket, date, at, { at.plusSeconds(60) }, { true }, { remote = it }) {
            store.failWrite = true
            HistoryExecutionResult(SyncResult(SyncStatus.SUCCESS, 2), setOf(DataType.STEPS, DataType.WEIGHT))
        }
        assertEquals(SyncStatus.SUCCESS, remote!!.result.status)
        assertEquals(2, remote!!.result.uploadedFiles)
        assertEquals(HistoryError.WRITE, session.state.value.error)
        assertEquals(success, session.state.value.read.history!!.success)
        assertEquals(success, store.saved.history!!.success)
        store.failWrite = false
        val reopened = session(store, this)
        reopened.restore()
        assertEquals(success, reopened.state.value.read.history!!.success)
        assertEquals(HistoryPhase.INTERRUPTED, reopened.state.value.read.history!!.attempt!!.phase)
    }

    @Test
    fun `전경 작업 취소는 독립 종결 저장을 끝내고 원격 업로드를 재실행하지 않는다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        val entered = CompletableDeferred<Unit>()
        val remoteWait = CompletableDeferred<Unit>()
        var uploads = 0
        val execution = launch(start = CoroutineStart.UNDISPATCHED) {
            HistoryEffects(session).execute(ticket, date, at, { at }, { true }, {}) {
                uploads++
                entered.complete(Unit)
                remoteWait.await()
                HistoryExecutionResult(SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS))
            }
        }
        entered.await()
        execution.cancelAndJoin()
        assertEquals(HistoryPhase.INTERRUPTED, store.saved.history!!.attempt!!.phase)
        assertEquals(at, store.saved.history!!.attempt!!.finishedAt)
        assertEquals(1, uploads)
        assertEquals(success, store.saved.history!!.success)
    }

    @Test
    fun `결과 저장 중 전경 취소는 원격 확인과 성공 커밋을 잃지 않는다`() = runBlocking {
        val store = store()
        val session = session(store, this)
        session.restore()
        val ticket = prepared(session)
        val gate = CompletableDeferred<Unit>()
        val execution = launch(start = CoroutineStart.UNDISPATCHED) {
            HistoryEffects(session).execute(ticket, date, at, { at.plusSeconds(1) }, { true }, {}) {
                store.gate = gate
                HistoryExecutionResult(SyncResult(SyncStatus.SUCCESS, 2), setOf(DataType.STEPS))
            }
        }
        assertEquals(HistoryOperation.WRITING, session.state.value.operation)
        execution.cancel()
        assertFalse(execution.isCompleted)
        gate.complete(Unit)
        execution.join()
        assertEquals(HistoryPhase.SUCCESS, store.saved.history!!.attempt!!.phase)
        assertEquals(2, store.saved.history!!.success!!.confirmedFiles)
    }

    @Test
    fun `재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다`() = runBlocking {
        for (phase in listOf<HistoryPhase?>(null, HistoryPhase.AUTHORIZING, HistoryPhase.AWAITING_UPLOAD)) {
            val store = store(history(phase))
            val session = session(store, this)
            val effects = HistoryEffects(session)
            session.restore()
            var credentials = 0
            var effectsCount = 0
            assertFalse(effects.acceptAuthorization(1) { credentials++ })
            assertNull(effects.execute(1, date, at, { at }, { true }, {}) {
                effectsCount++
                HistoryExecutionResult(SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS))
            })
            assertEquals(0, credentials)
            assertEquals(0, effectsCount)
            assertEquals(success, session.state.value.read.history!!.success)
        }
        val store = store()
        val session = session(store, this)
        session.restore()
        val stale = prepared(session)
        session.reload()
        val current = requireNotNull(session.beginAuthorization(date, at))
        var credentials = 0
        assertFalse(HistoryEffects(session).acceptAuthorization(stale) { credentials++ })
        assertTrue(HistoryEffects(session).acceptAuthorization(current) { credentials++ })
        assertEquals(1, credentials)
    }

    @Test
    fun `준비 뒤 날짜 변경은 실행을 막고 명시적 재승인으로 새 날짜를 선택한다`() = runBlocking {
        val store = store()
        val credentials = Credentials()
        val session = session(store, this, credentials)
        session.restore()
        val ticket = prepared(session)
        val tomorrow = date.plusDays(1)
        var reads = 0
        assertNull(HistoryEffects(session).execute(ticket, tomorrow, at, { at }, { true }, {}) {
            reads++
            HistoryExecutionResult(SyncResult(SyncStatus.SUCCESS, 1), setOf(DataType.STEPS))
        })
        session.reload()
        assertFalse(credentials.valid)
        val fresh = requireNotNull(session.beginAuthorization(tomorrow, at))
        assertTrue(fresh != ticket)
        assertEquals(tomorrow, store.saved.history!!.attempt!!.date)
        assertEquals(0, reads)
    }

}
