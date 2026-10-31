package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val time = Instant.parse("2026-10-30T00:00:00Z")
    private val exporter = VersionedCsvExporter()
    private fun emptyDay() = NormalizedDay(date, time, null, null, emptyList(), emptyList(), emptyList())

    @Test
    fun `CSV headers ordering quoting and missing values follow v0_1`() {
        val day = emptyDay().copy(
            steps = 0,
            heartRateSamples = listOf(HeartRateSample("id,\"quoted\"", "synthetic\r\nsource", time, 72)),
            sleepSessions = listOf(SleepSession("sleep", "synthetic.source", time, time.plusSeconds(90))),
            exerciseSessions = listOf(ExerciseSession("exercise", "synthetic.source", time, time.plusSeconds(30), 42)),
        )
        val files = exporter.export(day)
        assertEquals(listOf("daily/$date.csv", "workouts/$date.csv", "heart-rate/$date.csv", "sleep/$date.csv"), files.map { it.relativePath })
        assertEquals("schema_version,date,timezone,steps,weight_kg,resting_hr_bpm,sleep_min,exercise_min,generated_at\r\n0.1,2026-10-30,Asia/Seoul,0,,,,,2026-10-30T00:00:00Z\r\n", files[0].content)
        assertEquals("schema_version,record_id,source_package,start_time,end_time,exercise_type,duration_min\r\n0.1,exercise,synthetic.source,2026-10-30T00:00:00Z,2026-10-30T00:00:30Z,42,0.5\r\n", files[1].content)
        assertEquals("schema_version,record_id,source_package,sample_time,bpm\r\n0.1,\"id,\"\"quoted\"\"\",\"synthetic\r\nsource\",2026-10-30T00:00:00Z,72\r\n", files[2].content)
        assertEquals("schema_version,record_id,source_package,start_time,end_time,duration_min\r\n0.1,sleep,synthetic.source,2026-10-30T00:00:00Z,2026-10-30T00:01:30Z,1.5\r\n", files[3].content)
        assertTrue(exporter.export(emptyDay()).isEmpty())
        val weightOnly = exporter.export(emptyDay().copy(weightKg = 61.25)).single()
        assertEquals("daily/$date.csv", weightOnly.relativePath)
        assertEquals("0.1,2026-10-30,Asia/Seoul,,61.25,,,,2026-10-30T00:00:00Z", weightOnly.content.split("\r\n")[1])
        assertEquals(listOf("heart-rate/$date.csv"), exporter.export(day.copy(steps = null, sleepSessions = emptyList(), exerciseSessions = emptyList())).map { it.relativePath })
    }

    @Test
    fun `Repeated exports use the same date paths and stable row order`() {
        val day = emptyDay().copy(
            heartRateSamples = listOf(HeartRateSample("b", "synthetic.source", time, 80), HeartRateSample("a", "synthetic.source", time, 70)),
            sleepSessions = listOf(SleepSession("b", "synthetic.source", time, time.plusSeconds(90)), SleepSession("a", "synthetic.source", time, time.plusSeconds(60))),
            exerciseSessions = listOf(ExerciseSession("b", "synthetic.source", time, time.plusSeconds(90), 2), ExerciseSession("a", "synthetic.source", time, time.plusSeconds(60), 1)),
        )
        val files = exporter.export(day)
        assertEquals(files, exporter.export(day))
        assertEquals(files, exporter.export(day.copy(heartRateSamples = day.heartRateSamples.reversed(), sleepSessions = day.sleepSessions.reversed(), exerciseSessions = day.exerciseSessions.reversed())))
        assertEquals(listOf("workouts/$date.csv", "heart-rate/$date.csv", "sleep/$date.csv"), files.map { it.relativePath })
        for (file in files) {
            val rows = file.content.split("\r\n")
            assertTrue(rows[1].startsWith("0.1,a,"))
            assertTrue(rows[2].startsWith("0.1,b,"))
        }
    }
}
