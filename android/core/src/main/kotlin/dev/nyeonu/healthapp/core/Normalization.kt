package dev.nyeonu.healthapp.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object DayWindow {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")

    fun start(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    fun end(date: LocalDate): Instant = start(date.plusDays(1))

    fun yesterday(clock: Clock): LocalDate = LocalDate.now(clock.withZone(zone)).minusDays(1)
}

class DefaultNormalizer : Normalizer {
    override fun normalize(snapshot: HealthSnapshot, generatedAt: Instant): NormalizedDay {
        val start = DayWindow.start(snapshot.date)
        val end = DayWindow.end(snapshot.date)
        fun inDay(time: Instant) = time >= start && time < end
        fun overlaps(sessionStart: Instant, sessionEnd: Instant) = sessionStart < end && sessionEnd > start

        // Latest weight wins; ties use source, record ID, then kilograms (lexicographic max).
        // Detail rows sort by event bounds/time, source, identity, then measurement/type.
        val weightOrder = compareBy<WeightMeasurement> { it.time }
            .thenBy { it.sourcePackage }.thenBy { it.recordId }.thenBy { it.kilograms }
        val heartRateOrder = compareBy<HeartRateSample> { it.time }
            .thenBy { it.sourcePackage }.thenBy { it.recordId }.thenBy { it.bpm }
        val sleepOrder = compareBy<SleepSession> { it.start }.thenBy { it.end }
            .thenBy { it.sourcePackage }.thenBy { it.recordId }
        val exerciseOrder = compareBy<ExerciseSession> { it.start }.thenBy { it.end }
            .thenBy { it.sourcePackage }.thenBy { it.recordId }.thenBy { it.exerciseType }

        return NormalizedDay(
            date = snapshot.date,
            generatedAt = generatedAt,
            steps = snapshot.steps.takeIf { DataType.STEPS in snapshot.grantedTypes },
            weightKg = if (DataType.WEIGHT in snapshot.grantedTypes) {
                snapshot.weights.filter { inDay(it.time) }.maxWithOrNull(weightOrder)?.kilograms
            } else null,
            heartRateSamples = if (DataType.HEART_RATE in snapshot.grantedTypes) {
                snapshot.heartRateSamples.filter { inDay(it.time) }.sortedWith(heartRateOrder)
            } else emptyList(),
            // Preserve complete original session bounds; daily duration aggregation is unvalidated.
            sleepSessions = if (DataType.SLEEP in snapshot.grantedTypes) {
                snapshot.sleepSessions.filter { overlaps(it.start, it.end) }.sortedWith(sleepOrder)
            } else emptyList(),
            exerciseSessions = if (DataType.EXERCISE in snapshot.grantedTypes) {
                snapshot.exerciseSessions.filter { overlaps(it.start, it.end) }.sortedWith(exerciseOrder)
            } else emptyList(),
        )
    }
}
