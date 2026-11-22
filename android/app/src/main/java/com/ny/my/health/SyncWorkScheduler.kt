package com.ny.my.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ny.my.health.core.DestinationGeneration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

class SyncWorkScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context.applicationContext)
    private fun tag(generation: DestinationGeneration) = "automatic-sync:${generation.value}"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()

    fun schedule(generation: DestinationGeneration) {
        val request = PeriodicWorkRequest.Builder(SyncWorker::class.java, 24, TimeUnit.HOURS)
            .setInputData(workDataOf(SyncWorker.GENERATION to generation.value))
            .addTag(tag(generation)).addTag(SyncWorker.SCHEDULED)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("${tag(generation)}:periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueCatchUp(generation: DestinationGeneration) {
        val request = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setInputData(workDataOf(SyncWorker.GENERATION to generation.value))
            .addTag(tag(generation)).addTag(SyncWorker.CATCH_UP)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork("${tag(generation)}:catch-up", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(generation: DestinationGeneration) { manager.cancelAllWorkByTag(tag(generation)) }
    fun workInfos(generation: DestinationGeneration): Flow<List<WorkInfo>> = manager.getWorkInfosByTagFlow(tag(generation))
}
