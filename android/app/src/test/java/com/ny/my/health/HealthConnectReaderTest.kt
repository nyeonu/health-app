package com.ny.my.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.CsvFile
import com.ny.my.health.core.DefaultNormalizer
import com.ny.my.health.core.DriveUploader
import com.ny.my.health.core.SyncCoordinator
import com.ny.my.health.core.SyncStatus
import com.ny.my.health.core.VersionedCsvExporter
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual reader + SDK requests/records; the provider is synthetic, not a live Health Connect test. */
class HealthConnectReaderTest {
    private val day = LocalDate.of(2026, 10, 30)
    private val start = DayWindow.start(day)
    private val end = DayWindow.end(day)

    private fun sleep(id: String, from: Instant, until: Instant) =
        SleepSessionRecord(from, null, until, null, Metadata.manualEntryWithId(id))

    private fun exercise(id: String, from: Instant, until: Instant) =
        ExerciseSessionRecord(from, null, until, null, Metadata.manualEntryWithId(id),
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING)

    private fun heartRate(id: String, time: Instant, count: Int) =
        HeartRateRecord(time.minusSeconds(1), null, time.plusSeconds(1), null,
            List(count) { HeartRateRecord.Sample(time, 70) }, Metadata.manualEntryWithId(id))

    private class Provider(vararg granted: KClass<out Record>) {
        var grants = granted.map { HealthPermission.getReadPermission(it) }.toSet()
        val requests = mutableListOf<ReadRecordsRequest<*>>()
        val aggregates = mutableListOf<AggregateRequest>()
        var records: List<Record> = emptyList()
        var read: (ReadRecordsRequest<*>) -> ReadRecordsResponse<out Record> = { request ->
            // Model start-time selection, deliberately not an overlap-aware fake that hides the bug.
            val selected = records.filter { record ->
                val instant = when (record) {
                    is SleepSessionRecord -> record.startTime
                    is ExerciseSessionRecord -> record.startTime
                    is HeartRateRecord -> record.startTime
                    is WeightRecord -> record.time
                    else -> error("Unexpected synthetic record")
                }
                request.recordType.isInstance(record) &&
                    (request.timeRangeFilter.startTime?.let { instant >= it } ?: true) &&
                    (request.timeRangeFilter.endTime?.let { instant < it } ?: true)
            }
            ReadRecordsResponse(selected, null)
        }
        private val permissions = proxy(PermissionController::class.java) { name, _ ->
            when (name) {
                "getGrantedPermissions" -> grants
                else -> error("Unexpected permission operation: $name")
            }
        }
        private val client = proxy(HealthConnectClient::class.java) { name, args ->
            when (name) {
                "getPermissionController" -> permissions
                "readRecords" -> {
                    val request = args[0] as ReadRecordsRequest<*>
                    requests += request
                    read(request)
                }
                "aggregate" -> {
                    aggregates += args[0] as AggregateRequest
                    AggregationResult(emptyMap(), emptyMap(), emptySet())
                }
                else -> error("Unexpected Health Connect operation: $name")
            }
        }
        val reader = HealthConnectReader({ client }, { HealthConnectClient.SDK_AVAILABLE })

        companion object {
            // A fail-on-unexpected-call SDK interface double; no new mocking/runtime dependency.
            private fun <T : Any> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
                checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                    call(method.name, args ?: emptyArray())
                }))
        }
    }

    @Test
    fun `Sleep and exercise queries retain overnight and multi day sessions with original bounds`() = runBlocking {
        val provider = Provider(SleepSessionRecord::class, ExerciseSessionRecord::class)
        provider.records = listOf<Record>(
            sleep("overnight", start.minusSeconds(3600), start.plusSeconds(7 * 3600)),
            sleep("multi-day", start.minusSeconds(5 * 86400), end.plusSeconds(86400)),
            sleep("ends-at-start", start.minusSeconds(3600), start),
            sleep("starts-at-end", end, end.plusSeconds(3600)),
            exercise("overnight-workout", start.minusSeconds(3600), start.plusSeconds(3600)),
            exercise("long-workout", start.minusSeconds(3 * 86400), end.plusSeconds(3600)),
            exercise("outside", start.minusSeconds(7200), start),
        )
        val result = provider.reader.read(day)
        assertEquals(setOf("overnight", "multi-day"), result.sleepSessions.map { it.recordId }.toSet())
        assertEquals(setOf("overnight-workout", "long-workout"), result.exerciseSessions.map { it.recordId }.toSet())
        val longSleep = result.sleepSessions.single { it.recordId == "multi-day" }
        assertEquals(start.minusSeconds(5 * 86400), longSleep.start)
        assertEquals(end.plusSeconds(86400), longSleep.end)
        assertTrue(provider.requests.all { it.timeRangeFilter == TimeRangeFilter.before(end) })
    }

    @Test
    fun `Heart rate query includes earlier parent records but exports only half open day samples`() = runBlocking {
        val provider = Provider(HeartRateRecord::class)
        provider.records = listOf(HeartRateRecord(
            start.minusSeconds(4 * 86400), null, end.plusSeconds(1), null,
            listOf(start.minusNanos(1), start, end.minusNanos(1), end).map {
                HeartRateRecord.Sample(it, 70)
            }, Metadata.manualEntryWithId("spanning-parent"),
        ))
        val result = provider.reader.read(day)
        assertEquals(listOf(start, end.minusNanos(1)), result.heartRateSamples.map { it.time })
        assertTrue(result.heartRateSamples.all { it.recordId == "spanning-parent" })
        assertEquals(TimeRangeFilter.before(end), provider.requests.single().timeRangeFilter)
    }

    @Test
    fun `Weight and aggregated steps keep the original daily query bounds`() = runBlocking {
        val provider = Provider(WeightRecord::class, StepsRecord::class)
        provider.records = listOf(
            WeightRecord(start.minusNanos(1), null, Mass.kilograms(60.0), Metadata.manualEntry()),
            WeightRecord(start, null, Mass.kilograms(61.0), Metadata.manualEntry()),
            WeightRecord(end, null, Mass.kilograms(62.0), Metadata.manualEntry()),
        )
        assertEquals(listOf(start), provider.reader.read(day).weights.map { it.time })
        assertEquals(TimeRangeFilter.between(start, end), provider.requests.single().timeRangeFilter)
        assertEquals(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)),
            provider.aggregates.single())
    }

    @Test
    fun `Null and empty last page tokens terminate without an extra request`() = runBlocking {
        for (terminal in listOf(null, "")) {
            val provider = Provider(SleepSessionRecord::class)
            provider.read = {
                check(provider.requests.size == 1) { "Unexpected reread after terminal page" }
                ReadRecordsResponse(listOf(sleep("one", start, end)), terminal)
            }
            assertEquals(1, provider.reader.read(day).sleepSessions.size)
            assertEquals(1, provider.requests.size)
        }
    }

    @Test
    fun `Multiple pages preserve interval query bounds and continuation tokens`() = runBlocking {
        val provider = Provider(SleepSessionRecord::class)
        provider.read = { request ->
            when (request.pageToken) {
                null -> ReadRecordsResponse(listOf(sleep("first", start.minusSeconds(3600), start.plusSeconds(1))), "next")
                "next" -> ReadRecordsResponse(listOf(sleep("second", start, end)), "")
                else -> error("Unexpected continuation token")
            }
        }
        assertEquals(listOf("first", "second"), provider.reader.read(day).sleepSessions.map { it.recordId })
        assertEquals(listOf(null, "next"), provider.requests.map { it.pageToken })
        assertTrue(provider.requests.all { it.pageSize == 1000 && it.timeRangeFilter == TimeRangeFilter.before(end) })
    }

    @Test
    fun `Repeated nonempty tokens still fail instead of returning partial data`() = runBlocking {
        val provider = Provider(SleepSessionRecord::class)
        provider.read = { ReadRecordsResponse(emptyList<SleepSessionRecord>(), "repeat") }
        val failure = runCatching { provider.reader.read(day) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Health Connect repeated page token", failure?.message)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `Page limit fails and a terminal token on the last permitted page succeeds`() = runBlocking {
        for (completes in listOf(false, true)) {
            val provider = Provider(SleepSessionRecord::class)
            provider.read = {
                val page = provider.requests.size
                ReadRecordsResponse(emptyList<SleepSessionRecord>(), if (completes && page == 100) "" else "page-$page")
            }
            val result = runCatching { provider.reader.read(day) }
            assertEquals(100, provider.requests.size)
            if (completes) assertTrue(result.isSuccess)
            else assertEquals("Health Connect page limit exceeded", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `Fresh denied grants prevent reads and later revocation fails without a partial snapshot`() = runBlocking {
        val provider = Provider(SleepSessionRecord::class)
        provider.grants = emptySet()
        assertTrue(provider.reader.read(day).grantedTypes.isEmpty())
        assertTrue(provider.requests.isEmpty())
        provider.grants = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
        provider.read = {
            if (provider.requests.size == 1) ReadRecordsResponse(listOf(sleep("partial", start, end)), "next")
            else throw SecurityException("synthetic revocation")
        }
        assertTrue(runCatching { provider.reader.read(day) }.exceptionOrNull() is SecurityException)
        assertTrue(provider.requests.all { it.recordType == SleepSessionRecord::class })
    }

    @Test
    fun `Oversized pages and excessive samples fail without returning a truncated snapshot`() = runBlocking {
        val pages = Provider(SleepSessionRecord::class)
        pages.read = { ReadRecordsResponse(List(1001) { sleep("synthetic", start, end) }, null) }
        assertEquals("Health Connect page size exceeded", runCatching { pages.reader.read(day) }.exceptionOrNull()?.message)
        assertEquals(1, pages.requests.size)

        val samples = Provider(HeartRateRecord::class)
        samples.records = listOf(HeartRateRecord(start, null, end, null,
            List(100_001) { HeartRateRecord.Sample(start, 70) }, Metadata.manualEntry()))
        assertEquals("Health Connect sample limit exceeded", runCatching { samples.reader.read(day) }.exceptionOrNull()?.message)
    }

    @Test
    fun `Unavailable SDK fails before acquiring a client`() = runBlocking {
        val reader = HealthConnectReader({ error("Must not acquire unavailable client") },
            { HealthConnectClient.SDK_UNAVAILABLE })
        assertEquals("Health Connect unavailable", runCatching { reader.read(day) }.exceptionOrNull()?.message)
    }

    @Test
    fun `Heart rate sample cap stops after the second oversized page without requesting a third`() = runBlocking {
        val provider = Provider(HeartRateRecord::class)
        provider.read = {
            check(provider.requests.size <= 2) { "Unexpected third page after sample budget exhausted" }
            ReadRecordsResponse(listOf(heartRate("past", start.minusSeconds(86400), 60_000)),
                "next-${provider.requests.size}")
        }
        val failure = runCatching { provider.reader.read(day) }.exceptionOrNull()
        assertEquals("Requests must stop at the first over-budget page", 2, provider.requests.size)
        assertEquals("Health Connect sample limit exceeded", failure?.message)
    }

    @Test
    fun `Heart rate sample budget accepts exactly 100000 and rejects 100001 across pages`() = runBlocking {
        for (secondPageCount in listOf(40_000, 40_001)) {
            val provider = Provider(HeartRateRecord::class)
            provider.read = { request ->
                when (request.pageToken) {
                    null -> ReadRecordsResponse(listOf(heartRate("past", start.minusSeconds(86400), 60_000)), "next")
                    "next" -> ReadRecordsResponse(listOf(heartRate("today", start, secondPageCount)), "")
                    else -> error("Unexpected continuation token")
                }
            }
            val result = runCatching { provider.reader.read(day) }
            assertEquals(2, provider.requests.size)
            if (secondPageCount == 40_000) {
                assertEquals(40_000, result.getOrThrow().heartRateSamples.size)
                assertTrue(result.getOrThrow().heartRateSamples.all { it.recordId == "today" && it.time == start })
            } else assertEquals("Health Connect sample limit exceeded", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `Past heart rate samples are processed and discarded before requesting the next page`() = runBlocking {
        val provider = Provider(HeartRateRecord::class)
        var inspected = 0
        val oldTime = start.minusSeconds(86400)
        val oldSamples = object : AbstractList<HeartRateRecord.Sample>() {
            override val size = 3
            override fun get(index: Int): HeartRateRecord.Sample {
                inspected++
                return HeartRateRecord.Sample(oldTime, 70)
            }
        }
        val oldRecord = HeartRateRecord(oldTime.minusSeconds(1), null, oldTime.plusSeconds(1), null,
            oldSamples, Metadata.manualEntryWithId("past"))
        inspected = 0 // Ignore SDK-constructor validation; observe reader page processing only.
        provider.read = { request ->
            when (request.pageToken) {
                null -> ReadRecordsResponse(listOf(oldRecord), "next")
                "next" -> {
                    assertEquals("First page must be consumed before the next provider request", 3, inspected)
                    ReadRecordsResponse(listOf(heartRate("today", start, 2)), null)
                }
                else -> error("Unexpected continuation token")
            }
        }
        val result = provider.reader.read(day)
        assertEquals(2, provider.requests.size)
        assertEquals(2, result.heartRateSamples.size)
        assertTrue(result.heartRateSamples.all { it.recordId == "today" })
    }

    @Test
    fun `Read limit failure after a populated page never uploads a partial day`() = runBlocking {
        val provider = Provider(HeartRateRecord::class)
        provider.read = {
            check(provider.requests.size <= 2) { "Unexpected third page after sample budget exhausted" }
            ReadRecordsResponse(listOf(heartRate("today", start, 60_000)), "next-${provider.requests.size}")
        }
        var uploads = 0
        val uploader = object : DriveUploader {
            override suspend fun upsert(file: CsvFile): String { uploads++; return "synthetic-confirmation" }
        }
        val result = SyncCoordinator(provider.reader, DefaultNormalizer(), VersionedCsvExporter(), uploader,
            Clock.fixed(end, ZoneOffset.UTC)).sync(day)
        assertEquals(SyncStatus.FAILED, result.status)
        assertEquals("READ_FAILED", result.errorCode)
        assertEquals(0, result.uploadedFiles)
        assertEquals(0, uploads)
        assertEquals(2, provider.requests.size)
    }
}
