package com.ny.my.health.core

import java.time.Duration
import java.time.Instant

class VersionedCsvExporter : CsvExporter {
    override fun export(day: NormalizedDay): List<CsvFile> = buildList {
        if (day.steps != null || day.weightKg != null) {
            add(file("daily", day, listOf(
                listOf("schema_version", "date", "timezone", "steps", "weight_kg", "resting_hr_bpm", "sleep_min", "exercise_min", "generated_at"),
                listOf("0.1", day.date, "Asia/Seoul", day.steps, day.weightKg, null, null, null, day.generatedAt),
            )))
        }
        if (day.exerciseSessions.isNotEmpty()) {
            val rows = day.exerciseSessions.sortedWith(compareBy<ExerciseSession> { it.start }.thenBy { it.end }
                .thenBy { it.sourcePackage }.thenBy { it.recordId }.thenBy { it.exerciseType })
            add(file("workouts", day, listOf(listOf("schema_version", "record_id", "source_package", "start_time", "end_time", "exercise_type", "duration_min")) + rows.map {
                // Preserve the Health Connect exercise-type integer without a lossy label mapping.
                listOf("0.1", it.recordId, it.sourcePackage, it.start, it.end, it.exerciseType, minutes(it.start, it.end))
            }))
        }
        if (day.heartRateSamples.isNotEmpty()) {
            val rows = day.heartRateSamples.sortedWith(compareBy<HeartRateSample> { it.time }
                .thenBy { it.sourcePackage }.thenBy { it.recordId }.thenBy { it.bpm })
            add(file("heart-rate", day, listOf(listOf("schema_version", "record_id", "source_package", "sample_time", "bpm")) + rows.map {
                listOf("0.1", it.recordId, it.sourcePackage, it.time, it.bpm)
            }))
        }
        if (day.sleepSessions.isNotEmpty()) {
            val rows = day.sleepSessions.sortedWith(compareBy<SleepSession> { it.start }.thenBy { it.end }
                .thenBy { it.sourcePackage }.thenBy { it.recordId })
            add(file("sleep", day, listOf(listOf("schema_version", "record_id", "source_package", "start_time", "end_time", "duration_min")) + rows.map {
                listOf("0.1", it.recordId, it.sourcePackage, it.start, it.end, minutes(it.start, it.end))
            }))
        }
    }

    private fun minutes(start: Instant, end: Instant): Double {
        val duration = Duration.between(start, end)
        return duration.seconds / 60.0 + duration.nano / 60_000_000_000.0
    }

    private fun file(dataset: String, day: NormalizedDay, rows: List<List<Any?>>): CsvFile = CsvFile(
        "$dataset/${day.date}.csv",
        rows.joinToString(separator = "\r\n", postfix = "\r\n") { row -> row.joinToString(",") { escape(it) } },
    )

    private fun escape(value: Any?): String {
        val text = value?.toString() ?: ""
        return if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${text.replace("\"", "\"\"")}\""
        } else text
    }
}
