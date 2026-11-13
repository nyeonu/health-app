package com.ny.my.health

import com.ny.my.health.core.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test

class ManualSafSessionTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 11, 4)
    private val tree = "content://synthetic.local/tree/synthetic"
    private val all = DataType.entries.toSet()

    @Test fun `SAF selection and restore never export without explicit foreground continue`() = runBlocking {
        val ports = FakePorts()
        val session = editing(ports)
        assertTrue(session.state.value.intendedCategories.isNotEmpty())
        val ticket = checkNotNull(session.beginTreeSelection())
        assertTrue(session.acceptTreeValue(ticket, tree, 7))
        assertEquals(1, ports.validations)
        assertEquals(1, ports.configurations.size)
        assertEquals(day, ports.configurations.single().enrolledDay)
        assertFalse(ports.configurations.single().automatic)
        assertEquals("local-tree", ports.configurations.single().destinationHandle)
        assertEquals(0, ports.destinations)
        assertEquals(0, ports.reopens)
        assertEquals(0, ports.rawReads)
        val original = session.state.value.generation
        val same = checkNotNull(session.beginTreeSelection())
        assertTrue(session.acceptTreeValue(same, tree, 7))
        assertEquals(1, ports.configurations.size)
        assertEquals(original, session.state.value.generation)
        val restored = ManualSafSession(ports, clock)
        restored.setForeground(true)
        restored.refresh()
        assertEquals(ManualSafPhase.SELECTION_REQUIRED, restored.state.value.phase)
        assertEquals(setOf(DataType.STEPS), restored.state.value.intendedCategories)
        assertFalse(restored.state.value.privacyConsent)
        assertEquals(0, ports.destinations)
        restored.setPrivacyConsent(true)
        assertEquals(ManualSafPhase.READY, restored.state.value.phase)
        assertEquals(0, ports.destinations)
        assertEquals(BatchStatus.COMPLETED, restored.continueExport()!!.status)
        assertEquals(listOf("destination", "reopen", "raw"), ports.events)
        assertEquals(1, ports.destinations)
        assertEquals(1, ports.rawReads)
    }

    @Test fun `SAF intended category snapshots reject edited picker or stored scope mismatches and zero grants without shrinking partial grants`() = runBlocking {
        val empty = FakePorts()
        val emptySession = editing(empty, emptySet())
        assertNull(emptySession.beginTreeSelection())
        assertTrue(empty.configurations.isEmpty())
        for (zeroAt in listOf("begin", "selection", "continue")) {
            val ports = FakePorts()
            val session = editing(ports, all)
            if (zeroAt == "begin") ports.grants = emptySet()
            val ticket = session.beginTreeSelection()
            if (zeroAt == "begin") assertNull(ticket)
            else {
                if (zeroAt == "selection") ports.grants = emptySet()
                val accepted = session.acceptTreeValue(checkNotNull(ticket), tree, 7)
                assertEquals(zeroAt != "selection", accepted)
                if (zeroAt == "continue") {
                    ports.grants = emptySet()
                    assertNull(session.continueExport())
                }
            }
            assertEquals(if (zeroAt == "continue") 1 else 0, ports.configurations.size)
            assertEquals(0, ports.destinations)
            assertEquals(0, ports.rawReads)
        }
        val editedPorts = FakePorts()
        val edited = editing(editedPorts, all)
        val old = checkNotNull(edited.beginTreeSelection())
        edited.setCategories(setOf(DataType.WEIGHT))
        assertFalse(edited.acceptTreeValue(old, tree, 7))
        assertEquals(0, editedPorts.validations)
        assertTrue(editedPorts.configurations.isEmpty())
        val ports = FakePorts()
        val source = all.toMutableSet()
        val session = editing(ports, source)
        val ticket = checkNotNull(session.beginTreeSelection())
        source.clear()
        session.setCategories(all.toMutableSet())
        assertTrue(session.acceptTreeValue(ticket, tree, 7))
        assertEquals(all, ports.configurations.single().categories)
        assertEquals(all, session.state.value.categories)
        assertEquals(setOf(DataType.STEPS), session.state.value.grantedCategories)
        assertEquals(BatchStatus.COMPLETED, session.continueExport()!!.status)
        assertEquals(all, session.state.value.intendedCategories)
        val mismatched = FakePorts()
        val ready = ready(mismatched)
        mismatched.memory.current = mismatched.memory.current!!.let {
            it.copy(configuration = it.configuration!!.copy(categories = setOf(DataType.WEIGHT)))
        }
        assertNull(ready.continueExport())
        assertEquals(ManualSafPhase.BLOCKED, ready.state.value.phase)
        assertEquals(0, mismatched.destinations)
        assertEquals(0, mismatched.rawReads)
        val stale = FakePorts()
        val staleSession = editing(stale)
        val stalePicker = checkNotNull(staleSession.beginTreeSelection())
        stale.memory.current = state(DestinationGeneration("new-generation"), setOf(DataType.STEPS))
        assertFalse(staleSession.acceptTreeValue(stalePicker, tree, 7))
        assertEquals(0, stale.validations)
        assertTrue(stale.configurations.isEmpty())
    }

    @Test fun `SAF pause reset stale picker and double continue preserve journal and reject delayed results`() = runBlocking {
        val ports = FakePorts()
        val session = editing(ports)
        val picker = checkNotNull(session.beginTreeSelection())
        session.setForeground(false)
        session.setForeground(true)
        assertTrue(session.acceptTreeValue(picker, tree, 7))
        val twice = checkNotNull(session.beginTreeSelection())
        repeat(2) { session.setForeground(false); session.setForeground(true) }
        assertFalse(session.acceptTreeValue(twice, tree, 7))
        val old = checkNotNull(session.beginTreeSelection())
        val newer = checkNotNull(session.beginTreeSelection())
        assertFalse(session.acceptTreeValue(old, tree, 7))
        assertTrue(session.acceptTreeValue(newer, tree, 7))
        val nullResult = checkNotNull(session.beginTreeSelection())
        assertFalse(session.acceptTreeValue(nullResult, null, 0))
        assertFalse(session.acceptTreeValue(nullResult, tree, 7))
        for (failure in listOf(false, true)) {
            val delayedPorts = FakePorts()
            val delayedSession = ready(delayedPorts)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            delayedPorts.memory.duringScoped = { entered.complete(Unit); release.await(); if (failure) error("합성 메타데이터 실패") }
            val delayed = async { delayedSession.refresh() }
            entered.await()
            delayedSession.setCategories(setOf(DataType.WEIGHT))
            val newest = delayedSession.state.value
            release.complete(Unit)
            delayed.await()
            assertEquals(newest, delayedSession.state.value)
        }
        for (change in listOf("pause", "consent", "reset")) supervisorScope {
            val runningPorts = FakePorts()
            val running = ready(runningPorts)
            val entered = CompletableDeferred<Unit>()
            runningPorts.duringDestination = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            val owner = async { running.continueExport() }
            entered.await()
            assertEquals(BatchStatus.MERGED, running.continueExport()!!.status)
            assertEquals(1, runningPorts.destinations)
            val generation = checkNotNull(running.state.value.generation)
            when (change) {
                "pause" -> running.setForeground(false)
                "consent" -> running.setPrivacyConsent(false)
                "reset" -> assertTrue(running.reset(generation))
            }
            try { owner.await(); fail("취소 필요") } catch (_: CancellationException) { }
            assertTrue(owner.isCancelled)
            assertEquals(0, runningPorts.rawReads)
            assertEquals(0, runningPorts.reopens)
            assertEquals(if (change == "reset") 1 else 0, runningPorts.memory.resets.size)
            assertTrue(running.state.value.phase != ManualSafPhase.RUNNING)
            if (change == "reset") {
                val resetState = running.state.value
                assertFalse(running.reset(generation))
                assertEquals(resetState, running.state.value)
                assertFalse(resetState.configured)
                assertFalse(resetState.privacyConsent)
            }
        }
        supervisorScope {
            val delayedPorts = FakePorts()
            val delayedSession = editing(delayedPorts)
            val ticket = checkNotNull(delayedSession.beginTreeSelection())
            val entered = CompletableDeferred<Unit>()
            delayedPorts.duringValidation = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            val result = async { delayedSession.acceptTreeValue(ticket, tree, 7) }
            entered.await()
            delayedSession.setCategories(setOf(DataType.WEIGHT))
            try { result.await(); fail("지연 선택 취소 필요") } catch (_: CancellationException) { }
            assertTrue(result.isCancelled)
            assertTrue(delayedPorts.configurations.isEmpty())
        }
    }

    @Test fun `SAF recovery reopens blockers without clearing unknown creation or remote stale`() = runBlocking {
        val ports = FakePorts()
        val session = ready(ports)
        val generation = checkNotNull(session.state.value.generation)
        val revision = RevisionKey("a".repeat(64), clock.instant())
        val scope = ReadScopeKey("b".repeat(64))
        val unknown = DatasetState(Dataset.DAILY, revision, FilePhase.INDETERMINATE, true, null, clock.instant(), null, null)
        val remote = DatasetState(Dataset.SLEEP, revision, FilePhase.REMOTE_STALE, false,
            "content://synthetic.local/tree/synthetic/document/old", clock.instant(), null, null)
        val preserved = DayState(day, scope, mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY), revision,
            DayPhase.REMOTE_STALE, emptyMap(), null, mapOf(Dataset.DAILY to unknown, Dataset.SLEEP to remote), null)
        ports.memory.current = ports.memory.current!!.copy(days = mapOf(day to preserved))
        val journal = ports.memory.current!!.days
        session.refresh()
        val same = checkNotNull(session.beginTreeSelection())
        assertTrue(session.acceptTreeValue(same, tree, 7))
        assertEquals(1, ports.configurations.size)
        assertEquals(BatchStatus.COMPLETED, session.retry()!!.status)
        assertEquals(listOf(generation), ports.reopenExpected)
        assertEquals(journal, ports.memory.current!!.days)
        assertEquals(listOf("destination", "reopen", "raw"), ports.events)
        assertTrue(ports.memory.resets.isEmpty())
        val blocked = FakePorts()
        val blockedSession = ready(blocked)
        blocked.duringDestination = { throw DestinationException(BlockReason.DESTINATION_BLOCKED) }
        assertNull(blockedSession.retry())
        assertEquals(ManualSafPhase.BLOCKED, blockedSession.state.value.phase)
        assertEquals(0, blocked.reopens)
        assertEquals(0, blocked.rawReads)
        val cancelled = FakePorts()
        val cancelledSession = ready(cancelled)
        val original = CancellationException("합성 원래 취소")
        cancelled.duringDestination = { throw original }
        try { cancelledSession.continueExport(); fail("원래 취소 필요") }
        catch (failure: CancellationException) { assertSame(original, failure) }
        assertEquals(0, cancelled.reopens)
        assertEquals(0, cancelled.rawReads)
        val busy = FakePorts()
        val busySession = ready(busy)
        busy.externalBusy = true
        assertEquals(BatchStatus.MERGED, busySession.continueExport()!!.status)
        assertEquals(0, busy.destinations)
        assertEquals(0, busy.reopens)
        assertEquals(0, busy.rawReads)
    }

    @Test fun `Metadata and foreground refresh preserve current SAF result and blocker but never promote changed scope or generation as completed`() = runBlocking {
        val ports = FakePorts()
        val session = editing(ports, all)
        assertTrue(session.acceptTreeValue(checkNotNull(session.beginTreeSelection()), tree, 7))
        assertEquals(BatchStatus.COMPLETED, session.continueExport()!!.status)
        val events = ports.events.toList()
        session.refresh()
        session.setForeground(false)
        session.setForeground(true)
        session.refresh()
        assertEquals(BatchStatus.COMPLETED, session.state.value.outcome)
        assertEquals(ManualSafPhase.READY, session.state.value.phase)
        assertEquals(events, ports.events)
        ports.grants = emptySet()
        assertNull(session.continueExport())
        assertNull(session.state.value.outcome)
        assertEquals(ManualSafPhase.BLOCKED, session.state.value.phase)
        session.refresh()
        assertNull(session.state.value.outcome)
        assertEquals(ManualSafPhase.BLOCKED, session.state.value.phase)
        assertEquals(events, ports.events)
        ports.grants = setOf(DataType.WEIGHT)
        assertEquals(BatchStatus.COMPLETED, session.continueExport()!!.status)
        ports.memory.current = ports.memory.current!!.copy(generation = DestinationGeneration("other-generation"))
        session.refresh()
        assertNull(session.state.value.outcome)
        assertEquals(DestinationGeneration("other-generation"), session.state.value.generation)
        val afterRun = ports.events.toList()
        session.setForeground(false)
        session.setForeground(true)
        session.refresh()
        assertEquals(afterRun, ports.events)

        ports.duringDestination = { throw DestinationException(BlockReason.DESTINATION_BLOCKED) }
        assertNull(session.retry())
        assertEquals(ManualSafPhase.BLOCKED, session.state.value.phase)
        val blockedEvents = ports.events.toList()
        session.refresh()
        session.setForeground(false)
        session.setForeground(true)
        session.refresh()
        assertEquals(ManualSafPhase.BLOCKED, session.state.value.phase)
        assertEquals(blockedEvents, ports.events)
        session.setPrivacyConsent(false)
        assertNull(session.state.value.outcome)
        assertEquals(ManualSafPhase.SELECTION_REQUIRED, session.state.value.phase)
    }

    private suspend fun editing(ports: FakePorts, categories: Set<DataType> = setOf(DataType.STEPS)) =
        ManualSafSession(ports, clock).also {
            it.setForeground(true)
            it.setPrivacyConsent(true)
            it.setCategories(categories)
            it.refresh()
        }
    private suspend fun ready(ports: FakePorts) = editing(ports).also {
        assertTrue(it.acceptTreeValue(checkNotNull(it.beginTreeSelection()), tree, 7))
    }
    private fun coverage() = CoverageSummary(null, null, null, null, null, emptySet(), null, null, null, emptySet(), emptySet())
    private fun state(generation: DestinationGeneration, categories: Set<DataType>) = SyncState(1, generation,
        SyncConfiguration("local-tree", day, false, categories), null, null, emptyMap(), emptyMap(), emptyList(), coverage())

    private inner class FakePorts : ManualSafPorts {
        val memory = MemoryStore()
        override val store: SyncStateStore = memory
        var grants = setOf(DataType.STEPS)
        var validations = 0
        var destinations = 0
        var reopens = 0
        var rawReads = 0
        var running = false
        var externalBusy = false
        var treeBinding: String? = null
        var duringValidation: suspend () -> Unit = {}
        var duringDestination: suspend () -> Unit = {}
        val configurations = mutableListOf<SyncConfiguration>()
        val reopenExpected = mutableListOf<DestinationGeneration>()
        val events = mutableListOf<String>()
        override val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger): ReadAccess {
                assertEquals(SyncTrigger.MANUAL, trigger)
                return ReadAccess(true, grants, grants.associateWith { ReadScope.EXPLICIT_DAY })
            }
            override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot =
                error("controller 대역은 원시 건강 기록을 만들지 않는다")
        }
        override suspend fun binding(generation: DestinationGeneration) = treeBinding
        override suspend fun validateSelection(treeUri: String, returnedFlags: Int, privacyConsent: Boolean) {
            assertEquals(tree, treeUri)
            assertEquals(7, returnedFlags)
            assertTrue(privacyConsent)
            validations++
            duringValidation()
        }
        override suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration, treeUri: String): SyncCommit {
            assertEquals(memory.current?.generation, expected)
            configurations += configuration
            val generation = DestinationGeneration("configured-${configurations.size}")
            treeBinding = treeUri
            memory.current = state(generation, configuration.categories).copy(configuration = configuration)
            return SyncCommit(CommitStatus.COMMITTED, generation)
        }
        override suspend fun reopen(generation: DestinationGeneration): SyncCommit {
            events += "reopen"
            reopens++
            reopenExpected += generation
            return SyncCommit(CommitStatus.COMMITTED, generation)
        }
        override suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome {
            if (running || externalBusy) return BatchOutcome(BatchStatus.MERGED, 0, null)
            running = true
            try {
                prepare()
                events += "raw"
                rawReads++
                return BatchOutcome(BatchStatus.COMPLETED, 0, null)
            } finally { running = false }
        }
        override suspend fun destination(treeUri: String, generation: DestinationGeneration, privacyConsent: Boolean): StorageDestination {
            assertEquals(tree, treeUri)
            assertTrue(privacyConsent)
            assertTrue(running)
            events += "destination"
            destinations++
            duringDestination()
            return object : StorageDestination {
                override suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles = error("실행 대역")
                override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) = error("실행 대역")
            }
        }
    }
    private inner class MemoryStore : SyncStateStore {
        var current: SyncState? = null
        var duringScoped: suspend () -> Unit = {}
        val resets = mutableListOf<DestinationGeneration?>()
        override suspend fun load() = SyncStateRead(if (current == null) StateStatus.ABSENT else StateStatus.READY, current)
        override suspend fun reset(expected: DestinationGeneration?): SyncCommit {
            resets += expected
            assertEquals(current?.generation, expected)
            val generation = DestinationGeneration("reset-generation")
            current = current?.copy(generation = generation, configuration = null)
            return SyncCommit(CommitStatus.COMMITTED, generation)
        }
        override suspend fun loadForScope(expected: DestinationGeneration, todayInSeoul: LocalDate, access: ReadAccess): SyncStateRead {
            assertEquals(day.plusDays(1), todayInSeoul)
            val snapshot = current
            val callback = duringScoped
            duringScoped = {}
            callback()
            return SyncStateRead(StateStatus.READY, snapshot)
        }
        override suspend fun transact(expected: DestinationGeneration?, mutation: SyncMutation): SyncCommit = error("실행 대역")
        override suspend fun loadDay(expected: DestinationGeneration, date: LocalDate, scopeKey: ReadScopeKey) = current?.days?.get(date)
        override suspend fun loadUnresolvedCreates(expected: DestinationGeneration, date: LocalDate) = setOf(Dataset.DAILY)
    }
}
