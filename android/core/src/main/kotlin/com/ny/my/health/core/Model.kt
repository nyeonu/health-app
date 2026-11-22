package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import java.util.Collections

class AggregateDuration(duration: Duration?, dataOrigins: Set<String>) {
    val duration: Duration? = duration.also { require(it == null || !it.isNegative) }
    val dataOrigins: Set<String> = Collections.unmodifiableSet(LinkedHashSet(dataOrigins))
    val minutes: Double?
        get() = duration?.let { it.seconds / 60.0 + it.nano / 60000000000.0 }

    override fun equals(other: Any?): Boolean =
        other is AggregateDuration && duration == other.duration && dataOrigins == other.dataOrigins
    override fun hashCode(): Int = 31 * (duration?.hashCode() ?: 0) + dataOrigins.hashCode()
}

enum class DataType { STEPS, WEIGHT, HEART_RATE, SLEEP, EXERCISE }

data class WeightMeasurement(
    val recordId: String,
    val sourcePackage: String,
    val time: Instant,
    val kilograms: Double,
)

data class HeartRateSample(
    val recordId: String,
    val sourcePackage: String,
    val time: Instant,
    val bpm: Long,
)

data class SleepSession(
    val recordId: String,
    val sourcePackage: String,
    val start: Instant,
    val end: Instant,
)

data class ExerciseSession(
    val recordId: String,
    val sourcePackage: String,
    val start: Instant,
    val end: Instant,
    val exerciseType: Int,
)

data class HealthSnapshot(
    val date: LocalDate,
    val steps: Long?,
    val weights: List<WeightMeasurement>,
    val heartRateSamples: List<HeartRateSample>,
    val sleepSessions: List<SleepSession>,
    val exerciseSessions: List<ExerciseSession>,
    val grantedTypes: Set<DataType>,
    val sleepSummary: AggregateDuration? = null,
    val exerciseSummary: AggregateDuration? = null,
)

data class NormalizedDay(
    val date: LocalDate,
    val generatedAt: Instant,
    val steps: Long?,
    val weightKg: Double?,
    val heartRateSamples: List<HeartRateSample>,
    val sleepSessions: List<SleepSession>,
    val exerciseSessions: List<ExerciseSession>,
    val sleepSummary: AggregateDuration? = null,
    val exerciseSummary: AggregateDuration? = null,
)

data class CsvFile(val relativePath: String, val content: String)

data class SyncResult(
    val status: SyncStatus,
    val uploadedFiles: Int = 0,
    val errorCode: String? = null,
    val driveFailure: DriveFailureDetails? = null,
)

enum class SyncStatus { SUCCESS, EMPTY, PERMISSION_REQUIRED, FAILED }
