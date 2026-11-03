package com.ny.my.health

import com.ny.my.health.core.DriveOperation
import com.ny.my.health.core.ProfileOutcome
import com.ny.my.health.core.ProfileStage
import com.ny.my.health.core.ProfilingClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualSyncProfilingTest {
    @Test
    fun `Authorization and continuation waits never contribute to execution elapsed`() {
        var tick = 0L
        val profiling = ManualSyncProfiling(ProfilingClock { tick })
        profiling.collector.start(ProfileStage.AUTHORIZATION_HISTORY)
        tick = 7L
        profiling.collector.finish(ProfileStage.AUTHORIZATION_HISTORY, ProfileOutcome.COMPLETED)
        profiling.authorizationStarted()
        tick += 9_000_000_000_000L
        assertNull(profiling.collector.snapshot().stages[ProfileStage.EXECUTION])
        profiling.authorizationReturned(true)
        tick += 8_000_000_000_000L
        assertNull(profiling.collector.snapshot().stages[ProfileStage.EXECUTION])
        profiling.executionStarted()
        tick += 19L
        val stages = profiling.finish(ProfileOutcome.COMPLETED).stages
        assertEquals(7L, stages.getValue(ProfileStage.AUTHORIZATION_HISTORY).elapsedNanos)
        assertEquals(9_000_000_000_000L, stages.getValue(ProfileStage.AUTHORIZATION_WAIT).elapsedNanos)
        assertEquals(8_000_000_000_000L, stages.getValue(ProfileStage.CONTINUE_WAIT).elapsedNanos)
        assertEquals(19L, stages.getValue(ProfileStage.EXECUTION).elapsedNanos)
        assertTrue(stages.values.none { it.active })
    }

    @Test
    fun `Safe profiling summary contains only fixed enums and numeric statistics`() = runBlocking {
        var tick = 0L
        val profiling = ManualSyncProfiling(ProfilingClock { tick.also {
            tick = if (tick == 0L) Long.MAX_VALUE else 0L
        } })
        repeat(2) {
            ProfileStage.entries.forEach { stage -> profiling.collector.measureValue(stage) {} }
            DriveOperation.entries.forEach { operation ->
                profiling.collector.operation(operation) {}
                profiling.collector.request(operation) {}
            }
        }
        profiling.finish(ProfileOutcome.COMPLETED)
        val summary = profiling.safeSummary()
        assertTrue(summary.length <= 8192)
        val rows = summary.trimEnd().lines()
        val labels = ProfileStage.entries.map { it.name } + DriveOperation.entries.map { it.name } +
            DriveOperation.entries.map { it.name }
        assertEquals(labels, rows.map { it.substringBefore(' ') })
        rows.forEach { row ->
            val values = row.split(' ')
            assertEquals(7, values.size)
            values.drop(1).forEach { assertTrue(it.matches(Regex("[0-9]+"))) }
            assertEquals(Long.MAX_VALUE, values[5].toLong())
            assertEquals("0", values[6])
        }
        val emitted = mutableListOf<String>()
        profiling.publish(true, emitted::add)
        profiling.publish(true, emitted::add)
        assertEquals(listOf(summary), emitted)
    }

    @Test
    fun `인증 거부 뒤 늦은 콜백과 계속 요청은 실행을 시작하지 않는다`() {
        var tick = 0L
        val profiling = ManualSyncProfiling(ProfilingClock { tick })
        profiling.authorizationStarted()
        tick = 30L
        profiling.authorizationReturned(false)
        val rejected = profiling.collector.snapshot()
        tick = 1000L
        profiling.authorizationReturned(true)
        profiling.executionStarted()
        profiling.authorizationStarted()
        assertEquals(rejected, profiling.finish(ProfileOutcome.COMPLETED))
        assertEquals(1L, rejected.stages.getValue(ProfileStage.AUTHORIZATION_WAIT).failed)
        assertNull(rejected.stages[ProfileStage.CONTINUE_WAIT])
        assertNull(rejected.stages[ProfileStage.EXECUTION])
    }

    @Test
    fun `인증 취소와 동의 철회 또는 날짜 변경으로 폐기한 시도는 늦은 콜백과 격리된다`() {
        listOf(false, true).forEach { accepted ->
            var tick = 0L
            val discarded = ManualSyncProfiling(ProfilingClock { tick })
            discarded.authorizationStarted()
            if (accepted) discarded.authorizationReturned(true)
            tick = 51L
            val cancelled = discarded.finish(ProfileOutcome.CANCELLED)
            val stage = if (accepted) ProfileStage.CONTINUE_WAIT else ProfileStage.AUTHORIZATION_WAIT
            assertEquals(1L, cancelled.stages.getValue(stage).cancelled)
            assertTrue(cancelled.stages.values.none { it.active })
            val next = ManualSyncProfiling(ProfilingClock { tick })
            next.authorizationStarted()
            discarded.authorizationReturned(true)
            discarded.executionStarted()
            assertEquals(cancelled, discarded.finish(ProfileOutcome.COMPLETED))
            assertTrue(next.collector.snapshot().stages.getValue(ProfileStage.AUTHORIZATION_WAIT).active)
            assertNull(next.collector.snapshot().stages[ProfileStage.EXECUTION])
        }
    }

    @Test
    fun `전경 실행 취소는 원래 예외와 취소 통계를 보존한다`() = runBlocking {
        var tick = 0L
        val profiling = ManualSyncProfiling(ProfilingClock { tick })
        profiling.authorizationStarted()
        profiling.authorizationReturned(true)
        profiling.executionStarted()
        val cancellation = CancellationException("합성 취소")
        try {
            profiling.collector.measure(ProfileStage.HEALTH_READ) {
                tick = 23L
                throw cancellation
            }
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        } finally {
            profiling.finish(ProfileOutcome.CANCELLED)
        }
        val summary = profiling.collector.snapshot()
        assertEquals(1L, summary.stages.getValue(ProfileStage.EXECUTION).cancelled)
        assertEquals(23L, summary.stages.getValue(ProfileStage.EXECUTION).elapsedNanos)
        assertEquals(1L, summary.stages.getValue(ProfileStage.HEALTH_READ).cancelled)
        assertTrue(summary.stages.values.none { it.active })
    }

    @Test
    fun `비활성 출력과 실패하는 출력기는 업무 결과를 바꾸거나 반복 출력하지 않는다`() {
        val disabled = ManualSyncProfiling()
        var calls = 0
        disabled.publish(true) { calls++ }
        assertEquals(0, calls)
        disabled.finish(ProfileOutcome.FAILED)
        disabled.publish(false) { calls++ }
        disabled.publish(true) { calls++ }
        assertEquals(0, calls)

        val enabled = ManualSyncProfiling()
        val expected = enabled.finish(ProfileOutcome.COMPLETED)
        enabled.publish(true) {
            calls++
            throw IllegalStateException("합성 출력 실패")
        }
        enabled.publish(true) { calls++ }
        assertEquals(1, calls)
        assertEquals(expected, enabled.collector.snapshot())
    }
}
