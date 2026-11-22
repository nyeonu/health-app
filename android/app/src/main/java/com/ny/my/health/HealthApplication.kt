package com.ny.my.health

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.DestinationSync
import com.ny.my.health.core.PrivateDriveUploader
import com.ny.my.health.core.ScopedHealthReader
import com.ny.my.health.core.StateStatus
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

class HealthApplication : Application(), Configuration.Provider {
    private val factory = HealthWorkerFactory()
    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setWorkerFactory(factory)
        .build()

    internal val workManager: WorkManager by lazy { WorkManager.getInstance(this) }
    internal val syncStore: SqliteSyncStateStore by lazy { SqliteSyncStateStore(this) }

    internal fun newExecutionSession(reader: ScopedHealthReader): SyncExecutionSession =
        SyncExecutionSession(syncStore, reader, null, Clock.systemUTC(), { UUID.randomUUID().toString() })

    private inner class HealthWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? {
            if (workerClassName != SyncWorker::class.java.name) return null
            return SyncWorker(appContext, workerParameters) { trigger, generation ->
                val store = syncStore
                val initial = store.load()
                if (initial.status == StateStatus.ABSENT) return@SyncWorker BatchStatus.BLOCKED
                check(initial.status == StateStatus.READY && initial.state != null) { "Sync state unavailable" }
                val state = checkNotNull(initial.state)
                if (state.generation != generation) return@SyncWorker BatchStatus.STALE
                if (state.configuration?.automatic != true) return@SyncWorker BatchStatus.BLOCKED
                if (store.readBinding(generation)?.kind != "DRIVE") return@SyncWorker BatchStatus.BLOCKED
                val reader = AutomaticHealthReader(appContext)
                val clock = Clock.systemUTC()
                val session = SyncExecutionSession(store, reader, null, clock,
                    { UUID.randomUUID().toString() }, automaticSession = true)
                session.runAutomaticPrepared(trigger, generation) {
                    val binding = store.readBinding(generation)
                    val account = binding?.let {
                        if (it.kind != "DRIVE") null else BoundDriveAuthorization.pickerCandidate(
                            true, 1, 1, true, it.accountName, it.accountType)
                    } ?: throw BoundDriveAuthorizationException()
                    BackgroundDriveAuthorization(appContext).authorize(account, generation, {
                        val state = store.load()
                        currentCoroutineContext().isActive && state.status == StateStatus.READY &&
                            state.state?.generation == generation && state.state?.configuration?.automatic == true
                    }) { transport ->
                        DestinationSync(store, reader,
                            DriveApiDestination(PrivateDriveUploader(transport, clock = clock), generation, store, clock), clock)
                    }
                }.status
            }
        }
    }
}
