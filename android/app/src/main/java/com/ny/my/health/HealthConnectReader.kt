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
import java.time.LocalDate
import kotlin.reflect.KClass

/** Foreground-only, one-day reader. The P0 caller requests yesterday in Asia/Seoul. */
class HealthConnectReader(context: Context) : HealthReader {
    private val context = context.applicationContext

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

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
            readAll(client, HeartRateRecord::class, window).forEach { record ->
                sampleCount += record.samples.size
                check(sampleCount <= MAX_HEART_RATE_SAMPLES) { "Health Connect sample limit exceeded" }
                record.samples.filter { it.time >= start && it.time < end }.forEach { sample ->
                    samples += HeartRateSample(
                        record.metadata.id, record.metadata.dataOrigin.packageName,
                        sample.time, sample.beatsPerMinute,
                    )
                }
            }
            samples
        } else emptyList()

        val sleepSessions = if (DataType.SLEEP in grantedTypes) {
            readAll(client, SleepSessionRecord::class, window).map { record ->
                SleepSession(
                    record.metadata.id, record.metadata.dataOrigin.packageName,
                    record.startTime, record.endTime,
                )
            }
        } else emptyList()

        val exerciseSessions = if (DataType.EXERCISE in grantedTypes) {
            readAll(client, ExerciseSessionRecord::class, window).map { record ->
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
        return HealthConnectClient.getOrCreate(context)
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        recordType: KClass<T>,
        window: TimeRangeFilter,
    ): List<T> {
        val records = mutableListOf<T>()
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
            records += response.records
            val next = response.pageToken ?: return records
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
