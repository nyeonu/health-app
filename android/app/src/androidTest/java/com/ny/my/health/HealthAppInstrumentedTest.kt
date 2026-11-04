package com.ny.my.health

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented, account-free smoke and safety tests.
 *
 * These checks exercise the actual Activity and Android lifecycle on an emulator,
 * not real Samsung Health records, Google OAuth, or live Google Drive uploads.
 * Never use real health measurements or credentials in test fixtures.
 */
@RunWith(AndroidJUnit4::class)
class HealthAppInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchNeverStartsGoogleAuthorizationWithoutExplicitOptIn() {
        compose.onNodeWithText("Health App · 수동 동기화").assertIsDisplayed()
        compose.onNodeWithText("Google 권한 확인").assertIsNotEnabled()
        compose.onNodeWithText("마지막 업로드 확인", substring = true).assertExists()
    }

    @Test
    fun grantingDriveOptInWithoutHealthPermissionsDoesNotStartUpload() {
        // Fresh emulator install: the app has never been granted Health Connect access.
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Google 권한 확인").performScrollTo().performClick()
        compose.onNodeWithText("PERMISSION_REQUIRED:", substring = true).assertExists()
        compose.onNodeWithText("확인된 업로드 이력이 없습니다.").assertExists()
        compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
    }

    @Test
    fun backgroundAndReturnDoNotFabricateSuccessfulSynchronization() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText("Health App · 수동 동기화").assertExists()
        compose.onNodeWithText("마지막 업로드 확인", substring = true).assertExists()
        compose.onNodeWithText("확인된 업로드 이력이 없습니다.").assertExists()
        compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
    }

    @Test
    fun openingEmptyDiagnosticsAndRecreationNeverStartConsentOrUpload() {
        lateinit var model: SyncViewModel
        compose.activityRule.scenario.onActivity {
            model = androidx.lifecycle.ViewModelProvider(it)[SyncViewModel::class.java]
        }
        fun assertNoConsentOrUpload() {
            compose.runOnIdle {
                val state = model.state.value
                org.junit.Assert.assertFalse(state.uploadOptIn)
                org.junit.Assert.assertFalse(state.pendingConsent)
                org.junit.Assert.assertFalse(state.readyToUpload)
                org.junit.Assert.assertFalse(state.syncing)
                org.junit.Assert.assertNull(model.authorizationTicket)
                org.junit.Assert.assertNull(state.history?.read?.history?.attempt)
                org.junit.Assert.assertNull(state.transientRemoteStatus)
                org.junit.Assert.assertNull(state.phoneProfiling.report)
                org.junit.Assert.assertFalse(state.phoneProfiling.collecting)
                org.junit.Assert.assertEquals(PhoneCopyState.NONE, state.phoneProfiling.copyState)
            }
            compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("업로드 계속").assertDoesNotExist()
            compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
        }
        compose.waitUntil(10_000) {
            model.state.value.history?.operation == HistoryOperation.IDLE && !model.state.value.refreshing
        }
        compose.onNodeWithText("성능 진단 보기").performScrollTo().performClick()
        compose.onNodeWithText("표시할 진단이 없습니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("안전한 진단 요약 복사").performScrollTo().assertIsNotEnabled()
        assertNoConsentOrUpload()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
            model = androidx.lifecycle.ViewModelProvider(it)[SyncViewModel::class.java]
        }
        compose.waitUntil(10_000) { !model.state.value.refreshing }
        compose.onNodeWithText("표시할 진단이 없습니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("안전한 진단 요약 복사").performScrollTo().assertIsNotEnabled()
        assertNoConsentOrUpload()
    }

    /** 실제 Activity의 스크롤 뷰포트와 텍스트 전체 높이를 확인한다. */
    private fun assertFixedContentBoundary() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("본문 위쪽 여백은 스크롤과 무관하게 유지되어야 합니다",
            viewport.top > root.top + 1f)
        org.junit.Assert.assertTrue("본문 아래쪽 여백은 스크롤과 무관하게 유지되어야 합니다",
            viewport.bottom < root.bottom - 1f)
    }

    private fun assertWholeHeadingAtTop() {
        val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        for (label in listOf("Health App · 수동 동기화", "이번 동기화 대상:")) {
            val node = compose.onNodeWithText(label, substring = true).fetchSemanticsNode()
            val visible = node.boundsInRoot
            org.junit.Assert.assertTrue("상단 제목과 대상 문구 전체가 보여야 합니다",
                node.size.height > 0 && visible.height >= node.size.height - 1f &&
                    visible.top >= viewport.top - 1f && visible.bottom <= viewport.bottom + 1f)
        }
    }

    /** 계정 없는 CI 전용: 네이티브 앱 바까지 포함한 합성 화면만 보관한다. */
    private fun captureNativeLayout(name: String) {
        compose.waitUntil(10_000) {
            compose.runOnUiThread { compose.activity.hasWindowFocus() }
        }
        compose.waitForIdle()
        val bitmap = checkNotNull(androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().uiAutomation.takeScreenshot())
        try {
            androidx.test.platform.io.PlatformTestStorageRegistry.getInstance()
                .openOutputFile("v1-synthetic-ui/top-boundary-$name.png").use { output ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun nativeScreenKeepsContentBoundaryWhileScrollingAndReturningToTop() {
        compose.onNodeWithText("Health App · 수동 동기화").performScrollTo()
        assertWholeHeadingAtTop()
        captureNativeLayout("initial")
        compose.onNodeWithText("상세·설정 펼치기").performScrollTo().assertIsDisplayed()
        captureNativeLayout("scrolled")
        assertFixedContentBoundary()
        compose.onNodeWithText("Health App · 수동 동기화").performScrollTo()
        assertWholeHeadingAtTop()
        assertFixedContentBoundary()
        captureNativeLayout("returned-top")
        compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
    }

    @Test
    fun nativeScreenKeepsContentBoundaryAfterRecreationAtScrolledPosition() {
        compose.onNodeWithText("상세·설정 펼치기").performScrollTo()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        captureNativeLayout("recreated-scrolled")
        assertFixedContentBoundary()
        compose.onNodeWithText("Health App · 수동 동기화").performScrollTo()
        assertWholeHeadingAtTop()
        assertFixedContentBoundary()
        captureNativeLayout("recreated-top")
        compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
    }
}

/** 실제 SQLite와 새 Activity/화면 모델을 검증한다. OS 프로세스 종료 검증은 아니다. */
@RunWith(AndroidJUnit4::class)
class DurableHistoryActivityInstrumentedTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
    private var ownsFixture = false
    private lateinit var model: SyncViewModel
    private val date = java.time.LocalDate.parse("2026-01-02")
    private val requested = java.time.Instant.parse("2026-01-03T00:00:00Z")
    private val success = com.ny.my.health.core.HistorySuccess(
        date.minusDays(1), requested.minusSeconds(86400), setOf(com.ny.my.health.core.DataType.WEIGHT), 7,
    )

    private fun seed(phase: com.ny.my.health.core.HistoryPhase) {
        val file = java.io.File(context.noBackupFilesDir, "sync-history.db")
        // 기존 데이터가 있으면 즉시 실패한다. 소유하지 않은 DB는 지우거나 교체하지 않는다.
        org.junit.Assert.assertFalse("새 계정 없는 에뮬레이터에 기존 이력 DB가 없어야 합니다", file.exists())
        org.junit.Assert.assertFalse(java.io.File(file.path + "-journal").exists())
        ownsFixture = true
        kotlinx.coroutines.runBlocking {
            SqliteHistoryStore(context).replace(com.ny.my.health.core.SyncHistory(1,
                com.ny.my.health.core.HistoryAttempt(date, requested,
                    if (phase == com.ny.my.health.core.HistoryPhase.RUNNING) requested.plusSeconds(60) else null,
                    null, phase, setOf(com.ny.my.health.core.DataType.STEPS), null, null), success))
        }
    }

    private fun launchFresh() {
        scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[SyncViewModel::class.java] }
        compose.waitUntil(10_000) {
            model.state.value.history?.let { it.operation == HistoryOperation.IDLE } == true && !model.state.value.refreshing
        }
        compose.waitForIdle()
    }

    private fun assertManualState() {
        val state = model.state.value
        org.junit.Assert.assertFalse(state.uploadOptIn)
        org.junit.Assert.assertFalse(state.readyToUpload)
        org.junit.Assert.assertFalse(state.pendingConsent)
        org.junit.Assert.assertFalse(state.syncing)
        org.junit.Assert.assertNull(model.authorizationTicket)
        org.junit.Assert.assertNull(state.transientRemoteStatus)
        compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
    }

    @org.junit.After fun removeOnlyOwnedSyntheticFixture() {
        scenario?.close()
        if (ownsFixture) {
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                val file = java.io.File(context.noBackupFilesDir, "sync-history.db$suffix")
                check(!file.exists() || file.delete()) { "합성 이력 정리 실패" }
            }
        }
    }

    @Test fun persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset() {
        seed(com.ny.my.health.core.HistoryPhase.AWAITING_UPLOAD)
        launchFresh()
        val previousModel = model
        compose.onNodeWithText("업로드 시작 전 종료됨 · 다시 인증해 주세요.").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        org.junit.Assert.assertTrue(model.state.value.uploadOptIn)
        scenario!!.close()
        launchFresh()
        org.junit.Assert.assertNotSame(previousModel, model)
        assertManualState()
        org.junit.Assert.assertEquals(success, model.state.value.history!!.read.history!!.success)
        val reopened = kotlinx.coroutines.runBlocking { SqliteHistoryStore(context).load() }
        org.junit.Assert.assertEquals(success, reopened.history!!.success)
        org.junit.Assert.assertEquals(com.ny.my.health.core.HistoryPhase.UPLOAD_NOT_STARTED, reopened.history!!.attempt!!.phase)
        compose.onNodeWithText("확인된 파일 수: 7개").performScrollTo().assertIsDisplayed()
    }

    @Test fun persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess() {
        seed(com.ny.my.health.core.HistoryPhase.RUNNING)
        launchFresh()
        val previousModel = model
        scenario!!.close()
        launchFresh()
        org.junit.Assert.assertNotSame(previousModel, model)
        assertManualState()
        val reopened = kotlinx.coroutines.runBlocking { SqliteHistoryStore(context).load() }.history!!
        org.junit.Assert.assertEquals(com.ny.my.health.core.HistoryPhase.INTERRUPTED, reopened.attempt!!.phase)
        org.junit.Assert.assertNull(reopened.attempt!!.finishedAt)
        org.junit.Assert.assertNull(reopened.attempt!!.confirmedFiles)
        org.junit.Assert.assertEquals(success, reopened.success)
        compose.onNodeWithText("실행 중 종료됨 · 업로드 결과를 확인할 수 없습니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("상세·설정 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("종료 시각: 확인 불가").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("최근 시도에서 확인된 업로드: 확인되지 않음").performScrollTo().assertIsDisplayed()
    }

    @Test fun actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty() {
        seed(com.ny.my.health.core.HistoryPhase.AUTHORIZING)
        launchFresh()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("상세·설정 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("로컬 이력 삭제").performScrollTo().performClick()
        compose.onNodeWithText("취소").performClick()
        org.junit.Assert.assertTrue(model.state.value.uploadOptIn)
        org.junit.Assert.assertEquals(success, kotlinx.coroutines.runBlocking { SqliteHistoryStore(context).load() }.history!!.success)
        compose.onNodeWithText("로컬 이력 삭제").performScrollTo().performClick()
        compose.onNodeWithText("이력 삭제").performClick()
        compose.waitUntil(10_000) {
            model.state.value.history?.let { it.operation == HistoryOperation.IDLE && it.read.history?.success == null } == true
        }
        assertManualState()
        scenario!!.close()
        launchFresh()
        assertManualState()
        val reopened = kotlinx.coroutines.runBlocking { SqliteHistoryStore(context).load() }
        org.junit.Assert.assertEquals(com.ny.my.health.core.HistoryReadStatus.READY, reopened.status)
        org.junit.Assert.assertNull(reopened.history!!.attempt)
        org.junit.Assert.assertNull(reopened.history!!.success)
        compose.onNodeWithText("저장된 동기화 이력이 없습니다.").performScrollTo().assertIsDisplayed()
    }
}
