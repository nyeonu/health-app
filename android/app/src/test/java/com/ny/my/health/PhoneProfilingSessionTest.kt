package com.ny.my.health

import com.ny.my.health.core.DriveOperation
import com.ny.my.health.core.ProfileOutcome
import com.ny.my.health.core.ProfileStage
import com.ny.my.health.core.ProfileStats
import com.ny.my.health.core.ProfileSummary
import com.ny.my.health.core.ProfilingClock
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PhoneProfilingSessionTest {
    private val stage = ProfileStage.HEALTH_READ

    private fun measured(nanos: Long = 0L, outcome: ProfileOutcome = ProfileOutcome.COMPLETED): ManualSyncProfiling {
        var tick = 0L
        return ManualSyncProfiling(ProfilingClock { tick }).also {
            it.collector.start(stage)
            tick = nanos
            it.collector.finish(stage, outcome)
            it.finish(outcome)
        }
    }

    private fun report(profile: ManualSyncProfiling): PhoneProfilingReport {
        val session = PhoneProfilingSession(true)
        session.begin(profile)
        session.complete(profile)
        return requireNotNull(session.state.report)
    }

    private fun PhoneProfilingReport.value(): String = rows.single { it.stage == stage }.value

    @Test
    fun `진단 없음과 수집 중에는 복사와 과거 측정 표시가 없다`() {
        val session = PhoneProfilingSession(true)
        var writes = 0
        assertEquals(PhoneProfilingUiState(enabled = true), session.state)
        session.copy { writes++ }
        val old = measured()
        session.begin(old)
        session.complete(old)
        session.copy { writes++ }
        assertEquals(1, writes)
        session.begin(ManualSyncProfiling())
        assertEquals(PhoneProfilingUiState(enabled = true, collecting = true), session.state)
        session.copy { writes++ }
        assertEquals(1, writes)
    }

    @Test
    fun `미관측 미완료 실제 영과 작은 양수 시간은 서로 다르게 표시한다`() {
        assertEquals("측정 없음", report(ManualSyncProfiling()).value())
        val active = ManualSyncProfiling(ProfilingClock { 0L })
        active.collector.start(stage)
        assertEquals("미완료", report(active).value())
        val expected = mapOf(
            0L to "0 ms", 1L to "1 ms 미만", 999_999L to "1 ms 미만",
            1_000_000L to "1 ms", Long.MAX_VALUE to "9223372036854.775 ms",
        )
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            expected.forEach { (nanos, value) -> assertEquals(value, report(measured(nanos)).value()) }
            assertEquals("1.234 ms", report(measured(1_234_999L)).value())
        } finally {
            Locale.setDefault(previousLocale)
        }
        val failedClock = ManualSyncProfiling(ProfilingClock { error("합성 시계 고장") })
        failedClock.collector.measureValue(stage) { }
        assertEquals("측정 없음", report(failedClock).value())
        var clockCalls = 0
        val failedEnd = ManualSyncProfiling(ProfilingClock {
            if (clockCalls++ == 0) 0L else error("합성 종료 시계 고장")
        })
        failedEnd.collector.measureValue(stage) { }
        assertEquals("측정 없음", report(failedEnd).value())
        val noTerminal = ProfileStats(1L, 0L, 0L, 0L, 0L, false)
        assertEquals("측정 없음", phoneProfilingReport(ProfileSummary(mapOf(stage to noTerminal), emptyMap(), emptyMap())).value())
    }

    @Test
    fun `단계별 표시와 복사 요약에는 고정 안전 정보만 있다`() {
        val profile = measured(1_234_000L, ProfileOutcome.FAILED)
        val report = report(profile)
        assertEquals(ProfileStage.entries.toList(), report.rows.map { it.stage })
        assertEquals("1.234 ms (실패)", report.value())
        assertTrue(report.rows.filter { it.stage != stage }.all { it.value == "측정 없음" })
        val labels = ProfileStage.entries.map { it.name } + DriveOperation.entries.map { it.name } +
            DriveOperation.entries.map { it.name }
        val expected = labels.joinToString(separator = "\n", postfix = "\n") {
            if (it == stage.name) "$it 1 0 1 0 1234000 0" else "$it 0 0 0 0 0 0"
        }
        assertEquals(expected, report.safeText)
        assertEquals(profile.safeSummary(), report.safeText)
        assertEquals(24, report.safeText.trimEnd().lines().size)
        assertTrue(report.safeText.length <= 8192)
        assertTrue(report.safeText.trimEnd().lines().all { it.matches(Regex("[A-Z_]+( [0-9]+){6}")) })
        val session = PhoneProfilingSession(true)
        session.begin(profile)
        session.complete(profile)
        val copied = mutableListOf<String>()
        session.copy(copied::add)
        assertEquals(listOf(expected), copied)
        assertEquals(PhoneCopyState.COPIED, session.state.copyState)
    }

    @Test
    fun `최근 한 건의 불변 요약만 유지하고 새 세션은 비어 있다`() {
        val session = PhoneProfilingSession(true)
        val first = measured(1_000_000L)
        session.begin(first)
        session.complete(first)
        val retained = requireNotNull(session.state.report)
        first.collector.measureValue(ProfileStage.CSV_EXPORT) { }
        assertEquals("측정 없음", retained.rows.single { it.stage == ProfileStage.CSV_EXPORT }.value)
        repeat(4) { index ->
            val next = measured(index * 1_000_000L)
            session.begin(next)
            session.complete(next)
            assertEquals("$index ms", requireNotNull(session.state.report).value())
        }
        assertEquals("1 ms", retained.value())
        assertNull(PhoneProfilingSession(true).state.report)
        try {
            (retained.rows as MutableList<PhoneProfilingRow>).clear()
            fail("행 목록은 변경할 수 없어야 한다")
        } catch (_: UnsupportedOperationException) { }
        val stages = mutableMapOf(stage to ProfileStats(1L, 1L, 0L, 0L, 1_000_000L, false))
        val operations = mutableMapOf(DriveOperation.entries.first() to stages.getValue(stage))
        val requests = operations.toMutableMap()
        val fromMaps = phoneProfilingReport(ProfileSummary(stages, operations, requests))
        val safeBefore = fromMaps.safeText
        stages.clear()
        operations.clear()
        requests.clear()
        assertEquals("1 ms", fromMaps.value())
        assertEquals(safeBefore, fromMaps.safeText)
        assertEquals(24, fromMaps.safeText.trimEnd().lines().size)
    }

    @Test
    fun `새 시도와 무효화는 이전 요약과 늦은 완료를 폐기한다`() {
        val session = PhoneProfilingSession(true)
        val first = measured(1_000_000L)
        val next = measured(2_000_000L)
        session.begin(first)
        session.begin(next)
        session.complete(first)
        assertTrue(session.state.collecting)
        assertNull(session.state.report)
        session.complete(next)
        val expected = session.state
        session.complete(first)
        session.complete(next)
        assertEquals(expected, session.state)
        session.clear()
        session.complete(next)
        assertEquals(PhoneProfilingUiState(enabled = true), session.state)
        session.begin(first)
        session.clear()
        session.complete(first)
        assertNull(session.state.report)
    }

    @Test
    fun `복사 실패는 안전한 안내만 남기고 명시적 재시도만 허용한다`() {
        val session = PhoneProfilingSession(true)
        val profile = measured()
        session.begin(profile)
        session.complete(profile)
        val expected = session.state.report
        var writes = 0
        session.copy {
            writes++
            throw IllegalStateException("합성 공급자 원문 오류")
        }
        assertEquals(1, writes)
        assertEquals(PhoneCopyState.FAILED, session.state.copyState)
        assertSame(expected, session.state.report)
        assertFalse(session.state.toString().contains("합성 공급자"))
        session.complete(profile)
        assertEquals(1, writes)
        session.copy { writes++ }
        assertEquals(2, writes)
        assertEquals(PhoneCopyState.COPIED, session.state.copyState)
        session.begin(measured())
        assertEquals(PhoneCopyState.NONE, session.state.copyState)
    }

    @Test
    fun `복사 중 무효화 뒤 늦은 결과는 새 상태를 덮지 않는다`() {
        for (writerFails in listOf(false, true)) {
            val session = PhoneProfilingSession(true)
            val profile = measured()
            session.begin(profile)
            session.complete(profile)
            session.copy {
                session.clear()
                if (writerFails) error("합성 복사 실패")
            }
            assertEquals(PhoneProfilingUiState(enabled = true), session.state)
            session.begin(profile)
            session.complete(profile)
            val next = measured(2_000_000L)
            session.copy {
                session.begin(next)
                session.complete(next)
                session.copy { }
                if (writerFails) error("합성 복사 실패")
            }
            assertEquals("2 ms", requireNotNull(session.state.report).value())
            assertEquals(PhoneCopyState.COPIED, session.state.copyState)
        }
    }

    @Test
    fun `비디버그에서는 진단과 복사 경로가 없다`() {
        val session = PhoneProfilingSession(false)
        val profile = measured()
        var writes = 0
        repeat(2) {
            session.begin(profile)
            session.complete(profile)
            session.copy { writes++ }
            assertEquals(PhoneProfilingUiState(), session.state)
            session.clear()
        }
        assertEquals(0, writes)
    }

    @Test
    fun `종결 정상 실패 취소와 미완료를 업무 결과와 혼동하지 않는다`() {
        assertEquals("0 ms", report(measured()).value())
        assertEquals("0 ms (실패)", report(measured(outcome = ProfileOutcome.FAILED)).value())
        assertEquals("0 ms (취소)", report(measured(outcome = ProfileOutcome.CANCELLED)).value())
        val active = ManualSyncProfiling(ProfilingClock { 0L })
        active.collector.start(stage)
        assertEquals("미완료", report(active).value())
        listOf(ProfileOutcome.COMPLETED, ProfileOutcome.FAILED, ProfileOutcome.CANCELLED).forEach { outcome ->
            val profile = measured(outcome = outcome)
            val before = profile.collector.snapshot()
            val report = report(profile)
            assertEquals(before, profile.collector.snapshot())
            assertFalse(report.rows.any { it.value.contains("업로드 성공") })
            assertEquals("측정 없음", report.rows.single { it.stage == ProfileStage.DRIVE_TOTAL }.value)
        }
    }
}
