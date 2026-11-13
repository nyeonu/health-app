package com.ny.my.health

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ny.my.health.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 SQLite/session과 합성 외부 bridge를 연결한다. 실계정 로그인·원격 쓰기는 하지 않는다. */
@RunWith(AndroidJUnit4::class)
class ManualDriveFlowInstrumentedTest {
    @Test fun manualFlowRejectsStaleProofPauseResetAndDoubleContinueWhileReopeningActualSqliteState() = runBlocking {
        val f = ManualDriveNativeFixture()
        try {
            val session = f.session()
            session.refresh()
            session.setForeground(true)
            session.setPrivateFolderConsent(true)
            val bridge = SyntheticBridge()
            val picker = checkNotNull(session.beginAccountSelection())
            var selected: BoundDriveAuthorization.AccountSelection? = null
            bridge.selectAccount(picker) { ok, extras, name, type ->
                selected = BoundDriveAuthorization.pickerCandidate(ok, picker, picker, extras, name, type)
            }
            assertTrue(session.expectExternalResult(picker))
            session.setForeground(false)
            bridge.pickerResult!!(true, true, f.account.name, f.account.type)
            assertEquals(f.account, selected)
            assertEquals(StateStatus.ABSENT, f.reopen().load().status)
            session.setForeground(true)
            val ticket = checkNotNull(session.selectAccount(true, picker, true,
                selected!!.name, selected!!.type, f.categories))
            assertEquals(f.categories, f.reopen().load().state!!.configuration!!.categories)
            bridge.authorize(ticket) { resolution, token, scopes ->
                session.acceptAuthorization(ticket, resolution, token, scopes)
            }
            assertEquals(listOf(f.account), bridge.accounts)
            assertTrue(session.expectExternalResult(ticket.id))
            session.setForeground(false)
            bridge.authorizationResult!!(false, "synthetic-memory-only", f.scopes)
            assertEquals(ManualDrivePhase.AUTHORIZATION_REQUIRED, session.state.value.phase)
            session.setForeground(true)
            bridge.authorizationResult!!(false, "synthetic-memory-only", f.scopes)
            assertEquals(ManualDrivePhase.READY, session.state.value.phase)
            assertEquals(0, f.proofs)
            assertEquals(0, f.rawReads)

            val proofEntered = CompletableDeferred<Unit>()
            val proofRelease = CompletableDeferred<Unit>()
            f.duringProof = { proofEntered.complete(Unit); proofRelease.await() }
            val owner = async { session.continueManual() }
            proofEntered.await()
            val duplicate = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { session.continueManual() }
            // gate에는 하나의 준비만 들어가며 두 번째 continue는 credential을 소비하지 않는다.
            proofRelease.complete(Unit)
            assertEquals(BatchStatus.COMPLETED, owner.await()!!.status)
            assertEquals(BatchStatus.MERGED, duplicate.await()!!.status)
            assertEquals(1, f.proofs)
            assertEquals(1, f.rawReads)
            val durable = f.reopen().load().state!!
            assertEquals(f.categories, durable.configuration!!.categories)
            assertNotNull(durable.attempt!!.finishedAt)
            val scoped = f.reopen().loadForScope(durable.generation, f.today, f.reader.access(SyncTrigger.MANUAL)).state!!
            assertEquals(DayPhase.EMPTY, scoped.days.getValue(f.today.minusDays(1)).phase)
            assertNull(scoped.coverage.uploadedThrough)
            assertNull(scoped.coverage.scopeUploadedThrough)
            assertEquals(f.today.minusDays(1), scoped.coverage.scopeCheckedThrough)
            assertEquals(f.categories - DataType.STEPS, scoped.coverage.excludedTypes)

            val restored = f.session()
            restored.refresh()
            assertEquals(durable.generation, restored.state.value.generation)
            assertEquals(scoped.coverage, restored.state.value.coverage)
            assertEquals(ManualDrivePhase.AUTHORIZATION_REQUIRED, restored.state.value.phase)
            assertFalse(restored.state.value.privateFolderConsent)
            assertEquals(1, f.rawReads)
            session.setForeground(false)
            session.setForeground(true)
            assertFalse(session.acceptAuthorization(ticket, false, "stale-memory-only", f.scopes))

            val fresh = checkNotNull(session.beginAuthorization())
            assertEquals(ticket.generation, fresh.generation)
            f.duringProof = {}
            f.proofBody = """{"user":{"emailAddress":"other@example.invalid","me":true}}"""
            assertTrue(session.acceptAuthorization(fresh, false, "synthetic-memory-only", f.scopes))
            assertNull(session.continueManual())
            assertEquals(1, f.rawReads)

            val delayed = checkNotNull(session.beginAuthorization())
            assertTrue(session.expectExternalResult(delayed.id))
            assertTrue(session.resetConfiguration())
            assertFalse(session.acceptAuthorization(delayed, false, "late-memory-only", f.scopes))
            assertNull(f.reopen().load().state!!.configuration)
            assertEquals(1, f.rawReads)
            assertFalse(session.state.value.toString().contains("memory-only"))
        } finally { f.close() }
    }

    @Test fun reopenedSqliteRestoresScopedEmptyConfirmedBlockedAndGrantChangesWithoutHealthOrDrive() = runBlocking {
        val f = ManualDriveNativeFixture()
        try {
            val enrolled = f.today.minusDays(4)
            val config = SyncConfiguration("private-drive", enrolled, false, f.categories)
            val registering = SqliteSyncStateStore(SyncStateDatabase(f.file), { enrolled.plusDays(1) })
            val configured = registering.configure(null, config,
                PrivateDestinationBinding("DRIVE", f.account.name, f.account.type))
            assertEquals(CommitStatus.COMMITTED, configured.status)
            val generation = checkNotNull(configured.generation)
            val scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
            val key = checkNotNull(SyncPlanner.scopeKey(config, ReadAccess(true, scopes.keys, scopes)))
            val dates = (0L..3L).map(enrolled::plusDays)
            val time = f.clock.instant()
            val attempt = AttemptState(java.util.UUID.randomUUID().toString(), key, scopes, SyncTrigger.MANUAL,
                time, time, null, dates, "a".repeat(64), 0, emptySet())
            suspend fun commit(day: DayState? = null, confirmation: FileConfirmation? = null,
                start: AttemptState? = null, finish: java.time.Instant? = null) {
                assertEquals(CommitStatus.COMMITTED, f.reopen().transact(generation,
                    SyncMutation(attempt.id, start, day, confirmation, null, finish)).status)
            }
            commit(start = attempt)
            for ((index, date) in dates.withIndex()) {
                if (index == 2) {
                    commit(day = DayState(date, key, scopes, null, DayPhase.BLOCKED, emptyMap(), null,
                        emptyMap(), BlockReason.DESTINATION_BLOCKED))
                    continue
                }
                val revision = RevisionKey("b".repeat(64), time)
                val pending = DayState(date, key, scopes, revision, DayPhase.PENDING, scopes, time, emptyMap(), null)
                commit(day = pending)
                if (index == 1) commit(day = pending.copy(phase = DayPhase.EMPTY)) else {
                    val file = DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING, true, null, time, null, null)
                    commit(day = pending.copy(phase = DayPhase.RUNNING, datasets = mapOf(Dataset.DAILY to file)))
                    commit(confirmation = FileConfirmation(FileKey(generation, date, key, Dataset.DAILY, revision.digest),
                        "synthetic-target-$index", time))
                }
            }
            commit(finish = time)
            // 새 controller는 원시 기록 없이 실제 재개방한 SQLite의 현재 profile을 복원한다.
            val restored = f.session()
            restored.refresh()
            val coverage = checkNotNull(restored.state.value.coverage)
            assertEquals(key, coverage.scopeKey)
            assertEquals(enrolled.plusDays(1), coverage.scopeCheckedThrough)
            assertEquals(enrolled, coverage.scopeUploadedThrough)
            assertNull(coverage.checkedThrough)
            assertNull(coverage.uploadedThrough)
            assertEquals(1L, coverage.scopePendingCount)
            assertEquals(1L, coverage.scopeBlockedCount)
            assertEquals(setOf(f.today.minusDays(1)), coverage.recentCheckedDays)
            assertEquals(setOf(f.today.minusDays(1)), coverage.recentUploadedDays)
            assertEquals(f.categories - DataType.STEPS, coverage.excludedTypes)
            assertEquals(time, coverage.lastUploadAt)
            assertEquals(ManualDrivePhase.AUTHORIZATION_REQUIRED, restored.state.value.phase)
            assertFalse(f.reopen().load().state!!.configuration!!.automatic)

            f.grants = setOf(DataType.WEIGHT)
            restored.refreshGrants()
            val changed = checkNotNull(restored.state.value.coverage)
            assertNotEquals(key, changed.scopeKey)
            assertNull(changed.scopeCheckedThrough)
            assertNull(changed.scopeUploadedThrough)
            assertEquals(4L, changed.scopePendingCount)
            assertEquals(0L, changed.scopeBlockedCount)
            assertTrue(changed.recentCheckedDays.isEmpty())
            assertTrue(changed.recentUploadedDays.isEmpty())
            assertEquals(f.categories - DataType.WEIGHT, changed.excludedTypes)
            f.grants = emptySet()
            restored.refreshGrants()
            val revoked = checkNotNull(restored.state.value.coverage)
            assertNull(revoked.scopeKey)
            assertNull(revoked.scopePendingCount)
            assertNull(revoked.scopeBlockedCount)
            assertEquals(f.categories, revoked.excludedTypes)
            f.grants = setOf(DataType.STEPS)
            restored.refreshGrants()
            assertEquals(coverage, restored.state.value.coverage)
            assertEquals(0, f.rawReads)
            assertEquals(0, f.proofs)
            assertEquals(0, f.writes)
        } finally { f.close() }
    }

    @Test fun pauseAndResetDuringDelayedProofCancelOwnerBeforeRawReadsAndReleaseActualAdmission() = runBlocking {
        for (action in listOf("pause", "reset")) {
            val f = ManualDriveNativeFixture()
            try {
                val session = f.session()
                val ticket = f.configure(session)
                assertTrue(session.acceptAuthorization(ticket, false, "synthetic-memory-only", f.scopes))
                val entered = CompletableDeferred<Unit>()
                f.duringProof = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
                supervisorScope {
                    val owner = async { session.continueManual() }
                    entered.await()
                    if (action == "pause") session.setForeground(false) else assertTrue(session.resetConfiguration())
                    runCatching { owner.await() }
                    assertTrue(owner.isCancelled)
                }
                assertEquals(0, f.rawReads)
                session.setForeground(true)
                session.setPrivateFolderConsent(true)
                f.duringProof = {}
                val next = if (action == "reset") f.configure(session) else checkNotNull(session.beginAuthorization())
                assertTrue(session.acceptAuthorization(next, false, "synthetic-memory-only", f.scopes))
                assertEquals(BatchStatus.COMPLETED, session.continueManual()!!.status)
                assertEquals(1, f.rawReads)
            } finally { f.close() }
        }
    }

    @Test fun actualActivityDefersExternalResultsUntilResumeAndZeroGrantsNeverLaunchSyntheticBridge() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: SyncViewModel
            val bridge = SyntheticBridge()
            scenario.onActivity {
                it.manualBridge = bridge
                model = ViewModelProvider(it)[SyncViewModel::class.java]
                assertTrue(model.manual.state.value.foreground)
                model.manual.setPrivateFolderConsent(true)
                model.selectManualAccount(bridge)
                model.authorizeManual(bridge)
                model.continueManual()
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(bridge.accounts.isEmpty())
            assertNull(bridge.pickerResult)
            assertEquals(emptySet<DataType>(), model.manual.state.value.grantedCategories)
            assertNotEquals(ManualDrivePhase.RUNNING, model.manual.state.value.phase)
            scenario.moveToState(Lifecycle.State.CREATED)
            var delivered = 0
            scenario.onActivity {
                it.deliverForeground { delivered += 100 }
                it.deliverForeground(externalResult = true) { delivered++ }
            }
            assertEquals(0, delivered)
            scenario.moveToState(Lifecycle.State.RESUMED)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (delivered == 0 && android.os.SystemClock.uptimeMillis() < deadline) {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(10)
            }
            assertEquals(1, delivered)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(1, delivered)
        }
    }

    @Test fun lateInitialConfigurationRestorePreservesEditedDraftWithoutChangingConfiguredCategories() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val model = ViewModelProvider(it)[SyncViewModel::class.java]
                val configured = model.manual.state.value.categories
                // 초기 복원 → 사용자 변경 → 지연된 초기 복원 순서를 실제 화면 모델에 전달한다.
                model.restoreInitialManualCategories(setOf(DataType.STEPS))
                assertEquals(setOf(DataType.STEPS), model.manualCategories.value)
                model.changeManualCategories(setOf(DataType.WEIGHT))
                model.restoreInitialManualCategories(setOf(DataType.HEART_RATE))
                assertEquals(setOf(DataType.WEIGHT), model.manualCategories.value)
                assertEquals(configured, model.manual.state.value.categories)
            }
        }
    }

    @Test fun boundViewModelPublishesRealSqliteSuccessFailureCancellationAndDiscardsPreviousReportOnNewAttempt() = runBlocking {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as android.app.Application
        for (scenario in listOf("empty", "proof", "permission", "drive", "pause", "reset", "withdraw")) {
            val f = ManualDriveNativeFixture()
            val models = androidx.lifecycle.ViewModelStore()
            var finishedAfterCleanup = false
            var cleanupObserved = false
            var captured: ManualSyncProfiling? = null
            var uploaderCollector: SyncProfiling? = null
            val entered = CompletableDeferred<Unit>()
            val execution = SyncExecutionSession(f.store, f.reader, null, f.clock,
                { java.util.UUID.randomUUID().toString() }, { if (it == SyncSessionPhase.CLEANUP) cleanupObserved = true })
            val syntheticReader = object : ScopedHealthReader {
                override suspend fun access(trigger: SyncTrigger) = f.reader.access(trigger)
                override suspend fun readScoped(date: java.time.LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                    val snapshot = f.reader.readScoped(date, requestedScopes)
                    return if (scenario == "drive") snapshot.copy(snapshot = snapshot.snapshot.copy(steps = 0L)) else snapshot
                }
            }
            val ports = object : ManualDrivePorts {
                override val store: SyncStateStore = f.store
                override val reader: ScopedHealthReader = syntheticReader
                override suspend fun binding(generation: DestinationGeneration) = f.account
                override suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration,
                    account: BoundDriveAuthorization.AccountSelection) = f.store.configure(expected, configuration,
                        PrivateDestinationBinding("DRIVE", account.name, account.type))
                override suspend fun reopen(generation: DestinationGeneration) = f.store.reopenManualBlockers(generation)
                override suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync) =
                    execution.runPrepared(SyncTrigger.MANUAL, generation, prepare)
                override fun transport(token: String) = object : DriveTransport {
                    override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                        if (url == BoundDriveAuthorization.ABOUT_URL) {
                            entered.complete(Unit)
                            if (scenario in setOf("pause", "reset", "withdraw")) CompletableDeferred<Unit>().await()
                            return DriveResponse(200, if (scenario == "proof") "{}" else f.proofBody)
                        }
                        assertEquals("drive", scenario)
                        return DriveResponse(503, "")
                    }
                }
                override fun destination(transport: DriveTransport, generation: DestinationGeneration): StorageDestination =
                    profiledDestination(transport, generation, null)
                override fun profiledDestination(transport: DriveTransport, generation: DestinationGeneration,
                    profiling: SyncProfiling?): StorageDestination {
                    uploaderCollector = profiling
                    return DriveApiDestination(PrivateDriveUploader(transport, profiling, f.clock), generation, f.store, f.clock)
                }
            }
            try {
                val model = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val factory = object : ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return SyncViewModel(application) { create, begin, finish ->
                                ManualDriveSession(ports, f.clock,
                                    { create().also { captured = it } }, begin,
                                    { profile, outcome -> finishedAfterCleanup = cleanupObserved; finish(profile, outcome) })
                            } as T
                        }
                    }
                    ViewModelProvider(models, factory)[SyncViewModel::class.java]
                }
                instrumentation.waitForIdleSync()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val ticket = f.configure(model.manual)
                    assertTrue(model.state.value.phoneProfiling.collecting)
                    assertNull(model.state.value.phoneProfiling.report)
                    assertTrue(model.manual.acceptAuthorization(ticket, false, "synthetic-memory-only", f.scopes))
                    if (scenario == "permission") f.grants = emptySet()
                    model.continueManual()
                }
                if (scenario in setOf("pause", "reset", "withdraw")) {
                    entered.await()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        assertNull(model.state.value.phoneProfiling.report)
                        when (scenario) {
                            "pause" -> model.pause()
                            "reset" -> model.resetManualConfiguration()
                            "withdraw" -> model.manual.setPrivateFolderConsent(false)
                        }
                    }
                }
                kotlinx.coroutines.withTimeout(10_000) {
                    while (model.state.value.phoneProfiling.collecting) kotlinx.coroutines.delay(10)
                }
                val report = checkNotNull(model.state.value.phoneProfiling.report)
                val summary = checkNotNull(captured).collector.snapshot()
                assertTrue(finishedAfterCleanup)
                assertEquals(24, report.safeText.trim().lines().size)
                val outcome = summary.stages.getValue(ProfileStage.EXECUTION)
                when (scenario) {
                    "empty" -> {
                        assertEquals(1L, outcome.completed)
                        assertEquals(1L, summary.stages.getValue(ProfileStage.HEALTH_READ).completed)
                        assertEquals(2L, summary.stages.getValue(ProfileStage.NORMALIZE).completed)
                        assertEquals(2L, summary.stages.getValue(ProfileStage.CSV_EXPORT).completed)
                        val durable = f.reopen().load().state!!
                        assertNotNull(durable.attempt!!.finishedAt)
                        assertEquals(DayPhase.EMPTY, durable.days.values.single().phase)
                    }
                    "pause", "reset", "withdraw" -> assertEquals(1L, outcome.cancelled)
                    else -> assertEquals(1L, outcome.failed)
                }
                if (scenario == "drive") {
                    assertSame(checkNotNull(captured).collector, uploaderCollector)
                    assertTrue(summary.requests.values.sumOf { it.started } > 0L)
                    assertTrue(summary.operations.values.sumOf { it.started } > 0L)
                    assertEquals(1L, summary.stages.getValue(ProfileStage.DRIVE_TOTAL).failed)
                } else assertTrue(summary.requests.isEmpty())
                for (stage in listOf(ProfileStage.AUTHORIZATION_HISTORY, ProfileStage.START_HISTORY, ProfileStage.TERMINAL_HISTORY))
                    assertNull(summary.stages[stage])
                assertFalse(report.safeText.contains("memory-only"))
                assertFalse(report.safeText.contains(f.account.name))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    var copied: String? = null
                    model.copyPhoneProfiling { copied = it }
                    assertEquals(report.safeText, copied)
                    assertEquals(PhoneCopyState.COPIED, model.state.value.phoneProfiling.copyState)
                    f.grants = setOf(DataType.STEPS)
                    if (scenario == "reset") f.configure(model.manual) else {
                        model.manual.setForeground(true)
                        model.manual.setPrivateFolderConsent(true)
                        assertNotNull(model.manual.beginAuthorization())
                    }
                    assertTrue(model.state.value.phoneProfiling.collecting)
                    assertNull(model.state.value.phoneProfiling.report)
                    assertEquals(PhoneCopyState.NONE, model.state.value.phoneProfiling.copyState)
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { models.clear() }
                f.close()
            }
        }
    }

    private class SyntheticBridge : ManualDriveBridge {
        var pickerResult: ((Boolean, Boolean, String?, String?) -> Unit)? = null
        var authorizationResult: ((Boolean, String?, List<String>) -> Unit)? = null
        val accounts = mutableListOf<BoundDriveAuthorization.AccountSelection>()
        override fun selectAccount(requestId: Long, result: (Boolean, Boolean, String?, String?) -> Unit) {
            pickerResult = result
        }
        override fun authorize(ticket: BoundDriveAuthorization.Ticket, result: (Boolean, String?, List<String>) -> Unit) {
            accounts += ticket.account
            authorizationResult = result
        }
    }
}
