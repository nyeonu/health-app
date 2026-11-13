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
