package com.ny.my.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.SyncTrigger
import java.util.UUID
import kotlinx.coroutines.CancellationException

class SyncWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val execute: suspend (SyncTrigger, DestinationGeneration) -> BatchStatus,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val value = inputData.getString(GENERATION) ?: return Result.success()
        val valid = try { UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }
        if (!valid) return Result.success()
        val trigger = when {
            SCHEDULED in tags && CATCH_UP !in tags -> SyncTrigger.SCHEDULED
            CATCH_UP in tags && SCHEDULED !in tags -> SyncTrigger.CATCH_UP
            else -> return Result.success()
        }
        return try {
            if (execute(trigger, DestinationGeneration(value)) == BatchStatus.RETRYABLE) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure()
        }
    }

    internal companion object {
        const val GENERATION = "generation"
        const val SCHEDULED = "automatic-scheduled"
        const val CATCH_UP = "automatic-catch-up"
    }
}
