package com.ny.my.health

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerIsolationInstrumentedTest {
    @Test(timeout = 30_000)
    fun normalRuntimeBeforeHelperHasALiveProductionManager() = assertLiveProductionManager()

    @Test(timeout = 30_000)
    fun normalRuntimeAfterHelperHasALiveProductionManager() = assertLiveProductionManager()

    private fun assertLiveProductionManager() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as HealthApplication
        val manager = application.workManager
        assertSame(manager, application.workManager)
        assertSame(manager, WorkManager.getInstance(context))
        assertNull(WorkManagerTestInitHelper.getTestDriver(context))
        assertNotNull(runBlocking {
            withTimeout(10_000) { manager.getWorkInfosByTagFlow("workmanager-isolation-synthetic-probe").first() }
        })

        val fixture = ManualDriveNativeFixture()
        val execution = SyncExecutionSession(fixture.store, fixture.reader, null, fixture.clock, { UUID.randomUUID().toString() })
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) activity.viewModelFactory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = SyncViewModel(
                        application, SyncViewModel.ManualDestinationDependencies(
                            fixture.store, fixture.reader, execution,
                            SafTreeConfiguration(application.contentResolver), fixture.clock,
                        ), null,
                    ) as T
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        val query = WorkQuery.Builder.fromStates(WorkInfo.State.entries).build()
        fun workIds() = runBlocking {
            withTimeout(10_000) { manager.getWorkInfosFlow(query).first().map { it.id }.toSet() }
        }
        val before = workIds()
        application.registerActivityLifecycleCallbacks(callback)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertSame(application, it.application) }
                scenario.recreate()
                scenario.onActivity { assertSame(application, it.application) }
            }
            assertEquals(before, workIds())
            assertEquals(0, fixture.rawReads)
            assertNull(runBlocking { fixture.store.load().state?.configuration })
            assertSame(manager, WorkManager.getInstance(context))
        } finally {
            application.unregisterActivityLifecycleCallbacks(callback)
            fixture.close()
        }
    }
}
