package com.ny.my.health

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.view.captureRegionToBitmap
import androidx.test.platform.graphics.HardwareRendererCompat
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
    private fun nativeContent() = compose.onNodeWithTag("health-window-content")

    private fun assertFixedContentBoundary() {
        val content = nativeContent().fetchSemanticsNode()
        val root = content.boundsInRoot
        val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("본문 위쪽 여백은 스크롤과 무관하게 유지되어야 합니다",
            viewport.top > root.top + 1f)
        org.junit.Assert.assertTrue("본문 아래쪽 여백은 스크롤과 무관하게 유지되어야 합니다",
            viewport.bottom < root.bottom - 1f)
        val inset = 24f * compose.density.density
        org.junit.Assert.assertEquals("상단 고정 여백은 24dp여야 합니다", inset, viewport.top - root.top, 1f)
        org.junit.Assert.assertEquals("하단 고정 여백은 24dp여야 합니다", inset, root.bottom - viewport.bottom, 1f)
        val safeWindow = compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val insets = checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(decor))
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            Triple(insets, decor.width, decor.height)
        }
        val bounds = content.boundsInWindow
        val (safe, width, height) = safeWindow
        org.junit.Assert.assertTrue("본문은 시스템 바와 cutout의 안전 영역 안에 있어야 합니다",
            bounds.left >= safe.left - 1f && bounds.top >= safe.top - 1f &&
                bounds.right <= width - safe.right + 1f && bounds.bottom <= height - safe.bottom + 1f)
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

    private fun waitForHomeReady() {
        lateinit var model: SyncViewModel
        compose.activityRule.scenario.onActivity {
            model = androidx.lifecycle.ViewModelProvider(it)[SyncViewModel::class.java]
        }
        compose.waitUntil(10_000) {
            !model.state.value.refreshing && model.state.value.history?.operation == HistoryOperation.IDLE
        }
        compose.waitForIdle()
    }

    private fun currentScrollPosition(): Float = compose.onNode(hasScrollAction())
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun saveLayoutBitmap(name: String, bitmap: android.graphics.Bitmap) {
        androidx.test.platform.io.PlatformTestStorageRegistry.getInstance()
            .openOutputFile("v1-synthetic-ui/$name.png").use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
    }

    private fun closeColor(first: Int, second: Int, tolerance: Int): Boolean =
        listOf(0, 8, 16).all { shift ->
            kotlin.math.abs(((first ushr shift) and 255) - ((second ushr shift) and 255)) <= tolerance
        }

    /** 합성 CI 화면만 진단한다. View 텍스트나 계정 상태는 출력하지 않는다. */
    private fun logNativeGeometry(name: String) = compose.runOnUiThread {
        val activity = compose.activity
        val decor = activity.window.decorView
        val content = activity.findViewById<android.view.View>(android.R.id.content)
        fun logView(label: String, view: android.view.View) {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val parent = view.parent as? android.view.View
            println("TOP_LAYOUT_VIEW name=$name label=$label x=${location[0]} y=${location[1]} " +
                "width=${view.width} height=${view.height} measuredWidth=${view.measuredWidth} " +
                "measuredHeight=${view.measuredHeight} scrollY=${view.scrollY} " +
                "layoutRequested=${view.isLayoutRequested} parentTop=${parent?.top} parentHeight=${parent?.height}")
        }
        logView("decor", decor)
        logView("content", content)
        fun visit(view: android.view.View) {
            when (view.javaClass.name) {
                "androidx.compose.ui.platform.ComposeView" -> logView("compose", view)
                "androidx.compose.ui.platform.AndroidComposeView" -> logView("owner", view)
            }
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(content)
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
        val bars = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val cutout = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        val power = activity.getSystemService(android.os.PowerManager::class.java)
        val keyguard = activity.getSystemService(android.app.KeyguardManager::class.java)
        println("TOP_LAYOUT_WINDOW name=$name lifecycle=${activity.lifecycle.currentState.ordinal} " +
            "systemUiVisibility=${decor.systemUiVisibility} actionBarOverlay=${activity.window.hasFeature(android.view.Window.FEATURE_ACTION_BAR_OVERLAY)} " +
            "activityFocus=${activity.hasWindowFocus()} decorFocus=${decor.hasWindowFocus()} shown=${decor.isShown} " +
            "interactive=${power.isInteractive} keyguard=${keyguard.isKeyguardLocked} locked=${keyguard.isDeviceLocked} " +
            "barsLeft=${bars?.left} barsTop=${bars?.top} barsRight=${bars?.right} barsBottom=${bars?.bottom} " +
            "cutoutLeft=${cutout?.left} cutoutTop=${cutout?.top} cutoutRight=${cutout?.right} cutoutBottom=${cutout?.bottom}")
    }

    /** 계정 없는 CI 전용: 공식 drawing scope로 root/현재 창을 같은 상태에서 대조한다. */
    private fun captureNativeLayout(name: String) {
        try {
            compose.waitUntil(10_000) {
                compose.runOnUiThread { compose.activity.hasWindowFocus() }
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching {
                logNativeGeometry("focus-timeout-$name")
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot()?.let { bitmap ->
                        try { saveLayoutBitmap("focus-failure-$name", bitmap) } finally { bitmap.recycle() }
                    }
            }
            throw timeout
        }
        compose.waitForIdle()
        logNativeGeometry("before-$name")
        val window = compose.runOnUiThread { compose.activity.window }
        val beforeDrawing = HardwareRendererCompat.isDrawingEnabled()
        val beforeScroll = currentScrollPosition()
        val beforeRoot = nativeContent().fetchSemanticsNode().boundsInWindow
        val content = nativeContent().captureToImage().asAndroidBitmap()
        var native: android.graphics.Bitmap? = null
        try {
            compose.waitForIdle()
            val root = nativeContent().fetchSemanticsNode().boundsInWindow
            val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInWindow
            val bitmap = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        window.captureRegionToBitmap()
                    }
                }
            }
            native = bitmap
            compose.waitForIdle()
            val afterRoot = nativeContent().fetchSemanticsNode().boundsInWindow
            val afterScroll = currentScrollPosition()
            val afterDrawing = HardwareRendererCompat.isDrawingEnabled()
            logNativeGeometry("after-$name")
            // 건강 값/날짜/문구를 출력하지 않고 합성 화면 좌표와 렌더링 상태만 남긴다.
            println("TOP_LAYOUT_CAPTURE name=$name drawingBefore=$beforeDrawing drawingAfter=$afterDrawing " +
                "scrollBefore=$beforeScroll scrollAfter=$afterScroll rootBefore=$beforeRoot root=$root " +
                "rootAfter=$afterRoot viewport=$viewport density=${compose.density.density}")
            saveLayoutBitmap("top-content-root-$name", content)
            saveLayoutBitmap("top-boundary-$name", bitmap)
            org.junit.Assert.assertEquals("캡처는 drawing 상태를 복구해야 합니다", beforeDrawing, afterDrawing)
            org.junit.Assert.assertEquals("캡처는 스크롤을 바꾸지 않아야 합니다", beforeScroll, afterScroll, 1f)
            org.junit.Assert.assertEquals("캡처 중 root 위치는 유지되어야 합니다", beforeRoot, root)
            org.junit.Assert.assertEquals("두 캡처는 같은 root 위치를 사용해야 합니다", root, afterRoot)
            org.junit.Assert.assertEquals(root.width.toInt(), content.width)
            org.junit.Assert.assertEquals(root.height.toInt(), content.height)
            val offsetX = root.left.toInt()
            val offsetY = root.top.toInt()
            org.junit.Assert.assertTrue("root 픽셀은 현재 창 범위 안에 있어야 합니다",
                offsetX >= 0 && offsetY >= 0 && offsetX + content.width <= bitmap.width &&
                    offsetY + content.height <= bitmap.height)
            val dark = compose.runOnUiThread {
                compose.activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            val colors = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
            val background = colors.surface.toArgb()
            val foreground = colors.onSurface.toArgb()
            // 여백 중앙 행은 native 앱 바의 경계 shadow와 구분해 본문 paint 침범을 검사한다.
            val guardY = ((viewport.top - root.top) / 2).toInt()
            org.junit.Assert.assertTrue("상단 여백 픽셀 행이 있어야 합니다", guardY > 0)
            for (x in 0 until content.width) {
                org.junit.Assert.assertTrue("고정 여백에 본문 픽셀이 침범하면 안 됩니다",
                    closeColor(content.getPixel(x, guardY), background, 4))
                org.junit.Assert.assertTrue("root와 현재 창의 여백 프레임이 일치해야 합니다",
                    closeColor(content.getPixel(x, guardY), bitmap.getPixel(offsetX + x, offsetY + guardY), 2))
            }
            if (name in setOf("initial", "returned-top", "recreated-top")) {
                for ((index, label) in listOf("Health App · 수동 동기화", "이번 동기화 대상:").withIndex()) {
                    val node = compose.onNodeWithText(label, substring = true).fetchSemanticsNode()
                    val box = node.boundsInWindow
                    println("TOP_LAYOUT_HEADING index=$index bounds=$box size=${node.size}")
                    var hasBackground = false
                    var hasForeground = false
                    for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
                        val pixel = content.getPixel(x - offsetX, y - offsetY)
                        hasBackground = hasBackground || closeColor(pixel, background, 4)
                        hasForeground = hasForeground || closeColor(pixel, foreground, 8)
                        org.junit.Assert.assertTrue("제목 영역의 root/창 픽셀이 같은 프레임이어야 합니다",
                            closeColor(pixel, bitmap.getPixel(x, y), 2))
                    }
                    org.junit.Assert.assertTrue("제목과 대상 문구가 실제 본문 배경에 그려져야 합니다",
                        hasBackground && hasForeground)
                }
            }
        } finally {
            content.recycle()
            native?.recycle()
        }
    }

    @Test
    fun nativeScreenKeepsContentBoundaryWhileScrollingAndReturningToTop() {
        waitForHomeReady()
        compose.onNodeWithText("Health App · 수동 동기화").performScrollTo()
        assertWholeHeadingAtTop()
        assertFixedContentBoundary()
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
        waitForHomeReady()
        compose.onNodeWithText("상세·설정 펼치기").performScrollTo()
        val positionBefore = currentScrollPosition()
        val boundsBefore = nativeContent().fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertTrue("재생성 전에 본문이 실제로 스크롤되어 있어야 합니다", positionBefore > 0f)
        compose.activityRule.scenario.recreate()
        waitForHomeReady()
        org.junit.Assert.assertEquals("재생성 전후의 안전한 본문 경계는 같아야 합니다",
            boundsBefore, nativeContent().fetchSemanticsNode().boundsInWindow)
        org.junit.Assert.assertEquals("재생성은 기존 스크롤 위치를 보존해야 합니다",
            positionBefore, currentScrollPosition(), 1f)
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
