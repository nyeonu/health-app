package com.ny.my.health

import com.ny.my.health.core.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualDriveSessionTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-05T16:00:00Z"), ZoneOffset.UTC)
    private val account = BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")
    private val scopes = listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE)

    @Test
    fun `Account picker selection binds all authorization and account removal never falls back`() = runBlocking {
        val ports = FakePorts()
        val session = ManualDriveSession(ports, clock)
        session.refresh()
        assertEquals(0, ports.accesses)
        assertEquals(0, ports.rawReads)
        session.setForeground(true)
        assertNull(session.beginAccountSelection())
        session.setPrivateFolderConsent(true)
        ports.grants = emptySet()
        assertNull(session.beginAccountSelection())
        assertNull(session.beginAuthorization())
        assertEquals(0, ports.configurations.size)
        ports.grants = setOf(DataType.STEPS)
        val picker = checkNotNull(session.beginAccountSelection())
        assertNull(session.selectAccount(true, picker - 1, true, account.name, account.type, ports.grants))
        assertEquals(0, ports.configurations.size)
        val selected = configure(session, ports)
        assertEquals(account, selected.account)
        assertEquals(account, ports.boundAccount)
        assertTrue(session.acceptAuthorization(selected, false, "synthetic-memory-only", scopes))
        assertEquals(0, ports.rawReads)
        assertEquals(0, ports.proofs)
        assertEquals(ManualDrivePhase.READY, session.state.value.phase)
        assertEquals(BatchStatus.COMPLETED, session.continueManual()?.status)
        assertEquals(listOf("proof", "reopen", "destination", "raw"), ports.events)
        assertEquals(1, ports.rawReads)
        assertEquals(1, ports.batches)
        assertEquals(ManualDrivePhase.AUTHORIZATION_REQUIRED, session.state.value.phase)
        val retry = checkNotNull(session.beginAuthorization())
        assertEquals(selected.generation, retry.generation)
        assertEquals(1, ports.configurations.size)
        ports.boundAccount = null
        assertTrue(session.acceptAuthorization(retry, false, "synthetic-memory-only", scopes))
        assertNull(session.continueManual())
        assertEquals(1, ports.rawReads)
        assertNull(session.beginAuthorization())
        assertEquals(1, ports.proofs)

        val wrong = FakePorts()
        val wrongSession = ready(wrong)
        wrong.proofBody = """{"user":{"emailAddress":"other@example.invalid","me":true}}"""
        assertNull(wrongSession.continueManual())
        assertEquals(0, wrong.rawReads)
        assertEquals(0, wrong.reopens)
        wrong.grants = emptySet()
        assertNull(wrongSession.beginAuthorization())
        assertEquals(0, wrong.rawReads)

        val externalPorts = FakePorts()
        val external = ManualDriveSession(externalPorts, clock)
        external.setForeground(true)
        external.setPrivateFolderConsent(true)
        val externalPicker = checkNotNull(external.beginAccountSelection())
        assertTrue(external.expectExternalResult(externalPicker))
        external.setForeground(false)
        external.setForeground(true)
        val externalTicket = checkNotNull(external.selectAccount(true, externalPicker, true,
            account.name, account.type, setOf(DataType.STEPS)))
        assertFalse(external.acceptAuthorization(externalTicket, true, null, emptyList()))
        assertTrue(external.expectExternalResult(externalTicket.id))
        external.setForeground(false)
        assertFalse(external.acceptAuthorization(externalTicket, false, "synthetic-memory-only", scopes))
        external.setForeground(true)
        assertTrue(external.acceptAuthorization(externalTicket, false, "synthetic-memory-only", scopes))
        assertEquals(0, externalPorts.rawReads)
        external.setForeground(false)
        external.setForeground(true)
        assertFalse(external.acceptAuthorization(externalTicket, false, "synthetic-memory-only", scopes))
        val second = checkNotNull(external.beginAuthorization())
        assertTrue(external.expectExternalResult(second.id))
        external.setForeground(false)
        external.setForeground(true)
        external.setForeground(false)
        external.setForeground(true)
        assertFalse(external.acceptAuthorization(second, false, "synthetic-memory-only", scopes))
        val resetTicket = checkNotNull(external.beginAuthorization())
        assertTrue(external.resetConfiguration())
        assertFalse(external.expectExternalResult(resetTicket.id))
        assertFalse(external.acceptAuthorization(resetTicket, false, "synthetic-memory-only", scopes))

        val latestPorts = FakePorts()
        val latest = ready(latestPorts)
        val authorizationA = checkNotNull(latestPorts.lastTicket)
        val authorizationB = checkNotNull(latest.beginAuthorization())
        assertTrue(latest.acceptAuthorization(authorizationB, false, "synthetic-memory-only", scopes))
        val readyB = latest.state.value
        assertFalse(latest.acceptAuthorization(authorizationA, false, "late-memory-only", scopes))
        assertEquals(readyB, latest.state.value)
        assertEquals(BatchStatus.COMPLETED, latest.continueManual()?.status)
        assertEquals(1, latestPorts.rawReads)
        val pickerA = checkNotNull(latest.beginAccountSelection())
        val pickerB = checkNotNull(latest.beginAccountSelection())
        val beforeLatePicker = latest.state.value
        assertNull(latest.selectAccount(true, pickerA, true, account.name, account.type, setOf(DataType.STEPS)))
        assertEquals(beforeLatePicker, latest.state.value)
        assertNotNull(latest.selectAccount(true, pickerB, true, account.name, account.type, setOf(DataType.STEPS)))
        assertEquals(1, latestPorts.configurations.size)
    }

    @Test
    fun `Manual configuration and reset use generation CAS and never erase foundation history`() = runBlocking {
        val ports = FakePorts()
        val session = ready(ports)
        val configured = ports.configurations.single()
        assertNull(ports.configureExpected.single())
        assertEquals(LocalDate.parse("2026-11-05"), configured.enrolledDay)
        assertEquals(setOf(DataType.STEPS), configured.categories)
        assertFalse(configured.automatic)
        assertFalse(configured.toString().contains(account.name))
        val generation = checkNotNull(session.state.value.generation)
        val revision = RevisionKey("a".repeat(64), clock.instant())
        val scope = ReadScopeKey("b".repeat(64))
        val date = configured.enrolledDay
        val prior = FileConfirmation(FileKey(generation, date, scope, Dataset.DAILY, "c".repeat(64)),
            "synthetic-prior-target", clock.instant())
        val uncertain = DatasetState(Dataset.DAILY, revision, FilePhase.INDETERMINATE, true,
            "synthetic-known-target", clock.instant(), null, prior)
        val day = DayState(date, scope, mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY), revision,
            DayPhase.BLOCKED, mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY), clock.instant(),
            mapOf(Dataset.DAILY to uncertain), BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
        ports.memory.current = checkNotNull(ports.memory.current).copy(days = mapOf(date to day))
        val before = ports.memory.current
        val sameSelection = configure(session, ports)
        assertEquals(generation, sameSelection.generation)
        assertEquals(configured.enrolledDay, session.state.value.enrolledDay)
        assertEquals(before, ports.memory.current)
        assertEquals(1, ports.configurations.size)
        assertTrue(session.acceptAuthorization(sameSelection, false, "synthetic-memory-only", scopes))
        session.continueManual()
        val retry = checkNotNull(session.beginAuthorization())
        assertEquals(generation, retry.generation)
        assertTrue(session.acceptAuthorization(retry, false, "synthetic-memory-only", scopes))
        session.continueManual()
        assertEquals(before, ports.memory.current)
        assertEquals(uncertain, ports.memory.current?.days?.get(date)?.datasets?.get(Dataset.DAILY))
        assertEquals(setOf(Dataset.DAILY), ports.memory.loadUnresolvedCreates(generation, date))
        assertEquals(listOf(generation, generation), ports.reopenExpected)
        assertEquals(1, ports.configurations.size)
        ports.memory.resetStatus = CommitStatus.UNAVAILABLE
        assertFalse(session.resetConfiguration())
        assertTrue(session.state.value.configured)
        assertEquals(ManualDrivePhase.BLOCKED, session.state.value.phase)
        assertEquals(generation, ports.memory.resetExpected.last())
        ports.memory.resetStatus = CommitStatus.COMMITTED
        assertTrue(session.resetConfiguration())
        assertFalse(session.state.value.configured)
        assertFalse(session.state.value.privateFolderConsent)
        assertNull(session.state.value.enrolledDay)
        assertNull(session.beginAuthorization())
        assertEquals("foundation-preserved", ports.foundationHistory)
        assertEquals(generation, ports.memory.resetExpected.last())
        session.setPrivateFolderConsent(true)
        val next = configure(session, ports)
        assertEquals(ports.memory.resetGeneration, ports.configureExpected.last())
        assertNotEquals(generation, next.generation)
        assertEquals("foundation-preserved", ports.foundationHistory)

        val empty = FakePorts()
        val emptySession = ManualDriveSession(empty, clock)
        emptySession.setForeground(true)
        emptySession.setPrivateFolderConsent(true)
        val picker = checkNotNull(emptySession.beginAccountSelection())
        assertNull(emptySession.selectAccount(true, picker, true, account.name, account.type, emptySet()))
        assertTrue(empty.configurations.isEmpty())
        val deniedPicker = checkNotNull(emptySession.beginAccountSelection())
        assertNull(emptySession.selectAccount(true, deniedPicker, true, account.name, account.type, setOf(DataType.SLEEP)))
        assertTrue(empty.configurations.isEmpty())
        val partialPicker = checkNotNull(emptySession.beginAccountSelection())
        assertNotNull(emptySession.selectAccount(true, partialPicker, true, account.name, account.type, DataType.entries.toSet()))
        assertEquals(DataType.entries.toSet(), empty.configurations.single().categories)
    }

    @Test
    fun `Manual credentials never enter saved state store diagnostics or public fixtures`() = runBlocking {
        val ports = FakePorts()
        val session = ready(ports)
        assertFalse(session.state.value.toString().contains("synthetic-memory-only"))
        assertFalse(session.state.value.toString().contains(account.name))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ports.duringProof = { entered.complete(Unit); release.await() }
        val owner = async { session.continueManual() }
        entered.await()
        assertEquals(BatchStatus.MERGED, session.continueManual()?.status)
        assertEquals(1, ports.proofs)
        val obsolete = checkNotNull(ports.lastTicket)
        val admittedState = session.state.value
        assertNull(session.beginAuthorization())
        assertNull(session.beginAccountSelection())
        assertNull(session.selectAccount(true, obsolete.id, true, account.name, account.type, setOf(DataType.STEPS)))
        assertEquals(admittedState, session.state.value)
        assertFalse(session.acceptAuthorization(obsolete, false, "replacement-memory-only", scopes))
        release.complete(Unit)
        assertEquals(BatchStatus.COMPLETED, owner.await()?.status)
        assertEquals(1, ports.rawReads)
        assertEquals(1, ports.batches)
        assertEquals(1, ports.transports)
        assertFalse(session.state.value.toString().contains("replacement-memory-only"))

        for (action in listOf("authorization", "picker", "selection")) {
            val racingPorts = FakePorts()
            val racing = ready(racingPorts)
            val metadataEntered = CompletableDeferred<Unit>()
            val metadataRelease = CompletableDeferred<Unit>()
            val proofEntered = CompletableDeferred<Unit>()
            val proofRelease = CompletableDeferred<Unit>()
            val pauseMetadata: suspend () -> Unit = { metadataEntered.complete(Unit); metadataRelease.await() }
            val selection = if (action == "selection") checkNotNull(racing.beginAccountSelection()) else null
            when (action) {
                "authorization" -> racingPorts.duringBinding = pauseMetadata
                "picker" -> racingPorts.duringAccess = pauseMetadata
                "selection" -> racingPorts.memory.duringLoad = pauseMetadata
            }
            val obsoleteRequest = async {
                when (action) {
                    "authorization" -> racing.beginAuthorization()
                    "picker" -> racing.beginAccountSelection()
                    else -> racing.selectAccount(true, selection, true, account.name, account.type, setOf(DataType.STEPS))
                }
            }
            metadataEntered.await()
            if (action == "selection") {
                val newest = checkNotNull(racing.beginAuthorization())
                assertTrue(racing.acceptAuthorization(newest, false, "synthetic-memory-only", scopes))
            }
            racingPorts.duringProof = { proofEntered.complete(Unit); proofRelease.await() }
            val admitted = async { racing.continueManual() }
            proofEntered.await()
            val beforeObsoleteResume = racing.state.value
            metadataRelease.complete(Unit)
            assertNull(action, obsoleteRequest.await())
            assertEquals(action, beforeObsoleteResume, racing.state.value)
            proofRelease.complete(Unit)
            assertEquals(action, BatchStatus.COMPLETED, admitted.await()?.status)
            assertEquals(action, 1, racingPorts.rawReads)
        }

        val executingPorts = FakePorts()
        val executing = ready(executingPorts)
        val engineEntered = CompletableDeferred<Unit>()
        executingPorts.duringRun = { engineEntered.complete(Unit); CompletableDeferred<Unit>().await() }
        val activeEngine = async { executing.continueManual() }
        engineEntered.await()
        executing.setForeground(false)
        runCatching { activeEngine.await() }
        assertTrue(activeEngine.isCancelled)
        assertEquals(0, executingPorts.rawReads)

        for (change in listOf("pause", "reset", "binding", "generation", "grants", "consent")) {
            val stalePorts = FakePorts()
            val stale = ready(stalePorts)
            val proofEntered = CompletableDeferred<Unit>()
            val proofRelease = CompletableDeferred<Unit>()
            stalePorts.duringProof = { proofEntered.complete(Unit); proofRelease.await() }
            val pending = async { stale.continueManual() }
            proofEntered.await()
            when (change) {
                "pause" -> { stale.setForeground(false); stale.setForeground(true) }
                "reset" -> assertTrue(stale.resetConfiguration())
                "binding" -> stalePorts.boundAccount = account.copy(name = "changed@example.invalid")
                "generation" -> stalePorts.memory.current = checkNotNull(stalePorts.memory.current).copy(
                    generation = DestinationGeneration("changed-generation"))
                "grants" -> stalePorts.grants = emptySet()
                "consent" -> stale.setPrivateFolderConsent(false)
            }
            proofRelease.complete(Unit)
            assertNull(change, runCatching { pending.await() }.getOrNull())
            if (change in setOf("pause", "reset", "consent")) assertTrue(change, pending.isCancelled)
            assertEquals(change, 0, stalePorts.rawReads)
            assertEquals(change, 0, stalePorts.reopens)
            assertFalse(stale.state.value.toString().contains("synthetic-memory-only"))
            assertFalse(stalePorts.configurations.toString().contains("synthetic-memory-only"))
        }
        val restored = ManualDriveSession(ports, clock)
        restored.refresh()
        assertTrue(restored.state.value.configured)
        assertEquals(ManualDrivePhase.AUTHORIZATION_REQUIRED, restored.state.value.phase)
        assertEquals(1, ports.rawReads)
        assertEquals(1, ports.proofs)
        assertNull(restored.continueManual())
        assertEquals(1, ports.rawReads)
    }

    private suspend fun configure(session: ManualDriveSession, ports: FakePorts): BoundDriveAuthorization.Ticket {
        val picker = checkNotNull(session.beginAccountSelection())
        return checkNotNull(session.selectAccount(true, picker, true, account.name, account.type,
            setOf(DataType.STEPS))).also { ports.lastTicket = it }
    }

    private suspend fun ready(ports: FakePorts): ManualDriveSession = ManualDriveSession(ports, clock).also {
        it.setForeground(true)
        it.setPrivateFolderConsent(true)
        val selected = configure(it, ports)
        assertTrue(it.acceptAuthorization(selected, false, "synthetic-memory-only", scopes))
    }

    private inner class FakePorts : ManualDrivePorts {
        val memory = MemoryStore()
        override val store: SyncStateStore = memory
        var grants = setOf(DataType.STEPS)
        var accesses = 0
        var rawReads = 0
        var proofs = 0
        var reopens = 0
        var batches = 0
        var transports = 0
        var running = false
        var boundAccount: BoundDriveAuthorization.AccountSelection? = null
        var lastTicket: BoundDriveAuthorization.Ticket? = null
        var duringProof: suspend () -> Unit = {}
        var duringRun: suspend () -> Unit = {}
        var duringAccess: suspend () -> Unit = {}
        var duringBinding: suspend () -> Unit = {}
        var proofBody = """{"user":{"emailAddress":"selected@example.invalid","me":true}}"""
        val configurations = mutableListOf<SyncConfiguration>()
        val configureExpected = mutableListOf<DestinationGeneration?>()
        val reopenExpected = mutableListOf<DestinationGeneration>()
        val events = mutableListOf<String>()
        val foundationHistory = "foundation-preserved"
        override val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger): ReadAccess {
                val callback = duringAccess
                duringAccess = {}
                callback()
                accesses++
                return ReadAccess(true, grants, grants.associateWith { ReadScope.EXPLICIT_DAY })
            }
            override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                error("이 대역은 원시 건강 기록을 만들지 않는다")
            }
        }
        override suspend fun binding(generation: DestinationGeneration): BoundDriveAuthorization.AccountSelection? {
            val callback = duringBinding
            duringBinding = {}
            callback()
            return boundAccount
        }
        override suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration,
            account: BoundDriveAuthorization.AccountSelection): SyncCommit {
            configureExpected += expected
            configurations += configuration
            assertEquals(memory.current?.generation, expected)
            val generation = DestinationGeneration("configured-${configurations.size}")
            boundAccount = account
            memory.current = SyncState(1, generation, configuration, null, null, emptyMap(), emptyMap(), emptyList(), coverage())
            return SyncCommit(CommitStatus.COMMITTED, generation)
        }
        override suspend fun reopen(generation: DestinationGeneration): SyncCommit {
            events += "reopen"
            reopens++
            reopenExpected += generation
            return SyncCommit(CommitStatus.COMMITTED, generation)
        }
        override suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome {
            if (running) return BatchOutcome(BatchStatus.MERGED, 0, null)
            running = true
            try {
                prepare()
                duringRun()
                events += "raw"
                rawReads++
                batches++
                return BatchOutcome(BatchStatus.COMPLETED, 0, null)
            } finally { running = false }
        }
        override fun transport(token: String): DriveTransport {
            assertEquals("synthetic-memory-only", token)
            transports++
            return object : DriveTransport {
                override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                    assertEquals("GET", method)
                    assertEquals(BoundDriveAuthorization.ABOUT_URL, url)
                    assertNull(body)
                    assertNull(contentType)
                    events += "proof"
                    proofs++
                    duringProof()
                    return DriveResponse(200, proofBody)
                }
            }
        }
        override fun destination(transport: DriveTransport, generation: DestinationGeneration): StorageDestination {
            events += "destination"
            return object : StorageDestination {
                override suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles = error("실행 대역")
                override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) =
                    error("실행 대역")
            }
        }
    }

    private class MemoryStore : SyncStateStore {
        var current: SyncState? = null
        var resetStatus = CommitStatus.COMMITTED
        val resetExpected = mutableListOf<DestinationGeneration?>()
        val resetGeneration = DestinationGeneration("reset-generation")
        var duringLoad: suspend () -> Unit = {}
        override suspend fun load(): SyncStateRead {
            val callback = duringLoad
            duringLoad = {}
            callback()
            return SyncStateRead(if (current == null) StateStatus.ABSENT else StateStatus.READY, current)
        }
        override suspend fun reset(expected: DestinationGeneration?): SyncCommit {
            resetExpected += expected
            if (resetStatus == CommitStatus.COMMITTED) current = current?.copy(generation = resetGeneration, configuration = null)
            return SyncCommit(resetStatus, resetGeneration)
        }
        override suspend fun loadForScope(expected: DestinationGeneration, todayInSeoul: LocalDate, access: ReadAccess) = load()
        override suspend fun transact(expected: DestinationGeneration?, mutation: SyncMutation): SyncCommit = error("실행 대역")
        override suspend fun loadDay(expected: DestinationGeneration, date: LocalDate, scopeKey: ReadScopeKey): DayState? = null
        override suspend fun loadUnresolvedCreates(expected: DestinationGeneration, date: LocalDate): Set<Dataset>? = setOf(Dataset.DAILY)
    }

    private fun coverage() = CoverageSummary(null, null, null, null, null, emptySet(), null, null, null, emptySet(), emptySet())
}
