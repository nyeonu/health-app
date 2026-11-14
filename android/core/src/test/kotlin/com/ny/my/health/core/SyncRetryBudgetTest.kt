package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SyncRetryBudgetTest {
    @Test fun `Retry lineage permits one initial run and three claimed retries despite new attempt ids and changed observed revisions`() {
        val generation = DestinationGeneration("00000000-0000-4000-8000-000000000002")
        val lineage = "00000000-0000-4000-8000-000000000001"
        val scopes = mutableMapOf(DataType.STEPS to ReadScope.SUMMARY)
        val config = SyncConfiguration("synthetic", LocalDate.parse("2026-01-01"), true, setOf(DataType.STEPS))
        val access = ReadAccess(true, setOf(DataType.STEPS), scopes)
        val key = requireNotNull(SyncPlanner.scopeKey(config, access))
        val dates = mutableListOf(LocalDate.parse("2026-01-02"))
        val now = Instant.parse("2026-01-03T00:00:00Z")
        val initialId = "00000000-0000-4000-8000-000000000003"
        val proposal = InitialAutomaticProposal.validated(generation, key, scopes, dates, initialId,
            SyncTrigger.SCHEDULED, now, AutomaticRetryModel.accessFingerprint(access), config.enrolledDay, LocalDate.parse("2026-01-03"))
        dates.clear(); scopes.clear()
        assertEquals(1, proposal.frozenDates.size)
        assertEquals(1, proposal.requestedScopes.size)
        val start = AutomaticRetryModel.initialClaim(proposal, lineage, generation, config, key, proposal.requestedScopes,
            config.enrolledDay, LocalDate.parse("2026-01-03"), now) as RetryValidation.Valid
        val first = start.value as RetryClaim.Claimed
        var budget = first.budget
        assertEquals(mapOf(proposal.frozenDates.single() to null), budget.observedRevisions)
        try { (first.attempt.dates as MutableList).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (budget.observedRevisions as MutableMap).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (proposal.requestedScopes as MutableMap).clear(); fail() } catch (_: UnsupportedOperationException) { }
        assertEquals("2ae9b57cbb27eccbfdf58955bbbeb1d0ee1bd81e0d2af0463f67c5c4aa54b034", budget.retryBudgetKey)
        assertEquals(0L, budget.claimVersion)
        var current = now
        var owner = AutomaticOwner(lineage, initialId, 0)
        for (retry in 1..3) {
            budget = (AutomaticRetryModel.observeRevision(budget, generation, owner, proposal.frozenDates.single(),
                retry.toString().repeat(64), current) as RetryValidation.Valid).value
            assertEquals((retry - 1).toLong(), budget.claimVersion)
            budget = (AutomaticRetryModel.finish(budget, generation, owner, AutomaticFinishOutcome.RETRYABLE, current) as RetryValidation.Valid).value
            val due = requireNotNull(budget.retryNotBefore)
            assertEquals(listOf(30L, 60L, 120L)[retry - 1], due.epochSecond - current.epochSecond)
            val id = "00000000-0000-4000-8000-00000000000${retry + 3}"
            val staleDates = AutomaticRetryModel.reClaim(budget, generation, lineage, budget.claimVersion, key, proposal.requestedScopes,
                listOf(proposal.frozenDates.single().minusDays(1)), id, SyncTrigger.CATCH_UP, due) as RetryValidation.Valid
            assertEquals(RetryClaim.Stale, staleDates.value)
            val staleVersion = AutomaticRetryModel.reClaim(budget, generation, lineage, budget.claimVersion + 1, key, proposal.requestedScopes,
                proposal.frozenDates, id, SyncTrigger.CATCH_UP, due) as RetryValidation.Valid
            assertEquals(RetryClaim.Stale, staleVersion.value)
            val before = AutomaticRetryModel.reClaim(budget, generation, lineage, budget.claimVersion, key, proposal.requestedScopes,
                proposal.frozenDates, id, SyncTrigger.CATCH_UP, due.minusMillis(1)) as RetryValidation.Valid
            assertEquals(RetryClaim.Deferred(due), before.value)
            val claimed = AutomaticRetryModel.reClaim(budget, generation, lineage, budget.claimVersion, key, proposal.requestedScopes,
                proposal.frozenDates, id, SyncTrigger.CATCH_UP, due) as RetryValidation.Valid
            budget = (claimed.value as RetryClaim.Claimed).budget
            assertEquals(retry, budget.retriesUsed)
            assertEquals(retry.toLong(), budget.claimVersion)
            assertEquals(lineage, budget.lineageId)
            current = due
            owner = AutomaticOwner(lineage, id, budget.claimVersion)
        }
        budget = (AutomaticRetryModel.finish(budget, generation, owner, AutomaticFinishOutcome.RETRYABLE, current) as RetryValidation.Valid).value
        assertEquals(RetryBudgetStatus.EXHAUSTED, budget.status)
        assertNull(budget.retryNotBefore)
        assertEquals(3L, budget.claimVersion)
        val exhausted = AutomaticRetryModel.reClaim(budget, generation, lineage, budget.claimVersion, key, proposal.requestedScopes,
            proposal.frozenDates, initialId, SyncTrigger.SCHEDULED, current) as RetryValidation.Valid
        assertEquals(RetryClaim.Exhausted, exhausted.value)
        val completed = (AutomaticRetryModel.finish(first.budget, generation, AutomaticOwner(lineage, initialId, 0),
            AutomaticFinishOutcome.COMPLETED, now) as RetryValidation.Valid).value
        assertEquals(RetryBudgetStatus.COMPLETED, completed.status)
        val fresh = AutomaticRetryModel.initialClaim(proposal, "00000000-0000-4000-8000-000000000010", generation, config,
            key, proposal.requestedScopes, config.enrolledDay, LocalDate.parse("2026-01-03"), now) as RetryValidation.Valid
        assertEquals(0, (fresh.value as RetryClaim.Claimed).budget.retriesUsed)
        val blocked = (AutomaticRetryModel.finish(first.budget, generation, AutomaticOwner(lineage, initialId, 0),
            AutomaticFinishOutcome.BLOCKED, now) as RetryValidation.Valid).value
        assertEquals(RetryClaim.Blocked, (AutomaticRetryModel.reClaim(blocked, generation, lineage, 0, key,
            proposal.requestedScopes, proposal.frozenDates, initialId, SyncTrigger.SCHEDULED, now) as RetryValidation.Valid).value)
        val otherScopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
        val otherKey = requireNotNull(SyncPlanner.scopeKey(config, ReadAccess(true, setOf(DataType.STEPS), otherScopes)))
        assertEquals(RetryClaim.Stale, (AutomaticRetryModel.reClaim(blocked, generation, lineage, 0, otherKey, otherScopes,
            proposal.frozenDates, initialId, SyncTrigger.SCHEDULED, now) as RetryValidation.Valid).value)
        try {
            RetryBudget.validated(lineage, generation, key, proposal.requestedScopes, proposal.frozenDates,
                mapOf(proposal.frozenDates.single() to null), 0, now.plusSeconds(31), 30000, 0, null,
                RetryBudgetStatus.ACTIVE, now, now)
            fail()
        } catch (e: IllegalArgumentException) { assertEquals("유효하지 않은 자동 재시도 상태", e.message) }
        assertEquals("1c28d20a593cd831089fc8b7b157d529cf54146c376f293173f897d31b3bcd9f", AutomaticRetryModel.accessFingerprint(
            ReadAccess(true, setOf(DataType.SLEEP, DataType.STEPS), mapOf(DataType.SLEEP to ReadScope.SUMMARY, DataType.STEPS to ReadScope.SUMMARY))))
        assertEquals("fc7f546dd2128a4224bd2904de0cb8fddd24ae37a328ad3ebb604883bc05262b", AutomaticRetryModel.accessFingerprint(ReadAccess(false, emptySet(), emptyMap())))
    }
    @Test fun `Automatic mutation requires owner and unfinished pending while manual pending remains valid`() {
        val f = SyncFixture(LocalDate.of(2026, 11, 5), 3)
        val id = "00000000-0000-4000-8000-000000000003"
        val lineage = "00000000-0000-4000-8000-000000000001"
        val owner = AutomaticOwner(lineage, id, 0)
        val day = f.today.minusDays(1)
        val attempt = AttemptState(id, f.key, f.scopes, SyncTrigger.SCHEDULED, f.time, f.time, null,
            listOf(day), automaticRetryBudgetKey(lineage), 0, emptySet())
        val state = f.state().copy(attempt = attempt)
        fun mutation(pending: Set<SyncTrigger>? = null, row: DayState? = null, context: AutomaticOwner? = null) =
            SyncMutation(id, null, row, null, pending, null, context)
        assertEquals(CommitStatus.COMMITTED, state.validateMutation(state.generation, mutation(pending = setOf(SyncTrigger.CATCH_UP)), null, f.today, true))
        assertEquals(CommitStatus.INVALID, state.copy(attempt = attempt.copy(finishedAt = f.time)).validateMutation(state.generation,
            mutation(pending = setOf(SyncTrigger.CATCH_UP)), null, f.today, true))
        val manual = state.copy(attempt = attempt.copy(trigger = SyncTrigger.MANUAL, finishedAt = f.time))
        assertEquals(CommitStatus.COMMITTED, manual.validateMutation(state.generation, mutation(pending = setOf(SyncTrigger.MANUAL)), null, f.today, true))
        val row = f.day(day, false).copy(phase = DayPhase.PENDING)
        assertEquals(CommitStatus.INVALID, state.validateMutation(state.generation, mutation(row = row), null, f.today, true))
        assertEquals(CommitStatus.COMMITTED, state.validateMutation(state.generation, mutation(row = row, context = owner), null, f.today, true))
        assertEquals(CommitStatus.STALE, state.validateMutation(state.generation, mutation(row = row,
            context = owner.copy(attemptId = "00000000-0000-4000-8000-000000000004")), null, f.today, true))
        assertEquals(CommitStatus.INVALID, state.validateMutation(state.generation,
            mutation(pending = setOf(SyncTrigger.CATCH_UP), row = row, context = owner), null, f.today, true))
        assertEquals(CommitStatus.COMMITTED, state.validateMutation(state.generation, mutation(row = row), null, f.today))
        val finished = state.copy(attempt = attempt.copy(finishedAt = f.time))
        assertEquals(CommitStatus.COMMITTED, finished.validateMutation(state.generation,
            mutation(pending = setOf(SyncTrigger.CATCH_UP)), null, f.today))
        assertEquals(CommitStatus.INVALID, state.validateMutation(state.generation,
            SyncMutation(id, null, null, null, null, f.time, owner), null, f.today, true))
        val running = f.day(day, true).let { original ->
            original.copy(phase = DayPhase.RUNNING, datasets = original.datasets.mapValues { (_, file) ->
                file.copy(phase = FilePhase.RUNNING, confirmedAt = null)
            })
        }
        val confirmation = FileConfirmation(FileKey(state.generation, day, f.key, Dataset.DAILY, f.revision.digest),
            "synthetic-file", f.time)
        val confirm = SyncMutation(id, null, null, confirmation, null, null, owner)
        assertEquals(CommitStatus.COMMITTED, state.validateMutation(state.generation, confirm, running, f.today, true))
        assertEquals(CommitStatus.INVALID, state.validateMutation(state.generation, confirm.copy(automaticOwner = null), running, f.today, true))
        val manualActive = state.copy(attempt = attempt.copy(trigger = SyncTrigger.MANUAL))
        assertEquals(CommitStatus.INVALID, manualActive.validateMutation(state.generation, confirm, running, f.today, true))
        assertEquals(CommitStatus.COMMITTED, manualActive.validateMutation(state.generation,
            SyncMutation(id, null, null, null, null, f.time), null, f.today, true))
        val manualStart = attempt.copy(id = "00000000-0000-4000-8000-000000000007", trigger = SyncTrigger.MANUAL)
        assertEquals(CommitStatus.COMMITTED, manual.validateMutation(state.generation,
            SyncMutation(manualStart.id, manualStart, null, null, null, null), null, f.today, true))
        assertEquals(CommitStatus.INVALID, state.validateMutation(state.generation,
            SyncMutation(id, null, row, null, null, null, owner.copy(lineageId = "invalid")), null, f.today, true))
        assertEquals(CommitStatus.INVALID, state.validateMutation(state.generation,
            SyncMutation(id, null, row, null, null, null, owner.copy(lineageId = "00000000-0000-4000-8000-000000000005")), null, f.today))
        val nextId = "00000000-0000-4000-8000-000000000006"
        val start = SyncMutation(nextId, attempt.copy(id = nextId), null, null, null, null)
        assertEquals(CommitStatus.COMMITTED, finished.validateMutation(state.generation, start, null, f.today))
        assertEquals(CommitStatus.INVALID, finished.validateMutation(state.generation, start, null, f.today, true))
    }

}
