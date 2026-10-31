package com.ny.my.health.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizationTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val start = Instant.parse("2026-10-29T15:00:00Z")
    private val end = Instant.parse("2026-10-30T15:00:00Z")
    private val generatedAt = Instant.parse("2026-10-31T00:00:00Z")
    private val normalizer = DefaultNormalizer()

    @Test
    fun `KST day boundaries are half open across UTC midnight`() {
        assertEquals("Asia/Seoul", DayWindow.zone.id)
        assertEquals(start, DayWindow.start(date))
        assertEquals(end, DayWindow.end(date))
        assertEquals(date, DayWindow.yesterday(Clock.fixed(end, ZoneOffset.UTC)))
        assertEquals(date.minusDays(1), DayWindow.yesterday(Clock.fixed(end.minusNanos(1), ZoneOffset.UTC)))
        val atStart = HeartRateSample("start", "synthetic.source", start, 60)
        val atMidnight = HeartRateSample("midnight", "synthetic.source", Instant.parse("2026-10-30T00:00:00Z"), 70)
        val beforeEnd = HeartRateSample("before-end", "synthetic.source", end.minusNanos(1), 80)
        val snapshot = emptySnapshot().copy(heartRateSamples = listOf(
            HeartRateSample("end", "synthetic.source", end, 90),
            beforeEnd, atMidnight, atStart,
            HeartRateSample("before-start", "synthetic.source", start.minusNanos(1), 50),
        ))
        val result = normalizer.normalize(snapshot, generatedAt)
        assertEquals(listOf(atStart, atMidnight, beforeEnd), result.heartRateSamples)
        assertEquals(date, result.date)
        assertEquals(generatedAt, result.generatedAt)
    }

    @Test
    fun `Last authorized in-day weight is selected and missing values stay null`() {
        val early = WeightMeasurement("early", "synthetic.source", start, 60.0)
        val late = WeightMeasurement("late", "synthetic.source", end.minusSeconds(1), 61.5)
        val snapshot = emptySnapshot().copy(weights = listOf(
            WeightMeasurement("end", "synthetic.source", end, 99.0), late, early,
            WeightMeasurement("before-start", "synthetic.source", start.minusNanos(1), 98.0),
        ))
        assertEquals(61.5, normalizer.normalize(snapshot, generatedAt).weightKg!!, 0.0)
        val tied = snapshot.copy(weights = listOf(
            late.copy(recordId = "a", kilograms = 62.0),
            late.copy(recordId = "b", kilograms = 63.0),
        ))
        assertEquals(63.0, normalizer.normalize(tied, generatedAt).weightKg!!, 0.0)
        assertEquals(normalizer.normalize(tied, generatedAt), normalizer.normalize(tied.copy(weights = tied.weights.reversed()), generatedAt))
        val missing = normalizer.normalize(emptySnapshot(), generatedAt)
        assertNull(missing.steps)
        assertNull(missing.weightKg)
        assertTrue(missing.heartRateSamples.isEmpty())
        assertTrue(missing.sleepSessions.isEmpty())
        assertTrue(missing.exerciseSessions.isEmpty())
        assertEquals(0L, normalizer.normalize(emptySnapshot().copy(steps = 0), generatedAt).steps)
    }

    @Test
    fun `Denied categories never produce normalized measurements`() {
        val populated = emptySnapshot().copy(
            steps = 100,
            weights = listOf(WeightMeasurement("weight", "synthetic.source", start, 60.0)),
            heartRateSamples = listOf(HeartRateSample("hr", "synthetic.source", start, 60)),
            sleepSessions = listOf(SleepSession("sleep", "synthetic.source", start, end)),
            exerciseSessions = listOf(ExerciseSession("exercise", "synthetic.source", start, end, 1)),
        )
        for (denied in DataType.entries) {
            val result = normalizer.normalize(populated.copy(grantedTypes = DataType.entries.toSet() - denied), generatedAt)
            assertEquals(if (denied == DataType.STEPS) null else 100L, result.steps)
            assertEquals(if (denied == DataType.WEIGHT) null else 60.0, result.weightKg)
            assertEquals(if (denied == DataType.HEART_RATE) emptyList<HeartRateSample>() else populated.heartRateSamples, result.heartRateSamples)
            assertEquals(if (denied == DataType.SLEEP) emptyList<SleepSession>() else populated.sleepSessions, result.sleepSessions)
            assertEquals(if (denied == DataType.EXERCISE) emptyList<ExerciseSession>() else populated.exerciseSessions, result.exerciseSessions)
        }
    }

    @Test
    fun `Session identity and original bounds are preserved`() {
        val sleepAcrossStart = SleepSession("sleep-a", "synthetic.source.a", start.minusSeconds(3600), start.plusSeconds(3600))
        val sleepAcrossEnd = SleepSession("sleep-b", "synthetic.source.b", end.minusSeconds(3600), end.plusSeconds(3600))
        val exerciseAcrossDay = ExerciseSession("exercise-a", "synthetic.source.a", start.minusSeconds(60), end.plusSeconds(60), 42)
        val exerciseInDay = ExerciseSession("exercise-b", "synthetic.source.b", start.plusSeconds(60), start.plusSeconds(120), 7)
        val snapshot = emptySnapshot().copy(
            sleepSessions = listOf(sleepAcrossEnd, SleepSession("excluded", "synthetic.source", end, end.plusSeconds(1)), sleepAcrossStart),
            exerciseSessions = listOf(exerciseInDay, ExerciseSession("excluded", "synthetic.source", start.minusSeconds(1), start, 1), exerciseAcrossDay),
        )
        val result = normalizer.normalize(snapshot, generatedAt)
        assertEquals(listOf(sleepAcrossStart, sleepAcrossEnd), result.sleepSessions)
        assertEquals(listOf(exerciseAcrossDay, exerciseInDay), result.exerciseSessions)
        assertEquals(result, normalizer.normalize(snapshot.copy(sleepSessions = snapshot.sleepSessions.reversed(), exerciseSessions = snapshot.exerciseSessions.reversed()), generatedAt))
    }

    private fun emptySnapshot() = HealthSnapshot(
        date = date,
        steps = null,
        weights = emptyList(),
        heartRateSamples = emptyList(),
        sleepSessions = emptyList(),
        exerciseSessions = emptyList(),
        grantedTypes = DataType.entries.toSet(),
    )
}
