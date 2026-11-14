package com.ny.my.health

import android.content.Context
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 인메모리 WorkManager DB와 합성 worker만 사용한다. */
@RunWith(AndroidJUnit4::class)
class WorkManagerStarterInstrumentedTest {
    @Test(timeout = 60_000)
    fun realWorkManagerTestContextInitializesAndCompletesACoroutineWorkerOnce() {
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executions = AtomicInteger()
        val workerExecutor = Executors.newFixedThreadPool(2)
        // task executor는 Room query도 처리하므로 서로 다른 두 스레드를 확보한다.
        val taskExecutor = Executors.newFixedThreadPool(2)
        var manager: WorkManager? = null
        var failure: Throwable? = null
        try {
            val configuration = Configuration.Builder()
                .setWorkerFactory(SyntheticWorkerFactory(executions))
                .setExecutor(workerExecutor)
                .setTaskExecutor(taskExecutor)
                .build()
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                configuration,
                WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
            )
            val workManager = WorkManager.getInstance(context)
            manager = workManager
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)) {
                "실제 TestDriver가 설치되어야 한다"
            }
            val request = OneTimeWorkRequestBuilder<SyntheticWorker>()
                .setInputData(workDataOf(INPUT_KEY to INPUT_MARKER))
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            awaitOperationSuccess(workManager.enqueue(request))
            val waiting = checkNotNull(runBlocking {
                withTimeout(10_000) {
                    workManager.getWorkInfoByIdFlow(request.id).first { it != null }
                }
            })
            assertFalse(waiting.state.isFinished)
            assertEquals(WorkInfo.State.ENQUEUED, waiting.state)
            assertEquals(0, executions.get())

            driver.setInitialDelayMet(request.id)
            // instrumentation 스레드에서 관찰하며 실제 Android main executor는 계속 실행된다.
            val completed = runBlocking {
                withTimeout(10_000) {
                    workManager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
                }
            }
            assertEquals(WorkInfo.State.SUCCEEDED, checkNotNull(completed).state)
            assertEquals(workDataOf(OUTPUT_KEY to OUTPUT_MARKER), completed.outputData)
            assertEquals(1, executions.get())
        } catch (error: Throwable) {
            failure = error
        } finally {
            // 최초 실패는 유지하고 정리 실패는 suppressed로 모두 남긴다.
            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    val original = failure
                    if (original == null) failure = error else original.addSuppressed(error)
                }
            }
            manager?.let { workManager ->
                attempt { awaitOperationSuccess(workManager.cancelAllWork()) }
                attempt { WorkManagerTestInitHelper.closeWorkDatabase() }
            }
            attempt { shutdown(workerExecutor) }
            attempt { shutdown(taskExecutor) }
        }
        failure?.let { throw it }
    }

    /** 명령 ACK는 실제 main 스레드의 LiveData를 관찰하고 instrumentation 스레드에서 기다린다. */
    private fun awaitOperationSuccess(operation: Operation) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val terminal = CompletableDeferred<Operation.State>()
        val observer = Observer<Operation.State> { state ->
            if (state is Operation.State.SUCCESS || state is Operation.State.FAILURE) {
                terminal.complete(state)
            }
        }
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync { operation.state.observeForever(observer) }
            val state = runBlocking { withTimeout(10_000) { terminal.await() } }
            if (state is Operation.State.FAILURE) throw AssertionError("합성 WorkManager 명령이 실패했다")
            assertEquals(Operation.State.SUCCESS::class.java, state.javaClass)
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                instrumentation.runOnMainSync { operation.state.removeObserver(observer) }
            } catch (error: Throwable) {
                val original = failure
                if (original == null) failure = error else original.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun shutdown(executor: ExecutorService) {
        executor.shutdownNow()
        assertTrue("합성 fixture executor가 종료되어야 한다", executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    private class SyntheticWorkerFactory(private val executions: AtomicInteger) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            check(workerClassName == SyntheticWorker::class.java.name) { "합성 worker만 허용한다" }
            return SyntheticWorker(appContext, workerParameters, executions)
        }
    }

    private class SyntheticWorker(
        context: Context,
        parameters: WorkerParameters,
        private val executions: AtomicInteger,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            check(inputData == workDataOf(INPUT_KEY to INPUT_MARKER)) { "고정 합성 입력이어야 한다" }
            executions.incrementAndGet()
            return Result.success(workDataOf(OUTPUT_KEY to OUTPUT_MARKER))
        }
    }

    private companion object {
        const val INPUT_KEY = "starter-input"
        const val INPUT_MARKER = "synthetic-start"
        const val OUTPUT_KEY = "starter-output"
        const val OUTPUT_MARKER = "synthetic-complete"
    }
}
