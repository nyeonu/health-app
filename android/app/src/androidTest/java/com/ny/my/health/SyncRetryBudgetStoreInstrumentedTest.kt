package com.ny.my.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRetryBudgetStoreInstrumentedTest {
    private class Fixture {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        val file = File(root, "retry-store-${UUID.randomUUID()}.db")
        val db = SyncStateDatabase(file)
        var today: LocalDate = LocalDate.parse("2026-11-15")
        val now: Instant = Instant.parse("2026-11-15T00:00:00Z")
        val scopes = mapOf(DataType.STEPS to ReadScope.SUMMARY)
        val access = ReadAccess(true, scopes.keys, scopes)
        val day: LocalDate get() = today.minusDays(1)
        val dates: List<LocalDate> get() = listOf(day)
        fun store(before: () -> Unit = {}) = SqliteSyncStateStore(db, { today }, before)
        suspend fun configure(): DestinationGeneration {
            val config = SyncConfiguration("synthetic", day, false, scopes.keys)
            val result = store().configure(null, config, PrivateDestinationBinding("SYNTHETIC"))
            assertEquals(CommitStatus.COMMITTED, result.status)
            val generation = requireNotNull(result.generation)
            assertEquals(CommitStatus.COMMITTED, store().setAutomaticCAS(generation, true, now).status)
            return generation
        }
        fun key(): ReadScopeKey = requireNotNull(SyncPlanner.scopeKey(
            SyncConfiguration("synthetic", day, true, scopes.keys), access))
        fun proposal(generation: DestinationGeneration, id: String = UUID.randomUUID().toString()): InitialAutomaticProposal =
            InitialAutomaticProposal.validated(generation, key(), scopes, dates, id, SyncTrigger.SCHEDULED,
                now, AutomaticRetryModel.accessFingerprint(access), day, today)
        suspend fun initial(generation: DestinationGeneration, proposal: InitialAutomaticProposal = proposal(generation),
            before: () -> Unit = {}): RetryClaim = store(before).claimAutomaticAttempt(generation, null, null, proposal,
            key(), scopes, dates, proposal.attemptId, proposal.trigger, now)
        suspend fun reclaim(generation: DestinationGeneration, budget: RetryBudget, id: String, at: Instant): RetryClaim =
            store().claimAutomaticAttempt(generation, budget.lineageId, budget.claimVersion, null, key(), scopes, dates,
                id, SyncTrigger.CATCH_UP, at)
        suspend fun budget(generation: DestinationGeneration): RetryBudget =
            requireNotNull((store().loadAutomaticBudget(generation, key()) as AutomaticBudgetRead.Ready).budget)
        fun row(revision: String, time: Instant = now): DayState = DayState(day, key(), scopes,
            RevisionKey(revision, time), DayPhase.PENDING, scopes, time, emptyMap(), null)
        fun close() { root.listFiles()?.filter { it.name.startsWith(file.name) }?.forEach { it.deleteRecursively() } }
    }

    @Test
    fun sqliteRetryClaimsSurviveReopenAndRacingAttemptsCannotExceedThreeRetriesOrEraseOldMetadata() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val proposal = f.proposal(generation)
            val first = f.initial(generation, proposal) as RetryClaim.Claimed
            val owner = AutomaticOwner(first.budget.lineageId, first.attempt.id, 0)
            assertEquals(AutomaticPreflight.RecoveryRequired, f.store().preflightAutomaticAdmission(generation, f.now))
            val row = f.row("a".repeat(64))
            val ownerless = SyncMutation(first.attempt.id, null, row, null, null, null)
            assertEquals(CommitStatus.INVALID, f.store().transact(generation, ownerless).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, ownerless.copy(automaticOwner = owner)).status)
            assertEquals(row.revision!!.digest, f.budget(generation).observedRevisions[f.day])
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(first.attempt.id, null, null, null, setOf(SyncTrigger.MANUAL), null)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(first.attempt.id, null, null, null, setOf(SyncTrigger.CATCH_UP), null)).status)
            assertEquals(setOf(SyncTrigger.MANUAL, SyncTrigger.CATCH_UP), f.store().loadCurrentAttempt(generation)!!.pendingTriggers)
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, first.budget.lineageId,
                first.attempt.id, 0, AutomaticFinishOutcome.RETRYABLE, f.now).status)
            var budget = f.budget(generation)
            var at = f.now
            for (retry in 1..3) {
                val due = requireNotNull(budget.retryNotBefore)
                assertEquals(listOf(30L, 60L, 120L)[retry - 1], due.epochSecond - at.epochSecond)
                assertEquals(AutomaticPreflight.Deferred(due), f.store().preflightAutomaticAdmission(generation, due.minusMillis(1)))
                assertEquals(RetryClaim.Deferred(due), f.reclaim(generation, budget, UUID.randomUUID().toString(), due.minusMillis(1)))
                assertEquals(AutomaticPreflight.Ready, f.store().preflightAutomaticAdmission(generation, due))
                val old = budget
                val contenders = (1..2).map { async(Dispatchers.IO) { f.reclaim(generation, old, UUID.randomUUID().toString(), due) } }.awaitAll()
                val claimed = contenders.filterIsInstance<RetryClaim.Claimed>().single()
                assertEquals(1, contenders.count { it is RetryClaim.Claimed })
                assertEquals(retry, claimed.budget.retriesUsed)
                assertEquals(retry.toLong(), claimed.budget.claimVersion)
                assertEquals(row.revision!!.digest, f.budget(generation).observedRevisions[f.day])
                val replay = f.reclaim(generation, old, UUID.randomUUID().toString(), due)
                assertTrue(replay == RetryClaim.Busy || replay == RetryClaim.Stale)
                assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, old.lineageId,
                    claimed.attempt.id, claimed.budget.claimVersion, AutomaticFinishOutcome.RETRYABLE, due).status)
                budget = f.budget(generation)
                at = due
            }
            assertEquals(RetryBudgetStatus.EXHAUSTED, budget.status)
            assertEquals(3, budget.retriesUsed)
            assertNull(budget.retryNotBefore)
            val blocked = f.store().loadDay(generation, f.day, f.key())!!
            assertEquals(BlockReason.RETRY_EXHAUSTED, blocked.blocker)
            assertEquals(row.revision, blocked.revision)
            assertEquals(RetryClaim.Exhausted, f.reclaim(generation, budget, UUID.randomUUID().toString(), at))
            assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(generation).status)
            assertNull((f.store().loadAutomaticBudget(generation, f.key()) as AutomaticBudgetRead.Ready).budget)
            assertNull(f.store().loadDay(generation, f.day, f.key())!!.blocker)
        } finally { f.close() }
    }

    @Test
    fun sqliteClaimRollbackAndRecoveryPreserveOwnershipAndInterruptedFileUncertainty() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val proposal = f.proposal(generation)
            try { f.initial(generation, proposal) { error("합성 커밋 중단") }; fail() } catch (_: IllegalStateException) { }
            assertNull((f.store().loadAutomaticBudget(generation, f.key()) as AutomaticBudgetRead.Ready).budget)
            assertNull(f.store().loadCurrentAttempt(generation))
            val claim = f.initial(generation, proposal) as RetryClaim.Claimed
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            val revision = RevisionKey("b".repeat(64), f.now)
            val file = DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true, "synthetic-target", f.now, null, null)
            val running = DayState(f.day, f.key(), f.scopes, revision, DayPhase.RUNNING, f.scopes, f.now, mapOf(Dataset.DAILY to file), null)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(claim.attempt.id, null, running, null, null, null, owner)).status)
            assertEquals(CommitStatus.STALE, f.store().recoverAutomaticAttempt(generation, owner.copy(claimVersion = 1), f.now).status)
            assertEquals(CommitStatus.UNAVAILABLE, f.store { error("합성 커밋 중단") }.recoverAutomaticAttempt(generation, owner, f.now).status)
            assertNull(f.store().loadCurrentAttempt(generation)!!.finishedAt)
            assertEquals(FilePhase.RUNNING, f.store().loadDay(generation, f.day, f.key())!!.datasets.getValue(Dataset.DAILY).phase)
            assertEquals(CommitStatus.COMMITTED, f.store().recoverAutomaticAttempt(generation, owner, f.now).status)
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(generation).status)
            val recovered = f.store().loadDay(generation, f.day, f.key())!!
            assertEquals(FilePhase.INDETERMINATE, recovered.datasets.getValue(Dataset.DAILY).phase)
            assertEquals("synthetic-target", recovered.datasets.getValue(Dataset.DAILY).targetHandle)
            assertEquals(revision.digest, f.budget(generation).observedRevisions[f.day])
        } finally { f.close() }
    }

    @Test
    fun authorizationFailureBeforeDayReadAtomicallyBlocksFrozenDatesAndRetainsAttemptLink() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val claim = f.initial(generation) as RetryClaim.Claimed
            val result = f.store().finishAutomaticAttempt(generation, claim.budget.lineageId, claim.attempt.id, 0,
                AutomaticFinishOutcome.ACCOUNT_AUTHORIZATION_BLOCKED, f.now)
            assertEquals(CommitStatus.COMMITTED, result.status)
            val day = f.store().loadDay(generation, f.day, f.key())!!
            assertEquals(BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED, day.blocker)
            assertNull(day.revision)
            assertTrue(day.datasets.isEmpty())
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(generation).status)
            f.db.openForRead().use { db -> db!!.rawQuery("SELECT automatic_lineage_id FROM attempt WHERE generation=?", arrayOf(generation.value)).use {
                assertTrue(it.moveToFirst()); assertEquals(claim.budget.lineageId, it.getString(0))
            } }
            assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(generation).status)
            assertNull((f.store().loadAutomaticBudget(generation, f.key()) as AutomaticBudgetRead.Ready).budget)
        } finally { f.close() }
    }

    @Test
    fun brokenPositiveOwnerLinkFailsClosedAfterReopen() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val claim = f.initial(generation) as RetryClaim.Claimed
            f.db.openForWrite().use { db -> db.execSQL("UPDATE retry_budget SET owner_attempt_id=? WHERE lineage_id=?",
                arrayOf(UUID.randomUUID().toString(), claim.budget.lineageId)) }
            assertNotEquals(StateStatus.READY, f.store().load().status)
            try { f.store().preflightAutomaticAdmission(generation, f.now); fail() } catch (_: IllegalStateException) { }
            assertEquals(CommitStatus.UNAVAILABLE, f.store().recoverAutomaticAttempt(generation,
                AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0), f.now).status)
        } finally { f.close() }
    }
    @Test
    fun optedOutOwnerIsRecoveredAndStaleCallbacksCannotChangeArchivedLinkedAttempt() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val claim = f.initial(generation) as RetryClaim.Claimed
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            val row = f.row("c".repeat(64))
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(claim.attempt.id, null, row, null, null, null, owner)).status)
            val optOut = f.store().setAutomaticCAS(generation, false, f.now)
            assertEquals(CommitStatus.COMMITTED, optOut.status)
            assertNotEquals(generation, optOut.generation)
            assertEquals(CommitStatus.STALE, f.store().finishAutomaticAttempt(generation, owner.lineageId,
                owner.attemptId, 0, AutomaticFinishOutcome.COMPLETED, f.now).status)
            f.db.openForRead().use { db ->
                db!!.rawQuery("SELECT a.automatic_lineage_id,a.finished_at,b.status,b.observed_revisions_json FROM attempt a JOIN retry_budget b ON b.lineage_id=a.automatic_lineage_id WHERE a.generation=?", arrayOf(generation.value)).use {
                    assertTrue(it.moveToFirst()); assertEquals(owner.lineageId, it.getString(0)); assertNotNull(it.getString(1))
                    assertEquals("BLOCKED", it.getString(2)); assertTrue(it.getString(3).contains("c".repeat(64)))
                }
            }
            assertEquals(StateStatus.READY, f.store().load().status)
        } finally { f.close() }
    }

    @Test
    fun manualReplacementPreservesCompletedBudgetAndClearsOnlyTheAttemptLink() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val claim = f.initial(generation) as RetryClaim.Claimed
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            val row = f.row("d".repeat(64))
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(claim.attempt.id, null, row, null, null, null, owner)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(claim.attempt.id, null, row.copy(phase = DayPhase.EMPTY), null, null, null, owner)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, owner.lineageId,
                owner.attemptId, 0, AutomaticFinishOutcome.COMPLETED, f.now).status)
            val manual = AttemptState(UUID.randomUUID().toString(), f.key(), f.scopes, SyncTrigger.MANUAL,
                f.now, f.now, null, f.dates, "e".repeat(64), 0, emptySet())
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(manual.id, manual, null, null, null, null)).status)
            f.db.openForRead().use { db ->
                db!!.rawQuery("SELECT automatic_lineage_id FROM attempt WHERE generation=?", arrayOf(generation.value)).use {
                    assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
                }
                db.rawQuery("SELECT status,observed_revisions_json FROM retry_budget WHERE lineage_id=?", arrayOf(owner.lineageId)).use {
                    assertTrue(it.moveToFirst()); assertEquals("COMPLETED", it.getString(0)); assertTrue(it.getString(1).contains("d".repeat(64)))
                }
            }
        } finally { f.close() }
    }

    @Test
    fun orphanLinkAndMismatchedObservedRevisionFailClosedAcrossReopen() = runBlocking {
        for (kind in listOf("orphan", "revision")) {
            val f = Fixture()
            try {
                val generation = f.configure()
                val claim = f.initial(generation) as RetryClaim.Claimed
                val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
                val row = f.row("a".repeat(64))
                assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                    SyncMutation(claim.attempt.id, null, row, null, null, null, owner)).status)
                android.database.sqlite.SQLiteDatabase.openDatabase(f.file.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    if (kind == "orphan") db.execSQL("UPDATE attempt SET automatic_lineage_id=? WHERE generation=?",
                        arrayOf(UUID.randomUUID().toString(), generation.value))
                    else db.execSQL("UPDATE retry_budget SET observed_revisions_json=? WHERE lineage_id=?",
                        arrayOf("{\"${f.day}\":\"${"b".repeat(64)}\"}", owner.lineageId))
                }
                assertNotEquals(StateStatus.READY, f.store().load().status)
                assertEquals(CommitStatus.UNAVAILABLE, f.store().recoverAutomaticAttempt(generation, owner, f.now).status)
            } finally { f.close() }
        }
    }

}
