package com.ny.my.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val manager = WorkManager.getInstance(context)
        assertNull(WorkManagerTestInitHelper.getTestDriver(context))
        assertNotNull(runBlocking {
            withTimeout(10_000) { manager.getWorkInfosByTagFlow("workmanager-isolation-synthetic-probe").first() }
        })
    }
}
