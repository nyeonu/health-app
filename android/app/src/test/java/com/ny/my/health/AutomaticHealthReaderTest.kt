package com.ny.my.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import com.ny.my.health.core.BlockReason
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.SourceReadException
import com.ny.my.health.core.SyncTrigger
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutomaticHealthReaderTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val window = TimeRangeFilter.between(DayWindow.start(date), DayWindow.end(date))
    private val scopes = mapOf(DataType.STEPS to ReadScope.SUMMARY, DataType.SLEEP to ReadScope.SUMMARY,
        DataType.EXERCISE to ReadScope.SUMMARY, DataType.WEIGHT to ReadScope.EXPLICIT_DAY,
        DataType.HEART_RATE to ReadScope.WORKOUT_SCOPED)

    private class Provider {
        var available = true
        var grants = HealthConnectReader.permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        var grantCalls = 0
        var onGrants: (() -> Unit)? = null
        var failure: Throwable? = null
        val aggregates = mutableListOf<AggregateRequest>()
        val reads = mutableListOf<ReadRecordsRequest<*>>()
        var weights = emptyList<WeightRecord>()
        var page: ((ReadRecordsRequest<*>) -> ReadRecordsResponse<WeightRecord>)? = null
        var afterAggregate: (() -> Unit)? = null
        private val feature = proxy(HealthConnectFeatures::class.java) { _, _ ->
            if (available) HealthConnectFeatures.FEATURE_STATUS_AVAILABLE else HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
        }
        private val permissions = proxy(PermissionController::class.java) { name, _ ->
            check(name == "getGrantedPermissions")
            grantCalls++
            onGrants?.invoke()
            grants
        }
        private val client = proxy(HealthConnectClient::class.java) { name, args ->
            when (name) {
                "getFeatures" -> feature
                "getPermissionController" -> permissions
                "aggregate" -> {
                    failure?.let { throw it }
                    val request = args[0] as AggregateRequest
                    aggregates += request
                    afterAggregate?.invoke()
                    val origin = if (request == AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                            TimeRangeFilter.between(DayWindow.start(LocalDate.of(2026, 10, 30)), DayWindow.end(LocalDate.of(2026, 10, 30))))) "sleep.source" else "exercise.source"
                    AggregationResult(emptyMap(), emptyMap(), setOf(DataOrigin(origin)))
                }
                "readRecords" -> {
                    val request = args[0] as ReadRecordsRequest<*>
                    reads += request
                    page?.invoke(request) ?: ReadRecordsResponse(weights, null)
                }
                else -> error("Unexpected SDK operation: $name")
            }
        }
        val reader = AutomaticHealthReader({ client }, { HealthConnectClient.SDK_AVAILABLE })
        companion object {
            private fun <T : Any> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
                checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                    call(method.name, args ?: emptyArray())
                }))
        }
    }

    @Test fun `Feature and each grant missing block their scopes while HR remains blocked`() = runBlocking {
        val provider = Provider()
        provider.available = false
        assertFalse(provider.reader.access(SyncTrigger.SCHEDULED).available)
        provider.available = true
        provider.grants = provider.grants - HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        assertFalse(provider.reader.access(SyncTrigger.CATCH_UP).available)
        provider.grants = HealthConnectReader.permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        for ((type, permission) in HealthConnectReader.permissionByType) {
            provider.grants = (HealthConnectReader.permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) - permission
            val access = provider.reader.access(SyncTrigger.SCHEDULED)
            assertEquals(ReadScope.BLOCKED, access.scopeByType[type])
            assertEquals(ReadScope.BLOCKED, access.scopeByType[DataType.HEART_RATE])
            assertEquals(ReadScope.SUMMARY, access.scopeByType[if (type == DataType.STEPS) DataType.SLEEP else DataType.STEPS])
        }
        assertFalse(provider.reader.access(SyncTrigger.MANUAL).available)
        assertEquals(5, HealthConnectReader.permissions.size)
    }

    @Test fun `Null aggregates retain missing values and per metric origins while other categories proceed`() = runBlocking {
        val provider = Provider()
        val result = provider.reader.readScoped(date, scopes)
        assertNull(result.snapshot.steps)
        assertNull(result.snapshot.sleepSummary!!.duration)
        assertEquals(setOf("sleep.source"), result.snapshot.sleepSummary!!.dataOrigins)
        assertNull(result.snapshot.exerciseSummary!!.duration)
        assertEquals(setOf("exercise.source"), result.snapshot.exerciseSummary!!.dataOrigins)
        assertEquals(setOf(DataType.HEART_RATE), result.blockedTypes)
        assertEquals(listOf(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), window),
            AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), window),
            AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL), window)), provider.aggregates)
        assertEquals(WeightRecord::class, provider.reads.single().recordType)
        assertEquals(window, provider.reads.single().timeRangeFilter)
    }

    @Test fun `Bounded weight read returns the latest actual measurement`() = runBlocking {
        val provider = Provider()
        provider.weights = listOf(WeightRecord(DayWindow.start(date).plusSeconds(20), null, Mass.kilograms(71.0), Metadata.manualEntryWithId("latest")),
            WeightRecord(DayWindow.start(date).plusSeconds(10), null, Mass.kilograms(69.0), Metadata.manualEntryWithId("earlier")))
        val result = provider.reader.readScoped(date, mapOf(DataType.WEIGHT to ReadScope.EXPLICIT_DAY))
        assertEquals(1, result.snapshot.weights.size)
        assertEquals(71.0, result.snapshot.weights.single().kilograms, 0.0)
        assertEquals(window, provider.reads.single().timeRangeFilter)
    }

    @Test fun `Grant revocation blocks category or entire background read and cancellation propagates`() = runBlocking {
        val provider = Provider()
        provider.onGrants = { if (provider.grantCalls == 6) provider.grants = provider.grants - HealthConnectReader.permissionByType.getValue(DataType.SLEEP) }
        val result = provider.reader.readScoped(date, scopes)
        assertEquals(ReadScope.BLOCKED, result.scopeByType[DataType.SLEEP])
        assertNull(result.snapshot.sleepSummary)
        assertEquals(ReadScope.SUMMARY, result.scopeByType[DataType.STEPS])
        val revoked = Provider()
        revoked.onGrants = { if (revoked.grantCalls == 6) revoked.grants = revoked.grants - HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND }
        try { revoked.reader.readScoped(date, scopes); fail("revoked background grant") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED, e.reason) }
        val cancelled = Provider()
        cancelled.failure = CancellationException("cancelled")
        try { cancelled.reader.readScoped(date, scopes); fail("cancellation") }
        catch (_: CancellationException) { }
    }
    @Test fun `Weight tie ordering and day boundaries select the latest valid tuple`() = runBlocking {
        val provider = Provider()
        val time = DayWindow.start(date).plusSeconds(10)
        provider.weights = listOf(
            WeightRecord(DayWindow.end(date), null, Mass.kilograms(99.0), Metadata.manualEntryWithId("outside")),
            WeightRecord(time, null, Mass.kilograms(70.0), Metadata.manualEntryWithId("a")),
            WeightRecord(time, null, Mass.kilograms(71.0), Metadata.manualEntryWithId("b")),
            WeightRecord(time, null, Mass.kilograms(72.0), Metadata.manualEntryWithId("b")))
        val weight = provider.reader.readScoped(date, mapOf(DataType.WEIGHT to ReadScope.EXPLICIT_DAY)).snapshot.weights.single()
        assertEquals("b", weight.recordId)
        assertEquals(72.0, weight.kilograms, 0.0)
    }

    @Test fun `Mid metric and page revocation stops further provider reads`() = runBlocking {
        val provider = Provider()
        provider.afterAggregate = { provider.grants = provider.grants - HealthConnectReader.permissionByType.getValue(DataType.SLEEP) }
        try { provider.reader.readScoped(date, scopes); fail("revocation") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED, e.reason) }
        assertEquals(1, provider.aggregates.size)
        assertTrue(provider.reads.isEmpty())
        val pages = Provider()
        pages.page = { pages.grants = pages.grants - HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            ReadRecordsResponse(emptyList(), "next") }
        try { pages.reader.readScoped(date, mapOf(DataType.WEIGHT to ReadScope.EXPLICIT_DAY)); fail("revocation") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED, e.reason) }
        assertEquals(1, pages.reads.size)
    }

    @Test fun `Weight pagination bounds and repeated tokens fail without truncating`() = runBlocking {
        val repeated = Provider()
        repeated.page = { ReadRecordsResponse(emptyList(), "same") }
        try { repeated.reader.readScoped(date, mapOf(DataType.WEIGHT to ReadScope.EXPLICIT_DAY)); fail("repeated token") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_LIMIT_EXCEEDED, e.reason) }
        assertEquals(2, repeated.reads.size)
        val limit = Provider()
        limit.page = { ReadRecordsResponse(emptyList(), "page${limit.reads.size}") }
        try { limit.reader.readScoped(date, mapOf(DataType.WEIGHT to ReadScope.EXPLICIT_DAY)); fail("page limit") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_LIMIT_EXCEEDED, e.reason) }
        assertEquals(100, limit.reads.size)
        val oversized = Provider()
        val record = WeightRecord(DayWindow.start(date), null, Mass.kilograms(70.0), Metadata.manualEntryWithId("sample"))
        oversized.page = { ReadRecordsResponse(List(1001) { record }, null) }
        try { oversized.reader.readScoped(date, mapOf(DataType.WEIGHT to ReadScope.EXPLICIT_DAY)); fail("page size") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_LIMIT_EXCEEDED, e.reason) }
    }

    @Test fun `Unavailable SDK feature loss and provider security failures remain blocked`() = runBlocking {
        val unavailable = AutomaticHealthReader({ error("unavailable client") }, { HealthConnectClient.SDK_UNAVAILABLE })
        assertFalse(unavailable.access(SyncTrigger.SCHEDULED).available)
        try { unavailable.readScoped(date, scopes); fail("unavailable") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED, e.reason) }
        val lost = Provider()
        lost.afterAggregate = { lost.available = false }
        try { lost.reader.readScoped(date, scopes); fail("feature lost") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED, e.reason) }
        assertEquals(1, lost.aggregates.size)
        val denied = Provider()
        denied.failure = SecurityException("provider detail")
        try { denied.reader.readScoped(date, scopes); fail("security failure") }
        catch (e: SourceReadException) { assertEquals(BlockReason.SOURCE_ACCESS_REQUIRED, e.reason) }
    }

}
