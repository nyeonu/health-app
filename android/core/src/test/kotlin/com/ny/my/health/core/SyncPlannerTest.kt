package com.ny.my.health.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SyncPlannerTest {
    @Test
    fun `Recent two days reserve slots before oldest eligible gaps and retain blocked pending days`() {
        for (gapCount in listOf(0, 1, 7, 8)) {
            val f = SyncFixture(LocalDate.of(2026, 11, 5), 20)
            val gaps = (0 until gapCount).map { f.config.enrolledDay.plusDays(it.toLong()) }
            val batch = SyncPlanner.plan(f.state(candidates = gaps.take(7)), f.today, f.access)
            assertEquals((gaps.take(5) + listOf(f.today.minusDays(2), f.today.minusDays(1))).sorted(), batch.dates)
            assertEquals(20L, batch.remainingPending)
        }
        for (scope in listOf(ReadScope.SUMMARY, ReadScope.WORKOUT_SCOPED)) {
            val f = SyncFixture(LocalDate.of(2026, 11, 5), 16, scope)
            val start = f.config.enrolledDay
            val gaps = (0L..6L).map(start::plusDays)
            val first = SyncPlanner.plan(f.state(candidates = gaps), f.today, f.access)
            val recent = listOf(f.today.minusDays(2), f.today.minusDays(1))
            assertEquals((gaps.take(5) + recent).sorted(), first.dates)
            val secondGaps = (5L..11L).map(start::plusDays)
            val state = f.state(first.dates.take(2).map { f.day(it, false) }, secondGaps,
                f.summary.copy(scopePendingCount = 9))
            val second = SyncPlanner.plan(state, f.today, f.access)
            assertEquals((secondGaps.take(5) + recent).sorted(), second.dates)
            assertTrue(second.dates.first() > first.dates.first())
            assertNull(SyncPlanner.coverage(state, f.today).checkedThrough)
            assertEquals(9L, second.remainingPending)
            val duplicate = SyncPlanner.plan(f.state(candidates = listOf(f.today.minusDays(2))), f.today, f.access)
            assertEquals(recent, duplicate.dates)
        }
    }

    @Test
    fun `Dates below the conservative history floor and unknown access remain blocked instead of empty`() {
        val f = SyncFixture(LocalDate.of(2026, 11, 5), 40)
        val floor = f.today.minusDays(30)
        val state = f.state(candidates = listOf(floor.minusDays(1), floor, floor.plusDays(1)),
            summary = f.summary.copy(scopeBlockedCount = 10))
        val batch = SyncPlanner.plan(state, f.today, f.access)
        assertEquals(BlockReason.HISTORY_ACCESS_REQUIRED, batch.blocked[floor.minusDays(1)])
        assertTrue(batch.dates.containsAll(listOf(floor, floor.plusDays(1))))
        assertFalse(batch.dates.contains(floor.minusDays(1)))
        assertEquals(40L, batch.remainingPending)
        assertEquals(10L, SyncPlanner.coverage(state, f.today).scopeBlockedCount)
        for (access in listOf(f.access.copy(available = false), f.access.copy(granted = emptySet()),
            f.access.copy(scopeByType = emptyMap()), f.access.copy(scopeByType = mapOf(DataType.STEPS to ReadScope.BLOCKED)))) {
            val unknown = SyncPlanner.plan(state, f.today, access)
            assertNull(unknown.scopeKey)
            assertTrue(unknown.dates.isEmpty())
            assertEquals(40L, unknown.remainingPending)
            assertEquals(setOf(f.today.minusDays(2), f.today.minusDays(1)), unknown.blocked.keys)
            assertTrue(unknown.blocked.values.all { it == BlockReason.SOURCE_ACCESS_REQUIRED })
        }
        for (reason in listOf(BlockReason.SOURCE_ACCESS_REQUIRED, BlockReason.HISTORY_ACCESS_REQUIRED,
            BlockReason.DESTINATION_BLOCKED, BlockReason.RETRY_EXHAUSTED)) {
            val row = f.day(floor, false).copy(phase = DayPhase.BLOCKED, checkedAt = null, blocker = reason)
            val result = SyncPlanner.plan(f.state(listOf(row), listOf(floor)), f.today, f.access)
            assertEquals(reason !in setOf(BlockReason.DESTINATION_BLOCKED, BlockReason.RETRY_EXHAUSTED), floor in result.dates)
        }
        val unknownScope = state.copy(scopeKey = null, requestedScopes = emptyMap(),
            coverage = state.coverage.copy(scopeKey = null, scopePendingCount = null, scopeBlockedCount = null))
        assertNull(SyncPlanner.coverage(unknownScope, f.today).scopePendingCount)
        assertTrue(SyncPlanner.plan(state.copy(configuration = null), f.today, f.access).dates.isEmpty())
    }
}
