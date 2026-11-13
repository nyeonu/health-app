package com.ny.my.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ny.my.health.core.BlockReason
import com.ny.my.health.core.ReadAccess
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.ScopedHealthReader
import com.ny.my.health.core.ScopedSnapshot
import com.ny.my.health.core.SourceReadException
import com.ny.my.health.core.SyncTrigger
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.ExerciseSession
import com.ny.my.health.core.HealthReader
import com.ny.my.health.core.HealthSnapshot
import com.ny.my.health.core.HeartRateSample
import com.ny.my.health.core.SleepSession
import com.ny.my.health.core.WeightMeasurement
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass

/** Foreground-only daily export; interval lookup also covers earlier authorized record starts. */
class HealthConnectReader internal constructor(
    private val clientProvider: () -> HealthConnectClient,
    private val sdkStatus: () -> Int,
) : HealthReader, ScopedHealthReader {
    constructor(context: Context) : this(
        context.applicationContext.let { app -> { HealthConnectClient.getOrCreate(app) } },
        context.applicationContext.let { app -> { HealthConnectClient.getSdkStatus(app) } },
    )

    fun availability(): Int = sdkStatus()

    suspend fun grantedPermissions(): Set<String> = availableClient()
        .permissionController.getGrantedPermissions()

    override suspend fun access(trigger: SyncTrigger): ReadAccess {
        if (availability() != HealthConnectClient.SDK_AVAILABLE) {
            return ReadAccess(false, emptySet(), DataType.entries.associateWith { ReadScope.BLOCKED })
        }
        val grants = grantedPermissions()
        val types = permissionByType.filterValues { it in grants }.keys
        return ReadAccess(true, types, DataType.entries.associateWith {
            if (trigger == SyncTrigger.MANUAL && it in types) ReadScope.EXPLICIT_DAY else ReadScope.BLOCKED
        })
    }

    override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
        if (availability() != HealthConnectClient.SDK_AVAILABLE) {
            throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
        }
        try {
            val client = availableClient()
            val grants = client.permissionController.getGrantedPermissions()
            val allowed = requestedScopes.filter { (type, scope) ->
                scope == ReadScope.EXPLICIT_DAY && permissionByType[type] in grants
            }.keys
            val snapshot = readFiltered(date, client, allowed, true)
            // 읽기 중 철회도 빈 날짜나 부분 성공으로 확인하지 않는다.
            val latest = client.permissionController.getGrantedPermissions()
            val scopes = requestedScopes.mapValues { (type, _) ->
                if (type in allowed && permissionByType[type] in latest) ReadScope.EXPLICIT_DAY else ReadScope.BLOCKED
            }
            return ScopedSnapshot(snapshot, scopes, scopes.filterValues { it == ReadScope.BLOCKED }.keys)
        } catch (_: SecurityException) {
            throw SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED)
        }
    }

    override suspend fun read(date: LocalDate): HealthSnapshot {
        // Never cache either SDK readiness or the user's revocable grants between syncs.
        val client = availableClient()
        val granted = client.permissionController.getGrantedPermissions()
        val grantedTypes = permissionByType.filterValues { it in granted }.keys
        return readFiltered(date, client, grantedTypes, false)
    }

    private suspend fun readFiltered(date: LocalDate, client: HealthConnectClient,
        grantedTypes: Set<DataType>, typedLimits: Boolean): HealthSnapshot {
        val start = DayWindow.start(date)
        val end = DayWindow.end(date)
        val window = TimeRangeFilter.between(start, end)
        // Raw interval queries can select by start time. Search authorized history before
        // day-end, then filter overlaps: no fixed lookback can cover arbitrary session lengths.
        // Provider history permissions still apply; page/sample limits fail rather than truncate.
        val intervalWindow = TimeRangeFilter.before(end)
        fun overlaps(from: Instant, until: Instant) = from < end && until > start

        // Health Connect resolves provider priority; raw StepsRecord sums can double count.
        val steps = if (DataType.STEPS in grantedTypes) {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), window))[
                StepsRecord.COUNT_TOTAL
            ]
        } else null

        val weights = if (DataType.WEIGHT in grantedTypes) {
            readAll(client, WeightRecord::class, window, typedLimits).map { record ->
                WeightMeasurement(
                    record.metadata.id, record.metadata.dataOrigin.packageName,
                    record.time, record.weight.inKilograms,
                )
            }
        } else emptyList()

        val heartRateSamples = if (DataType.HEART_RATE in grantedTypes) {
            val samples = mutableListOf<HeartRateSample>()
            var sampleCount = 0L
            readPages(client, HeartRateRecord::class, intervalWindow, typedLimits) { page ->
                // Check the received page before processing it or requesting another one.
                sampleCount += page.sumOf { it.samples.size.toLong() }
                checkLimit(sampleCount <= MAX_HEART_RATE_SAMPLES, typedLimits) { "Health Connect sample limit exceeded" }
                page.forEach { record ->
                    record.samples.forEach { sample ->
                        if (sample.time >= start && sample.time < end) {
                            samples += HeartRateSample(
                                record.metadata.id, record.metadata.dataOrigin.packageName,
                                sample.time, sample.beatsPerMinute,
                            )
                        }
                    }
                }
            }
            samples
        } else emptyList()

        val sleepSessions = if (DataType.SLEEP in grantedTypes) {
            readAll(client, SleepSessionRecord::class, intervalWindow, typedLimits)
                .filter { overlaps(it.startTime, it.endTime) }.map { record ->
                    SleepSession(
                        record.metadata.id, record.metadata.dataOrigin.packageName,
                        record.startTime, record.endTime,
                    )
                }
        } else emptyList()

        val exerciseSessions = if (DataType.EXERCISE in grantedTypes) {
            readAll(client, ExerciseSessionRecord::class, intervalWindow, typedLimits)
                .filter { overlaps(it.startTime, it.endTime) }.map { record ->
                    ExerciseSession(
                        record.metadata.id, record.metadata.dataOrigin.packageName,
                        record.startTime, record.endTime, record.exerciseType,
                    )
                }
        } else emptyList()

        // Empty grants remain explicit so the coordinator reports PERMISSION_REQUIRED, not EMPTY.
        // Revocation, provider and pagination failures propagate; no partial snapshot is returned.
        return HealthSnapshot(
            date, steps, weights, heartRateSamples, sleepSessions, exerciseSessions, grantedTypes,
        )
    }

    private fun availableClient(): HealthConnectClient {
        check(availability() == HealthConnectClient.SDK_AVAILABLE) { "Health Connect unavailable" }
        return clientProvider()
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        recordType: KClass<T>,
        window: TimeRangeFilter,
        typedLimits: Boolean,
    ): List<T> {
        val records = mutableListOf<T>()
        readPages(client, recordType, window, typedLimits) { records += it }
        return records
    }

    private suspend fun <T : Record> readPages(
        client: HealthConnectClient,
        recordType: KClass<T>,
        window: TimeRangeFilter,
        typedLimits: Boolean,
        consume: (List<T>) -> Unit,
    ) {
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = window,
                    pageSize = PAGE_SIZE,
                    pageToken = token,
                ),
            )
            checkLimit(response.records.size <= PAGE_SIZE, typedLimits) { "Health Connect page size exceeded" }
            consume(response.records)
            val next = response.pageToken
            if (next.isNullOrEmpty()) return
            checkLimit(seenTokens.add(next), typedLimits) { "Health Connect repeated page token" }
            token = next
        }
        checkLimit(false, typedLimits) { "Health Connect page limit exceeded" }
    }

    private inline fun checkLimit(condition: Boolean, typed: Boolean, message: () -> String) {
        if (!condition && typed) throw SourceReadException(BlockReason.SOURCE_LIMIT_EXCEEDED)
        check(condition, message)
    }

    companion object {
        // P0 safety bounds fail explicitly rather than silently exporting truncated records.
        private const val PAGE_SIZE = 1000
        private const val MAX_PAGES = 100
        private const val MAX_HEART_RATE_SAMPLES = 100_000L
        private val permissionByType = mapOf(
            DataType.STEPS to HealthPermission.getReadPermission(StepsRecord::class),
            DataType.WEIGHT to HealthPermission.getReadPermission(WeightRecord::class),
            DataType.HEART_RATE to HealthPermission.getReadPermission(HeartRateRecord::class),
            DataType.SLEEP to HealthPermission.getReadPermission(SleepSessionRecord::class),
            DataType.EXERCISE to HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )
        val permissions: Set<String> = permissionByType.values.toSet()
    }
}
