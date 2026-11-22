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
        val driveBinding = PrivateDestinationBinding("DRIVE", accountName = "synthetic-account", accountType = "synthetic-type")
        suspend fun configure(drive: Boolean = false): DestinationGeneration {
            val config = SyncConfiguration(if (drive) "private-drive" else "synthetic", day, false, scopes.keys)
            val result = store().configure(null, config, if (drive) driveBinding else PrivateDestinationBinding("SYNTHETIC"))
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

    @Test
    fun sameRevisionRereadIsRecordedAtomicallyEvenWhenManualDayAlreadyMatches() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val manual = AttemptState(UUID.randomUUID().toString(), f.key(), f.scopes, SyncTrigger.MANUAL,
                f.now, f.now, null, f.dates, "e".repeat(64), 0, emptySet())
            val row = f.row("a".repeat(64))
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(manual.id, manual, null, null, null, null)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(manual.id, null, row, null, null, null)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(manual.id, null, null, null, null, f.now)).status)
            val claim = f.initial(generation) as RetryClaim.Claimed
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            val reread = SyncMutation(claim.attempt.id, null, row, null, null, null, owner)
            assertEquals(CommitStatus.UNAVAILABLE, f.store { error("합성 커밋 중단") }.transact(generation, reread).status)
            assertNull(f.budget(generation).observedRevisions[f.day])
            assertEquals(row, f.store().loadDay(generation, f.day, f.key()))
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, reread).status)
            assertEquals(row.revision!!.digest, f.budget(generation).observedRevisions[f.day])
            assertEquals(row, f.store().loadDay(generation, f.day, f.key()))
        } finally { f.close() }
    }

    @Test
    fun authorizationAndExhaustionPreserveRemoteStaleAndPriorConfirmationThroughReopen() = runBlocking {
        for (auth in listOf(true, false)) {
            val f = Fixture()
            try {
                val generation = f.configure()
                var claim = f.initial(generation) as RetryClaim.Claimed
                var at = f.now
                if (!auth) repeat(3) {
                    assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, claim.budget.lineageId,
                        claim.attempt.id, claim.budget.claimVersion, AutomaticFinishOutcome.RETRYABLE, at).status)
                    val budget = f.budget(generation)
                    at = requireNotNull(budget.retryNotBefore)
                    claim = f.reclaim(generation, budget, UUID.randomUUID().toString(), at) as RetryClaim.Claimed
                }
                val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, claim.budget.claimVersion)
                val revision = RevisionKey("a".repeat(64), at)
                val prior = FileConfirmation(FileKey(generation, f.day, f.key(), Dataset.DAILY, "b".repeat(64)), "prior-target", at)
                val runningFile = DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true, "old-target", at, null, prior)
                val running = DayState(f.day, f.key(), f.scopes, revision, DayPhase.RUNNING, f.scopes, at,
                    mapOf(Dataset.DAILY to runningFile), null)
                assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                    SyncMutation(owner.attemptId, null, running, null, null, null, owner)).status)
                val stale = running.copy(phase = DayPhase.REMOTE_STALE,
                    datasets = mapOf(Dataset.DAILY to runningFile.copy(phase = FilePhase.REMOTE_STALE, populated = false)))
                assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                    SyncMutation(owner.attemptId, null, stale, null, null, null, owner)).status)
                val outcome = if (auth) AutomaticFinishOutcome.ACCOUNT_AUTHORIZATION_BLOCKED else AutomaticFinishOutcome.RETRYABLE
                assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, owner.lineageId,
                    owner.attemptId, owner.claimVersion, outcome, at).status)
                assertEquals(stale, f.store().loadDay(generation, f.day, f.key()))
                assertEquals(prior, f.store().loadDay(generation, f.day, f.key())!!.datasets.getValue(Dataset.DAILY).priorConfirmation)
                assertEquals(if (auth) RetryBudgetStatus.BLOCKED else RetryBudgetStatus.EXHAUSTED, f.budget(generation).status)
                assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(generation).status)
                assertEquals(stale, f.store().loadDay(generation, f.day, f.key()))
                assertEquals(if (auth) RetryBudgetStatus.BLOCKED else RetryBudgetStatus.EXHAUSTED, f.budget(generation).status)
            } finally { f.close() }
        }
    }

    @Test
    fun manualRecoveryRetiresPristineInterruptionAndOtherEligibleCohortOnlyAfterCommit() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val first = f.initial(generation) as RetryClaim.Claimed
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, first.budget.lineageId,
                first.attempt.id, 0, AutomaticFinishOutcome.ACCOUNT_AUTHORIZATION_BLOCKED, f.now).status)
            val otherScopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
            val access = ReadAccess(true, otherScopes.keys, otherScopes)
            val otherKey = requireNotNull(SyncPlanner.scopeKey(SyncConfiguration("synthetic", f.day, true, otherScopes.keys), access))
            val proposal = InitialAutomaticProposal.validated(generation, otherKey, otherScopes, f.dates,
                UUID.randomUUID().toString(), SyncTrigger.SCHEDULED, f.now, AutomaticRetryModel.accessFingerprint(access), f.day, f.today)
            val other = f.store().claimAutomaticAttempt(generation, null, null, proposal, otherKey, otherScopes, f.dates,
                proposal.attemptId, proposal.trigger, f.now) as RetryClaim.Claimed
            val owner = AutomaticOwner(other.budget.lineageId, other.attempt.id, 0)
            assertEquals(CommitStatus.COMMITTED, f.store().recoverAutomaticAttempt(generation, owner, f.now).status)
            assertNull(f.store().loadDay(generation, f.day, otherKey))
            assertEquals(CommitStatus.UNAVAILABLE, f.store { error("합성 커밋 중단") }.reopenManualBlockers(generation).status)
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(generation).status)
            assertEquals(RetryBudgetStatus.BLOCKED, (f.store().loadAutomaticBudget(generation, otherKey) as AutomaticBudgetRead.Ready).budget!!.status)
            assertEquals(BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED, f.store().loadDay(generation, f.day, f.key())!!.blocker)
            assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(generation).status)
            assertNull((f.store().loadAutomaticBudget(generation, f.key()) as AutomaticBudgetRead.Ready).budget)
            assertNull((f.store().loadAutomaticBudget(generation, otherKey) as AutomaticBudgetRead.Ready).budget)
            assertNull(f.store().loadDay(generation, f.day, otherKey))
            assertNull(f.store().loadDay(generation, f.day, f.key())!!.blocker)
        } finally { f.close() }
    }

    @Test
    fun multiDateFinishFailureRollsBackAllRowsAndRetainsOwnerAfterReopen() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val enrolled = f.day
            f.today = f.today.plusDays(1)
            val dates = listOf(enrolled, enrolled.plusDays(1))
            val proposal = InitialAutomaticProposal.validated(generation, f.key(), f.scopes, dates, UUID.randomUUID().toString(),
                SyncTrigger.SCHEDULED, f.now, AutomaticRetryModel.accessFingerprint(f.access), enrolled, f.today)
            val claim = f.store().claimAutomaticAttempt(generation, null, null, proposal, f.key(), f.scopes, dates,
                proposal.attemptId, proposal.trigger, f.now) as RetryClaim.Claimed
            assertEquals(CommitStatus.UNAVAILABLE, f.store { error("합성 커밋 중단") }.finishAutomaticAttempt(generation,
                claim.budget.lineageId, claim.attempt.id, 0, AutomaticFinishOutcome.ACCOUNT_AUTHORIZATION_BLOCKED, f.now).status)
            for (date in dates) assertNull(f.store().loadDay(generation, date, f.key()))
            assertNull(f.store().loadCurrentAttempt(generation)!!.finishedAt)
            assertEquals(claim.attempt.id, f.budget(generation).ownerAttemptId)
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, claim.budget.lineageId,
                claim.attempt.id, 0, AutomaticFinishOutcome.ACCOUNT_AUTHORIZATION_BLOCKED, f.now).status)
            for (date in dates) assertEquals(BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED, f.store().loadDay(generation, date, f.key())!!.blocker)
        } finally { f.close() }
    }

    @Test
    fun ineligibleCohortRemainsBlockedWhileOtherEligibleCohortReopens() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val first = f.initial(generation) as RetryClaim.Claimed
            val owner = AutomaticOwner(first.budget.lineageId, first.attempt.id, 0)
            val sourceBlocked = DayState(f.day, f.key(), f.scopes, null, DayPhase.BLOCKED,
                emptyMap(), null, emptyMap(), BlockReason.SOURCE_READ_FAILED)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(owner.attemptId, null, sourceBlocked, null, null, null, owner)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, owner.lineageId,
                owner.attemptId, 0, AutomaticFinishOutcome.BLOCKED, f.now).status)
            val otherScopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
            val access = ReadAccess(true, otherScopes.keys, otherScopes)
            val otherKey = requireNotNull(SyncPlanner.scopeKey(SyncConfiguration("synthetic", f.day, true, otherScopes.keys), access))
            val proposal = InitialAutomaticProposal.validated(generation, otherKey, otherScopes, f.dates,
                UUID.randomUUID().toString(), SyncTrigger.SCHEDULED, f.now, AutomaticRetryModel.accessFingerprint(access), f.day, f.today)
            val other = f.store().claimAutomaticAttempt(generation, null, null, proposal, otherKey, otherScopes, f.dates,
                proposal.attemptId, proposal.trigger, f.now) as RetryClaim.Claimed
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, other.budget.lineageId,
                other.attempt.id, 0, AutomaticFinishOutcome.ACCOUNT_AUTHORIZATION_BLOCKED, f.now).status)
            assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(generation).status)
            assertEquals(sourceBlocked, f.store().loadDay(generation, f.day, f.key()))
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(generation).status)
            assertNull((f.store().loadAutomaticBudget(generation, otherKey) as AutomaticBudgetRead.Ready).budget)
            assertNull(f.store().loadDay(generation, f.day, otherKey)!!.blocker)
        } finally { f.close() }
    }

    @Test
    fun pristineInterruptedCohortCanReopenAfterAnotherScopeReplacesItsAttempt() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val first = f.initial(generation) as RetryClaim.Claimed
            assertEquals(CommitStatus.COMMITTED, f.store().recoverAutomaticAttempt(generation,
                AutomaticOwner(first.budget.lineageId, first.attempt.id, 0), f.now).status)
            val otherScopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
            val access = ReadAccess(true, otherScopes.keys, otherScopes)
            val otherKey = requireNotNull(SyncPlanner.scopeKey(SyncConfiguration("synthetic", f.day, true, otherScopes.keys), access))
            val proposal = InitialAutomaticProposal.validated(generation, otherKey, otherScopes, f.dates,
                UUID.randomUUID().toString(), SyncTrigger.SCHEDULED, f.now, AutomaticRetryModel.accessFingerprint(access), f.day, f.today)
            val second = f.store().claimAutomaticAttempt(generation, null, null, proposal, otherKey, otherScopes, f.dates,
                proposal.attemptId, proposal.trigger, f.now) as RetryClaim.Claimed
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, second.budget.lineageId,
                second.attempt.id, 0, AutomaticFinishOutcome.BLOCKED, f.now).status)
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(generation).status)
            assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(generation).status)
            assertNull((f.store().loadAutomaticBudget(generation, f.key()) as AutomaticBudgetRead.Ready).budget)
            assertNull(f.store().loadDay(generation, f.day, f.key()))
            assertTrue(f.initial(generation) is RetryClaim.Claimed)
        } finally { f.close() }
    }

    @Test
    fun pristineInterruptedCohortCanReopenAfterManualAttemptReplacesItsLink() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val first = f.initial(generation) as RetryClaim.Claimed
            assertEquals(CommitStatus.COMMITTED, f.store().recoverAutomaticAttempt(generation,
                AutomaticOwner(first.budget.lineageId, first.attempt.id, 0), f.now).status)
            val manual = AttemptState(UUID.randomUUID().toString(), f.key(), f.scopes, SyncTrigger.MANUAL,
                f.now, f.now, null, f.dates, "e".repeat(64), 0, emptySet())
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(manual.id, manual, null, null, null, null)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(manual.id, null, null, null, null, f.now)).status)
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(generation).status)
            assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(generation).status)
            assertNull((f.store().loadAutomaticBudget(generation, f.key()) as AutomaticBudgetRead.Ready).budget)
            assertNull(f.store().loadDay(generation, f.day, f.key()))
            assertTrue(f.initial(generation) is RetryClaim.Claimed)
        } finally { f.close() }
    }

    @Test
    fun failedSourceReadPreservesOldRevisionAndPriorWithoutRecordingANewObservation() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val manual = AttemptState(UUID.randomUUID().toString(), f.key(), f.scopes, SyncTrigger.MANUAL,
                f.now, f.now, null, f.dates, "e".repeat(64), 0, emptySet())
            val revision = RevisionKey("a".repeat(64), f.now)
            val prior = FileConfirmation(FileKey(generation, f.day, f.key(), Dataset.DAILY, "b".repeat(64)),
                "prior-target", f.now)
            val runningFile = DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true,
                "current-target", f.now, null, prior)
            val running = DayState(f.day, f.key(), f.scopes, revision, DayPhase.RUNNING, f.scopes,
                f.now, mapOf(Dataset.DAILY to runningFile), null)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(manual.id, manual, null, null, null, null)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(manual.id, null, running, null, null, null)).status)
            val confirmation = FileConfirmation(FileKey(generation, f.day, f.key(), Dataset.DAILY, revision.digest),
                "current-target", f.now)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(manual.id, null, null, confirmation, null, null)).status)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(manual.id, null, null, null, null, f.now)).status)
            val previous = f.store().loadDay(generation, f.day, f.key())!!
            assertEquals(DayPhase.CONFIRMED, previous.phase)
            val claim = f.initial(generation) as RetryClaim.Claimed
            assertNull(f.budget(generation).observedRevisions[f.day])
            val failed = previous.copy(phase = DayPhase.BLOCKED, checkedAt = null, blocker = BlockReason.SOURCE_READ_FAILED)
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(owner.attemptId, null, failed, null, null, null, owner)).status)
            val reopened = f.store().loadDay(generation, f.day, f.key())!!
            assertEquals(failed, reopened)
            assertEquals(revision, reopened.revision)
            assertEquals(prior, reopened.datasets.getValue(Dataset.DAILY).priorConfirmation)
            assertEquals(confirmation.targetHandle, reopened.datasets.getValue(Dataset.DAILY).targetHandle)
            assertNull(f.budget(generation).observedRevisions[f.day])
        } finally { f.close() }
    }

    @Test
    fun driveOptOutCarriesUnknownAndStaleJournalsConfirmedMetadataAndRejectsStaleCallbacks() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure(drive = true)
            val claim = f.initial(generation) as RetryClaim.Claimed
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            val revision = RevisionKey("a".repeat(64), f.now)
            val prior = FileConfirmation(FileKey(generation, f.day, f.key(), Dataset.WORKOUTS, "b".repeat(64)), "prior-target", f.now)
            val unknown = DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true, null, f.now, null, null)
            val running = DatasetState(Dataset.WORKOUTS, revision, FilePhase.RUNNING, true, "stale-target", f.now, null, prior)
            val confirmedFile = DatasetState(Dataset.SLEEP, revision, FilePhase.RUNNING, true, "confirmed-target", f.now, null, null)
            val day = DayState(f.day, f.key(), f.scopes, revision, DayPhase.RUNNING, f.scopes, f.now,
                mapOf(Dataset.DAILY to unknown, Dataset.WORKOUTS to running, Dataset.SLEEP to confirmedFile), null)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(owner.attemptId, null, day, null, null, null, owner)).status)
            val confirmation = FileConfirmation(FileKey(generation, f.day, f.key(), Dataset.SLEEP, revision.digest), "confirmed-target", f.now)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(owner.attemptId, null, null, confirmation, null, null, owner)).status)
            val withConfirmation = f.store().loadDay(generation, f.day, f.key())!!
            val stale = withConfirmation.copy(datasets = withConfirmation.datasets +
                (Dataset.WORKOUTS to running.copy(phase = FilePhase.REMOTE_STALE, populated = false)))
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(owner.attemptId, null, stale, null, null, null, owner)).status)
            assertEquals(CommitStatus.UNAVAILABLE, f.store { error("합성 커밋 중단") }.setAutomaticCAS(generation, false, f.now).status)
            assertEquals(generation, f.store().load().state!!.generation)
            assertEquals(owner.attemptId, f.budget(generation).ownerAttemptId)
            val disabled = f.store().setAutomaticCAS(generation, false, f.now)
            assertEquals(CommitStatus.COMMITTED, disabled.status)
            val next = requireNotNull(disabled.generation)
            assertNotEquals(generation, next)
            assertEquals(CommitStatus.STALE, f.store().finishAutomaticAttempt(generation, owner.lineageId, owner.attemptId,
                0, AutomaticFinishOutcome.COMPLETED, f.now).status)
            val carried = f.store().loadDay(next, f.day, f.key())!!
            assertEquals(FilePhase.INDETERMINATE, carried.datasets.getValue(Dataset.DAILY).phase)
            assertEquals(FilePhase.REMOTE_STALE, carried.datasets.getValue(Dataset.WORKOUTS).phase)
            assertEquals(prior.copy(key = prior.key.copy(generation = next)), carried.datasets.getValue(Dataset.WORKOUTS).priorConfirmation)
            assertEquals(FilePhase.CONFIRMED, carried.datasets.getValue(Dataset.SLEEP).phase)
            assertEquals("confirmed-target", carried.datasets.getValue(Dataset.SLEEP).targetHandle)
            assertTrue(Dataset.DAILY in f.store().loadUnresolvedCreates(next, f.day)!!)
            assertEquals(setOf("stale-target", "prior-target"), f.store().loadKnownTargets(next, f.day).getValue(Dataset.WORKOUTS))
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(next).status)
            val configured = f.store().configure(next, SyncConfiguration("private-drive", f.day, false, f.scopes.keys), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, configured.status)
            val configuredGeneration = requireNotNull(configured.generation)
            val configuredDay = f.store().loadDay(configuredGeneration, f.day, f.key())!!
            assertEquals(carried.phase, configuredDay.phase)
            assertEquals(prior.copy(key = prior.key.copy(generation = configuredGeneration)),
                configuredDay.datasets.getValue(Dataset.WORKOUTS).priorConfirmation)
            assertTrue(Dataset.DAILY in f.store().loadUnresolvedCreates(configuredGeneration, f.day)!!)
            assertEquals(CommitStatus.COMMITTED, f.store().setAutomaticCAS(configuredGeneration, true, f.now).status)
            assertEquals(RetryClaim.Blocked, f.initial(configuredGeneration))
            assertEquals(configuredDay, f.store().loadDay(configuredGeneration, f.day, f.key()))
        } finally { f.close() }
    }

    @Test
    fun driveOptOutAndInvalidationRetainRetryCountDueAndExplicitRecoveryBoundary() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure(drive = true)
            val first = f.initial(generation) as RetryClaim.Claimed
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, first.budget.lineageId,
                first.attempt.id, 0, AutomaticFinishOutcome.RETRYABLE, f.now).status)
            val waiting = f.budget(generation)
            val disabled = f.store().setAutomaticCAS(generation, false, f.now)
            assertEquals(CommitStatus.COMMITTED, disabled.status)
            val next = requireNotNull(disabled.generation)
            val carried = f.budget(next)
            assertNotEquals(waiting.lineageId, carried.lineageId)
            assertEquals(waiting.retriesUsed, carried.retriesUsed)
            assertEquals(waiting.claimVersion, carried.claimVersion)
            assertEquals(waiting.retryNotBefore, carried.retryNotBefore)
            assertEquals(waiting.retryDelayMillis, carried.retryDelayMillis)
            assertEquals(waiting.observedRevisions, carried.observedRevisions)
            assertEquals(CommitStatus.COMMITTED, f.store().setAutomaticCAS(next, true, f.now).status)
            val due = requireNotNull(carried.retryNotBefore)
            assertEquals(RetryClaim.Deferred(due), f.reclaim(next, carried, UUID.randomUUID().toString(), due.minusMillis(1)))
            val retry = f.reclaim(next, carried, UUID.randomUUID().toString(), due) as RetryClaim.Claimed
            assertEquals(1, retry.budget.retriesUsed)
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(next, retry.budget.lineageId,
                retry.attempt.id, retry.budget.claimVersion, AutomaticFinishOutcome.RETRYABLE, due).status)
            val waitingAgain = f.budget(next)
            val configured = f.store().configure(next, SyncConfiguration("private-drive", f.day, false, f.scopes.keys), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, configured.status)
            val configuredGeneration = requireNotNull(configured.generation)
            assertEquals(waitingAgain.retriesUsed, f.budget(configuredGeneration).retriesUsed)
            assertEquals(waitingAgain.retryNotBefore, f.budget(configuredGeneration).retryNotBefore)
            val invalidated = f.store().invalidate(configuredGeneration)
            assertEquals(CommitStatus.COMMITTED, invalidated.status)
            val newer = requireNotNull(invalidated.generation)
            val retained = f.budget(newer)
            assertEquals(1, retained.retriesUsed)
            assertEquals(waitingAgain.claimVersion, retained.claimVersion)
            assertEquals(waitingAgain.retryNotBefore, retained.retryNotBefore)
            assertEquals(waitingAgain.observedRevisions, retained.observedRevisions)
            assertEquals(CommitStatus.STALE, f.store().finishAutomaticAttempt(next, retry.budget.lineageId,
                retry.attempt.id, retry.budget.claimVersion, AutomaticFinishOutcome.BLOCKED, due).status)
            assertEquals(CommitStatus.COMMITTED, f.store().setAutomaticCAS(newer, true, due).status)
            assertEquals(RetryClaim.Deferred(retained.retryNotBefore!!), f.reclaim(newer, retained,
                UUID.randomUUID().toString(), retained.retryNotBefore!!.minusMillis(1)))
        } finally { f.close() }
    }

    @Test
    fun driveOptOutPreservesExhaustedBudgetUntilExplicitManualRecovery() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure(drive = true)
            var claim = f.initial(generation) as RetryClaim.Claimed
            var at = f.now
            repeat(3) {
                assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, claim.budget.lineageId,
                    claim.attempt.id, claim.budget.claimVersion, AutomaticFinishOutcome.RETRYABLE, at).status)
                val waiting = f.budget(generation)
                at = requireNotNull(waiting.retryNotBefore)
                claim = f.reclaim(generation, waiting, UUID.randomUUID().toString(), at) as RetryClaim.Claimed
            }
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, claim.budget.lineageId,
                claim.attempt.id, claim.budget.claimVersion, AutomaticFinishOutcome.RETRYABLE, at).status)
            val original = f.budget(generation)
            assertEquals(RetryBudgetStatus.EXHAUSTED, original.status)
            val disabled = f.store().setAutomaticCAS(generation, false, at)
            assertEquals(CommitStatus.COMMITTED, disabled.status)
            val next = requireNotNull(disabled.generation)
            val carried = f.budget(next)
            assertEquals(RetryBudgetStatus.EXHAUSTED, carried.status)
            assertEquals(3, carried.retriesUsed)
            assertEquals(original.claimVersion, carried.claimVersion)
            assertEquals(BlockReason.RETRY_EXHAUSTED, f.store().loadDay(next, f.day, f.key())!!.blocker)
            assertEquals(CommitStatus.COMMITTED, f.store().setAutomaticCAS(next, true, at).status)
            assertEquals(RetryClaim.Exhausted, f.initial(next))
            assertEquals(CommitStatus.COMMITTED, f.store().reopenManualBlockers(next).status)
            assertNull((f.store().loadAutomaticBudget(next, f.key()) as AutomaticBudgetRead.Ready).budget)
            assertNull(f.store().loadDay(next, f.day, f.key())!!.blocker)
        } finally { f.close() }
    }

    @Test
    fun driveInvalidationRetainsInFlightUnknownAndRejectsOldOwner() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure(drive = true)
            val claim = f.initial(generation) as RetryClaim.Claimed
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            val revision = RevisionKey("a".repeat(64), f.now)
            val file = DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true, null, f.now, null, null)
            val day = DayState(f.day, f.key(), f.scopes, revision, DayPhase.RUNNING, f.scopes, f.now,
                mapOf(Dataset.DAILY to file), null)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation,
                SyncMutation(owner.attemptId, null, day, null, null, null, owner)).status)
            val invalidated = f.store().invalidate(generation)
            assertEquals(CommitStatus.COMMITTED, invalidated.status)
            val next = requireNotNull(invalidated.generation)
            assertTrue(Dataset.DAILY in f.store().loadUnresolvedCreates(next, f.day)!!)
            assertEquals(FilePhase.INDETERMINATE, f.store().loadDay(next, f.day, f.key())!!.datasets.getValue(Dataset.DAILY).phase)
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(next).status)
            assertEquals(CommitStatus.STALE, f.store().recoverAutomaticAttempt(generation, owner, f.now).status)
            val configured = f.store().configure(next, SyncConfiguration("private-drive", f.day, false, f.scopes.keys), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, configured.status)
            val configuredGeneration = requireNotNull(configured.generation)
            assertTrue(Dataset.DAILY in f.store().loadUnresolvedCreates(configuredGeneration, f.day)!!)
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(configuredGeneration).status)
        } finally { f.close() }
    }

    @Test
    fun sameDriveCategoryChangesPreserveHistoricalTargetsUnknownsPriorAndAccountIsolation() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure(drive = true)
            val enrolled = f.day
            val claim = f.initial(generation) as RetryClaim.Claimed
            val owner = AutomaticOwner(claim.budget.lineageId, claim.attempt.id, 0)
            val revision = RevisionKey("a".repeat(64), f.now)
            val prior = FileConfirmation(FileKey(generation, enrolled, f.key(), Dataset.WORKOUTS, "b".repeat(64)), "prior-target", f.now)
            val unknown = DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true, null, f.now, null, null)
            val running = DatasetState(Dataset.WORKOUTS, revision, FilePhase.RUNNING, true, "confirmed-target", f.now, null, prior)
            val day = DayState(enrolled, f.key(), f.scopes, revision, DayPhase.RUNNING, f.scopes, f.now,
                mapOf(Dataset.DAILY to unknown, Dataset.WORKOUTS to running), null)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(owner.attemptId, null, day, null, null, null, owner)).status)
            val confirmation = FileConfirmation(FileKey(generation, enrolled, f.key(), Dataset.WORKOUTS, revision.digest), "confirmed-target", f.now)
            assertEquals(CommitStatus.COMMITTED, f.store().transact(generation, SyncMutation(owner.attemptId, null, null, confirmation, null, null, owner)).status)
            f.today = f.today.plusDays(1)
            val expanded = setOf(DataType.STEPS, DataType.SLEEP)
            val request = SyncConfiguration("private-drive", f.day, false, expanded)
            assertEquals(CommitStatus.UNAVAILABLE, f.store { error("합성 커밋 중단") }.configure(generation, request, f.driveBinding).status)
            assertEquals(generation, f.store().load().state!!.generation)
            assertNull(f.store().loadCurrentAttempt(generation)!!.finishedAt)
            val added = f.store().configure(generation, request, f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, added.status)
            val addedGeneration = requireNotNull(added.generation)
            val addedConfig = f.store().load().state!!.configuration!!
            assertEquals(expanded, addedConfig.categories)
            assertEquals(enrolled, addedConfig.enrolledDay)
            assertEquals(CommitStatus.STALE, f.store().recoverAutomaticAttempt(generation, owner, f.now).status)
            assertTrue(Dataset.DAILY in f.store().loadUnresolvedCreates(addedGeneration, enrolled)!!)
            assertEquals(setOf("confirmed-target", "prior-target"), f.store().loadKnownTargets(addedGeneration, enrolled).getValue(Dataset.WORKOUTS))
            val onlySleep = setOf(DataType.SLEEP)
            val removed = f.store().configure(addedGeneration, SyncConfiguration("private-drive", f.day, false, onlySleep), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, removed.status)
            val removedGeneration = requireNotNull(removed.generation)
            assertEquals(onlySleep, f.store().load().state!!.configuration!!.categories)
            assertEquals(enrolled, f.store().load().state!!.configuration!!.enrolledDay)
            val carried = f.store().loadDay(removedGeneration, enrolled, f.key())!!
            assertEquals(FilePhase.INDETERMINATE, carried.datasets.getValue(Dataset.DAILY).phase)
            assertEquals(FilePhase.CONFIRMED, carried.datasets.getValue(Dataset.WORKOUTS).phase)
            assertEquals(prior.copy(key = prior.key.copy(generation = removedGeneration)), carried.datasets.getValue(Dataset.WORKOUTS).priorConfirmation)
            assertEquals(RetryBudgetStatus.BLOCKED, f.budget(removedGeneration).status)
            val different = f.store().configure(removedGeneration, SyncConfiguration("private-drive", f.day, false, onlySleep),
                PrivateDestinationBinding("DRIVE", accountName = "another-synthetic-account", accountType = "synthetic-type"))
            assertEquals(CommitStatus.COMMITTED, different.status)
            val otherGeneration = requireNotNull(different.generation)
            assertNull(f.store().loadDay(otherGeneration, enrolled, f.key()))
            assertTrue(f.store().loadKnownTargets(otherGeneration, enrolled).isEmpty())
            assertNull((f.store().loadAutomaticBudget(otherGeneration, f.key()) as AutomaticBudgetRead.Ready).budget)
        } finally { f.close() }
    }

    @Test
    fun removedCategoryCannotClaimCarriedRetryAndReselectionRetainsBudgetAndDue() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure(drive = true)
            val date = f.day
            val first = f.initial(generation) as RetryClaim.Claimed
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, first.budget.lineageId,
                first.attempt.id, 0, AutomaticFinishOutcome.RETRYABLE, f.now).status)
            val original = f.budget(generation)
            f.today = f.today.plusDays(1)
            val added = f.store().configure(generation, SyncConfiguration("private-drive", f.day, false,
                setOf(DataType.STEPS, DataType.SLEEP)), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, added.status)
            val addedGeneration = requireNotNull(added.generation)
            val removed = f.store().configure(addedGeneration, SyncConfiguration("private-drive", f.day, false,
                setOf(DataType.SLEEP)), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, removed.status)
            val removedGeneration = requireNotNull(removed.generation)
            assertEquals(CommitStatus.COMMITTED, f.store().setAutomaticCAS(removedGeneration, true, f.now).status)
            val carried = f.budget(removedGeneration)
            assertEquals(original.retryNotBefore, carried.retryNotBefore)
            assertEquals(original.retryDelayMillis, carried.retryDelayMillis)
            assertEquals(original.retriesUsed, carried.retriesUsed)
            assertEquals(original.claimVersion, carried.claimVersion)
            assertEquals(original.observedRevisions, carried.observedRevisions)
            try {
                f.store().claimAutomaticAttempt(removedGeneration, carried.lineageId, carried.claimVersion, null,
                    f.key(), f.scopes, listOf(date), UUID.randomUUID().toString(), SyncTrigger.CATCH_UP,
                    requireNotNull(carried.retryNotBefore))
                fail("제거된 범위는 실행할 수 없습니다")
            } catch (_: IllegalArgumentException) { }
            assertEquals(carried.retriesUsed, f.budget(removedGeneration).retriesUsed)
            assertEquals(carried.claimVersion, f.budget(removedGeneration).claimVersion)
            val selected = f.store().configure(removedGeneration, SyncConfiguration("private-drive", f.day, false,
                setOf(DataType.STEPS)), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, selected.status)
            val selectedGeneration = requireNotNull(selected.generation)
            assertEquals(setOf(DataType.STEPS), f.store().load().state!!.configuration!!.categories)
            assertEquals(date, f.store().load().state!!.configuration!!.enrolledDay)
            assertEquals(CommitStatus.COMMITTED, f.store().setAutomaticCAS(selectedGeneration, true, f.now).status)
            val restored = f.budget(selectedGeneration)
            assertEquals(original.retryNotBefore, restored.retryNotBefore)
            assertEquals(original.retriesUsed, restored.retriesUsed)
            val due = requireNotNull(restored.retryNotBefore)
            assertEquals(RetryClaim.Deferred(due), f.store().claimAutomaticAttempt(selectedGeneration, restored.lineageId,
                restored.claimVersion, null, f.key(), f.scopes, listOf(date), UUID.randomUUID().toString(), SyncTrigger.CATCH_UP, due.minusMillis(1)))
            val retry = f.store().claimAutomaticAttempt(selectedGeneration, restored.lineageId, restored.claimVersion,
                null, f.key(), f.scopes, listOf(date), UUID.randomUUID().toString(), SyncTrigger.CATCH_UP, due) as RetryClaim.Claimed
            assertEquals(1, retry.budget.retriesUsed)
        } finally { f.close() }
    }

    @Test
    fun removedAndReselectedCategoryRetainsExhaustedBudgetAndBlocker() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure(drive = true)
            var claim = f.initial(generation) as RetryClaim.Claimed
            var at = f.now
            repeat(3) {
                assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, claim.budget.lineageId,
                    claim.attempt.id, claim.budget.claimVersion, AutomaticFinishOutcome.RETRYABLE, at).status)
                val waiting = f.budget(generation)
                at = requireNotNull(waiting.retryNotBefore)
                claim = f.reclaim(generation, waiting, UUID.randomUUID().toString(), at) as RetryClaim.Claimed
            }
            assertEquals(CommitStatus.COMMITTED, f.store().finishAutomaticAttempt(generation, claim.budget.lineageId,
                claim.attempt.id, claim.budget.claimVersion, AutomaticFinishOutcome.RETRYABLE, at).status)
            val exhausted = f.budget(generation)
            val removed = f.store().configure(generation, SyncConfiguration("private-drive", f.day, false,
                setOf(DataType.SLEEP)), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, removed.status)
            val removedGeneration = requireNotNull(removed.generation)
            val historical = f.budget(removedGeneration)
            assertEquals(RetryBudgetStatus.EXHAUSTED, historical.status)
            assertEquals(3, historical.retriesUsed)
            assertEquals(exhausted.claimVersion, historical.claimVersion)
            assertEquals(BlockReason.RETRY_EXHAUSTED, f.store().loadDay(removedGeneration, f.day, f.key())!!.blocker)
            val selected = f.store().configure(removedGeneration, SyncConfiguration("private-drive", f.day, false,
                setOf(DataType.STEPS)), f.driveBinding)
            assertEquals(CommitStatus.COMMITTED, selected.status)
            val selectedGeneration = requireNotNull(selected.generation)
            assertEquals(CommitStatus.COMMITTED, f.store().setAutomaticCAS(selectedGeneration, true, at).status)
            val restored = f.budget(selectedGeneration)
            assertEquals(RetryBudgetStatus.EXHAUSTED, restored.status)
            assertEquals(3, restored.retriesUsed)
            assertEquals(exhausted.claimVersion, restored.claimVersion)
            assertEquals(RetryClaim.Exhausted, f.store().claimAutomaticAttempt(selectedGeneration, restored.lineageId,
                restored.claimVersion, null, f.key(), f.scopes, listOf(f.day), UUID.randomUUID().toString(), SyncTrigger.CATCH_UP, at))
        } finally { f.close() }
    }

}
