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
) : HealthReader {
    constructor(context: Context) : this(
        context.applicationContext.let { app -> { HealthConnectClient.getOrCreate(app) } },
        context.applicationContext.let { app -> { HealthConnectClient.getSdkStatus(app) } },
    )

    fun availability(): Int = sdkStatus()

    suspend fun grantedPermissions(): Set<String> = availableClient()
        .permissionController.getGrantedPermissions()

    override suspend fun read(date: LocalDate): HealthSnapshot {
        // Never cache either SDK readiness or the user's revocable grants between syncs.
        val client = availableClient()
        val granted = client.permissionController.getGrantedPermissions()
        val grantedTypes = permissionByType.filterValues { it in granted }.keys
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
            readAll(client, WeightRecord::class, window).map { record ->
                WeightMeasurement(
                    record.metadata.id, record.metadata.dataOrigin.packageName,
                    record.time, record.weight.inKilograms,
                )
            }
        } else emptyList()

        val heartRateSamples = if (DataType.HEART_RATE in grantedTypes) {
            val samples = mutableListOf<HeartRateSample>()
            var sampleCount = 0L
            readPages(client, HeartRateRecord::class, intervalWindow) { page ->
                // Check the received page before processing it or requesting another one.
                sampleCount += page.sumOf { it.samples.size.toLong() }
                check(sampleCount <= MAX_HEART_RATE_SAMPLES) { "Health Connect sample limit exceeded" }
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
            readAll(client, SleepSessionRecord::class, intervalWindow)
                .filter { overlaps(it.startTime, it.endTime) }.map { record ->
                    SleepSession(
                        record.metadata.id, record.metadata.dataOrigin.packageName,
                        record.startTime, record.endTime,
                    )
                }
        } else emptyList()

        val exerciseSessions = if (DataType.EXERCISE in grantedTypes) {
            readAll(client, ExerciseSessionRecord::class, intervalWindow)
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
    ): List<T> {
        val records = mutableListOf<T>()
        readPages(client, recordType, window) { records += it }
        return records
    }

    private suspend fun <T : Record> readPages(
        client: HealthConnectClient,
        recordType: KClass<T>,
        window: TimeRangeFilter,
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
            check(response.records.size <= PAGE_SIZE) { "Health Connect page size exceeded" }
            consume(response.records)
            val next = response.pageToken
            if (next.isNullOrEmpty()) return
            check(seenTokens.add(next)) { "Health Connect repeated page token" }
            token = next
        }
        error("Health Connect page limit exceeded")
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
