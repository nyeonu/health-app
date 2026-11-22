package com.ny.my.health

import androidx.work.WorkInfo
import com.ny.my.health.core.CommitStatus
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.ReadAccess
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.SyncCommit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutomaticSettingsTest {
    private val old = DestinationGeneration("old")
    private val next = DestinationGeneration("next")

    @Test
    fun `Opt out commits generation invalidation before cancelling old work`() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AutomaticSettingsController(
            commit = { _, enabled -> events += "commit:$enabled"; SyncCommit(CommitStatus.COMMITTED, next) },
            isCurrent = { generation, enabled -> generation == next && !enabled },
            cancel = { events += "cancel:${it.value}" }, schedule = { events += "schedule" }, catchUp = { events += "catchup" },
        )
        assertTrue(controller.change(controller.invalidate(), old, false))
        assertEquals(listOf("commit:false", "cancel:old"), events)
    }

    @Test
    fun `Failed invalidation does not cancel old work`() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AutomaticSettingsController(
            commit = { _, _ -> SyncCommit(CommitStatus.STALE, next) }, isCurrent = { _, _ -> true },
            cancel = { events += "cancel" }, schedule = { events += "schedule" }, catchUp = { events += "catchup" },
        )
        assertFalse(controller.change(controller.invalidate(), old, false))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `Confirmed reset cancels old work only after new generation is verified`() {
        val events = mutableListOf<String>()
        val controller = AutomaticSettingsController(
            commit = { _, _ -> SyncCommit(CommitStatus.INVALID, null) }, isCurrent = { _, _ -> false },
            cancel = { events += it.value }, schedule = {}, catchUp = {},
        )
        controller.cancelReplaced(old, null)
        controller.cancelReplaced(old, old)
        assertTrue(events.isEmpty())
        controller.cancelReplaced(old, next)
        assertEquals(listOf("old"), events)
    }

    @Test
    fun `Off restore has no scheduling effects and stale enable cannot enqueue`() = runBlocking {
        val events = mutableListOf<String>()
        val resumed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = AutomaticSettingsController(
            commit = { _, _ -> resumed.complete(Unit); release.await(); SyncCommit(CommitStatus.COMMITTED, old) },
            isCurrent = { _, _ -> true }, cancel = { events += "cancel" },
            schedule = { events += "schedule" }, catchUp = { events += "catchup" },
        )
        controller.restore(controller.invalidate(), old, false)
        val ticket = controller.invalidate()
        val pending = async { controller.change(ticket, old, true) }
        resumed.await()
        controller.invalidate()
        release.complete(Unit)
        try { pending.await(); fail("취소된 변경이 완료되었습니다") } catch (_: CancellationException) { }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `Explicit opt in schedules and enqueues after verified commit`() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AutomaticSettingsController(
            commit = { _, _ -> events += "commit"; SyncCommit(CommitStatus.COMMITTED, old) },
            isCurrent = { _, _ -> events += "verify"; true }, cancel = { events += "cancel" },
            schedule = { events += "schedule" }, catchUp = { events += "catchup" },
        )
        assertTrue(controller.change(controller.invalidate(), old, true))
        assertEquals(listOf("commit", "verify", "schedule", "verify", "catchup"), events)
    }

    @Test
    fun `Unsupported missing grant and local destination cannot enable automatic scope`() {
        val summary = ReadAccess(true, setOf(DataType.STEPS), mapOf(DataType.STEPS to ReadScope.SUMMARY))
        assertTrue(automaticAccessAllowed(true, summary, setOf(DataType.STEPS)))
        assertFalse(automaticAccessAllowed(false, summary, setOf(DataType.STEPS)))
        assertFalse(automaticAccessAllowed(true, summary.copy(available = false), setOf(DataType.STEPS)))
        assertFalse(automaticAccessAllowed(true, summary.copy(granted = emptySet()), setOf(DataType.STEPS)))
        assertFalse(automaticAccessAllowed(true, summary, setOf(DataType.HEART_RATE)))
        val weight = ReadAccess(true, setOf(DataType.WEIGHT), mapOf(DataType.WEIGHT to ReadScope.EXPLICIT_DAY))
        assertTrue(automaticAccessAllowed(true, weight, setOf(DataType.WEIGHT)))
    }

    @Test
    fun `Queued and dependency blocked work remain distinct`() {
        assertEquals("예약 대기 중 (제약 또는 재시도 지연 가능)", automaticWorkLabel(setOf(WorkInfo.State.ENQUEUED)))
        assertEquals("선행 작업 대기 중", automaticWorkLabel(setOf(WorkInfo.State.BLOCKED)))
        assertEquals("실행 중", automaticWorkLabel(setOf(WorkInfo.State.RUNNING)))
        assertEquals("확인되지 않음", automaticWorkLabel(emptySet()))
        assertEquals("작업 실패 · 다시 확인 필요 · 작업 취소됨", automaticWorkLabel(setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)))
        assertEquals("자동 Drive 동기화: 확인되지 않음", automaticStatusLabel(false, true))
        assertEquals("자동 Drive 동기화: 확인되지 않음", automaticStatusLabel(false, false))
        assertEquals("자동 Drive 동기화: 꺼짐", automaticStatusLabel(true, false))
        assertEquals("자동 범위 미완료 날짜: 확인되지 않음 · 차단 날짜: 2", automaticGapLabel(null, 2))
    }
    @Test
    fun `Work observation failures and unavailable store produce unknown state`() = runBlocking {
        var unknown = 0
        var published = 0
        collectAutomaticWork(flow { throw IllegalStateException("synthetic") }, { true },
            read = { AutomaticWorkRead.CURRENT }, publish = { published++ }, unknown = { unknown++ })
        collectAutomaticWork(flow { emit(emptyList()) }, { true },
            read = { AutomaticWorkRead.UNKNOWN }, publish = { published++ }, unknown = { unknown++ })
        assertEquals(2, unknown)
        assertEquals(0, published)
    }

    @Test
    fun `Background permission request requires foreground idle state without authorization or token`() {
        val state = SyncUiState()
        assertTrue(mayRequestBackgroundPermission(true, state, false, false))
        assertFalse(mayRequestBackgroundPermission(false, state, false, false))
        assertFalse(mayRequestBackgroundPermission(true, state.copy(pendingConsent = true), false, false))
        assertFalse(mayRequestBackgroundPermission(true, state.copy(readyToUpload = true), false, false))
        assertFalse(mayRequestBackgroundPermission(true, state, true, false))
        assertFalse(mayRequestBackgroundPermission(true, state, false, true))
    }

}
