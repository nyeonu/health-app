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
        assertEquals(1, ports.accesses)
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

    @Test
    fun `Restored metadata uses current manual grants and Seoul date without raw reads or Drive`() = runBlocking {
        val ports = FakePorts()
        val original = ready(ports)
        val generation = checkNotNull(original.state.value.generation)
        val today = LocalDate.parse("2026-11-06")
        val checked = today.minusDays(1)
        val scope = ReadScopeKey("d".repeat(64))
        val expected = CoverageSummary(null, null, scope, checked, null, emptySet(), 2, 1,
            clock.instant(), setOf(checked), emptySet())
        ports.memory.coverageForAccess = { access ->
            if (access.granted.isNotEmpty()) expected else coverage().copy(excludedTypes = setOf(DataType.STEPS))
        }
        val restored = ManualDriveSession(ports, clock)
        restored.refresh()
        assertEquals(expected, restored.state.value.coverage)
        assertEquals(generation, ports.memory.scopedRequests.last().first)
        assertEquals(today, ports.memory.scopedRequests.last().second)
        assertEquals(setOf(DataType.STEPS), restored.state.value.grantedCategories)
        assertEquals(ManualDrivePhase.AUTHORIZATION_REQUIRED, restored.state.value.phase)
        ports.grants = emptySet()
        restored.refreshGrants()
        val revoked = checkNotNull(restored.state.value.coverage)
        assertNull(revoked.scopeKey)
        assertNull(revoked.scopeCheckedThrough)
        assertNull(revoked.scopePendingCount)
        assertNull(revoked.scopeBlockedCount)
        assertTrue(revoked.recentCheckedDays.isEmpty())
        assertEquals(setOf(DataType.STEPS), revoked.excludedTypes)
        assertTrue(restored.state.value.grantedCategories.isEmpty())
        ports.grants = setOf(DataType.STEPS)
        restored.setForeground(true)
        restored.setPrivateFolderConsent(true)
        val selected = configure(restored, ports)
        assertEquals(generation, selected.generation)
        assertEquals(expected, restored.state.value.coverage)
        assertEquals(0, ports.rawReads)
        assertEquals(0, ports.proofs)
        assertEquals(0, ports.transports)
    }

    @Test
    fun `Delayed metadata success or failure cannot replace newer ticket generation owner or refresh`() = runBlocking {
        for (change in listOf("ticket", "reset", "owner", "refresh", "grants")) {
            for (fail in listOf(false, true)) {
                val ports = FakePorts()
                val session = ready(ports)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                ports.memory.duringScoped = {
                    entered.complete(Unit)
                    release.await()
                    if (fail) error("합성 지연 메타데이터 실패")
                }
                val delayed = async { session.refresh() }
                entered.await()
                val proofEntered = CompletableDeferred<Unit>()
                val proofRelease = CompletableDeferred<Unit>()
                var owner: kotlinx.coroutines.Deferred<BatchOutcome?>? = null
                when (change) {
                    "ticket" -> {
                        val ticket = checkNotNull(session.beginAuthorization())
                        assertTrue(session.acceptAuthorization(ticket, false, "synthetic-memory-only", scopes))
                    }
                    "reset" -> assertTrue(session.resetConfiguration())
                    "owner" -> {
                        ports.duringProof = { proofEntered.complete(Unit); proofRelease.await() }
                        owner = async { session.continueManual() }
                        proofEntered.await()
                        session.refreshGrants()
                    }
                    "refresh" -> {
                        ports.memory.current = checkNotNull(ports.memory.current).copy(
                            coverage = coverage().copy(lastUploadAt = clock.instant()))
                        session.refresh()
                    }
                    "grants" -> { ports.grants = emptySet(); session.refreshGrants() }
                }
                val newest = session.state.value
                release.complete(Unit)
                delayed.await()
                assertEquals("$change failure=$fail", newest, session.state.value)
                proofRelease.complete(Unit)
                owner?.await()
                assertEquals(if (change == "owner") 1 else 0, ports.rawReads)
            }
        }
        for (failure in listOf("status", "generation")) {
            val ports = FakePorts()
            val session = ready(ports)
            ports.memory.scopedStatus = if (failure == "status") StateStatus.UNAVAILABLE else StateStatus.READY
            ports.memory.scopedGeneration = if (failure == "generation") DestinationGeneration("stale") else null
            session.refresh()
            assertEquals(ManualDrivePhase.BLOCKED, session.state.value.phase)
            assertEquals(0, ports.rawReads)
            assertEquals(0, ports.proofs)
        }
    }

    @Test
    fun `Valid ticket profiling keeps external authorization wait and stale busy merged requests cannot finish owner`() = runBlocking {
        val ports = FakePorts()
        val profiles = mutableListOf<ManualSyncProfiling>()
        val finished = mutableListOf<ProfileOutcome>()
        val session = ManualDriveSession(ports, clock, { ManualSyncProfiling() }, { profiles += it },
            { _, outcome -> assertFalse(ports.running); finished += outcome })
        session.setForeground(true)
        session.setPrivateFolderConsent(true)
        val ticketA = configure(session, ports)
        assertEquals(1L, profiles.single().collector.snapshot().stages[ProfileStage.AUTHORIZATION_WAIT]?.started)
        assertTrue(session.expectExternalResult(ticketA.id))
        session.setForeground(false)
        assertTrue(finished.isEmpty())
        assertTrue(profiles.single().collector.snapshot().stages[ProfileStage.AUTHORIZATION_WAIT]!!.active)
        session.setForeground(true)
        assertTrue(profiles.single().collector.snapshot().stages[ProfileStage.AUTHORIZATION_WAIT]!!.active)
        assertFalse(session.acceptAuthorization(ticketA, true, null, emptyList()))
        assertFalse(profiles.single().collector.snapshot().stages[ProfileStage.AUTHORIZATION_WAIT]!!.active)
        assertEquals(1L, profiles.single().collector.snapshot().stages[ProfileStage.AUTHORIZATION_WAIT]!!.failed)
        val ticketB = checkNotNull(session.beginAuthorization())
        assertEquals(listOf(ProfileOutcome.FAILED), finished)
        assertFalse(session.acceptAuthorization(ticketA, false, "synthetic-memory-only", scopes))
        assertTrue(session.acceptAuthorization(ticketB, false, "synthetic-memory-only", scopes))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ports.duringProof = { entered.complete(Unit); release.await() }
        val owner = async { session.continueManual() }
        entered.await()
        assertNull(session.beginAuthorization())
        assertNull(session.beginAccountSelection())
        assertEquals(BatchStatus.MERGED, session.continueManual()!!.status)
        assertEquals(2, profiles.size)
        assertEquals(1, finished.size)
        assertTrue(profiles.last().collector.snapshot().stages[ProfileStage.EXECUTION]!!.active)
        release.complete(Unit)
        assertEquals(BatchStatus.COMPLETED, owner.await()!!.status)
        assertSame(profiles.last().collector, ports.destinationProfiling)
        assertEquals(listOf(ProfileOutcome.FAILED, ProfileOutcome.COMPLETED), finished)
        val summary = profiles.last().collector.snapshot()
        assertEquals(1L, summary.stages[ProfileStage.CONTINUE_WAIT]!!.completed)
        assertEquals(1L, summary.stages[ProfileStage.EXECUTION]!!.completed)
        assertTrue(summary.requests.isEmpty())
        assertNull(summary.stages[ProfileStage.AUTHORIZATION_HISTORY])
        assertNull(summary.stages[ProfileStage.START_HISTORY])
        assertNull(summary.stages[ProfileStage.TERMINAL_HISTORY])
    }

    @Test
    fun `Bound outcomes and proof permission failures finish failed profiles without changing business results`() = runBlocking {
        for (status in listOf(BatchStatus.COMPLETED, BatchStatus.BLOCKED, BatchStatus.STALE,
            BatchStatus.RETRYABLE, BatchStatus.PENDING)) {
            val ports = FakePorts().also { it.batchStatus = status }
            var finished: ProfileOutcome? = null
            val session = ManualDriveSession(ports, clock, { ManualSyncProfiling() }, {}, { _, result -> finished = result })
            session.setForeground(true)
            session.setPrivateFolderConsent(true)
            val ticket = configure(session, ports)
            assertTrue(session.acceptAuthorization(ticket, false, "synthetic-memory-only", scopes))
            assertEquals(status, session.continueManual()!!.status)
            assertEquals(if (status == BatchStatus.COMPLETED) ProfileOutcome.COMPLETED else ProfileOutcome.FAILED, finished)
        }
        for (failure in listOf("proof", "permission", "drive")) {
            val ports = FakePorts()
            var finished: ProfileOutcome? = null
            val session = ManualDriveSession(ports, clock, { ManualSyncProfiling() }, {}, { _, result -> finished = result })
            session.setForeground(true)
            session.setPrivateFolderConsent(true)
            val ticket = configure(session, ports)
            assertTrue(session.acceptAuthorization(ticket, false, "synthetic-memory-only", scopes))
            when (failure) {
                "proof" -> ports.proofBody = "{}"
                "permission" -> ports.grants = emptySet()
                "drive" -> ports.duringRun = { error("합성 목적지 실패") }
            }
            assertNull(session.continueManual())
            assertEquals(ProfileOutcome.FAILED, finished)
            assertEquals(0, ports.rawReads)
        }
    }

    @Test
    fun `Pause reset and withdrawal cancel waiting and running profiles after structured owner cleanup`() = runBlocking {
        for (running in listOf(false, true)) for (action in listOf("pause", "reset", "withdraw")) {
            val ports = FakePorts()
            var finished: ProfileOutcome? = null
            val session = ManualDriveSession(ports, clock, { ManualSyncProfiling() }, {},
                { _, result -> assertFalse(ports.running); finished = result })
            session.setForeground(true)
            session.setPrivateFolderConsent(true)
            val ticket = configure(session, ports)
            kotlinx.coroutines.supervisorScope {
                val entered = CompletableDeferred<Unit>()
                ports.duringProof = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
                val owner = if (running) {
                    assertTrue(session.acceptAuthorization(ticket, false, "synthetic-memory-only", scopes))
                    async { session.continueManual() }.also { entered.await() }
                } else null
                when (action) {
                    "pause" -> session.setForeground(false)
                    "reset" -> assertTrue(session.resetConfiguration())
                    "withdraw" -> session.setPrivateFolderConsent(false)
                }
                if (running) assertNull(finished)
                if (owner != null) {
                    runCatching { owner.await() }
                    assertTrue(owner.isCancelled)
                }
            }
            assertEquals(ProfileOutcome.CANCELLED, finished)
            assertEquals(0, ports.rawReads)
        }
    }

    @Test
    fun `Null throwing factory and throwing observers preserve successful execution and actual cancellation`() = runBlocking {
        for (factory in listOf<() -> ManualSyncProfiling?>({ null }, { error("합성 factory 실패") }, { ManualSyncProfiling() })) {
            val ports = FakePorts()
            val session = ManualDriveSession(ports, clock, factory,
                { throw kotlinx.coroutines.CancellationException("합성 관찰 실패") }, { _, _ -> error("합성 관찰 실패") },
                { throw kotlinx.coroutines.CancellationException("합성 폐기 관찰 실패") })
            session.setForeground(true)
            session.setPrivateFolderConsent(true)
            val ticket = configure(session, ports)
            assertTrue(session.acceptAuthorization(ticket, false, "synthetic-memory-only", scopes))
            assertEquals(BatchStatus.COMPLETED, session.continueManual()!!.status)
            assertEquals(1, ports.rawReads)
            val next = checkNotNull(session.beginAuthorization())
            assertTrue(session.acceptAuthorization(next, false, "synthetic-memory-only", scopes))
            ports.duringRun = { throw kotlinx.coroutines.CancellationException("합성 실제 취소") }
            try {
                session.continueManual()
                fail("실제 취소가 전파되어야 한다")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(ports.running)
            session.setPrivateFolderConsent(false)
            assertFalse(session.state.value.privateFolderConsent)
        }
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
        var batchStatus = BatchStatus.COMPLETED
        var destinationProfiling: SyncProfiling? = null
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
                assertEquals(SyncTrigger.MANUAL, trigger)
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
                return BatchOutcome(batchStatus, 0, null)
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
        override fun profiledDestination(transport: DriveTransport, generation: DestinationGeneration,
            profiling: SyncProfiling?): StorageDestination {
            destinationProfiling = profiling
            return destination(transport, generation)
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

    private inner class MemoryStore : SyncStateStore {
        var current: SyncState? = null
        var resetStatus = CommitStatus.COMMITTED
        val resetExpected = mutableListOf<DestinationGeneration?>()
        val resetGeneration = DestinationGeneration("reset-generation")
        var duringLoad: suspend () -> Unit = {}
        var duringScoped: suspend () -> Unit = {}
        var scopedStatus = StateStatus.READY
        var scopedGeneration: DestinationGeneration? = null
        var coverageForAccess: ((ReadAccess) -> CoverageSummary)? = null
        val scopedRequests = mutableListOf<Triple<DestinationGeneration, LocalDate, ReadAccess>>()
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
        override suspend fun loadForScope(expected: DestinationGeneration, todayInSeoul: LocalDate, access: ReadAccess): SyncStateRead {
            scopedRequests += Triple(expected, todayInSeoul, access)
            val snapshot = current
            val callback = duringScoped
            duringScoped = {}
            callback()
            if (scopedStatus != StateStatus.READY) return SyncStateRead(scopedStatus, null)
            return SyncStateRead(StateStatus.READY, snapshot?.copy(
                generation = scopedGeneration ?: snapshot.generation,
                coverage = coverageForAccess?.invoke(access) ?: snapshot.coverage))
        }
        override suspend fun transact(expected: DestinationGeneration?, mutation: SyncMutation): SyncCommit = error("실행 대역")
        override suspend fun loadDay(expected: DestinationGeneration, date: LocalDate, scopeKey: ReadScopeKey): DayState? = null
        override suspend fun loadUnresolvedCreates(expected: DestinationGeneration, date: LocalDate): Set<Dataset>? = setOf(Dataset.DAILY)
    }

    private fun coverage() = CoverageSummary(null, null, null, null, null, emptySet(), null, null, null, emptySet(), emptySet())
}
