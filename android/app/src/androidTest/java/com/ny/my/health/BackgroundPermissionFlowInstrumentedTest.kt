package com.ny.my.health

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.DataType
import com.ny.my.health.core.ReadAccess
import com.ny.my.health.core.ReadScope
import com.ny.my.health.core.ScopedHealthReader
import com.ny.my.health.core.ScopedSnapshot
import com.ny.my.health.core.SyncTrigger
import java.time.Clock
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** SDK launcher 경계만 합성하며 실제 시스템 권한을 주장하지 않는다. */
@RunWith(AndroidJUnit4::class)
class BackgroundPermissionFlowInstrumentedTest {
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    @Test fun delayedDeniedResultSurvivesPauseAndRecreationWhileDriveIsBlocked() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        var reads = 0
        val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger): ReadAccess = throw IllegalStateException("synthetic")
            override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                reads++
                throw IllegalStateException("unexpected")
            }
        }
        val store = SqliteSyncStateStore(app)
        val clock = Clock.systemUTC()
        val execution = SyncExecutionSession(store, reader, null, clock, { java.util.UUID.randomUUID().toString() })
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) activity.viewModelFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return SyncViewModel(app) { create, begin, finish, invalidate ->
                            ManualDriveSession(store, reader, execution, clock, create, begin, finish, invalidate)
                        } as T
                    }
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
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: SyncViewModel
                scenario.onActivity { model = ViewModelProvider(it)[SyncViewModel::class.java] }
                await { !model.state.value.busy && model.manual.state.value.phase == ManualDrivePhase.BLOCKED }
                var launches = 0
                scenario.onActivity {
                    it.backgroundFeatureAvailable = { true }
                    it.launchBackgroundPermission = { launches++ }
                    it.requestBackgroundPermission()
                    it.requestBackgroundPermission()
                    assertTrue(model.state.value.pendingConsent)
                }
                assertEquals(1, launches)
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { it.backgroundPermissionResult(); it.backgroundPermissionResult() }
                assertTrue(model.state.value.pendingConsent)
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { !model.state.value.pendingConsent }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { !model.state.value.busy }
                assertEquals(1, launches)
                assertEquals(0, reads)
                assertFalse(model.state.value.automaticEnabled)
                assertFalse(model.state.value.syncing)
                assertFalse(model.state.value.readyToUpload)
            }
        } finally { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    @Test fun unavailableOrUnknownFeatureNeverLaunchesAndExplicitResultClearsBusy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: SyncViewModel
            scenario.onActivity { model = ViewModelProvider(it)[SyncViewModel::class.java] }
            await { !model.state.value.busy }
            var launches = 0
            scenario.onActivity {
                it.launchBackgroundPermission = { launches++ }
                it.backgroundFeatureAvailable = { false }
                it.requestBackgroundPermission()
                assertNotNull(model.state.value.automaticMessage)
                it.backgroundFeatureAvailable = { throw IllegalStateException("unknown") }
                it.requestBackgroundPermission()
                assertFalse(model.state.value.pendingConsent)
                it.backgroundFeatureAvailable = { true }
                it.requestBackgroundPermission()
                assertTrue(model.state.value.pendingConsent)
                it.backgroundPermissionResult()
            }
            await { !model.state.value.pendingConsent }
            assertEquals(1, launches)
            await { !model.state.value.busy }
            scenario.onActivity {
                it.requestBackgroundPermission()
                assertTrue(model.state.value.pendingConsent)
                it.backgroundPermissionResult()
            }
            await { !model.state.value.pendingConsent }
            assertEquals(2, launches)
            assertFalse(model.state.value.automaticEnabled)
            assertFalse(model.state.value.syncing)
        }
    }
}
