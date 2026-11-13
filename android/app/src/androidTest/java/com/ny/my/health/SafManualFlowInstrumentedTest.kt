package com.ny.my.health

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 실제 Activity 결과와 합성 provider를 사용하며 실제 계정이나 건강 provider는 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class SafManualFlowInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun safActualActivitySelectionConfiguresWithoutExportingAndExplicitContinueConfirmsOnlySelectedLocalTarget() {
        val f = FlowFixture()
        try {
            f.launch()
            f.prepareUi()
            f.pick()
            compose.waitUntil(10_000) { f.model.saf.state.value.configured }
            val selected = f.model.saf.state.value
            assertTrue("전체 선택 범위 보존", selected.categories == setOf(DataType.STEPS, DataType.WEIGHT))
            assertTrue("일부 권한 보존", selected.grantedCategories == setOf(DataType.STEPS))
            assertTrue("선택만으로 건강 읽기 없음", f.native.rawReads == 0)
            val generation = requireNotNull(selected.generation) { "구성 세대 없음" }
            val before = f.observeAfterRoundTrip()
            assertTrue("선택 probe 정리", SyntheticDocumentsProvider.createCount(before) == 1 &&
                SyntheticDocumentsProvider.deleteCount(before) == 1)
            val durable = runBlocking { f.native.reopen().load() }
            assertTrue("재개방한 구성 세대 일치", durable.state?.generation == generation)
            assertTrue("재개방한 전체 범위 일치", durable.state?.configuration?.categories == selected.categories)
            assertTrue("자동 실행 비활성", durable.state?.configuration?.automatic == false)
            assertTrue("계속 전 로컬 준비: ${f.diagnostic()}",
                f.model.saf.state.value.phase == ManualSafPhase.READY &&
                    f.model.committedDestination.value.kind == ManualDestination.LOCAL &&
                    f.model.committedDestination.value.generation == generation)
            f.continueFromUi()
            assertTrue("명시 계속 완료: ${f.diagnostic()}", f.model.saf.state.value.outcome == BatchStatus.COMPLETED)
            assertTrue("명시적 계속 뒤 건강 읽기", f.native.rawReads > 0)
            val confirmed = runBlocking { f.native.reopen().load() }.state
            assertTrue("완료 세대 유지", confirmed?.generation == generation)
            val targetDay = f.native.today.minusDays(1)
            val handles = runBlocking { f.native.reopen().loadKnownTargets(generation, targetDay) }
            val daily = handles[Dataset.DAILY].orEmpty()
            assertTrue("확인된 일별 파일 하나", daily.size == 1 && handles.values.flatten().size == 1)
            val scoped = runBlocking { f.native.reopen().loadForScope(generation, f.native.today, f.reader.access(SyncTrigger.MANUAL)) }.state
            val day = scoped?.days?.get(targetDay)
            assertTrue("저장된 확인과 revision 존재", day?.phase == DayPhase.CONFIRMED && day.revision != null)
            val generatedAt = day!!.revision!!.generatedAt
            val expected = ("schema_version,date,timezone,steps,weight_kg,resting_hr_bpm,sleep_min,exercise_min,generated_at\r\n" +
                "0.1,$targetDay,Asia/Seoul,1,,,,,$generatedAt\r\n").toByteArray(Charsets.UTF_8)
            assertTrue("실제 resolver 내용과 합성 기대 bytes 일치", daily.all { handle ->
                runCatching { f.provider.resolver.openInputStream(Uri.parse(handle))?.use { it.readBytes().contentEquals(expected) } == true }
                    .getOrDefault(false)
            })
            f.action { f.model.retrySaf() }
            assertTrue("성공 재시도 결과 보존", f.model.saf.state.value.outcome == BatchStatus.COMPLETED)
            val retried = runBlocking { f.native.reopen().loadKnownTargets(generation, targetDay) }
            assertTrue("재시도 대상 identity 보존", retried == handles)
            f.moveToState(Lifecycle.State.CREATED)
            f.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { f.model.saf.state.value.foreground }
            f.action { f.model.refreshPermissions() }
            assertTrue("완료 결과 보존", f.model.saf.state.value.outcome == BatchStatus.COMPLETED)
            val localCoverage = f.model.saf.state.value.coverage
            assertTrue("로컬 coverage 존재", localCoverage != null)
            f.onMain { f.model.changeDestination(ManualDestination.DRIVE) }
            compose.waitForIdle()
            val draftStore = runBlocking { f.native.reopen().load() }.state
            val draftBinding = runBlocking { f.native.reopen().readBinding(generation) }
            assertTrue("draft 변경은 로컬 binding과 세대 보존", draftStore?.generation == generation && draftBinding?.kind == "LOCAL")
            assertTrue("draft 변경은 로컬 coverage 보존", f.model.saf.state.value.coverage == localCoverage && f.model.saf.state.value.configured)
            assertTrue("실제 활성 목적지는 로컬", f.model.committedDestination.value.kind == ManualDestination.LOCAL &&
                f.model.committedDestination.value.generation == generation)
            compose.onNodeWithText("활성 로컬 확인 날짜:", substring = true).performScrollTo().assertExists()
            compose.onNodeWithText("미구성 · 업로드에 동의하고 Google 계정을 선택해 주세요.").performScrollTo().assertExists()
            compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
            f.onMain { f.model.changeDestination(ManualDestination.LOCAL); f.model.saf.setPrivacyConsent(true) }
            assertTrue("Drive 호출 없음", f.driveCalls == 0)
            f.provider.command(SyntheticDocumentsProvider.Control.REVOKE)
            val reads = f.native.rawReads
            f.action { f.model.continueSaf() }
            compose.waitUntil(10_000) { f.model.saf.state.value.phase == ManualSafPhase.BLOCKED }
            assertTrue("철회 뒤 건강 읽기 없음", f.native.rawReads == reads)
            assertTrue("이전 완료가 현재 차단을 가리지 않음", f.model.saf.state.value.outcome != BatchStatus.COMPLETED)
            f.action { f.model.retrySaf() }
            assertTrue("재시도 뒤 차단 보존", f.model.saf.state.value.phase == ManualSafPhase.BLOCKED)
            f.moveToState(Lifecycle.State.CREATED)
            f.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { f.model.saf.state.value.foreground }
            f.action { f.model.refreshPermissions() }
            assertTrue("복귀와 권한 확인 뒤 차단 보존", f.model.saf.state.value.phase == ManualSafPhase.BLOCKED)
            f.onMain {
                f.model.changeDestination(ManualDestination.DRIVE)
                f.model.changeManualCategories(setOf(DataType.STEPS))
                f.model.manual.setPrivateFolderConsent(true)
            }
            assertTrue("Drive draft에서 로컬 활성 유지", f.model.saf.state.value.configured && f.model.saf.state.value.coverage != null)
            f.allowDrive = true
            f.onMain { f.model.selectManualAccount(f.activity.manualBridge) }
            compose.waitUntil(10_000) { f.authorization != null && f.model.committedDestination.value.kind == ManualDestination.DRIVE }
            assertTrue("Drive 구성에서 로컬 coverage 비활성", !f.model.saf.state.value.configured && f.model.saf.state.value.coverage == null)
            val authorizations = f.authorizeCalls
            f.onMain { f.model.manual.setPrivateFolderConsent(false) }
            compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
            f.action { f.model.authorizeManual(f.activity.manualBridge) }
            assertTrue("동의 철회 시 새 인증 요청 없음", f.authorizeCalls == authorizations)
            f.onMain { f.model.manual.setPrivateFolderConsent(true); f.model.changeManualCategories(setOf(DataType.WEIGHT)) }
            compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
            f.action { f.model.authorizeManual(f.activity.manualBridge) }
            assertTrue("범위 편집 시 새 인증 요청 없음", f.authorizeCalls == authorizations)
            f.onMain { f.model.changeManualCategories(setOf(DataType.STEPS)); f.model.changeDestination(ManualDestination.LOCAL); f.model.manual.setPrivateFolderConsent(true) }
            f.action { f.model.authorizeManual(f.activity.manualBridge) }
            assertTrue("다른 목적지 draft에서 새 인증 요청 없음", f.authorizeCalls == authorizations)
            f.onMain { f.model.changeDestination(ManualDestination.DRIVE) }
            val driveGeneration = requireNotNull(f.model.committedDestination.value.generation) { "Drive 세대 없음" }
            assertTrue("Drive binding과 새 세대", driveGeneration != generation &&
                runBlocking { f.native.reopen().readBinding(driveGeneration) }?.kind == "DRIVE")
            f.action { f.model.resetManualConfiguration() }
            assertTrue("Drive 초기화 뒤 구성 없음", runBlocking { f.native.reopen().load() }.state?.configuration == null)
            assertTrue("Drive 초기화 뒤 로컬 coverage 없음", !f.model.saf.state.value.configured && f.model.saf.state.value.coverage == null)
            f.onMain { f.authorization?.invoke(false, "synthetic-memory-only", listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE)) }
            compose.waitForIdle()
            assertTrue("초기화 뒤 늦은 인증 거부", f.model.manual.state.value.phase != ManualDrivePhase.READY)
            assertTrue("명시적 Drive 호출만 수행", f.driveCalls == 2)
        } finally { f.close() }
    }

    @Test fun safActualActivityRejectsStaleTreeResultsAndPausedRevokedGrantsWithoutDriveFallback() {
        val f = FlowFixture()
        try {
            f.launch()
            f.prepareUi()
            f.control = SyntheticDocumentsProvider.Control.GRANT_READ_ONLY
            f.pick()
            compose.waitUntil(10_000) { f.model.saf.state.value.phase == ManualSafPhase.BLOCKED }
            assertTrue("읽기 전용 결과 구성 거부", !f.model.saf.state.value.configured)
            assertTrue("읽기 전용 결과 건강 읽기 없음", f.native.rawReads == 0)
            assertTrue("읽기 전용 결과 probe 없음", SyntheticDocumentsProvider.createCount(f.provider.counts()) == 0)
            f.control = SyntheticDocumentsProvider.Control.GRANT_READ_WRITE
            f.hold = true
            val editedBefore = f.provider.counts()
            f.pick()
            compose.waitUntil(10_000) { f.pending != null }
            f.onMain { f.model.saf.setCategories(setOf(DataType.STEPS)) }
            f.release()
            assertTrue("편집된 picker 결과 거부", !f.model.saf.state.value.configured)
            assertTrue("편집된 결과 SQLite 구성 없음", runBlocking { f.native.reopen().load() }.state?.configuration == null)
            f.assertNoProviderMutation(editedBefore)
            f.onMain { f.model.saf.setCategories(setOf(DataType.STEPS, DataType.WEIGHT)) }
            val pausedBefore = f.provider.counts()
            f.pick()
            compose.waitUntil(10_000) { f.pending != null }
            f.moveToState(Lifecycle.State.CREATED)
            f.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { f.model.saf.state.value.foreground }
            f.release()
            assertTrue("두 번째 일시 정지 뒤 결과 거부", !f.model.saf.state.value.configured)
            assertTrue("일시 정지 결과 SQLite 구성 없음", runBlocking { f.native.reopen().load() }.state?.configuration == null)
            f.assertNoProviderMutation(pausedBefore)
            f.hold = false
            f.native.grants = emptySet()
            f.action { f.model.refreshPermissions() }
            assertTrue("권한 철회 반영", f.model.saf.state.value.grantedCategories.isEmpty())
            val before = f.provider.counts()
            f.action { f.model.selectSafTree(f.activity.safBridge) }
            assertTrue("권한 없음 구성 거부", !f.model.saf.state.value.configured)
            assertTrue("권한 없음 SQLite 구성 없음", runBlocking { f.native.reopen().load() }.state?.configuration == null)
            f.assertNoProviderMutation(before)
            assertTrue("차단 경로 건강 읽기 없음", f.native.rawReads == 0)
            assertTrue("Drive fallback 없음", f.driveCalls == 0)
        } finally { f.close() }
    }

    private inner class FlowFixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val provider = SafDestinationFixture()
        val native = ManualDriveNativeFixture()
        @Volatile var pauses = 0
        @Volatile var resumes = 0
        var driveCalls = 0
        var authorizeCalls = 0
        var allowDrive = false
        var authorization: ((Boolean, String?, List<String>) -> Unit)? = null
        var hold = false
        @Volatile var pending: (() -> Unit)? = null
        var control = SyntheticDocumentsProvider.Control.GRANT_READ_WRITE
        lateinit var scenario: ActivityScenario<MainActivity>
        lateinit var activity: MainActivity
        lateinit var model: SyncViewModel
        val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger) = native.reader.access(trigger)
            override suspend fun readScoped(date: java.time.LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                val result = native.reader.readScoped(date, requestedScopes)
                return result.copy(snapshot = result.snapshot.copy(steps = 1L))
            }
        }
        private val execution = SyncExecutionSession(native.store, reader, null, native.clock, { UUID.randomUUID().toString() })
        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, state: Bundle?) {
                if (a !is MainActivity) return
                activity = a
                a.viewModelFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return SyncViewModel(application, SyncViewModel.ManualDestinationDependencies(native.store, reader,
                            execution, SafTreeConfiguration.forTesting(provider.resolver, SyntheticDocumentsProvider.AUTHORITY),
                            native.clock), null).also { model = it } as T
                    }
                }
                a.safTreeIntent = { Intent().apply {
                    component = ComponentName(instrumentation.context.packageName, SyntheticDocumentsProvider.GrantActivity::class.java.name)
                    putExtra(SyntheticDocumentsProvider.EXTRA_CONTROL, control.name)
                } }
                val original = a.safBridge
                a.safBridge = object : ManualSafBridge {
                    override fun isBusy() = original.isBusy()
                    override fun selectTree(requestId: Long, result: (Uri?, Int) -> Unit) {
                        original.selectTree(requestId) { uri, flags ->
                            if (hold) pending = { result(uri, flags) } else result(uri, flags)
                        }
                    }
                }
                a.manualBridge = object : ManualDriveBridge {
                    override fun selectAccount(requestId: Long, result: (Boolean, Boolean, String?, String?) -> Unit) {
                        driveCalls++
                        if (allowDrive) result(true, true, native.account.name, native.account.type)
                    }
                    override fun authorize(ticket: BoundDriveAuthorization.Ticket, result: (Boolean, String?, List<String>) -> Unit) {
                        driveCalls++
                        authorizeCalls++
                        if (allowDrive) authorization = result
                    }
                }
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) { if (a is MainActivity && a === activity) resumes++ }
            override fun onActivityPaused(a: Activity) { if (a is MainActivity && a === activity) pauses++ }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        fun launch() {
            provider.close()
            application.registerActivityLifecycleCallbacks(callbacks)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { ::model.isInitialized && model.saf.state.value.foreground && model.committedDestination.value.selectionAllowed }
        }
        fun prepareUi() {
            compose.onNodeWithText("로컬 폴더 선택").performScrollTo().performClick()
            compose.onNodeWithContentDescription("로컬 동기화 항목: 걸음").performScrollTo().performClick()
            compose.onNodeWithContentDescription("로컬 동기화 항목: 체중").performScrollTo().performClick()
            compose.onNodeWithContentDescription("로컬 폴더 저장 동의").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.saf.state.value.intendedCategories.size == 2 && model.saf.state.value.privacyConsent }
        }
        fun pick() { compose.onAllNodesWithText("로컬 폴더 선택")[1].performScrollTo().performClick() }
        fun onMain(block: () -> Unit) { instrumentation.runOnMainSync { block() } }
        fun diagnostic(): String {
            val state = model.saf.state.value
            val committed = model.committedDestination.value
            return "phase=${state.phase}, outcome=${state.outcome}, foreground=${state.foreground}, " +
                "configured=${state.configured}, kind=${committed.kind}, reads=${native.rawReads}"
        }
        fun observeAfterRoundTrip(): Bundle {
            var oldPauses = 0
            var oldResumes = 0
            var baseline: Set<Job> = emptySet()
            onMain {
                oldPauses = pauses
                oldResumes = resumes
                baseline = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            }
            val observed = provider.counts()
            try {
                compose.waitUntil(10_000) { pauses > oldPauses && resumes > oldResumes }
            } catch (_: Exception) {
                throw AssertionError("observer 복귀 대기 실패: ${diagnostic()}")
            }
            var children: List<Job> = emptyList()
            var resumed = false
            onMain {
                resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.saf.state.value.foreground
                children = model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in baseline }.toList()
            }
            assertTrue("observer 실제 전경 복귀: ${diagnostic()}", resumed)
            runBlocking { withTimeout(10_000) { children.joinAll() } }
            return observed
        }
        fun continueFromUi() {
            var jobs: List<Job> = emptyList()
            compose.onNodeWithText("로컬 업로드 계속").performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) { click ->
                    val parent = model.viewModelScope.coroutineContext[Job]!!
                    val before = parent.children.toSet()
                    assertTrue("실제 계속 버튼 실행: ${diagnostic()}", click())
                    jobs = parent.children.filter { it !in before }.toList()
                }
            runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        }
        fun moveToState(state: Lifecycle.State) {
            var before: Set<Job> = emptySet()
            onMain { before = model.viewModelScope.coroutineContext[Job]!!.children.toSet() }
            scenario.moveToState(state)
            var children: List<Job> = emptyList()
            onMain { children = model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in before }.toList() }
            runBlocking { withTimeout(10_000) { children.joinAll() } }
        }
        fun action(block: () -> Unit) {
            var children: List<Job> = emptyList()
            onMain {
                val parent = model.viewModelScope.coroutineContext[Job]!!
                val before = parent.children.toSet()
                block()
                children = parent.children.filter { it !in before }.toList()
            }
            runBlocking { withTimeout(10_000) { children.joinAll() } }
        }
        fun release() { action { val callback = pending; pending = null; callback?.invoke() } }
        fun assertNoProviderMutation(before: Bundle) {
            val after = provider.counts()
            assertTrue("provider 생성·쓰기·삭제 불변", SyntheticDocumentsProvider.createCount(before) == SyntheticDocumentsProvider.createCount(after) &&
                SyntheticDocumentsProvider.writeCount(before) == SyntheticDocumentsProvider.writeCount(after) &&
                SyntheticDocumentsProvider.deleteCount(before) == SyntheticDocumentsProvider.deleteCount(after))
        }
        fun close() {
            if (::scenario.isInitialized) scenario.close()
            application.unregisterActivityLifecycleCallbacks(callbacks)
            provider.close()
            native.close()
        }
    }
}
