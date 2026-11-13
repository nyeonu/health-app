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
import com.ny.my.health.core.BlockReason
import com.ny.my.health.core.DataType
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.SourceReadException
import com.ny.my.health.core.SyncTrigger
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
    fun `Scoped manual read queries only requested current grants and revoked access never becomes empty`() = runBlocking {
        val recordTypes = mapOf(DataType.STEPS to StepsRecord::class, DataType.WEIGHT to WeightRecord::class,
            DataType.HEART_RATE to HeartRateRecord::class, DataType.SLEEP to SleepSessionRecord::class,
            DataType.EXERCISE to ExerciseSessionRecord::class)
        for (selected in listOf(setOf(DataType.STEPS), setOf(DataType.HEART_RATE),
            setOf(DataType.WEIGHT, DataType.SLEEP, DataType.EXERCISE))) {
            val provider = Provider(*recordTypes.values.toTypedArray())
            val access = provider.reader.access(SyncTrigger.MANUAL)
            assertEquals(recordTypes.keys, access.granted)
            assertTrue(access.scopeByType.values.all { it == ReadScope.EXPLICIT_DAY })
            for (trigger in listOf(SyncTrigger.SCHEDULED, SyncTrigger.CATCH_UP)) {
                assertTrue(provider.reader.access(trigger).scopeByType.values.all { it == ReadScope.BLOCKED })
            }
            val result = provider.reader.readScoped(day, selected.associateWith { ReadScope.EXPLICIT_DAY })
            assertEquals(selected, result.snapshot.grantedTypes)
            assertTrue(result.blockedTypes.isEmpty())
            assertEquals(selected - DataType.STEPS,
                provider.requests.map { request -> recordTypes.entries.single { it.value == request.recordType }.key }.toSet())
            assertEquals(if (DataType.STEPS in selected) 1 else 0, provider.aggregates.size)
            provider.aggregates.forEach { assertEquals(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(start, end)), it) }
        }
        val denied = Provider(StepsRecord::class, HeartRateRecord::class)
        val requested = denied.reader.access(SyncTrigger.MANUAL).scopeByType.filterValues { it == ReadScope.EXPLICIT_DAY }
        denied.grants = setOf(HealthPermission.getReadPermission(StepsRecord::class))
        val revoked = denied.reader.readScoped(day, requested)
        assertEquals(setOf(DataType.HEART_RATE), revoked.blockedTypes)
        assertEquals(ReadScope.BLOCKED, revoked.scopeByType[DataType.HEART_RATE])
        assertTrue(denied.requests.isEmpty())
        assertEquals(1, denied.aggregates.size)
        val blocked = Provider(*recordTypes.values.toTypedArray())
        val unsupported = DataType.entries.associateWith { ReadScope.BLOCKED }
        assertEquals(recordTypes.keys, blocked.reader.readScoped(day, unsupported).blockedTypes)
        assertTrue(blocked.requests.isEmpty()); assertTrue(blocked.aggregates.isEmpty())
        for (scope in listOf(ReadScope.SUMMARY, ReadScope.WORKOUT_SCOPED)) {
            assertEquals(setOf(DataType.HEART_RATE), blocked.reader.readScoped(day,
                mapOf(DataType.HEART_RATE to scope)).blockedTypes)
        }
        assertTrue(blocked.requests.isEmpty()); assertTrue(blocked.aggregates.isEmpty())
        val duringRead = Provider(HeartRateRecord::class)
        duringRead.read = { throw SecurityException("합성 철회") }
        assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED,
            (runCatching { duringRead.reader.readScoped(day, mapOf(DataType.HEART_RATE to ReadScope.EXPLICIT_DAY)) }
                .exceptionOrNull() as SourceReadException).reason)
        val afterRead = Provider(HeartRateRecord::class)
        afterRead.read = {
            afterRead.grants = emptySet()
            ReadRecordsResponse(emptyList<HeartRateRecord>(), null)
        }
        assertEquals(setOf(DataType.HEART_RATE), afterRead.reader.readScoped(day,
            mapOf(DataType.HEART_RATE to ReadScope.EXPLICIT_DAY)).blockedTypes)
        val unknown = HealthConnectReader({ error("사용 불가 SDK는 client를 만들지 않는다") }, { -1 })
        assertFalse(unknown.access(SyncTrigger.MANUAL).available)
        assertTrue(unknown.access(SyncTrigger.MANUAL).scopeByType.values.all { it == ReadScope.BLOCKED })
        assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED, (runCatching {
            unknown.readScoped(day, mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        }.exceptionOrNull() as SourceReadException).reason)
    }

    @Test
    fun `Scoped source limits remain blocked and legacy pagination overlap and CSV remain unchanged`() = runBlocking {
        for (mode in listOf("repeated", "page-limit", "oversize", "samples")) {
            val provider = Provider(HeartRateRecord::class)
            provider.read = {
                when (mode) {
                    "repeated" -> ReadRecordsResponse(emptyList<HeartRateRecord>(), "same")
                    "page-limit" -> ReadRecordsResponse(emptyList<HeartRateRecord>(), "page-${provider.requests.size}")
                    "oversize" -> ReadRecordsResponse(List(1001) { heartRate("synthetic", start, 1) }, null)
                    else -> ReadRecordsResponse(listOf(heartRate("synthetic", start, 100_001)), null)
                }
            }
            val error = runCatching { provider.reader.readScoped(day,
                mapOf(DataType.HEART_RATE to ReadScope.EXPLICIT_DAY)) }.exceptionOrNull()
            assertEquals(BlockReason.SOURCE_LIMIT_EXCEEDED, (error as SourceReadException).reason)
            assertEquals(when (mode) { "repeated" -> 2; "page-limit" -> 100; else -> 1 }, provider.requests.size)
        }
        val boundary = Provider(SleepSessionRecord::class)
        val page = List(1000) { sleep("synthetic", start, end) }
        boundary.read = {
            ReadRecordsResponse(page, if (boundary.requests.size == 100) null else "page-${boundary.requests.size}")
        }
        val full = boundary.reader.readScoped(day, mapOf(DataType.SLEEP to ReadScope.EXPLICIT_DAY))
        assertEquals(100_000, full.snapshot.sleepSessions.size)
        assertEquals(100, boundary.requests.size)
        assertTrue(boundary.requests.all { it.pageSize == 1000 && it.timeRangeFilter == TimeRangeFilter.before(end) })
        assertTrue(full.blockedTypes.isEmpty())
        val provider = Provider(WeightRecord::class, SleepSessionRecord::class, HeartRateRecord::class)
        provider.records = listOf(WeightRecord(start, null, Mass.kilograms(61.0), Metadata.manualEntry()),
            sleep("overnight", start.minusSeconds(3600), start.plusSeconds(3600)), heartRate("sample", start, 1))
        val legacy = provider.reader.read(day)
        val scoped = provider.reader.readScoped(day, legacy.grantedTypes.associateWith { ReadScope.EXPLICIT_DAY })
        assertEquals(legacy, scoped.snapshot)
        val generated = end
        val exporter = VersionedCsvExporter()
        assertEquals(exporter.export(DefaultNormalizer().normalize(legacy, generated)),
            exporter.export(DefaultNormalizer().normalize(scoped.snapshot, generated)))
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
