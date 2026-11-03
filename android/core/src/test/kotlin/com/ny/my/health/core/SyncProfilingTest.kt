package com.ny.my.health.core

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncProfilingTest {
    @Test
    fun `Unstarted stages are absent while completed zero duration stages remain present`() {
        val profiling = SyncProfiling(ProfilingClock { 17L })
        assertTrue(profiling.snapshot().stages.isEmpty())
        assertTrue(profiling.snapshot().operations.isEmpty())
        assertTrue(profiling.snapshot().requests.isEmpty())
        profiling.finish(ProfileStage.HEALTH_READ, ProfileOutcome.COMPLETED)
        assertTrue(profiling.snapshot().stages.isEmpty())
        profiling.start(ProfileStage.CSV_EXPORT)
        profiling.start(ProfileStage.CSV_EXPORT)
        profiling.finish(ProfileStage.CSV_EXPORT, ProfileOutcome.COMPLETED)
        profiling.finish(ProfileStage.CSV_EXPORT, ProfileOutcome.COMPLETED)
        assertEquals(
            mapOf(ProfileStage.CSV_EXPORT to ProfileStats(1, 1, 0, 0, 0, false)),
            profiling.snapshot().stages,
        )
    }

    @Test
    fun `Monotonic stage timing survives wall clock changes and preserves failed cancelled and active states`() = runBlocking {
        var nanos = 0L
        var wallTime = Instant.parse("2026-11-02T00:00:00Z")
        val profiling = SyncProfiling(ProfilingClock { nanos })
        val value = Any()
        assertSame(value, profiling.measure(ProfileStage.EXECUTION) {
            nanos = 10L
            wallTime = wallTime.minusSeconds(3600)
            value
        })
        assertEquals(Instant.parse("2026-11-01T23:00:00Z"), wallTime)
        val failure = IllegalStateException("합성 오류")
        try {
            profiling.measureValue(ProfileStage.CSV_EXPORT) {
                nanos = 30L
                throw failure
            }
            fail("원래 예외를 던져야 한다")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        val cancellation = CancellationException("합성 취소")
        try {
            profiling.measure(ProfileStage.HEALTH_READ) {
                nanos = 60L
                throw cancellation
            }
            fail("원래 취소를 던져야 한다")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        profiling.start(ProfileStage.TERMINAL_HISTORY)
        nanos = 90L
        val summary = profiling.snapshot()
        assertEquals(ProfileStats(1, 1, 0, 0, 10, false), summary.stages[ProfileStage.EXECUTION])
        assertEquals(ProfileStats(1, 0, 1, 0, 20, false), summary.stages[ProfileStage.CSV_EXPORT])
        assertEquals(ProfileStats(1, 0, 0, 1, 30, false), summary.stages[ProfileStage.HEALTH_READ])
        assertEquals(ProfileStats(1, 0, 0, 0, 0, true), summary.stages[ProfileStage.TERMINAL_HISTORY])
        profiling.finish(ProfileStage.TERMINAL_HISTORY, ProfileOutcome.COMPLETED)
        assertTrue(summary.stages.getValue(ProfileStage.TERMINAL_HISTORY).active)
        assertEquals(ProfileStats(1, 1, 0, 0, 30, false), profiling.snapshot().stages[ProfileStage.TERMINAL_HISTORY])
    }

    @Test
    fun `Profiling storage remains bounded after repeated requests and terminal snapshots`() = runBlocking {
        var nanos = 0L
        val profiling = SyncProfiling(ProfilingClock { nanos })
        repeat(2_000) {
            profiling.measure(ProfileStage.TERMINAL_HISTORY) {
                for (kind in DriveOperation.entries) {
                    profiling.operation(kind) {
                        profiling.request(kind) { nanos++ }
                    }
                }
            }
            val summary = profiling.snapshot()
            assertEquals(setOf(ProfileStage.TERMINAL_HISTORY), summary.stages.keys)
            assertEquals(DriveOperation.entries.toSet(), summary.operations.keys)
            assertEquals(DriveOperation.entries.toSet(), summary.requests.keys)
        }
        val summary = profiling.snapshot()
        assertEquals(ProfileStats(2_000, 2_000, 0, 0, 14_000, false), summary.stages[ProfileStage.TERMINAL_HISTORY])
        for (kind in DriveOperation.entries) {
            assertEquals(ProfileStats(2_000, 2_000, 0, 0, 2_000, false), summary.operations[kind])
            assertEquals(ProfileStats(2_000, 2_000, 0, 0, 2_000, false), summary.requests[kind])
        }
        val ticks = ArrayDeque(listOf(0L, Long.MAX_VALUE, 0L, 2L))
        val saturating = SyncProfiling(ProfilingClock { ticks.removeFirst() })
        repeat(2) { saturating.measureValue(ProfileStage.EXECUTION) {} }
        assertEquals(ProfileStats(2, 2, 0, 0, Long.MAX_VALUE, false), saturating.snapshot().stages[ProfileStage.EXECUTION])
    }

    @Test
    fun `Clock failures preserve block return values and original exception identity`() = runBlocking {
        for (failAtStart in listOf(true, false)) {
            var calls = 0
            val profiling = SyncProfiling(ProfilingClock {
                calls++
                if (failAtStart || calls % 2 == 0) error("합성 시계 결함")
                10L
            })
            val value = Any()
            assertSame(value, profiling.measure(ProfileStage.EXECUTION) { value })
            assertSame(value, profiling.measureValue(ProfileStage.CSV_EXPORT) { value })
            assertSame(value, profiling.operation(DriveOperation.FILE_CREATE) { value })
            assertSame(value, profiling.request(DriveOperation.FILE_UPDATE) { value })
            val failure = IllegalStateException("합성 원래 오류")
            val cancellation = CancellationException("합성 원래 취소")
            for (original in listOf(failure, cancellation)) {
                try {
                    profiling.measure(ProfileStage.HEALTH_READ) { throw original }
                    fail("원래 예외를 던져야 한다")
                } catch (actual: Throwable) { assertSame(original, actual) }
                try {
                    profiling.measureValue(ProfileStage.NORMALIZE) { throw original }
                    fail("원래 예외를 던져야 한다")
                } catch (actual: Throwable) { assertSame(original, actual) }
                try {
                    profiling.operation(DriveOperation.FILE_CREATE) { throw original }
                    fail("원래 예외를 던져야 한다")
                } catch (actual: Throwable) { assertSame(original, actual) }
                try {
                    profiling.request(DriveOperation.FILE_UPDATE) { throw original }
                    fail("원래 예외를 던져야 한다")
                } catch (actual: Throwable) { assertSame(original, actual) }
            }
            assertEquals(ProfileSummary(emptyMap(), emptyMap(), emptyMap()), profiling.snapshot())
        }
        val ticks = ArrayDeque(listOf(10L, 9L))
        val backwards = SyncProfiling(ProfilingClock { ticks.removeFirst() })
        assertEquals("반환값", backwards.measureValue(ProfileStage.EXECUTION) { "반환값" })
        assertTrue(backwards.snapshot().stages.isEmpty())
    }
}
