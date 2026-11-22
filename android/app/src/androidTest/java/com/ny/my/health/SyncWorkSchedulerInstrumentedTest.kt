package com.ny.my.health

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.SyncConfiguration
import com.ny.my.health.core.DataType
import com.ny.my.health.core.ScopedHealthReader
import com.ny.my.health.core.ReadAccess
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.ScopedSnapshot
import com.ny.my.health.core.StateStatus
import java.time.LocalDate
import com.ny.my.health.core.SyncTrigger
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkSchedulerInstrumentedTest {
    @Test(timeout = 30000)
    fun uniqueDailyWorkUsesNetworkAndBatteryConstraintsAndCatchUpIsUnique() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = SyncWorkScheduler(context)
        val generation = DestinationGeneration(UUID.randomUUID().toString())
        try {
            scheduler.schedule(generation)
            scheduler.schedule(generation)
            scheduler.enqueueCatchUp(generation)
            scheduler.enqueueCatchUp(generation)
            val infos = withTimeout(10000) { scheduler.workInfos(generation).first { it.size >= 2 } }
            assertEquals(2, infos.size)
            val periodic = infos.single { it.periodicityInfo != null }
            assertEquals(TimeUnit.HOURS.toMillis(24), periodic.periodicityInfo!!.repeatIntervalMillis)
            infos.forEach {
                assertEquals(NetworkType.CONNECTED, it.constraints.requiredNetworkType)
                assertTrue(it.constraints.requiresBatteryNotLow())
            }
        } finally { scheduler.cancel(generation) }
    }

    @Test(timeout = 30000)
    fun workerRoutesTriggersAndRetriesOnlyRetryableOutcomes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val generation = DestinationGeneration(UUID.randomUUID().toString())
        val received = mutableListOf<Pair<SyncTrigger, DestinationGeneration>>()
        var result = BatchStatus.RETRYABLE
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters) { trigger, value ->
                    received.add(trigger to value); result
                } else null
        }
        fun worker(tag: String) = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
            .setWorkerFactory(factory).setInputData(workDataOf(SyncWorker.GENERATION to generation.value))
            .setTags(listOf(tag)).build()
        assertEquals(ListenableWorker.Result.retry(), worker(SyncWorker.SCHEDULED).doWork())
        result = BatchStatus.BLOCKED
        assertEquals(ListenableWorker.Result.success(), worker(SyncWorker.CATCH_UP).doWork())
        assertEquals(listOf(SyncTrigger.SCHEDULED to generation, SyncTrigger.CATCH_UP to generation), received)
    }
    @Test(timeout = 30000)
    fun disabledAndStaleWorkersDoNotAccessHealthOrPrepareDestination() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = ManualDriveNativeFixture()
        try {
            val generation = requireNotNull(fixture.store.configure(null,
                SyncConfiguration("drive", fixture.today.minusDays(1), false, setOf(DataType.STEPS)),
                PrivateDestinationBinding("DRIVE", fixture.account.name, fixture.account.type)).generation)
            var healthCalls = 0
            var prepareCalls = 0
            val reader = object : ScopedHealthReader {
                override suspend fun access(trigger: SyncTrigger): ReadAccess {
                    healthCalls++
                    return ReadAccess(false, emptySet(), DataType.entries.associateWith { ReadScope.BLOCKED })
                }
                override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                    healthCalls++
                    error("unexpected read")
                }
            }
            val session = SyncExecutionSession(fixture.store, reader, null, fixture.clock,
                { UUID.randomUUID().toString() }, automaticSession = true)
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String,
                    workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters) { trigger, value ->
                        session.runAutomaticPrepared(trigger, value) { prepareCalls++; error("unexpected preparation") }.status
                    } else null
            }
            fun worker(value: DestinationGeneration) = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
                .setWorkerFactory(factory).setInputData(workDataOf(SyncWorker.GENERATION to value.value))
                .setTags(listOf(SyncWorker.SCHEDULED)).build()
            assertEquals(StateStatus.READY, fixture.store.load().status)
            assertEquals(ListenableWorker.Result.success(), worker(generation).doWork())
            assertEquals(ListenableWorker.Result.success(), worker(DestinationGeneration(UUID.randomUUID().toString())).doWork())
            assertEquals(0, healthCalls)
            assertEquals(0, prepareCalls)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000)
    fun unexpectedWorkerErrorFailsAndCancellationPropagates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val generation = DestinationGeneration(UUID.randomUUID().toString())
        var cancel = false
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters) { _, _ ->
                    if (cancel) throw CancellationException("cancelled")
                    error("synthetic error")
                } else null
        }
        fun worker() = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
            .setWorkerFactory(factory).setInputData(workDataOf(SyncWorker.GENERATION to generation.value))
            .setTags(listOf(SyncWorker.SCHEDULED)).build()
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        cancel = true
        try {
            worker().doWork()
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test(timeout = 30000)
    fun productionManagerFactoryConstructsWorkerAndStaleOrDisabledWorkHasNoEffect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as HealthApplication
        val factory = app.workManagerConfiguration.workerFactory
        assertSame(factory, app.workManager.configuration.workerFactory)
        val before = app.syncStore.load()
        assertTrue(before.status == StateStatus.ABSENT || before.status == StateStatus.READY)
        val current = before.state
        val generation = if (current != null && current.configuration?.automatic != true) current.generation
            else DestinationGeneration(UUID.randomUUID().toString())
        val worker = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
            .setWorkerFactory(factory).setInputData(workDataOf(SyncWorker.GENERATION to generation.value))
            .setTags(listOf(SyncWorker.SCHEDULED)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(before, app.syncStore.load())
    }

}
