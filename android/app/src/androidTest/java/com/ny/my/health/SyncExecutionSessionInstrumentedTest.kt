package com.ny.my.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 파일을 매번 다시 열고 합성 source와 destination으로 admission을 검증한다. */
@RunWith(AndroidJUnit4::class)
class SyncExecutionSessionInstrumentedTest {
    @Test
    fun preparedSessionAdmitsOneCredentialFactoryAndBusyCallersOnlyMergeTriggers() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val preparing = CountDownLatch(1)
            val release = CompletableDeferred<Unit>()
            val queued = CountDownLatch(1)
            val prepared = AtomicInteger()
            val busyPrepared = AtomicInteger()
            val session = f.session(legacyEngine = false)
            val owner = async(Dispatchers.IO) {
                session.runPrepared(SyncTrigger.MANUAL, f.generation) {
                    prepared.incrementAndGet()
                    preparing.countDown()
                    release.await()
                    f.engine()
                }
            }
            await(preparing)
            val busy = async(Dispatchers.IO) {
                f.session(alias = true, legacyEngine = false, observer = {
                    if (it == SyncSessionPhase.RECEIPT_QUEUED) queued.countDown()
                }).runPrepared(SyncTrigger.CATCH_UP, f.generation) {
                    busyPrepared.incrementAndGet()
                    error("대기 호출은 credential을 준비하면 안 됩니다")
                }
            }
            await(queued)
            assertFalse(busy.isCompleted)
            assertEquals(1, prepared.get())
            assertEquals(0, busyPrepared.get())
            assertTrue(f.accesses.isEmpty())
            assertEquals(0, f.reads.get())
            assertEquals(0, f.writes.get())
            assertNull(f.ready().attempt)
            release.complete(Unit)
            assertEquals(BatchStatus.MERGED, withTimeout(30000) { busy.await() }.status)
            assertEquals(BatchStatus.COMPLETED, withTimeout(30000) { owner.await() }.status)
            assertEquals(setOf(SyncTrigger.MANUAL, SyncTrigger.CATCH_UP), f.ready().attempt!!.pendingTriggers)
            assertEquals(1, prepared.get())
            assertEquals(0, busyPrepared.get())
            assertEquals(listOf(SyncTrigger.MANUAL, SyncTrigger.MANUAL), f.accesses)
            assertEquals(1, f.reads.get())
            assertEquals(1, f.writes.get())
            // 준비한 engine을 같은 session의 다음 legacy 호출에 재사용하지 않는다.
            try { session.run(SyncTrigger.MANUAL, f.generation); fail("새 실행 준비가 필요합니다") }
            catch (error: IllegalStateException) { assertEquals("동기화 실행 준비가 필요합니다", error.message) }
            assertEquals(2, f.accesses.size)
            assertEquals(1, f.reads.get())
        } finally { f.close() }
    }

    @Test
    fun failedOrCancelledPrepareResolvesReceiptsWithoutHealthIoAndAllowsFreshPreparation() = runBlocking {
        for (cancelled in listOf(false, true)) {
            val f = Fixture()
            try {
                f.configure()
                val preparing = CountDownLatch(1)
                val queued = CountDownLatch(1)
                val release = CompletableDeferred<Unit>()
                val prepared = AtomicInteger()
                val busyPrepared = AtomicInteger()
                val session = f.session(legacyEngine = false)
                val owner = launch(Dispatchers.IO) {
                    try {
                        session.runPrepared(SyncTrigger.MANUAL, f.generation) {
                            prepared.incrementAndGet()
                            preparing.countDown()
                            release.await()
                            error("합성 준비 실패")
                        }
                        fail("준비 실패가 전파되어야 합니다")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: IllegalStateException) {
                        assertFalse(cancelled)
                        assertEquals("합성 준비 실패", error.message)
                    }
                }
                await(preparing)
                val busy = async(Dispatchers.IO) {
                    f.session(legacyEngine = false, observer = {
                        if (it == SyncSessionPhase.RECEIPT_QUEUED) queued.countDown()
                    }).runPrepared(SyncTrigger.SCHEDULED, f.generation) {
                        busyPrepared.incrementAndGet()
                        error("대기 호출은 credential을 준비하면 안 됩니다")
                    }
                }
                await(queued)
                assertFalse(busy.isCompleted)
                assertTrue(f.accesses.isEmpty())
                if (cancelled) owner.cancel() else release.complete(Unit)
                withTimeout(30000) { owner.join() }
                assertEquals(cancelled, owner.isCancelled)
                assertEquals(BatchStatus.BLOCKED, withTimeout(30000) { busy.await() }.status)
                assertEquals(1, prepared.get())
                assertEquals(0, busyPrepared.get())
                assertTrue(f.accesses.isEmpty())
                assertEquals(0, f.reads.get())
                assertEquals(0, f.writes.get())
                assertNull(f.ready().attempt)
                assertEquals(BatchStatus.COMPLETED, session.runPrepared(SyncTrigger.MANUAL, f.generation) {
                    prepared.incrementAndGet()
                    f.engine()
                }.status)
                assertEquals(2, prepared.get())
                assertEquals(1, f.reads.get())
                assertEquals(1, f.writes.get())
            } finally { f.close() }
        }
    }

    @Test
    fun prepareFollowsDurableRecoveryAndGenerationChangeRejectsBeforeAccess() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val seeded = f.seed()
            var prepared = 0
            assertEquals(BatchStatus.STALE, f.session(legacyEngine = false).runPrepared(SyncTrigger.MANUAL, f.generation) {
                prepared++
                assertNotNull(f.ready().attempt!!.finishedAt)
                assertEquals(seeded.afterInterruption(), f.day())
                assertTrue(f.accesses.isEmpty())
                assertEquals(0, f.reads.get())
                assertEquals(CommitStatus.COMMITTED, f.store().invalidate(f.generation).status)
                f.engine()
            }.status)
            assertEquals(1, prepared)
            assertTrue(f.accesses.isEmpty())
            assertEquals(0, f.reads.get())
            assertEquals(0, f.writes.get())
            assertNull(f.ready().attempt)
        } finally { f.close() }
    }

    @Test
    fun concurrentManualPeriodicAndCatchUpRequestsAdmitOnceAndRejectOldGenerationCallbacksAfterRestart() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val preparing = CountDownLatch(1)
            val releaseStart = CountDownLatch(1)
            val receipts = CountDownLatch(2)
            val writing = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val observe: suspend (SyncSessionPhase) -> Unit = {
                when (it) {
                    SyncSessionPhase.BEFORE_START -> { preparing.countDown(); await(releaseStart) }
                    SyncSessionPhase.RECEIPT_QUEUED -> receipts.countDown()
                    else -> Unit
                }
            }
            f.write = { files, confirm ->
                writing.countDown(); await(releaseWrite)
                files.forEach { confirm(f.confirmation(it.key)) }
            }
            val owner = async(Dispatchers.IO) { f.session(observer = observe).run(SyncTrigger.MANUAL, f.generation) }
            await(preparing)
            // 같은 canonical 경로를 사용하는 별도 객체도 하나의 gate와 receipt를 공유한다.
            val periodic = async(Dispatchers.IO) { f.session(alias = true, observer = observe).run(SyncTrigger.SCHEDULED, f.generation) }
            val catchUp = async(Dispatchers.IO) { f.session(observer = observe).run(SyncTrigger.CATCH_UP, f.generation) }
            await(receipts)
            assertFalse(periodic.isCompleted)
            assertFalse(catchUp.isCompleted)
            releaseStart.countDown()
            assertEquals(BatchStatus.MERGED, withTimeout(30000) { periodic.await() }.status)
            assertEquals(BatchStatus.MERGED, withTimeout(30000) { catchUp.await() }.status)
            await(writing)
            val live = f.ready().attempt!!
            assertEquals(SyncTrigger.entries.toSet(), live.pendingTriggers)
            val accesses = f.accesses.size
            assertNull(f.session().detectPending().scopePendingCount)
            assertEquals(accesses, f.accesses.size)
            assertNull(f.ready().attempt!!.finishedAt)
            assertEquals(1, f.reads.get())
            assertEquals(1, f.writes.get())
            assertEquals(BatchStatus.MERGED, f.session(sessionClock = Clock.offset(f.clock,
                java.time.Duration.ofDays(90))).run(SyncTrigger.SCHEDULED, f.generation).status)
            assertEquals(1, f.reads.get())
            releaseWrite.countDown()
            assertEquals(BatchStatus.COMPLETED, withTimeout(30000) { owner.await() }.status)
            val oldGeneration = f.generation
            val oldDay = f.store().loadDay(oldGeneration, f.date.minusDays(1), f.key)!!
            val oldAttempt = f.ready().attempt!!
            val invalidated = f.session().invalidate(oldGeneration)
            assertEquals(CommitStatus.COMMITTED, invalidated.status)
            val fresh = f.ready()
            assertEquals(f.config.copy(automatic = false), fresh.configuration)
            assertNull(fresh.attempt)
            assertEquals(PrivateDestinationBinding("SYNTHETIC"), f.store().readBinding(fresh.generation))
            assertEquals(CommitStatus.STALE, f.store().transact(oldGeneration, f.mutation(oldAttempt.id,
                confirmation = f.confirmation(FileKey(oldGeneration, oldDay.date, f.key, Dataset.DAILY, oldDay.revision!!.digest)))) .status)
            assertEquals(CommitStatus.STALE, f.store().transact(oldGeneration,
                f.mutation(oldAttempt.id, finish = f.time)).status)
            assertEquals(BatchStatus.STALE, f.session().run(SyncTrigger.MANUAL, oldGeneration).status)
            assertEquals(fresh, f.ready())
            f.db.openForRead()!!.use { db ->
                db.rawQuery("SELECT count(*) FROM day WHERE generation=?", arrayOf(oldGeneration.value)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
            }
        } finally { f.close() }
    }

    @Test
    fun finishedOwnerBeforeGateReleasePersistsLateTriggerUnionWithoutChangingCompletion() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val finished = CountDownLatch(1)
            val release = CountDownLatch(1)
            val owner = async(Dispatchers.IO) { f.session {
                if (it == SyncSessionPhase.ENGINE_RETURNED) { finished.countDown(); await(release) }
            }.run(SyncTrigger.MANUAL, f.generation) }
            await(finished)
            val before = f.ready()
            assertNotNull(before.attempt!!.finishedAt)
            assertEquals(BatchStatus.MERGED, f.session().run(SyncTrigger.CATCH_UP, f.generation).status)
            val after = f.ready()
            assertEquals(before.attempt!!.copy(pendingTriggers = setOf(SyncTrigger.MANUAL, SyncTrigger.CATCH_UP)), after.attempt)
            assertEquals(before.copy(attempt = after.attempt), after)
            release.countDown()
            assertEquals(BatchStatus.COMPLETED, withTimeout(30000) { owner.await() }.status)
        } finally { f.close() }
    }

    @Test
    fun cancellingWriterKeepsGateUntilStructuredWriterExitAndDurableCleanup() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val writing = CountDownLatch(1)
            val exiting = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.write = { _, _ ->
                try { writing.countDown(); awaitCancellation() }
                finally { withContext(NonCancellable) { exiting.countDown(); await(release) } }
            }
            val owner = launch(Dispatchers.IO) { f.session().run(SyncTrigger.MANUAL, f.generation) }
            await(writing)
            owner.cancel()
            await(exiting)
            assertFalse(owner.isCompleted)
            assertEquals(BatchStatus.MERGED, f.session().run(SyncTrigger.SCHEDULED, f.generation).status)
            assertNull(f.session().detectPending().scopePendingCount)
            assertEquals(1, f.reads.get())
            assertEquals(FilePhase.RUNNING, f.day()!!.datasets.getValue(Dataset.DAILY).phase)
            release.countDown()
            withTimeout(30000) { owner.join() }
            assertTrue(owner.isCancelled)
            val row = f.day()!!
            assertEquals(FilePhase.INDETERMINATE, row.datasets.getValue(Dataset.DAILY).phase)
            assertNull(row.datasets.getValue(Dataset.DAILY).confirmedAt)
            assertEquals(f.time, row.checkedAt)
            assertNotNull(f.ready().attempt!!.finishedAt)
        } finally { f.close() }
    }

    @Test
    fun firstDetectRecoversExactUnfinishedJournalPreservingConfirmedPriorAndBlockedMetadataWithoutHealthIo() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val seeded = f.seed()
            val session = f.session()
            val coverage = session.detectPending()
            assertEquals(0, f.reads.get())
            assertEquals(0, f.writes.get())
            assertEquals(listOf(SyncTrigger.MANUAL), f.accesses)
            val recovered = f.day()!!
            assertEquals(seeded.afterInterruption(), recovered)
            assertEquals(seeded.datasets.getValue(Dataset.SLEEP), recovered.datasets.getValue(Dataset.SLEEP))
            assertEquals(seeded.blocker, recovered.blocker)
            assertEquals(seeded.checkedAt, recovered.checkedAt)
            assertNull(recovered.datasets.getValue(Dataset.DAILY).confirmedAt)
            assertNotNull(f.ready().attempt!!.finishedAt)
            assertEquals(1L, coverage.scopePendingCount)
            val before = f.ready()
            session.detectPending()
            assertEquals(before, f.ready())
        } finally { f.close() }
    }

    @Test
    fun cleanupCommitFailureKeepsCancellationAndNextOwnerRetriesRecoveryBeforeAdmission() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            f.write = { _, _ -> f.failCommit = true; throw CancellationException("합성 취소") }
            try { f.session().run(SyncTrigger.MANUAL, f.generation); fail("취소가 전파되어야 합니다") }
            catch (_: CancellationException) { }
            assertNull(f.ready().attempt!!.finishedAt)
            assertEquals(FilePhase.RUNNING, f.day()!!.datasets.getValue(Dataset.DAILY).phase)
            val accessCount = f.accesses.size
            try { f.session().detectPending(); fail("복구 실패는 admission을 차단해야 합니다") }
            catch (error: IllegalStateException) { assertEquals("동기화 저장소를 사용할 수 없습니다", error.message) }
            assertEquals(accessCount, f.accesses.size)
            assertEquals(1, f.reads.get())
            f.failCommit = false
            f.session().detectPending()
            assertEquals(FilePhase.INDETERMINATE, f.day()!!.datasets.getValue(Dataset.DAILY).phase)
            assertNotNull(f.ready().attempt!!.finishedAt)
            assertEquals(1, f.reads.get())
        } finally { f.close() }
    }

    @Test
    fun failedStartResolvesBoundedPreStartReceiptsWithoutClaimingMerged() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val preparing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val queued = CountDownLatch(1)
            val observe: suspend (SyncSessionPhase) -> Unit = {
                when (it) {
                    SyncSessionPhase.BEFORE_START -> { preparing.countDown(); await(release) }
                    SyncSessionPhase.RECEIPT_QUEUED -> queued.countDown()
                    else -> Unit
                }
            }
            val owner = async(Dispatchers.IO) { f.session(observer = observe).run(SyncTrigger.MANUAL, f.generation) }
            await(preparing)
            val pending = async(Dispatchers.IO) { f.session(observer = observe).run(SyncTrigger.CATCH_UP, f.generation) }
            await(queued)
            f.failCommit = true
            release.countDown()
            assertEquals(BatchStatus.BLOCKED, withTimeout(30000) { owner.await() }.status)
            assertEquals(BatchStatus.BLOCKED, withTimeout(30000) { pending.await() }.status)
            assertNull(f.ready().attempt)
            assertEquals(0, f.reads.get())
        } finally { f.close() }
    }

    @Test
    fun cancelledPreStartOwnerResolvesReceiptsWithoutStartingHealthOrRemoteWork() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val preparing = CountDownLatch(1)
            val queued = CountDownLatch(1)
            val observe: suspend (SyncSessionPhase) -> Unit = {
                when (it) {
                    SyncSessionPhase.BEFORE_START -> { preparing.countDown(); awaitCancellation() }
                    SyncSessionPhase.RECEIPT_QUEUED -> queued.countDown()
                    else -> Unit
                }
            }
            val owner = launch(Dispatchers.IO) { f.session(observer = observe).run(SyncTrigger.MANUAL, f.generation) }
            await(preparing)
            val pending = async(Dispatchers.IO) { f.session(observer = observe).run(SyncTrigger.SCHEDULED, f.generation) }
            await(queued)
            owner.cancel()
            withTimeout(30000) { owner.join() }
            assertEquals(BatchStatus.BLOCKED, withTimeout(30000) { pending.await() }.status)
            assertTrue(owner.isCancelled)
            assertNull(f.ready().attempt)
            assertEquals(0, f.reads.get())
            assertEquals(0, f.writes.get())
        } finally { f.close() }
    }

    @Test
    fun detectingAutomaticPolicyUsesAccessMetadataOnlyAndAbsentOrUnavailableStoresNeverFabricateCoverage() = runBlocking {
        val f = Fixture()
        try {
            try { f.session().detectPending(); fail("미설정을 구분해야 합니다") }
            catch (error: IllegalStateException) { assertEquals("동기화가 설정되지 않았습니다", error.message) }
            f.configure()
            f.session().detectPending()
            f.db.openForWrite().use { it.execSQL("UPDATE config SET automatic=1 WHERE id=1") }
            f.session().detectPending()
            assertEquals(listOf(SyncTrigger.MANUAL, SyncTrigger.CATCH_UP), f.accesses)
            assertEquals(0, f.reads.get())
            assertEquals(0, f.writes.get())
            val saved = f.file.readBytes()
            f.file.writeText("합성 손상")
            try { f.session().detectPending(); fail("저장소 오류를 구분해야 합니다") }
            catch (error: IllegalStateException) { assertEquals("동기화 저장소를 사용할 수 없습니다", error.message) }
            assertEquals(2, f.accesses.size)
            f.file.writeBytes(saved)
            val tombstone = f.store().reset(f.generation)
            assertEquals(CommitStatus.COMMITTED, tombstone.status)
            assertEquals(0L, f.session().detectPending().scopePendingCount)
            assertEquals(2, f.accesses.size)
        } finally { f.close() }
    }

    @Test
    fun differentDatabasePathsAdmitIndependentlyWhileFirstDetectAndRunShareOneGate() = runBlocking {
        val f = Fixture()
        val other = Fixture()
        try {
            f.configure(); other.configure(); f.seed()
            val accessing = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.onAccess = { accessing.countDown(); await(release) }
            val detecting = async(Dispatchers.IO) { f.session().detectPending() }
            await(accessing)
            assertNotNull(f.ready().attempt!!.finishedAt)
            assertEquals(BatchStatus.PENDING, f.session().run(SyncTrigger.MANUAL, f.generation).status)
            assertEquals(BatchStatus.COMPLETED, other.session().run(SyncTrigger.MANUAL, other.generation).status)
            assertEquals(0, f.reads.get())
            release.countDown()
            withTimeout(30000) { detecting.await() }
            Unit
        } finally { f.close(); other.close() }
    }

    @Test
    fun unknownCreateSurvivesRereadCancellationAndSqliteReopenWithoutBlindCreate() = runBlocking {
        for (changed in listOf(false, true)) for (found in listOf(false, true)) {
            val f = Fixture()
            try {
                f.configure()
                // Synthetic remote create succeeds, but its confirmation is lost.
                f.write = { _, _ -> }
                assertEquals(BatchStatus.PENDING, f.session().run(SyncTrigger.MANUAL, f.generation).status)
                assertEquals(1, f.writes.get())
                val exposed = f.day()!!
                assertNull(exposed.blocker)
                assertEquals(FilePhase.INDETERMINATE, exposed.datasets.getValue(Dataset.DAILY).phase)
                assertNull(exposed.datasets.getValue(Dataset.DAILY).targetHandle)
                if (changed) f.steps = 13
                fun reopenedStore() = SqliteSyncStateStore(SyncStateDatabase(f.file), { f.date })
                f.reconcile = { files ->
                    assertTrue(files.single().createMayHaveSucceeded)
                    val durable = reopenedStore().loadDay(f.generation, f.date.minusDays(1), f.key)!!
                    assertEquals(FilePhase.INDETERMINATE, durable.datasets.getValue(Dataset.DAILY).phase)
                    assertEquals(changed, exposed.revision != durable.revision)
                    throw CancellationException("합성 재확인 이전 재중단")
                }
                try {
                    f.session().run(SyncTrigger.MANUAL, f.generation)
                    fail("재확인 이전 취소를 전파해야 한다")
                } catch (_: CancellationException) { }
                val reopened = reopenedStore().loadDay(f.generation, f.date.minusDays(1), f.key)!!
                assertEquals(FilePhase.INDETERMINATE, reopened.datasets.getValue(Dataset.DAILY).phase)
                assertNull(reopened.datasets.getValue(Dataset.DAILY).targetHandle)
                f.reconcile = { files ->
                    assertTrue(files.single().createMayHaveSucceeded)
                    if (!found) throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
                    ReconciledFiles(files.map { f.confirmation(it.key) }, emptyList(), emptySet())
                }
                val outcome = f.session(alias = true).run(SyncTrigger.MANUAL, f.generation)
                assertEquals(if (found) BatchStatus.COMPLETED else BatchStatus.BLOCKED, outcome.status)
                assertEquals(1, f.writes.get())
                val final = reopenedStore().loadDay(f.generation, f.date.minusDays(1), f.key)!!
                if (found) {
                    assertTrue(final.isUploaded())
                    assertEquals("synthetic-target-DAILY", final.datasets.getValue(Dataset.DAILY).targetHandle)
                } else {
                    assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED, final.blocker)
                    assertEquals(FilePhase.INDETERMINATE, final.datasets.getValue(Dataset.DAILY).phase)
                }
            } finally { f.close() }
        }
    }

    @Test
    fun crossScopeUnknownCreatesSurviveNewJournalCancellationAndSqliteReopen() = runBlocking {
        for (found in listOf(false, true)) {
            val f = Fixture()
            try {
                f.configure()
                f.write = { _, _ -> }
                assertEquals(BatchStatus.PENDING, f.session().run(SyncTrigger.MANUAL, f.generation).status)
                val summary = mapOf(DataType.STEPS to ReadScope.SUMMARY)
                f.access = ReadAccess(true, summary.keys, summary)
                val summaryKey = SyncPlanner.scopeKey(f.config, ReadAccess(true, summary.keys, summary))!!
                fun reopenedStore() = SqliteSyncStateStore(SyncStateDatabase(f.file), { f.date })
                assertNull(reopenedStore().loadDay(f.generation, f.date.minusDays(1), summaryKey))
                assertEquals(setOf(Dataset.DAILY), reopenedStore().loadUnresolvedCreates(f.generation, f.date.minusDays(1)))
                f.reconcile = { files ->
                    assertTrue(files.single().createMayHaveSucceeded)
                    val journal = reopenedStore().loadDay(f.generation, f.date.minusDays(1), summaryKey)!!
                    assertEquals(FilePhase.INDETERMINATE, journal.datasets.getValue(Dataset.DAILY).phase)
                    throw CancellationException("합성 새 범위 journal 뒤 재중단")
                }
                try { f.session().run(SyncTrigger.MANUAL, f.generation); fail("취소를 전파해야 한다") }
                catch (_: CancellationException) { }
                assertEquals(setOf(Dataset.DAILY), reopenedStore().loadUnresolvedCreates(f.generation, f.date.minusDays(1)))
                f.reconcile = { files ->
                    assertTrue(files.single().createMayHaveSucceeded)
                    if (!found) throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
                    ReconciledFiles(files.map { f.confirmation(it.key) }, emptyList(), emptySet())
                }
                assertEquals(if (found) BatchStatus.COMPLETED else BatchStatus.BLOCKED,
                    f.session(alias = true).run(SyncTrigger.MANUAL, f.generation).status)
                assertEquals(1, f.writes.get())
                val result = reopenedStore().loadDay(f.generation, f.date.minusDays(1), summaryKey)!!
                if (found) assertTrue(result.isUploaded())
                else assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED, result.blocker)
                assertTrue(reopenedStore().loadUnresolvedCreates(f.generation, f.date.minusDays(2))!!.isEmpty())
                val next = f.store().invalidate(f.generation)
                assertEquals(CommitStatus.COMMITTED, next.status)
                assertNull(reopenedStore().loadUnresolvedCreates(f.generation, f.date.minusDays(1)))
                assertTrue(reopenedStore().loadUnresolvedCreates(next.generation!!, f.date.minusDays(1))!!.isEmpty())
            } finally { f.close() }
        }
    }

    @Test
    fun physicalUncertaintyReadRejectsMalformedOtherProfilesOrphansAndUnavailableDatabases() = runBlocking {
        for (damage in listOf("day", "dataset", "orphan", "unsupported", "corrupt", "absent")) {
            val f = Fixture()
            try {
                if (damage != "absent") {
                    f.configure()
                    f.write = { _, _ -> }
                    assertEquals(BatchStatus.PENDING, f.session().run(SyncTrigger.MANUAL, f.generation).status)
                    val summary = mapOf(DataType.STEPS to ReadScope.SUMMARY)
                    f.access = ReadAccess(true, summary.keys, summary)
                    f.reconcile = { throw CancellationException("합성 다른 범위 journal 준비") }
                    try { f.session().run(SyncTrigger.MANUAL, f.generation); fail("취소를 전파해야 한다") }
                    catch (_: CancellationException) { }
                    if (damage == "corrupt") f.file.writeText("합성 손상")
                    else f.db.openForWrite().use { db ->
                        when (damage) {
                            "day" -> db.execSQL("UPDATE day SET phase='INVALID' WHERE scope_key=?", arrayOf(f.key.value))
                            "dataset" -> db.execSQL("UPDATE dataset SET phase='INVALID' WHERE scope_key=?", arrayOf(f.key.value))
                            "orphan" -> {
                                db.setForeignKeyConstraintsEnabled(false)
                                db.execSQL("DELETE FROM day WHERE scope_key=?", arrayOf(f.key.value))
                            }
                            "unsupported" -> db.version = 2
                        }
                    }
                }
                val generation = if (damage == "absent") DestinationGeneration(UUID.randomUUID().toString()) else f.generation
                try {
                    SqliteSyncStateStore(SyncStateDatabase(f.file), { f.date })
                        .loadUnresolvedCreates(generation, f.date.minusDays(1))
                    fail("$damage 조회 실패를 빈 집합으로 바꾸면 안 된다")
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { }
            } finally { f.close() }
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "session-${UUID.randomUUID()}.db")
        val db = SyncStateDatabase(file)
        val date = LocalDate.of(2026, 11, 5)
        val time = Instant.parse("2026-11-05T00:00:00Z")
        val clock = Clock.fixed(time, ZoneOffset.UTC)
        val config = SyncConfiguration("synthetic-session", date.minusDays(1), false, setOf(DataType.STEPS))
        val scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
        var access = ReadAccess(true, scopes.keys, scopes)
        val key = SyncPlanner.scopeKey(config, access)!!
        private var configuredGeneration: DestinationGeneration? = null
        val generation: DestinationGeneration get() = requireNotNull(configuredGeneration)
        @Volatile var failCommit = false
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val accesses = CopyOnWriteArrayList<SyncTrigger>()
        var onAccess: suspend () -> Unit = {}
        var steps = 12L
        var reconcile: suspend (List<PlannedFile>) -> ReconciledFiles = { ReconciledFiles(emptyList(), it, emptySet()) }
        var write: suspend (List<PlannedFile>, suspend (FileConfirmation) -> Unit) -> Unit = { files, confirm ->
            files.forEach { confirm(confirmation(it.key)) }
        }
        val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger): ReadAccess {
                accesses += trigger; onAccess(); return access
            }
            override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                reads.incrementAndGet()
                return ScopedSnapshot(HealthSnapshot(date, steps, emptyList(), emptyList(), emptyList(), emptyList(), scopes.keys),
                    requestedScopes, emptySet())
            }
        }
        val destination = object : StorageDestination {
            override suspend fun reconcile(files: List<PlannedFile>) = this@Fixture.reconcile(files)
            override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) {
                writes.incrementAndGet(); write(files, onConfirmed)
            }
        }
        fun store(alias: Boolean = false) = SqliteSyncStateStore(
            if (alias) SyncStateDatabase(File(file.parentFile, "./${file.name}")) else db, { date },
            { if (failCommit) error("합성 커밋 실패") })
        fun engine(store: SqliteSyncStateStore = store(), sessionClock: Clock = clock) =
            DestinationSync(store, reader, destination, sessionClock)
        fun session(alias: Boolean = false, sessionClock: Clock = clock, legacyEngine: Boolean = true,
            observer: suspend (SyncSessionPhase) -> Unit = {}): SyncExecutionSession {
            val store = store(alias)
            return SyncExecutionSession(store, reader, if (legacyEngine) engine(store, sessionClock) else null, sessionClock,
                { UUID.randomUUID().toString() }, observer)
        }
        suspend fun configure() {
            val result = store().configure(null, config, PrivateDestinationBinding("SYNTHETIC"))
            assertEquals(CommitStatus.COMMITTED, result.status)
            configuredGeneration = result.generation!!
        }
        suspend fun ready() = store().load().also { assertEquals(StateStatus.READY, it.status) }.state!!
        suspend fun day() = store().loadDay(generation, date.minusDays(1), key)
        fun confirmation(key: FileKey) = FileConfirmation(key, "synthetic-target-${key.dataset.name}", time)
        fun mutation(id: String, start: AttemptState? = null, day: DayState? = null,
            confirmation: FileConfirmation? = null, finish: Instant? = null) = SyncMutation(id, start, day, confirmation, null, finish)
        suspend fun seed(): DayState {
            val id = UUID.randomUUID().toString()
            val attempt = AttemptState(id, key, scopes, SyncTrigger.MANUAL, time, time, null,
                listOf(date.minusDays(1)), "b".repeat(64), 0, setOf(SyncTrigger.MANUAL))
            assertEquals(CommitStatus.COMMITTED, store().transact(generation, mutation(id, start = attempt)).status)
            val revision = RevisionKey("a".repeat(64), time)
            val prior = confirmation(FileKey(generation, date.minusDays(1), key, Dataset.DAILY, "c".repeat(64)))
            val row = DayState(date.minusDays(1), key, scopes, revision, DayPhase.RUNNING, scopes, time,
                mapOf(Dataset.DAILY to DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true,
                    "synthetic-target-DAILY", time, null, prior),
                    Dataset.SLEEP to DatasetState(Dataset.SLEEP, revision, FilePhase.RUNNING, true,
                        "synthetic-target-SLEEP", time, null, null)), null)
            assertEquals(CommitStatus.COMMITTED, store().transact(generation, mutation(id, day = row)).status)
            assertEquals(CommitStatus.COMMITTED, store().transact(generation, mutation(id,
                confirmation = confirmation(FileKey(generation, row.date, key, Dataset.SLEEP, revision.digest)))).status)
            val blocked = day()!!.copy(phase = DayPhase.BLOCKED, blocker = BlockReason.DESTINATION_BLOCKED)
            assertEquals(CommitStatus.COMMITTED, store().transact(generation, mutation(id, day = blocked)).status)
            return blocked
        }
        fun close() {
            file.delete(); File(file.path + "-journal").delete(); db.resetDirectory.deleteRecursively()
        }
    }

    companion object {
        private fun await(latch: CountDownLatch) {
            check(latch.await(20, TimeUnit.SECONDS)) { "합성 동시성 단계 시간 초과" }
        }
    }
}
