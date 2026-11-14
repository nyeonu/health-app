package com.ny.my.health

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ny.my.health.core.ScopedHealthReader
import java.time.Clock
import java.util.UUID

class HealthApplication : Application(), Configuration.Provider {
    private val factory = HealthWorkerFactory()
    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setWorkerFactory(factory)
        .build()

    internal val workManager: WorkManager by lazy { WorkManager.getInstance(this) }
    internal val syncStore: SqliteSyncStateStore by lazy { SqliteSyncStateStore(this) }

    internal fun newExecutionSession(reader: ScopedHealthReader): SyncExecutionSession =
        SyncExecutionSession(syncStore, reader, null, Clock.systemUTC(), { UUID.randomUUID().toString() })

    private class HealthWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = null
    }
}
