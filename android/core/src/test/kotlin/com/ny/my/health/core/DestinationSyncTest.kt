package com.ny.my.health.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DestinationSyncTest {
    private val now = Instant.parse("2026-11-05T00:00:00Z")
    private val date = LocalDate.of(2026, 11, 4)
    private val all = DataType.entries.associateWith { ReadScope.EXPLICIT_DAY }

    private class MovingClock(private val time: () -> Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MovingClock(time, zone)
        override fun instant(): Instant = time()
    }

    /** 실제 DB 대신 상태 검증 함수를 사용하는 합성 영속 경계다. */
    private inner class Store(scopes: Map<DataType, ReadScope>) : SyncStateStore {
        val generation = DestinationGeneration("00000000-0000-0000-0000-000000000001")
        val rows = mutableMapOf<Pair<LocalDate, ReadScopeKey>, DayState>()
        val currentObservedAfterFullRead = mutableListOf<Boolean>()
        var unresolvedError: Exception? = null
        var unresolvedStale = false
        val unresolvedDates = mutableListOf<LocalDate>()
        var fullReads = 0
        var currentReads = 0
        var beforeCurrent: () -> Unit = {}
        var sequence = 1
        var failConfirmation = false
        var failDay = false
        var failFailureJournal = false
        var callbackError: Exception? = null
        val events = mutableListOf<String>()
        val scopeQueryDays = mutableListOf<LocalDate>()
        var state: SyncState
        init {
            val key = requireNotNull(scopeFingerprint(scopes))
            state = SyncState(1, generation, SyncConfiguration("synthetic-destination", date, false, all.keys),
                null, key, scopes, emptyMap(), listOf(date), CoverageSummary(null, null, key, null,
                    null, all.keys - scopes.keys, 1, 0, null, emptySet(), emptySet()))
            admit()
        }
        fun admit(scopes: Map<DataType, ReadScope> = state.requestedScopes, trigger: SyncTrigger = SyncTrigger.MANUAL) {
            sequence++
            val key = requireNotNull(scopeFingerprint(scopes))
            state = state.copy(scopeKey = key, requestedScopes = scopes, attempt = AttemptState(
                "00000000-0000-0000-0000-${sequence.toString().padStart(12, '0')}", key, scopes,
                trigger, now, now, null, listOf(date), "b".repeat(64), 0, emptySet()),
                coverage = state.coverage.copy(scopeKey = key))
        }
        private fun selected() = state.copy(days = rows.filterKeys { it.second == state.scopeKey }.mapKeys { it.key.first })
        override suspend fun load(): SyncStateRead {
            fullReads++
            return SyncStateRead(StateStatus.READY, selected())
        }
        override suspend fun loadCurrentAttempt(expected: DestinationGeneration): AttemptState? {
            currentReads++
            currentObservedAfterFullRead += fullReads > 0
            beforeCurrent()
            return state.attempt.takeIf { state.generation == expected }
        }
        override suspend fun loadForScope(expected: DestinationGeneration, todayInSeoul: LocalDate, access: ReadAccess): SyncStateRead {
            scopeQueryDays += todayInSeoul
            return load()
        }
        override suspend fun loadDay(expected: DestinationGeneration, date: LocalDate, scopeKey: ReadScopeKey): DayState? {
            check(expected == state.generation)
            return rows[date to scopeKey]
        }
        override suspend fun loadUnresolvedCreates(expected: DestinationGeneration, date: LocalDate): Set<Dataset>? {
            unresolvedDates += date
            unresolvedError?.let { throw it }
            if (unresolvedStale || expected != state.generation) return null
            return rows.filterKeys { it.first == date }.values.flatMap { day ->
                check(day.isValid(state.generation, state.configuration!!.enrolledDay, this@DestinationSyncTest.date.plusDays(2)))
                day.datasets.values.filter { it.targetHandle == null && it.phase in setOf(FilePhase.RUNNING,
                    FilePhase.INDETERMINATE, FilePhase.REMOTE_STALE, FilePhase.BLOCKED) }.map { it.dataset }
            }.toSet()
        }
        override suspend fun reset(expected: DestinationGeneration?) = SyncCommit(CommitStatus.INVALID, generation)
        override suspend fun transact(expected: DestinationGeneration?, mutation: SyncMutation): SyncCommit {
            val key = mutation.day?.let { it.date to it.scopeKey } ?: mutation.confirmation?.key?.let { it.date to it.scopeKey }
            val status = selected().validateMutation(expected, mutation, rows[key], date.plusDays(2))
            if (status != CommitStatus.COMMITTED) return SyncCommit(status, state.generation)
            if (mutation.confirmation != null) callbackError?.let { throw it }
            if ((failConfirmation && mutation.confirmation != null) || (failDay && mutation.day != null) ||
                (failFailureJournal && mutation.day?.blocker != null))
                return SyncCommit(CommitStatus.UNAVAILABLE, state.generation)
            mutation.day?.let { next ->
                events += "journal"
                rows[requireNotNull(key)] = next
                val writing = next.datasets.filterValues { it.phase == FilePhase.RUNNING }.keys
                for ((otherKey, old) in rows.toMap()) {
                    if (old.date != next.date || old.scopeKey == next.scopeKey) continue
                    rows[otherKey] = old.copy(phase = DayPhase.INDETERMINATE, datasets = old.datasets.mapValues { (dataset, file) ->
                        if (dataset !in writing) file else file.copy(phase = FilePhase.INDETERMINATE, confirmedAt = null,
                            priorConfirmation = if (file.phase == FilePhase.CONFIRMED) FileConfirmation(
                                FileKey(generation, old.date, old.scopeKey, dataset, file.revision.digest),
                                requireNotNull(file.targetHandle), requireNotNull(file.confirmedAt)) else file.priorConfirmation)
                    })
                }
            }
            mutation.confirmation?.let { confirmation ->
                events += "confirmation"
                val row = rows.getValue(requireNotNull(key))
                rows[key] = row.copy(datasets = row.datasets + (confirmation.key.dataset to
                    row.datasets.getValue(confirmation.key.dataset).copy(phase = FilePhase.CONFIRMED,
                        targetHandle = confirmation.targetHandle, confirmedAt = confirmation.confirmedAt)))
            }
            mutation.finishAt?.let { state = state.copy(attempt = state.attempt!!.copy(finishedAt = it)) }
            return SyncCommit(CommitStatus.COMMITTED, state.generation)
        }
    }
    private inner class Fixture(scopes: Map<DataType, ReadScope> = all) {
        val store = Store(scopes)
        var observedAt = now
        var access = ReadAccess(true, scopes.keys, scopes)
        var accessByTrigger = emptyMap<SyncTrigger, ReadAccess>()
        val accessTriggers = mutableListOf<SyncTrigger>()
        val requestedReads = mutableListOf<Map<DataType, ReadScope>>()
        var reads = 0
        var readFailure: Exception? = null
        var blocked = false
        var steps: Long? = 12L
        var beforeRead: () -> Unit = {}
        val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger): ReadAccess {
                accessTriggers += trigger
                return accessByTrigger[trigger] ?: access
            }
            override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                reads++; requestedReads += requestedScopes.toMap()
                beforeRead(); readFailure?.let { throw it }
                assertEquals(store.state.attempt!!.requestedScopes, requestedScopes)
                val effective = requestedScopes.mapValues { if (blocked) ReadScope.BLOCKED else it.value }
                val granted = effective.filterValues { it != ReadScope.BLOCKED }.keys
                val start = DayWindow.start(date).plusSeconds(3600)
                return ScopedSnapshot(HealthSnapshot(date, steps.takeIf { DataType.STEPS in granted }, emptyList(),
                    if (DataType.HEART_RATE in granted) listOf(HeartRateSample("hr", "synthetic", start, 70)) else emptyList(),
                    if (DataType.SLEEP in granted) listOf(SleepSession("sleep", "synthetic", start, start.plusSeconds(3600))) else emptyList(),
                    if (DataType.EXERCISE in granted) listOf(ExerciseSession("workout", "synthetic", start, start.plusSeconds(3600), 1)) else emptyList(),
                    granted), effective, effective.filterValues { it == ReadScope.BLOCKED }.keys)
            }
        }
        val remote = mutableMapOf<String, Pair<PlannedFile, FileConfirmation>>()
        val written = mutableListOf<Dataset>()
        var failAfter = Int.MAX_VALUE
        var destinationReason: BlockReason? = null
        var reconcileError: Exception? = null
        var beforeReconcile: (List<PlannedFile>) -> Unit = {}
        var unknownCreateAt: Int? = null
        var cancel = false
        var omitAck = false
        var forged: ((FileConfirmation) -> FileConfirmation)? = null
        var beforeWrite: () -> Unit = {}
        var reconcileMode = "healthy"
        val reconciledDatasets = mutableListOf<Set<Dataset>>()
        val destination = object : StorageDestination {
            override suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles {
                assertTrue(store.events.contains("journal"))
                reconciledDatasets += files.map { it.key.dataset }.toSet()
                beforeReconcile(files)
                reconcileError?.let { throw it }
                for (file in files) {
                    val journal = store.rows.getValue(file.key.date to file.key.scopeKey).datasets.getValue(file.key.dataset)
                    assertNotEquals(FilePhase.CONFIRMED, journal.phase)
                    assertNull(journal.confirmedAt)
                }
                when (reconcileMode) {
                    "failure" -> error("합성 재확인 실패")
                    "unsafe", "moved", "shared" -> return ReconciledFiles(emptyList(), emptyList(), files.map { it.key }.toSet())
                    "absent" -> return ReconciledFiles(emptyList(), files, emptySet())
                }
                val confirmed = files.mapNotNull { file -> remote[file.file.relativePath]?.takeIf {
                    it.first.byteSha256 == file.byteSha256
                }?.second?.copy(key = file.key, confirmedAt = observedAt) }
                val pending = files.filter { file -> confirmed.none { it.key == file.key } }
                if (reconcileMode == "unresolved-absent" &&
                    pending.any { it.createMayHaveSucceeded && it.previousTargetHandle == null })
                    throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
                return ReconciledFiles(confirmed, pending, emptySet())
            }
            override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) {
                beforeWrite()
                for ((index, file) in files.withIndex()) {
                    val row = store.rows.getValue(file.key.date to file.key.scopeKey)
                    assertEquals(FilePhase.RUNNING, row.datasets.getValue(file.key.dataset).phase)
                    if (index == failAfter) {
                        if (cancel) throw CancellationException("합성 취소")
                        destinationReason?.let { throw DestinationException(it) }
                        error("합성 실패")
                    }
                    val confirmation = FileConfirmation(file.key, "synthetic-${file.key.dataset.name}", observedAt)
                    written += file.key.dataset
                    remote[file.file.relativePath] = file to confirmation
                    if (index == unknownCreateAt) throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
                    if (!omitAck) onConfirmed(forged?.invoke(confirmation) ?: confirmation)
                }
                if (cancel) throw CancellationException("합성 취소")
            }
        }
        val engine by lazy { DestinationSync(store, reader, destination, MovingClock({ observedAt })) }
        suspend fun run(trigger: SyncTrigger = SyncTrigger.MANUAL) = engine
            .run(trigger, store.state.generation, store.state.attempt!!.id)
        fun day() = store.rows.getValue(date to store.state.scopeKey!!)
    }

    @Test
    fun `Source limit and access failures block without confirming empty or losing prior uncertainty`() = runBlocking {
        for ((failure, reason) in listOf(
            SourceReadException(BlockReason.SOURCE_LIMIT_EXCEEDED) to BlockReason.SOURCE_LIMIT_EXCEEDED,
            SourceReadException(BlockReason.SOURCE_ACCESS_REQUIRED) to BlockReason.SOURCE_ACCESS_REQUIRED,
            IllegalStateException("합성 실패") to BlockReason.SOURCE_READ_FAILED)) {
            val f = Fixture()
            f.omitAck = true
            assertEquals(BatchStatus.PENDING, f.run().status)
            val previous = f.day()
            assertTrue(previous.datasets.values.all { it.phase == FilePhase.INDETERMINATE })
            f.store.admit()
            f.readFailure = failure
            val writes = f.written.size
            val reconciles = f.reconciledDatasets.size
            assertEquals(BatchStatus.BLOCKED, f.run().status)
            assertEquals(reason, f.day().blocker)
            assertEquals(DayPhase.BLOCKED, f.day().phase)
            assertNull(f.day().checkedAt)
            assertEquals(previous.revision, f.day().revision)
            assertEquals(previous.datasets, f.day().datasets)
            assertEquals(writes, f.written.size)
            assertEquals(reconciles, f.reconciledDatasets.size)
            assertNotNull(f.store.state.attempt!!.finishedAt)
        }
    }

    @Test
    fun `New scope preserves unknown create from another scope for the same physical dataset`() = runBlocking {
        val f = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        f.omitAck = true
        assertEquals(BatchStatus.PENDING, f.run().status)
        val explicit = f.day()
        assertNull(explicit.blocker)
        assertEquals(FilePhase.INDETERMINATE, explicit.datasets.getValue(Dataset.DAILY).phase)
        assertNull(explicit.datasets.getValue(Dataset.DAILY).targetHandle)
        assertEquals(1, f.written.size)

        val summary = mapOf(DataType.STEPS to ReadScope.SUMMARY)
        f.store.admit(summary)
        f.access = ReadAccess(true, summary.keys, summary)
        assertNotEquals(explicit.scopeKey, f.store.state.scopeKey)
        assertNull(f.store.rows[date to f.store.state.scopeKey!!])
        f.remote.clear()
        f.reconcileMode = "unresolved-absent"
        f.beforeReconcile = { files ->
            assertTrue("다른 범위의 같은 physical dataset에 남은 create 불확실성을 보존해야 한다",
                files.single().createMayHaveSucceeded)
            assertEquals(FilePhase.INDETERMINATE, f.day().datasets.getValue(Dataset.DAILY).phase)
        }
        val inspect = f.beforeReconcile
        f.beforeReconcile = { files -> inspect(files); throw CancellationException("합성 새 범위 journal 뒤 재중단") }
        try { f.run(); fail("취소를 전파해야 한다") } catch (_: CancellationException) { }
        assertEquals(1, f.written.size)
        val reopened = Fixture(summary)
        reopened.store.state = f.store.state
        reopened.store.rows.putAll(f.store.rows)
        reopened.store.sequence = f.store.sequence
        reopened.store.admit()
        reopened.reconcileMode = "unresolved-absent"
        reopened.beforeReconcile = { assertTrue(it.single().createMayHaveSucceeded) }
        assertEquals(BatchStatus.BLOCKED, reopened.run().status)
        assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED, reopened.day().blocker)
        assertTrue(reopened.written.isEmpty())
    }

    @Test
    fun `Reduced grants adopt a freshly proven target despite another scope unknown create`() = runBlocking {
        val f = Fixture()
        f.omitAck = true
        assertEquals(BatchStatus.PENDING, f.run().status)
        val previousWrites = f.written.size
        val reduced = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
        f.store.admit(reduced)
        f.access = ReadAccess(true, reduced.keys, reduced)
        f.beforeReconcile = { assertTrue(it.single().createMayHaveSucceeded) }
        assertEquals(BatchStatus.COMPLETED, f.run().status)
        assertEquals(previousWrites, f.written.size)
        assertTrue(f.day().isUploaded())
    }

    @Test
    fun `Uncertainty queries exclude other dates and generations and fail closed on stale or unavailable reads`() = runBlocking {
        val f = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        f.omitAck = true
        assertEquals(BatchStatus.PENDING, f.run().status)
        assertEquals(setOf(Dataset.DAILY), f.store.loadUnresolvedCreates(f.store.generation, date))
        assertTrue(f.store.loadUnresolvedCreates(f.store.generation, date.minusDays(1))!!.isEmpty())
        assertNull(f.store.loadUnresolvedCreates(DestinationGeneration("00000000-0000-0000-0000-000000000099"), date))
        val freshGeneration = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        val nextGeneration = DestinationGeneration("00000000-0000-0000-0000-000000000099")
        freshGeneration.store.state = freshGeneration.store.state.copy(generation = nextGeneration)
        assertTrue(freshGeneration.store.loadUnresolvedCreates(nextGeneration, date)!!.isEmpty())
        for (stale in listOf(false, true)) {
            val blocked = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
            if (stale) blocked.store.unresolvedStale = true
            else blocked.store.unresolvedError = IllegalStateException("합성 손상 또는 사용 불가")
            assertEquals(if (stale) BatchStatus.STALE else BatchStatus.PENDING, blocked.run().status)
            assertEquals(listOf(date), blocked.store.unresolvedDates)
            assertEquals(0, blocked.reads)
            assertTrue(blocked.reconciledDatasets.isEmpty())
            assertTrue(blocked.written.isEmpty())
        }
    }

    @Test
    fun `Reread journal preserves unknown create uncertainty before reconciliation is interrupted again`() = runBlocking {
        val fixture = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        fixture.omitAck = true
        assertEquals(BatchStatus.PENDING, fixture.run().status)
        assertNull(fixture.day().blocker)
        val exposed = fixture.day().datasets.getValue(Dataset.DAILY)
        assertEquals(FilePhase.INDETERMINATE, exposed.phase)
        assertNull(exposed.targetHandle)
        assertEquals(1, fixture.written.size)

        fixture.store.admit()
        fixture.observedAt = now.plusSeconds(60)
        var rereadJournal: DatasetState? = null
        fixture.beforeReconcile = { files ->
            val key = files.single().key
            rereadJournal = fixture.store.rows.getValue(key.date to key.scopeKey).datasets.getValue(key.dataset)
            assertTrue(files.single().createMayHaveSucceeded)
            throw CancellationException("합성 재확인 직전 재중단")
        }
        try {
            fixture.run()
            fail("재확인 직전 취소를 전파해야 한다")
        } catch (_: CancellationException) { }

        assertNotNull(rereadJournal)
        assertNull(rereadJournal!!.targetHandle)
        assertEquals("이미 노출된 null-handle create의 불확실성은 재조회 journal에도 남아야 한다",
            FilePhase.INDETERMINATE, rereadJournal!!.phase)
        assertEquals(1, fixture.written.size)

        // Recreate the synthetic store and engine from durable rows only; no PlannedFile survives.
        val reopened = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        reopened.store.state = fixture.store.state
        reopened.store.rows.putAll(fixture.store.rows)
        reopened.store.sequence = fixture.store.sequence
        reopened.store.admit()
        reopened.reconcileMode = "unresolved-absent"
        reopened.beforeReconcile = { assertTrue(it.single().createMayHaveSucceeded) }
        assertEquals(BatchStatus.BLOCKED, reopened.run().status)
        assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED, reopened.day().blocker)
        assertTrue(reopened.written.isEmpty())
    }

    @Test
    fun `Unknown null handle remains unresolved across changed revision and conservative phases`() = runBlocking {
        for (phase in listOf(FilePhase.RUNNING, FilePhase.INDETERMINATE, FilePhase.BLOCKED)) {
            val f = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
            f.omitAck = true
            assertEquals(BatchStatus.PENDING, f.run().status)
            val old = f.day()
            f.store.rows[date to old.scopeKey] = old.copy(datasets = old.datasets.mapValues { (_, file) -> file.copy(phase = phase) })
            f.store.admit()
            f.steps = 13
            f.observedAt = now.plusSeconds(60)
            f.remote.clear()
            f.reconcileMode = "unresolved-absent"
            f.beforeReconcile = { files ->
                assertTrue(phase.name, files.single().createMayHaveSucceeded)
                assertNotEquals(old.revision, f.day().revision)
                assertEquals(FilePhase.INDETERMINATE, f.day().datasets.getValue(Dataset.DAILY).phase)
            }
            assertEquals(BatchStatus.BLOCKED, f.run().status)
            assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED, f.day().blocker)
            assertEquals(1, f.written.size)
        }
    }

    @Test
    fun `Unknown create survives empty roundtrip while fresh pending and proven targets remain usable`() = runBlocking {
        val fresh = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        fresh.beforeReconcile = { throw CancellationException("합성 첫 쓰기 이전 취소") }
        try { fresh.run(); fail("취소를 전파해야 한다") } catch (_: CancellationException) { }
        assertEquals(FilePhase.PENDING, fresh.day().datasets.getValue(Dataset.DAILY).phase)
        assertTrue(fresh.written.isEmpty())
        fresh.store.admit()
        fresh.beforeReconcile = { assertFalse(it.single().createMayHaveSucceeded) }
        assertEquals(BatchStatus.COMPLETED, fresh.run().status)
        assertEquals(1, fresh.written.size)

        val f = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        f.beforeReconcile = { assertFalse(it.single().createMayHaveSucceeded) }
        f.omitAck = true
        assertEquals(BatchStatus.PENDING, f.run().status)
        f.store.admit()
        f.steps = null
        f.beforeReconcile = { fail("빈 데이터셋은 원격 쓰기를 요청하지 않는다") }
        assertEquals(BatchStatus.BLOCKED, f.run().status)
        assertEquals(FilePhase.REMOTE_STALE, f.day().datasets.getValue(Dataset.DAILY).phase)
        assertNull(f.day().datasets.getValue(Dataset.DAILY).targetHandle)
        f.store.admit()
        f.steps = 12
        f.omitAck = false
        f.beforeReconcile = { assertTrue(it.single().createMayHaveSucceeded) }
        // The unchanged remote body is fresh evidence and can be adopted without another create.
        assertEquals(BatchStatus.COMPLETED, f.run().status)
        assertEquals(1, f.written.size)
        val confirmed = f.day().datasets.getValue(Dataset.DAILY)
        f.store.admit()
        f.steps = 13
        f.beforeReconcile = { files ->
            assertFalse(files.single().createMayHaveSucceeded)
            assertEquals(confirmed.targetHandle, files.single().previousTargetHandle)
            assertEquals(confirmed.targetHandle, f.day().datasets.getValue(Dataset.DAILY).priorConfirmation?.targetHandle)
        }
        assertEquals(BatchStatus.COMPLETED, f.run().status)
        assertEquals(2, f.written.size)
    }

    @Test
    fun currentAttemptChecksRejectChangedIdentitiesWithoutRepeatingFullCoverageReadsForEveryFile() = runBlocking {
        val one = Fixture(mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY))
        val four = Fixture()
        assertEquals(BatchStatus.COMPLETED,one.run().status)
        assertEquals(BatchStatus.COMPLETED,four.run().status)
        assertEquals(1,one.written.size)
        assertEquals(4,four.written.size)
        assertTrue(one.store.fullReads > 0)
        assertTrue(one.store.currentObservedAfterFullRead.all { it })
        assertTrue(four.store.currentObservedAfterFullRead.all { it })
        assertEquals(one.store.fullReads,four.store.fullReads)
        assertTrue(four.store.currentReads > one.store.currentReads)
        for (kind in listOf("generation","id","scope","requested","dates","finished")) {
            val f = Fixture()
            f.store.state = f.store.state.copy(configuration = f.store.state.configuration!!.copy(enrolledDay = date.minusDays(1)))
            f.store.beforeCurrent = {
                val state = f.store.state
                val attempt = state.attempt!!
                f.store.state = if (kind == "generation") state.copy(generation = DestinationGeneration("00000000-0000-0000-0000-000000000099"))
                    else state.copy(attempt = when (kind) {
                        "id" -> attempt.copy(id = "00000000-0000-0000-0000-000000000099")
                        "scope", "requested" -> {
                            val scopes = mapOf(DataType.STEPS to ReadScope.SUMMARY)
                            attempt.copy(scopeKey = requireNotNull(scopeFingerprint(scopes)),requestedScopes = scopes)
                        }
                        "dates" -> attempt.copy(dates = listOf(date.minusDays(1)))
                        else -> attempt.copy(finishedAt = now)
                    })
            }
            assertEquals(kind,BatchStatus.STALE,f.run().status)
            assertEquals(kind,0,f.reads)
            assertTrue(f.reconciledDatasets.isEmpty())
            assertTrue(f.written.isEmpty())
        }
        val remoteGuard = Fixture()
        remoteGuard.beforeRead = {
            remoteGuard.store.state = remoteGuard.store.state.copy(attempt = remoteGuard.store.state.attempt!!.copy(id = "00000000-0000-0000-0000-000000000099"))
        }
        assertEquals(BatchStatus.STALE,remoteGuard.run().status)
        assertTrue(remoteGuard.reconciledDatasets.isEmpty())
        assertTrue(remoteGuard.written.isEmpty())
    }

    @Test
    fun `After partial failure cancellation or unknown create reconciliation retries only pending files`() = runBlocking {
        val partial = Fixture()
        partial.failAfter = 2
        assertEquals(2, partial.run().confirmedFiles)
        assertEquals(2, partial.day().datasets.values.count { it.phase == FilePhase.CONFIRMED })
        partial.store.admit(); partial.failAfter = Int.MAX_VALUE
        val retry = partial.run()
        assertEquals(BatchStatus.COMPLETED, retry.status)
        assertEquals(4, retry.confirmedFiles)
        assertEquals(4, partial.written.size)
        assertEquals(2, partial.reads)
        val stableRevision = partial.day().revision
        partial.store.admit()
        val healthy = partial.run()
        assertEquals(BatchStatus.COMPLETED, healthy.status)
        assertEquals(4, healthy.confirmedFiles)
        assertEquals(3, partial.reads)
        assertEquals(List(3) { Dataset.entries.toSet() }, partial.reconciledDatasets)
        assertEquals(stableRevision, partial.day().revision)
        assertEquals(4, partial.written.size)

        // 동일 리비전의 과거 성공은 현재 대상의 안전성이나 존재를 대신하지 않는다.
        for (mode in listOf("unsafe", "moved", "shared", "absent", "failure")) {
            val recheck = Fixture()
            assertEquals(BatchStatus.COMPLETED, recheck.run().status)
            val previous = recheck.day()
            recheck.store.admit()
            recheck.observedAt = now.plusSeconds(60)
            recheck.reconcileMode = mode
            if (mode == "absent") recheck.omitAck = true
            val outcome = recheck.run()
            assertEquals(if (mode == "absent") BatchStatus.PENDING else BatchStatus.BLOCKED,
                outcome.status)
            assertEquals(0, outcome.confirmedFiles)
            assertFalse(recheck.day().isUploaded())
            assertEquals(previous.revision, recheck.day().revision)
            assertEquals(List(2) { Dataset.entries.toSet() }, recheck.reconciledDatasets)
            assertEquals(if (mode == "absent") 8 else 4, recheck.written.size)
            for ((dataset, file) in recheck.day().datasets) {
                assertNotEquals(FilePhase.CONFIRMED, file.phase)
                assertNull(file.confirmedAt)
                assertEquals(previous.datasets.getValue(dataset).targetHandle, file.priorConfirmation?.targetHandle)
                assertEquals(previous.datasets.getValue(dataset).confirmedAt, file.priorConfirmation?.confirmedAt)
            }
        }

        val unknown = Fixture()
        unknown.store.failConfirmation = true
        assertEquals(0, unknown.run().confirmedFiles)
        assertEquals(1, unknown.remote.size)
        unknown.store.failConfirmation = false; unknown.store.admit()
        assertEquals(BatchStatus.COMPLETED, unknown.run().status)
        assertEquals(4, unknown.written.size)
        assertEquals(2, unknown.reads)

        for (stage in listOf("before", "during", "after")) {
            val cancelled = Fixture()
            if (stage == "before") cancelled.readFailure = CancellationException("합성 취소")
            else { cancelled.cancel = true; cancelled.failAfter = if (stage == "during") 2 else Int.MAX_VALUE }
            try { cancelled.run(); fail("취소를 전파해야 한다") } catch (_: CancellationException) { }
            val kept = cancelled.store.rows.values.firstOrNull()?.datasets?.values?.count { it.phase == FilePhase.CONFIRMED } ?: 0
            assertEquals(if (stage == "before") 0 else if (stage == "during") 2 else 4, kept)
            cancelled.cancel = false; cancelled.readFailure = null; cancelled.failAfter = Int.MAX_VALUE; cancelled.store.admit()
            assertEquals(BatchStatus.COMPLETED, cancelled.run().status)
            assertEquals(2, cancelled.reads)
            assertEquals(4, cancelled.written.size)
        }

        val noJournal = Fixture(); noJournal.store.failDay = true
        assertEquals(BatchStatus.BLOCKED, noJournal.run().status)
        assertTrue(noJournal.remote.isEmpty())
        val noAck = Fixture(); noAck.omitAck = true
        val pending = noAck.run()
        assertEquals(BatchStatus.PENDING, pending.status)
        assertEquals(0, pending.confirmedFiles)
        assertFalse(noAck.day().isUploaded())
        noAck.omitAck = false; noAck.store.admit()
        assertEquals(4, noAck.run().confirmedFiles)
        assertEquals(4, noAck.written.size)

        val narrow = Fixture(mapOf(DataType.STEPS to ReadScope.SUMMARY))
        assertEquals(BatchStatus.COMPLETED, narrow.run().status)
        val oldScope = narrow.store.state.scopeKey!!
        val old = narrow.day()
        narrow.steps = 13
        narrow.store.admit(all); narrow.access = ReadAccess(true, all.keys, all)
        narrow.beforeWrite = {
            val invalidated = narrow.store.rows.getValue(date to oldScope)
            val oldFile = old.datasets.getValue(Dataset.DAILY)
            val invalidatedFile = invalidated.datasets.getValue(Dataset.DAILY)
            assertEquals(FilePhase.INDETERMINATE, invalidatedFile.phase)
            assertEquals(old.revision, invalidated.revision)
            assertEquals(old.requestedScopes, invalidated.requestedScopes)
            assertEquals(oldFile.targetHandle, invalidatedFile.priorConfirmation?.targetHandle)
        }
        assertEquals(BatchStatus.COMPLETED, narrow.run().status)

        for (kind in listOf("generation", "revision", "scope", "date", "dataset", "attempt")) {
            val stale = Fixture(if (kind == "dataset") mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY) else all)
            stale.forged = { confirmation ->
                if (kind == "attempt") stale.store.state = stale.store.state.copy(attempt = stale.store.state.attempt!!.copy(
                    id = "00000000-0000-0000-0000-000000000099"))
                confirmation.copy(key = when (kind) {
                    "generation" -> confirmation.key.copy(generation = DestinationGeneration("00000000-0000-0000-0000-000000000099"))
                    "revision" -> confirmation.key.copy(revision = "d".repeat(64))
                    "scope" -> confirmation.key.copy(scopeKey = ReadScopeKey("c".repeat(64)))
                    "date" -> confirmation.key.copy(date = date.minusDays(1))
                    "dataset" -> confirmation.key.copy(dataset = Dataset.SLEEP)
                    else -> confirmation.key
                })
            }
            assertEquals(BatchStatus.STALE, stale.run().status)
            assertEquals(0, stale.day().datasets.values.count { it.phase == FilePhase.CONFIRMED })
        }
        val denied = Fixture(); denied.access = denied.access.copy(granted = setOf(DataType.STEPS))
        assertEquals(BatchStatus.STALE, denied.run().status)
        assertEquals(0, denied.reads); assertTrue(denied.written.isEmpty())
        val blocked = Fixture(); blocked.blocked = true
        assertEquals(BatchStatus.BLOCKED, blocked.run().status)
        assertEquals(all, blocked.day().requestedScopes)
        assertTrue(blocked.day().scopes.values.all { it == ReadScope.BLOCKED })
        assertTrue(blocked.remote.isEmpty())
        val sourceFailure = Fixture(); sourceFailure.readFailure = IllegalStateException("합성 실패")
        assertEquals(BatchStatus.BLOCKED, sourceFailure.run().status)
        assertNull(sourceFailure.day().checkedAt)
        assertTrue(sourceFailure.remote.isEmpty())

        // 목적지 경계의 명시된 사유만 재시도를 허용하며 이전 확인과 source 체크는 보존한다.
        val lookupTransient = Fixture()
        lookupTransient.reconcileError = DestinationException(BlockReason.DESTINATION_TRANSIENT_FAILURE)
        assertEquals(BatchStatus.RETRYABLE, lookupTransient.run().status)
        assertTrue(lookupTransient.written.isEmpty())
        assertTrue(lookupTransient.day().isChecked())
        assertEquals(DayPhase.PENDING, lookupTransient.day().phase)
        assertNotNull(lookupTransient.store.state.attempt!!.finishedAt)
        val selectedTransient = lookupTransient.store.load().state!!
        assertEquals(listOf(date), SyncPlanner.plan(selectedTransient, date.plusDays(1), lookupTransient.access).dates)
        assertFalse(lookupTransient.day().copy(phase = DayPhase.BLOCKED).isValid(
            lookupTransient.store.generation, date, date.plusDays(1)))
        assertTrue(lookupTransient.day().copy(phase = DayPhase.INDETERMINATE).isValid(
            lookupTransient.store.generation, date, date.plusDays(1)))

        val partialTransient = Fixture()
        partialTransient.failAfter = 2
        partialTransient.destinationReason = BlockReason.DESTINATION_TRANSIENT_FAILURE
        val transientOutcome = partialTransient.run()
        assertEquals(BatchStatus.RETRYABLE, transientOutcome.status)
        assertEquals(2, transientOutcome.confirmedFiles)
        assertEquals(2, partialTransient.day().datasets.values.count { it.phase == FilePhase.CONFIRMED })
        assertEquals(2, partialTransient.day().datasets.values.count { it.phase == FilePhase.INDETERMINATE })
        assertTrue(partialTransient.day().isChecked())
        assertNotNull(partialTransient.store.state.attempt!!.finishedAt)

        for (reason in listOf(BlockReason.DESTINATION_RECONCILIATION_REQUIRED,
            BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED, BlockReason.DESTINATION_BLOCKED)) {
            val permanent = Fixture()
            assertEquals(BatchStatus.COMPLETED, permanent.run().status)
            val oldFiles = permanent.day().datasets
            permanent.store.admit(); permanent.observedAt = now.plusSeconds(60)
            permanent.steps = 13
            permanent.failAfter = 2; permanent.destinationReason = reason
            // 변경된 CSV를 모두 쓰도록 합성 원격을 제거하되 이전 로컬 확인은 이력으로 남긴다.
            permanent.remote.clear()
            val outcome = permanent.run()
            assertEquals(BatchStatus.BLOCKED, outcome.status)
            assertEquals(2, outcome.confirmedFiles)
            assertEquals(reason, permanent.day().blocker)
            assertTrue(permanent.day().isChecked())
            assertEquals(2, permanent.day().datasets.values.count { it.phase == FilePhase.CONFIRMED })
            for ((dataset, file) in permanent.day().datasets)
                assertEquals(oldFiles.getValue(dataset).targetHandle, file.priorConfirmation?.targetHandle)
            val selected = permanent.store.load().state!!
            val planned = SyncPlanner.plan(selected, date.plusDays(1), permanent.access)
            assertTrue(planned.dates.isEmpty())
            assertEquals(reason, planned.blocked[date])
            assertEquals(1L, planned.remainingPending)
            if (reason != BlockReason.DESTINATION_BLOCKED)
                assertFalse(permanent.day().copy(phase = DayPhase.PENDING).isValid(
                    permanent.store.generation, date, date.plusDays(1)))
        }

        val unknownCreate = Fixture()
        unknownCreate.unknownCreateAt = 2
        val unknownOutcome = unknownCreate.run()
        assertEquals(BatchStatus.BLOCKED, unknownOutcome.status)
        assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED, unknownCreate.day().blocker)
        assertEquals(2, unknownOutcome.confirmedFiles)
        assertEquals(3, unknownCreate.remote.size)
        unknownCreate.unknownCreateAt = null; unknownCreate.store.admit()
        assertEquals(BatchStatus.COMPLETED, unknownCreate.run().status)
        assertEquals(4, unknownCreate.written.size)

        val mixed = Fixture()
        mixed.observedAt = now.plusSeconds(86400)
        mixed.store.state = mixed.store.state.copy(attempt = mixed.store.state.attempt!!.copy(
            dates = listOf(date, date.plusDays(1))))
        mixed.beforeReconcile = { files ->
            throw DestinationException(if (files.first().key.date == date) BlockReason.DESTINATION_TRANSIENT_FAILURE
                else BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED)
        }
        assertEquals(BatchStatus.BLOCKED, mixed.run().status)
        assertEquals(BlockReason.DESTINATION_TRANSIENT_FAILURE, mixed.day().blocker)
        assertEquals(BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED,
            mixed.store.rows.getValue(date.plusDays(1) to mixed.store.state.scopeKey!!).blocker)
        assertNotNull(mixed.store.state.attempt!!.finishedAt)
        assertTrue(mixed.written.isEmpty())

        val unknownLookup = Fixture()
        unknownLookup.reconcileError = IllegalStateException("합성 분류되지 않은 실패")
        assertEquals(BatchStatus.BLOCKED, unknownLookup.run().status)
        assertEquals(BlockReason.DESTINATION_BLOCKED, unknownLookup.day().blocker)
        assertTrue(unknownLookup.written.isEmpty())
        val failedJournal = Fixture()
        failedJournal.reconcileError = DestinationException(BlockReason.DESTINATION_TRANSIENT_FAILURE)
        failedJournal.store.failFailureJournal = true
        assertEquals(BatchStatus.BLOCKED, failedJournal.run().status)
        assertNull(failedJournal.day().blocker)
        assertTrue(failedJournal.written.isEmpty())
        val callbackTransient = Fixture()
        callbackTransient.store.callbackError = DestinationException(BlockReason.DESTINATION_TRANSIENT_FAILURE)
        assertEquals(BatchStatus.PENDING, callbackTransient.run().status)
        assertNull(callbackTransient.day().blocker)
        assertEquals(0, callbackTransient.day().datasets.values.count { it.phase == FilePhase.CONFIRMED })
        val cancelledLookup = Fixture()
        cancelledLookup.reconcileError = CancellationException("합성 취소")
        try { cancelledLookup.run(); fail("취소를 전파해야 한다") } catch (_: CancellationException) { }
        assertNull(cancelledLookup.day().blocker)
        assertTrue(cancelledLookup.written.isEmpty())
        try { DestinationException(BlockReason.SOURCE_READ_FAILED); fail("허용 목록 밖의 사유는 거부해야 한다") }
        catch (_: IllegalArgumentException) { }

        // 같은 reader와 engine이 trigger마다 범위를 고르며 자동 실패가 수동 읽기를 막지 않는다.
        val policy = Fixture()
        val automaticScopes = all.mapValues { (type, _) ->
            if (type == DataType.HEART_RATE) ReadScope.WORKOUT_SCOPED else ReadScope.SUMMARY
        }
        policy.accessByTrigger = mapOf(
            SyncTrigger.MANUAL to ReadAccess(true, all.keys, all),
            SyncTrigger.SCHEDULED to ReadAccess(true, automaticScopes.keys, automaticScopes),
            SyncTrigger.CATCH_UP to ReadAccess(false, automaticScopes.keys, automaticScopes))
        val sameEngine = policy.engine
        assertEquals(BatchStatus.COMPLETED, policy.run(SyncTrigger.MANUAL).status)
        policy.store.admit(automaticScopes, SyncTrigger.SCHEDULED)
        assertEquals(BatchStatus.COMPLETED, policy.run(SyncTrigger.SCHEDULED).status)
        policy.store.admit(all, SyncTrigger.MANUAL)
        assertEquals(BatchStatus.COMPLETED, policy.run(SyncTrigger.MANUAL).status)
        assertEquals(listOf(all, automaticScopes, all), policy.requestedReads)
        assertEquals(ReadScope.WORKOUT_SCOPED, policy.requestedReads[1][DataType.HEART_RATE])
        assertFalse(policy.requestedReads[1].values.contains(ReadScope.EXPLICIT_DAY))
        val readsBeforeUnavailable = policy.reads
        val reconcilesBeforeUnavailable = policy.reconciledDatasets.size
        val writesBeforeUnavailable = policy.written.size
        policy.store.admit(automaticScopes, SyncTrigger.CATCH_UP)
        assertEquals(BatchStatus.STALE, policy.run(SyncTrigger.CATCH_UP).status)
        assertEquals(readsBeforeUnavailable, policy.reads)
        assertEquals(reconcilesBeforeUnavailable, policy.reconciledDatasets.size)
        assertEquals(writesBeforeUnavailable, policy.written.size)
        policy.store.admit(all, SyncTrigger.MANUAL)
        assertEquals(BatchStatus.COMPLETED, policy.run(SyncTrigger.MANUAL).status)
        assertEquals(listOf(all, automaticScopes, all, all), policy.requestedReads)
        assertEquals(listOf(SyncTrigger.MANUAL, SyncTrigger.SCHEDULED, SyncTrigger.MANUAL,
            SyncTrigger.CATCH_UP, SyncTrigger.MANUAL), policy.accessTriggers)
        assertSame(sameEngine, policy.engine)

        // 전날 승인한 30일 경계 날짜가 대기 중 범위를 벗어나면 원격 접근 없이 이력을 보존한다.
        val delayedFloor = Fixture(mapOf(DataType.STEPS to ReadScope.SUMMARY))
        val admissionDay = LocalDate.of(2026, 11, 5)
        val admittedFloor = admissionDay.minusDays(30)
        val historic = SyncFixture(admissionDay, 30).day(admittedFloor, true)
        delayedFloor.store.rows[admittedFloor to historic.scopeKey] = historic
        delayedFloor.store.state = delayedFloor.store.state.copy(
            configuration = delayedFloor.store.state.configuration!!.copy(enrolledDay = admittedFloor),
            attempt = delayedFloor.store.state.attempt!!.copy(dates = listOf(admittedFloor)))
        delayedFloor.observedAt = now.plusSeconds(86400)
        val delayedOutcome = delayedFloor.run()
        assertEquals(BatchStatus.BLOCKED, delayedOutcome.status)
        assertEquals(0, delayedOutcome.confirmedFiles)
        assertEquals(0, delayedFloor.reads)
        assertTrue(delayedFloor.reconciledDatasets.isEmpty())
        assertTrue(delayedFloor.written.isEmpty())
        val historicBlocked = delayedFloor.store.rows.getValue(admittedFloor to historic.scopeKey)
        assertEquals(DayPhase.BLOCKED, historicBlocked.phase)
        assertEquals(BlockReason.HISTORY_ACCESS_REQUIRED, historicBlocked.blocker)
        assertNull(historicBlocked.checkedAt)
        assertEquals(historic.datasets, historicBlocked.datasets)
        assertEquals(historic.revision, historicBlocked.revision)
        assertNotNull(delayedFloor.store.state.attempt!!.finishedAt)

        // 계획이 다음 KST 날짜의 최근 날짜를 포함해도 이미 승인한 날짜는 늘리지 않는다.
        val midnight = Fixture()
        midnight.beforeRead = { midnight.observedAt = now.plusSeconds(86400) }
        assertEquals(BatchStatus.COMPLETED, midnight.run().status)
        assertEquals(setOf(date), midnight.store.rows.keys.map { it.first }.toSet())
        assertEquals(1, midnight.reads)
        assertEquals(listOf(admissionDay, admissionDay.plusDays(1)), midnight.store.scopeQueryDays)
    }
}
