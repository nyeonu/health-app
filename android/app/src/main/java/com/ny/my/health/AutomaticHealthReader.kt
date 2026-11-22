package com.ny.my.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ny.my.health.core.AggregateDuration
import com.ny.my.health.core.BlockReason
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.HealthSnapshot
import com.ny.my.health.core.ReadAccess
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.ScopedHealthReader
import com.ny.my.health.core.ScopedSnapshot
import com.ny.my.health.core.SourceReadException
import com.ny.my.health.core.SyncTrigger
import com.ny.my.health.core.WeightMeasurement
import java.time.LocalDate

/** 자동 조회는 별도의 background 권한과 현재 범위를 매번 확인한다. */
class AutomaticHealthReader internal constructor(
    private val clientProvider: () -> HealthConnectClient,
    private val sdkStatus: () -> Int,
) : ScopedHealthReader {
    constructor(context: Context) : this(
        context.applicationContext.let { app -> { HealthConnectClient.getOrCreate(app) } },
        context.applicationContext.let { app -> { HealthConnectClient.getSdkStatus(app) } },
    )

    private fun supported(client: HealthConnectClient): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    override suspend fun access(trigger: SyncTrigger): ReadAccess {
        if (trigger == SyncTrigger.MANUAL || sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return blocked()
        return try {
            val client = clientProvider()
            if (!supported(client)) return blocked()
            val grants = client.permissionController.getGrantedPermissions()
            if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in grants) return blocked()
            val granted = HealthConnectReader.permissionByType.filterValues { it in grants }.keys
            ReadAccess(true, granted, DataType.entries.associateWith { type ->
                if (type !in granted) ReadScope.BLOCKED else scope(type)
            })
        } catch (_: SecurityException) {
            blocked()
        }
    }

    override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
        try {
            val client = clientProvider()
            if (!supported(client)) throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
            val grants = client.permissionController.getGrantedPermissions()
            if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in grants) throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
            val allowed = requestedScopes.filter { (type, requested) ->
                requested != ReadScope.BLOCKED && requested == scope(type) && HealthConnectReader.permissionByType[type] in grants
            }.keys
            val window = TimeRangeFilter.between(DayWindow.start(date), DayWindow.end(date))
            val steps = if (DataType.STEPS in allowed) { requireGrant(client, DataType.STEPS); client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), window))[StepsRecord.COUNT_TOTAL] } else null
            val sleep = if (DataType.SLEEP in allowed) { requireGrant(client, DataType.SLEEP); client.aggregate(
                AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), window)).let { result ->
                    AggregateDuration(result[SleepSessionRecord.SLEEP_DURATION_TOTAL], result.dataOrigins.map { it.packageName }.toSet())
                } } else null
            val exercise = if (DataType.EXERCISE in allowed) { requireGrant(client, DataType.EXERCISE); client.aggregate(
                AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL), window)).let { result ->
                    AggregateDuration(result[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL], result.dataOrigins.map { it.packageName }.toSet())
                } } else null
            val weights = if (DataType.WEIGHT in allowed) latestWeight(client, window, date) else emptyList()
            if (!supported(client)) throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
            val latest = client.permissionController.getGrantedPermissions()
            if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in latest) throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
            val scopes = requestedScopes.mapValues { (type, _) ->
                if (type in allowed && HealthConnectReader.permissionByType[type] in latest) scope(type) else ReadScope.BLOCKED
            }
            val valid = scopes.filterValues { it != ReadScope.BLOCKED }.keys
            return ScopedSnapshot(HealthSnapshot(date, steps.takeIf { DataType.STEPS in valid },
                weights.takeIf { DataType.WEIGHT in valid } ?: emptyList(), emptyList(), emptyList(), emptyList(), valid,
                sleep.takeIf { DataType.SLEEP in valid }, exercise.takeIf { DataType.EXERCISE in valid }),
                scopes, scopes.filterValues { it == ReadScope.BLOCKED }.keys)
        } catch (_: SecurityException) {
            throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
        }
    }

    private suspend fun requireGrant(client: HealthConnectClient, type: DataType) {
        if (!supported(client)) throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
        val grants = client.permissionController.getGrantedPermissions()
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in grants ||
            HealthConnectReader.permissionByType[type] !in grants) throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
    }

    private suspend fun latestWeight(client: HealthConnectClient, window: TimeRangeFilter, date: LocalDate): List<WeightMeasurement> {
        val seen = mutableSetOf<String>()
        var token: String? = null
        var latest: WeightRecord? = null
        repeat(100) {
            requireGrant(client, DataType.WEIGHT)
            val response = client.readRecords(ReadRecordsRequest(WeightRecord::class, window, pageSize = 1000, pageToken = token))
            if (response.records.size > 1000) throw SourceReadException(BlockReason.SOURCE_LIMIT_EXCEEDED)
            val ordering = compareBy<WeightRecord>({ it.time }, { it.metadata.dataOrigin.packageName },
                { it.metadata.id }, { it.weight.inKilograms })
            response.records.filter { it.time >= DayWindow.start(date) && it.time < DayWindow.end(date) }
                .forEach { if (latest == null || ordering.compare(it, latest!!) > 0) latest = it }
            val next = response.pageToken
            if (next.isNullOrEmpty()) return latest?.let { listOf(WeightMeasurement(it.metadata.id,
                it.metadata.dataOrigin.packageName, it.time, it.weight.inKilograms)) } ?: emptyList()
            if (!seen.add(next)) throw SourceReadException(BlockReason.SOURCE_LIMIT_EXCEEDED)
            token = next
        }
        throw SourceReadException(BlockReason.SOURCE_LIMIT_EXCEEDED)
    }

    private fun scope(type: DataType): ReadScope = when (type) {
        DataType.STEPS, DataType.SLEEP, DataType.EXERCISE -> ReadScope.SUMMARY
        DataType.WEIGHT -> ReadScope.EXPLICIT_DAY
        DataType.HEART_RATE -> ReadScope.BLOCKED
    }

    private fun blocked() = ReadAccess(false, emptySet(), DataType.entries.associateWith { ReadScope.BLOCKED })
}
