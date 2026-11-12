package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SyncStateTest {
    @Test
    fun `Enrollment at KST midnight excludes old history today future and pre-enrollment days`() {
        for (instant in listOf("2026-11-04T14:59:59Z", "2026-11-04T15:00:00Z")) {
            val today = Instant.parse(instant).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
            for (age in listOf(0L, 1L, 7L, 8L)) {
                val f = SyncFixture(today, age)
                val batch = SyncPlanner.plan(f.state(candidates = listOf(today.minusDays(age + 1), today, today.plusDays(1))), today, f.access)
                assertEquals(listOf(today.minusDays(2), today.minusDays(1)).filter { it >= f.config.enrolledDay }, batch.dates)
                assertEquals(age, batch.remainingPending)
            }
        }
        val f = SyncFixture(LocalDate.of(2026, 11, 5), 1)
        val state = f.state()
        assertTrue(state.isValid())
        assertTrue(state.copy(configuration = null, scopeKey = null, requestedScopes = emptyMap(),
            coverage = CoverageSummary(null, null, null, null, null, emptySet(), 0, 0, null, emptySet(), emptySet())).isValid())
        val row = f.day(f.today.minusDays(1), false)
        for (invalid in listOf(state.copy(version = 2), state.copy(generation = DestinationGeneration("")),
            state.copy(requestedScopes = mapOf(DataType.STEPS to ReadScope.BLOCKED)),
            state.copy(coverage = state.coverage.copy(scopePendingCount = -1)),
            state.copy(days = mapOf(row.date to row.copy(checkedAt = null))),
            state.copy(days = mapOf(row.date to row.copy(scopes = emptyMap()))),
            state.copy(days = mapOf(row.date to row.copy(phase = DayPhase.CONFIRMED))),
            state.copy(days = mapOf(row.date to row.copy(date = row.date.plusDays(1)))))) assertFalse(invalid.isValid())
    }

    @Test
    fun `Empty and partial grants never claim five categories upload success or continuous uploaded coverage`() {
        val vectorFixture = SyncFixture(LocalDate.of(2026, 11, 5), 3)
        val vectorAccess = ReadAccess(true, setOf(DataType.STEPS, DataType.HEART_RATE),
            linkedMapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY, DataType.HEART_RATE to ReadScope.SUMMARY))
        val vectorKey = SyncPlanner.scopeKey(vectorFixture.config, vectorAccess)
        assertEquals("b4a8d8eedf4c548fa42d9d5f0377422f4a6a5d78c65d5280353bca3fd83bcba5", vectorKey?.value)
        assertEquals(vectorKey, SyncPlanner.scopeKey(vectorFixture.config.copy(categories = DataType.entries.reversed().toSet()),
            vectorAccess.copy(granted = vectorAccess.granted.reversed().toSet(), scopeByType = vectorAccess.scopeByType.entries.reversed().associate { it.toPair() })))
        assertNotEquals(vectorKey, SyncPlanner.scopeKey(vectorFixture.config,
            vectorAccess.copy(scopeByType = vectorAccess.scopeByType + (DataType.HEART_RATE to ReadScope.WORKOUT_SCOPED))))
        val original = SyncFixture(LocalDate.of(2026, 11, 5), 3, ReadScope.EXPLICIT_DAY)
        val originalRow = original.day(original.today.minusDays(1), true)
        val originalState = original.state(listOf(originalRow))
        for (scope in listOf(ReadScope.EXPLICIT_DAY, ReadScope.SUMMARY, ReadScope.WORKOUT_SCOPED)) {
            val f = SyncFixture(original.today, 3, scope)
            val yesterday = f.today.minusDays(1)
            val empty = f.day(yesterday, false)
            val uploaded = f.day(yesterday.minusDays(1), true)
            val state = f.state(listOf(empty, uploaded), summary = f.summary.copy(scopeCheckedThrough = yesterday,
                scopePendingCount = 1, lastUploadAt = f.time))
            val coverage = SyncPlanner.coverage(state, f.today)
            assertNull(coverage.checkedThrough)
            assertNull(coverage.uploadedThrough)
            assertEquals(yesterday, coverage.scopeCheckedThrough)
            assertNull(coverage.scopeUploadedThrough)
            assertEquals(setOf(yesterday, yesterday.minusDays(1)), coverage.recentCheckedDays)
            assertEquals(setOf(yesterday.minusDays(1)), coverage.recentUploadedDays)
            assertEquals(DataType.entries.toSet() - DataType.STEPS, coverage.excludedTypes)
            assertEquals(f.time, coverage.lastUploadAt)
            assertTrue(state.isValid())
            val pending = uploaded.copy(datasets = uploaded.datasets.mapValues { (_, file) ->
                file.copy(phase = FilePhase.INDETERMINATE, confirmedAt = null)
            })
            assertFalse(state.copy(days = mapOf(pending.date to pending)).isValid())
            assertTrue(SyncPlanner.coverage(state.copy(days = mapOf(pending.date to pending)), f.today).recentUploadedDays.isEmpty())
            for ((dayPhase, filePhase) in listOf(DayPhase.RUNNING to FilePhase.RUNNING,
                DayPhase.INDETERMINATE to FilePhase.INDETERMINATE, DayPhase.REMOTE_STALE to FilePhase.REMOTE_STALE)) {
                val known = uploaded.copy(date = f.config.enrolledDay, phase = dayPhase,
                    datasets = uploaded.datasets.mapValues { (_, file) -> file.copy(phase = filePhase, confirmedAt = null) })
                assertTrue(known.isChecked())
                assertFalse(known.isUploaded())
                val knownState = f.state(listOf(known), listOf(known.date))
                assertTrue(knownState.isValid())
                val planned = SyncPlanner.plan(knownState, f.today, f.access)
                if (dayPhase == DayPhase.REMOTE_STALE) {
                    assertEquals(BlockReason.DESTINATION_BLOCKED, planned.blocked[known.date])
                } else assertTrue(known.date in planned.dates)
                for (reason in listOf(BlockReason.SOURCE_READ_FAILED, BlockReason.SOURCE_ACCESS_REQUIRED,
                    BlockReason.SOURCE_LIMIT_EXCEEDED)) assertFalse(known.copy(blocker = reason).isChecked())
                assertFalse(known.copy(revision = null).isChecked())
                assertFalse(known.copy(phase = DayPhase.EMPTY).isChecked())
            }
            if (scope != ReadScope.EXPLICIT_DAY) {
                assertTrue(SyncPlanner.plan(state, f.today, original.access).dates.isEmpty())
                assertEquals(3L, SyncPlanner.plan(state, f.today, original.access).remainingPending)
            }
        }
        // 프로필 전환 뒤 저장소가 원래 프로필을 선택해 돌려준 행은 그대로 보존된다.
        assertEquals(originalRow, originalState.days[originalRow.date])
        assertEquals(original.revision, originalState.days[originalRow.date]!!.revision)
        assertEquals(setOf(originalRow.date), SyncPlanner.coverage(originalState, original.today).recentUploadedDays)
    }

    @Test
    fun `Canonical leaves roundtrip bounded metadata and reject unknown noncanonical malformed input`() {
        val f = SyncFixture(LocalDate.of(2026, 11, 5), 20)
        val confirmation = FileConfirmation(FileKey(f.state().generation, f.today.minusDays(1), f.key,
            Dataset.DAILY, f.revision.digest), "가".repeat(1365) + "a", f.time)
        fun rejected(block: () -> Unit) {
            try { block(); fail("거부해야 한다") } catch (_: IllegalArgumentException) { }
        }
        val categories = DataType.entries.toSet()
        val scopes = categories.associateWith { ReadScope.SUMMARY }
        val dates = (1L..7).map { f.today.minusDays(it) }.sorted()
        val triggers = SyncTrigger.entries.toSet()
        assertEquals(categories, SyncStateCodec.decodeCategories(SyncStateCodec.encodeCategories(categories), 1))
        assertEquals(scopes, SyncStateCodec.decodeScopes(SyncStateCodec.encodeScopes(scopes), 1))
        assertEquals(dates, SyncStateCodec.decodeDates(SyncStateCodec.encodeDates(dates), 1))
        assertEquals(triggers, SyncStateCodec.decodeTriggers(SyncStateCodec.encodeTriggers(triggers), 1))
        val encoded = SyncStateCodec.encodeConfirmation(confirmation)
        assertEquals(confirmation, SyncStateCodec.decodeConfirmation(encoded, 1))
        rejected { SyncStateCodec.encodeConfirmation(confirmation.copy(targetHandle = confirmation.targetHandle + "a")) }
        rejected { SyncStateCodec.encodeDates(dates + f.today) }
        for (json in listOf("[ \"STEPS\"]", "[\"steps\"]", "[\"UNKNOWN\"]", "[\"STEPS\",\"STEPS\"]", "[\"\\u0053TEPS\"]", " ".repeat(16385)))
            rejected { SyncStateCodec.decodeCategories(json, 1) }
        for (json in listOf("{\"STEPS\":\"SUMMARY\",\"STEPS\":\"SUMMARY\"}", "{\"STEPS\":\"SUMMARY\",\"EXERCISE\":\"SUMMARY\"}", "{\"UNKNOWN\":\"SUMMARY\"}"))
            rejected { SyncStateCodec.decodeScopes(json, 1) }
        rejected { SyncStateCodec.decodeConfirmation(encoded.dropLast(1) + ",\"extra\":\"x\"}", 1) }
        rejected { SyncStateCodec.decodeConfirmation(encoded.replace("CONFIRMED", "confirmed").replace("DAILY", "daily"), 1) }
        try { SyncStateCodec.decodeCategories("[]", 2); fail("지원하지 않는 버전") } catch (_: UnsupportedOperationException) { }
        rejected { SyncStateCodec.decodeDates("[\"2026-11-04\",\"2026-11-03\"]", 1) }
        rejected { SyncStateCodec.decodeConfirmation("null", 1) }
    }

    @Test
    fun `Mutation validates exact journal identities preserves prior confirmation and leaves global coverage unchanged`() {
        val f = SyncFixture(LocalDate.of(2026, 11, 5), 20)
        val date = f.today.minusDays(10)
        val id = "00000000-0000-0000-0000-000000000002"
        val attempt = AttemptState(id, f.key, f.scopes, SyncTrigger.MANUAL, f.time, f.time, null,
            listOf(date), "b".repeat(64), 3, setOf(SyncTrigger.MANUAL))
        val state = f.state().copy(attempt = attempt)
        val generation = state.generation
        val journal = f.day(date, true).copy(phase = DayPhase.RUNNING, datasets = mapOf(Dataset.DAILY to
            f.day(date, true).datasets.getValue(Dataset.DAILY).copy(phase = FilePhase.RUNNING, confirmedAt = null)))
        val confirmation = FileConfirmation(FileKey(generation, date, f.key, Dataset.DAILY, f.revision.digest), "synthetic-file", f.time)
        fun mutation(day: DayState? = null, confirm: FileConfirmation? = null, finish: Instant? = null,
            pending: Set<SyncTrigger>? = null, start: AttemptState? = null) = SyncMutation(id, start, day, confirm, pending, finish)
        fun check(expected: CommitStatus, value: SyncMutation, row: DayState? = journal,
            candidate: SyncState = state, expectedGeneration: DestinationGeneration? = generation) =
            assertEquals(expected, candidate.validateMutation(expectedGeneration, value, row, f.today))
        check(CommitStatus.COMMITTED, mutation(confirm = confirmation))
        val unscoped = state.copy(scopeKey = null, requestedScopes = emptyMap(),
            coverage = state.coverage.copy(scopeKey = null, scopeCheckedThrough = null, scopeUploadedThrough = null,
                scopePendingCount = null, scopeBlockedCount = null, excludedTypes = emptySet(),
                recentCheckedDays = emptySet(), recentUploadedDays = emptySet()))
        val unscopedFinished = unscoped.copy(attempt = attempt.copy(finishedAt = f.time))
        assertTrue(unscoped.isValid())
        assertTrue(unscopedFinished.isValid())
        assertFalse(unscoped.copy(attempt = attempt.copy(id = "malformed")).isValid())
        assertFalse(unscoped.copy(attempt = attempt.copy(scopeKey = ReadScopeKey("c".repeat(64)))).isValid())
        assertFalse(unscoped.copy(configuration = f.config.copy(categories = setOf(DataType.WEIGHT))).isValid())
        val otherScopes = mapOf(DataType.HEART_RATE to ReadScope.SUMMARY)
        val otherKey = requireNotNull(SyncPlanner.scopeKey(f.config, ReadAccess(true, otherScopes.keys, otherScopes)))
        assertFalse(state.copy(scopeKey = otherKey, requestedScopes = otherScopes,
            coverage = state.coverage.copy(scopeKey = otherKey)).isValid())
        assertFalse(unscoped.copy(days = mapOf(date to journal)).isValid())
        assertFalse(unscoped.copy(oldestPendingDays = listOf(date)).isValid())
        for (invalidCoverage in listOf(unscoped.coverage.copy(scopePendingCount = 0),
            unscoped.coverage.copy(scopeBlockedCount = 0), unscoped.coverage.copy(scopeCheckedThrough = date),
            unscoped.coverage.copy(scopeUploadedThrough = date), unscoped.coverage.copy(recentCheckedDays = setOf(date)),
            unscoped.coverage.copy(recentUploadedDays = setOf(date)))) {
            assertFalse(unscoped.copy(coverage = invalidCoverage).isValid())
        }
        check(CommitStatus.COMMITTED, mutation(confirm = confirmation), candidate = unscoped)
        check(CommitStatus.INVALID, mutation(confirm = confirmation), row = null, candidate = unscoped)
        check(CommitStatus.INVALID, mutation(confirm = confirmation), candidate = unscopedFinished)
        check(CommitStatus.COMMITTED, mutation(pending = SyncTrigger.entries.toSet()), candidate = unscopedFinished)
        check(CommitStatus.INVALID, mutation(pending = emptySet()), candidate = unscopedFinished)
        check(CommitStatus.INVALID, mutation(day = journal), candidate = unscopedFinished)
        check(CommitStatus.INVALID, mutation(finish = f.time), candidate = unscopedFinished)
        check(CommitStatus.STALE, mutation(pending = SyncTrigger.entries.toSet()).copy(
            attemptId = "00000000-0000-0000-0000-000000000003"), candidate = unscopedFinished)
        check(CommitStatus.STALE, mutation(pending = SyncTrigger.entries.toSet()), candidate = unscopedFinished,
            expectedGeneration = DestinationGeneration("00000000-0000-0000-0000-000000000003"))
        val recovered = journal.afterInterruption()
        assertEquals(DayPhase.INDETERMINATE, recovered.phase)
        assertEquals(FilePhase.INDETERMINATE, recovered.datasets.getValue(Dataset.DAILY).phase)
        assertEquals(journal.checkedAt, recovered.checkedAt)
        assertNull(recovered.datasets.getValue(Dataset.DAILY).confirmedAt)
        check(CommitStatus.COMMITTED, mutation(day = recovered))

        check(CommitStatus.STALE, mutation(confirm = confirmation.copy(key = confirmation.key.copy(revision = "d".repeat(64)))), candidate = unscoped)
        check(CommitStatus.STALE, mutation(confirm = confirmation.copy(key = confirmation.key.copy(scopeKey = otherKey))), candidate = unscoped)
        check(CommitStatus.STALE, mutation(confirm = confirmation), row = journal.copy(date = date.minusDays(1)), candidate = unscoped)
        assertTrue(state.days.isEmpty())
        assertEquals(f.summary, state.coverage)
        check(CommitStatus.STALE, mutation(confirm = confirmation), expectedGeneration = DestinationGeneration("00000000-0000-0000-0000-000000000003"))
        check(CommitStatus.STALE, mutation(confirm = confirmation).copy(attemptId = "00000000-0000-0000-0000-000000000003"))
        check(CommitStatus.STALE, mutation(confirm = confirmation.copy(key = confirmation.key.copy(scopeKey = ReadScopeKey("c".repeat(64))))))
        check(CommitStatus.STALE, mutation(confirm = confirmation.copy(key = confirmation.key.copy(revision = "d".repeat(64)))))
        check(CommitStatus.INVALID, mutation(confirm = confirmation), row = null)
        check(CommitStatus.INVALID, mutation(confirm = confirmation), row = journal.copy(datasets = journal.datasets.mapValues { (_, it) -> it.copy(phase = FilePhase.PENDING) }))
        check(CommitStatus.INVALID, mutation(confirm = confirmation), candidate = state.copy(attempt = attempt.copy(finishedAt = f.time)))
        val confirmed = f.day(date, true)
        check(CommitStatus.COMMITTED, mutation(confirm = confirmation), confirmed)
        check(CommitStatus.INVALID, mutation(confirm = confirmation.copy(confirmedAt = f.time.plusSeconds(1))), confirmed)
        check(CommitStatus.INVALID, mutation(confirm = confirmation.copy(targetHandle = "different")), confirmed)
        check(CommitStatus.INVALID, mutation(confirm = confirmation.copy(targetHandle = "bad handle")))
        check(CommitStatus.INVALID, mutation(finish = f.time.minusSeconds(1)))
        check(CommitStatus.INVALID, mutation(pending = emptySet()))
        check(CommitStatus.COMMITTED, mutation(pending = SyncTrigger.entries.toSet()), row = null)
        check(CommitStatus.COMMITTED, mutation(finish = f.time), row = null)
        val newer = RevisionKey("c".repeat(64), f.time.plusSeconds(1))
        val replacement = journal.copy(revision = newer, phase = DayPhase.PENDING,
            datasets = journal.datasets.mapValues { (_, it) -> it.copy(revision = newer, phase = FilePhase.PENDING, priorConfirmation = confirmation) })
        check(CommitStatus.COMMITTED, mutation(day = replacement), confirmed)
        check(CommitStatus.INVALID, mutation(day = replacement.copy(datasets = replacement.datasets.mapValues { (_, it) -> it.copy(priorConfirmation = null) })), confirmed)
        check(CommitStatus.INVALID, mutation(day = replacement.copy(phase = DayPhase.CONFIRMED)), confirmed)
        check(CommitStatus.INVALID, mutation(day = replacement.copy(scopeKey = ReadScopeKey("c".repeat(64)))), confirmed)
        check(CommitStatus.INVALID, mutation(day = replacement, confirm = confirmation), confirmed)
        val start = attempt.copy(id = "00000000-0000-0000-0000-000000000004", retriesUsed = 0)
        val startMutation = mutation(start = start).copy(attemptId = start.id)
        check(CommitStatus.COMMITTED, startMutation, row = null, candidate = state.copy(attempt = attempt.copy(finishedAt = f.time)))
        check(CommitStatus.INVALID, startMutation, row = null)
        check(CommitStatus.INVALID, startMutation, row = null, candidate = unscopedFinished)
        val manualScopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
        val manualKey = requireNotNull(SyncPlanner.scopeKey(f.config, ReadAccess(true, manualScopes.keys, manualScopes)))
        val finishedManual = attempt.copy(scopeKey = manualKey, requestedScopes = manualScopes, finishedAt = f.time)
        for (automaticScope in listOf(ReadScope.SUMMARY, ReadScope.WORKOUT_SCOPED)) {
            val automaticScopes = mapOf(DataType.STEPS to automaticScope)
            val automaticKey = requireNotNull(SyncPlanner.scopeKey(f.config,
                ReadAccess(true, automaticScopes.keys, automaticScopes)))
            val selectedAutomatic = state.copy(scopeKey = automaticKey, requestedScopes = automaticScopes,
                attempt = finishedManual, coverage = state.coverage.copy(scopeKey = automaticKey))
            assertTrue(selectedAutomatic.isValid())
            assertFalse(selectedAutomatic.copy(attempt = finishedManual.copy(finishedAt = null)).isValid())
            val automaticStart = start.copy(scopeKey = automaticKey, requestedScopes = automaticScopes,
                trigger = SyncTrigger.SCHEDULED, pendingTriggers = setOf(SyncTrigger.SCHEDULED))
            val automaticMutation = mutation(start = automaticStart).copy(attemptId = automaticStart.id)
            check(CommitStatus.COMMITTED, automaticMutation, row = null, candidate = selectedAutomatic)
            val mismatchedStart = automaticStart.copy(scopeKey = manualKey, requestedScopes = manualScopes)
            check(CommitStatus.INVALID, mutation(start = mismatchedStart).copy(attemptId = mismatchedStart.id),
                row = null, candidate = selectedAutomatic)
            for (malformedFinished in listOf(finishedManual.copy(id = "malformed"),
                finishedManual.copy(scopeKey = ReadScopeKey("c".repeat(64))),
                finishedManual.copy(finishedAt = f.time.minusSeconds(1)))) {
                val malformedState = selectedAutomatic.copy(attempt = malformedFinished)
                assertFalse(malformedState.isValid())
                check(CommitStatus.INVALID, automaticMutation, row = null, candidate = malformedState)
            }
            assertFalse(selectedAutomatic.copy(configuration = f.config.copy(categories = setOf(DataType.WEIGHT))).isValid())
            assertFalse(selectedAutomatic.copy(configuration = f.config.copy(categories = setOf(DataType.STEPS)),
                attempt = finishedManual.copy(scopeKey = otherKey, requestedScopes = otherScopes)).isValid())
        }
        assertFalse(attempt.copy(dates = listOf(f.today)).isValid(f.config.enrolledDay, f.today))
        assertFalse(attempt.copy(retriesUsed = 4).isValid(f.config.enrolledDay, f.today))
        assertFalse(confirmed.copy(datasets = confirmed.datasets.mapValues { (_, it) -> it.copy(confirmedAt = null) }).isValid(generation, f.config.enrolledDay, f.today))
    }

}

internal class SyncFixture(val today: LocalDate, val age: Long, scope: ReadScope = ReadScope.SUMMARY) {
    val time: Instant = Instant.parse("2026-11-05T00:00:00Z")
    val config = SyncConfiguration("synthetic-destination", today.minusDays(age), true, DataType.entries.toSet())
    val scopes = mapOf(DataType.STEPS to scope)
    val access = ReadAccess(true, setOf(DataType.STEPS), scopes)
    val key = requireNotNull(SyncPlanner.scopeKey(config, access))
    val revision = RevisionKey("a".repeat(64), time)
    val summary = CoverageSummary(null, null, key, null, null, DataType.entries.toSet() - DataType.STEPS,
        age, 0, null, emptySet(), emptySet())
    fun state(rows: List<DayState> = emptyList(), candidates: List<LocalDate> = emptyList(),
        summary: CoverageSummary = this.summary): SyncState = SyncState(1, DestinationGeneration("00000000-0000-0000-0000-000000000001"),
        config, null, key, scopes, rows.associateBy { it.date }, candidates, summary)
    fun day(date: LocalDate, uploaded: Boolean): DayState = DayState(date, key, scopes, revision,
        if (uploaded) DayPhase.CONFIRMED else DayPhase.EMPTY, scopes, time,
        if (!uploaded) emptyMap() else mapOf(Dataset.DAILY to DatasetState(Dataset.DAILY, revision,
            FilePhase.CONFIRMED, true, "synthetic-file", time, time, null)), null)
}
