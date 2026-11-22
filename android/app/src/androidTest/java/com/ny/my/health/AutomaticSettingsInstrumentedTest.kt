package com.ny.my.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.CommitStatus
import com.ny.my.health.core.DataType
import com.ny.my.health.core.StateStatus
import com.ny.my.health.core.SyncConfiguration
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticSettingsInstrumentedTest {
    @Test
    fun cancellationBeforeCommitLeavesAutomaticOffAfterSqliteReopenAndDoesNotEnqueue() = runBlocking {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        val file = File(root, "automatic-settings-${UUID.randomUUID()}.db")
        val today = LocalDate.of(2026, 11, 15)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val enqueued = AtomicInteger()
        fun store(before: () -> Unit = {}) = SqliteSyncStateStore(SyncStateDatabase(file), { today }, before)
        try {
            val configured = store().configure(null, SyncConfiguration("synthetic", today.minusDays(1), false,
                setOf(DataType.STEPS)), PrivateDestinationBinding("SYNTHETIC"))
            assertEquals(CommitStatus.COMMITTED, configured.status)
            val generation = requireNotNull(configured.generation)
            val writing = store {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            val controller = AutomaticSettingsController(
                commit = { expected, enabled -> writing.setAutomaticCAS(expected, enabled, Instant.parse("2026-11-15T00:00:00Z")) },
                isCurrent = { _, _ -> true }, cancel = {}, schedule = { enqueued.incrementAndGet() },
                catchUp = { enqueued.incrementAndGet() },
            )
            val ticket = controller.invalidate()
            val request = async(Dispatchers.Default) { controller.change(ticket, generation, true) }
            assertTrue("transaction reached pre-commit", entered.await(10, TimeUnit.SECONDS))
            controller.invalidate()
            release.countDown()
            try { request.await(); fail("cancelled transaction returned normally") } catch (_: CancellationException) { }
            val reopened = store().load()
            assertEquals(StateStatus.READY, reopened.status)
            assertEquals(generation, reopened.state?.generation)
            assertEquals(false, reopened.state?.configuration?.automatic)
            assertEquals(0, enqueued.get())
        } finally {
            release.countDown()
            root.listFiles()?.filter { it.name.startsWith(file.name) }?.forEach { it.deleteRecursively() }
        }
    }
}
