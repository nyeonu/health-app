package dev.nyeonu.healthapp.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SyncCoordinatorTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val generatedAt = Instant.parse("2026-10-31T00:00:00Z")
    private val clock = Clock.fixed(generatedAt, ZoneOffset.UTC)
    private fun snapshot() = HealthSnapshot(date, null, emptyList(), emptyList(), emptyList(), emptyList(), DataType.entries.toSet())
    private fun reader(snapshot: HealthSnapshot) = object : HealthReader {
        override suspend fun read(date: LocalDate): HealthSnapshot {
            assertEquals(this@SyncCoordinatorTest.date, date)
            return snapshot
        }
    }

    @Test
    fun `Empty and denied reads never upload or report success`() = runBlocking {
        var uploads = 0
        val uploader = object : DriveUploader {
            override suspend fun upsert(file: CsvFile): String { uploads++; return "synthetic-id" }
        }
        val denied = snapshot().copy(steps = 42, grantedTypes = emptySet())
        assertEquals(SyncResult(SyncStatus.PERMISSION_REQUIRED), SyncCoordinator(reader(denied), DefaultNormalizer(), VersionedCsvExporter(), uploader, clock).sync(date))
        assertEquals(SyncResult(SyncStatus.EMPTY), SyncCoordinator(reader(snapshot()), DefaultNormalizer(), VersionedCsvExporter(), uploader, clock).sync(date))
        assertEquals(0, uploads)
    }

    @Test
    fun `Partial upload failure reports only confirmed files`() = runBlocking {
        val events = mutableListOf<String>()
        val populated = snapshot().copy(steps = 42, heartRateSamples = listOf(HeartRateSample("synthetic-record", "synthetic.source", generatedAt.minusSeconds(86400), 70)))
        val trackedReader = object : HealthReader {
            override suspend fun read(date: LocalDate): HealthSnapshot { events += "read"; return populated }
        }
        val normalizer = object : Normalizer {
            override fun normalize(snapshot: HealthSnapshot, generatedAt: Instant): NormalizedDay {
                events += "normalize"
                assertEquals(this@SyncCoordinatorTest.generatedAt, generatedAt)
                return DefaultNormalizer().normalize(snapshot, generatedAt)
            }
        }
        val exporter = object : CsvExporter {
            override fun export(day: NormalizedDay): List<CsvFile> { events += "export"; return VersionedCsvExporter().export(day) }
        }
        for (blankConfirmation in listOf(false, true)) {
            events.clear()
            val uploader = object : DriveUploader {
                override suspend fun upsert(file: CsvFile): String {
                    events += file.relativePath
                    if (file.relativePath.startsWith("daily/")) return "synthetic-confirmed-id"
                    if (blankConfirmation) return " "
                    throw IllegalStateException("sensitive detail must not escape")
                }
            }
            val result = SyncCoordinator(trackedReader, normalizer, exporter, uploader, clock).sync(date)
            assertEquals(SyncResult(SyncStatus.FAILED, 1, "UPLOAD_FAILED"), result)
            assertEquals(listOf("read", "normalize", "export", "daily/$date.csv", "heart-rate/$date.csv"), events)
        }
    }

    @Test
    fun `Cancellation propagates without reporting success`() = runBlocking {
        val cancellation = CancellationException("synthetic cancellation")
        val populated = snapshot().copy(steps = 42)
        for (phase in listOf("read", "normalize", "export", "upload")) {
            var uploads = 0
            val reader = object : HealthReader {
                override suspend fun read(date: LocalDate): HealthSnapshot {
                    if (phase == "read") throw cancellation
                    return populated
                }
            }
            val normalizer = object : Normalizer {
                override fun normalize(snapshot: HealthSnapshot, generatedAt: Instant): NormalizedDay {
                    if (phase == "normalize") throw cancellation
                    return DefaultNormalizer().normalize(snapshot, generatedAt)
                }
            }
            val exporter = object : CsvExporter {
                override fun export(day: NormalizedDay): List<CsvFile> {
                    if (phase == "export") throw cancellation
                    return VersionedCsvExporter().export(day)
                }
            }
            val uploader = object : DriveUploader {
                override suspend fun upsert(file: CsvFile): String { uploads++; throw cancellation }
            }
            try {
                SyncCoordinator(reader, normalizer, exporter, uploader, clock).sync(date)
                fail("Cancellation must propagate from $phase")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
            assertEquals(if (phase == "upload") 1 else 0, uploads)
        }
    }

    @Test
    fun `Successful partial-grant sync uploads only authorized populated datasets`() = runBlocking {
        val uploads = mutableListOf<CsvFile>()
        val time = generatedAt.minusSeconds(86400)
        val partial = snapshot().copy(
            steps = 42,
            weights = listOf(WeightMeasurement("denied-weight", "synthetic.source", time, 61.25)),
            heartRateSamples = listOf(HeartRateSample("authorized-heart-rate", "synthetic.source", time, 70)),
            sleepSessions = listOf(SleepSession("denied-sleep", "synthetic.source", time, time.plusSeconds(90))),
            exerciseSessions = listOf(ExerciseSession("denied-exercise", "synthetic.source", time, time.plusSeconds(60), 1)),
            grantedTypes = setOf(DataType.STEPS, DataType.HEART_RATE),
        )
        val uploader = object : DriveUploader {
            override suspend fun upsert(file: CsvFile): String {
                uploads += file
                return "synthetic-confirmed-${uploads.size}"
            }
        }
        val result = SyncCoordinator(reader(partial), DefaultNormalizer(), VersionedCsvExporter(), uploader, clock).sync(date)
        assertEquals(SyncResult(SyncStatus.SUCCESS, 2), result)
        assertEquals(listOf("daily/$date.csv", "heart-rate/$date.csv"), uploads.map { it.relativePath })
        assertEquals("schema_version,date,timezone,steps,weight_kg,resting_hr_bpm,sleep_min,exercise_min,generated_at\r\n0.1,2026-10-30,Asia/Seoul,42,,,,,2026-10-31T00:00:00Z\r\n", uploads[0].content)
        assertEquals("schema_version,record_id,source_package,sample_time,bpm\r\n0.1,authorized-heart-rate,synthetic.source,2026-10-30T00:00:00Z,70\r\n", uploads[1].content)
    }

    @Test
    fun `Read normalization and export failures expose only redacted phase codes`() = runBlocking {
        for ((phase, expectedCode) in listOf("read" to "READ_FAILED", "normalize" to "NORMALIZATION_FAILED", "export" to "EXPORT_FAILED")) {
            var uploads = 0
            val reader = object : HealthReader {
                override suspend fun read(date: LocalDate): HealthSnapshot {
                    if (phase == "read") throw IllegalStateException("sensitive synthetic failure")
                    return snapshot().copy(steps = 42)
                }
            }
            val normalizer = object : Normalizer {
                override fun normalize(snapshot: HealthSnapshot, generatedAt: Instant): NormalizedDay {
                    if (phase == "normalize") throw IllegalStateException("sensitive synthetic failure")
                    return DefaultNormalizer().normalize(snapshot, generatedAt)
                }
            }
            val exporter = object : CsvExporter {
                override fun export(day: NormalizedDay): List<CsvFile> {
                    if (phase == "export") throw IllegalStateException("sensitive synthetic failure")
                    return VersionedCsvExporter().export(day)
                }
            }
            val uploader = object : DriveUploader {
                override suspend fun upsert(file: CsvFile): String { uploads++; return "synthetic-id" }
            }
            assertEquals(SyncResult(SyncStatus.FAILED, 0, expectedCode), SyncCoordinator(reader, normalizer, exporter, uploader, clock).sync(date))
            assertEquals(0, uploads)
        }
    }

    @Test
    fun `Already cancelled sync performs no read or upload`() = runBlocking {
        var reads = 0
        var uploads = 0
        val reader = object : HealthReader {
            override suspend fun read(date: LocalDate): HealthSnapshot { reads++; return snapshot().copy(steps = 42) }
        }
        val uploader = object : DriveUploader {
            override suspend fun upsert(file: CsvFile): String { uploads++; return "synthetic-id" }
        }
        val coordinator = SyncCoordinator(reader, DefaultNormalizer(), VersionedCsvExporter(), uploader, clock)
        val cancelledJob = Job().apply { cancel() }
        // UNDISPATCHED enters sync even though its supplied parent is already cancelled.
        val result = async(cancelledJob, start = CoroutineStart.UNDISPATCHED) { coordinator.sync(date) }
        try {
            result.await()
            fail("Already cancelled sync must propagate cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, reads)
            assertEquals(0, uploads)
        }
    }
}
