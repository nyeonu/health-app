package com.ny.my.health

import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-orchestrated only: excluded from the ordinary suite, never reports a killed hold as PASS. */
@RunWith(AndroidJUnit4::class)
class HistoryProcessRecoveryInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val database = HistoryDatabase(context)
    private val owner = File(context.noBackupFilesDir, "history-process-fixture-owner")
    private val date = LocalDate.parse("2026-01-02")
    private val time = Instant.parse("2026-01-03T00:00:00Z")
    private val success = HistorySuccess(date, time, setOf(DataType.WEIGHT), 7)
    private val committed = SyncHistory(1, HistoryAttempt(date, time.minusSeconds(120),
        time.minusSeconds(60), time, HistoryPhase.SUCCESS, success.coverage, 7, null), success)
    private val empty = SyncHistory(1, null, null)

    private fun gate() {
        check(args.getString("historyProcessHost") == "true") { "Use ci/verify-history-process-recovery.sh" }
    }
    private fun marker(value: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\n$value pid=${Process.myPid()}\n")
    })
    private fun kind() = requireNotNull(args.getString("fixture"))
    private fun fixture(): SyncHistory = when (kind()) {
        "success", "reset-before" -> committed
        "empty", "reset-after", "reset-empty-before" -> empty
        "running", "authorizing", "awaiting" -> committed.copy(attempt = HistoryAttempt(
            date.plusDays(1), time.plusSeconds(86400),
            if (kind() == "running") time.plusSeconds(86460) else null, null,
            when (kind()) {
                "running" -> HistoryPhase.RUNNING
                "authorizing" -> HistoryPhase.AUTHORIZING
                else -> HistoryPhase.AWAITING_UPLOAD
            }, setOf(DataType.STEPS), null, null))
        else -> error("Unknown synthetic fixture")
    }
    private fun assertOwned() {
        check(owner.isFile && owner.readText() == "synthetic-process-fixture-v1")
    }

    @Test fun seedAndHoldUntilHostKillsProcess(): Unit = runBlocking {
        gate()
        check(!owner.exists()) { "Existing fixture ownership marker; refusing to seed" }
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            check(!File(database.databaseFile.path + suffix).exists()) { "Existing DB; refusing to seed" }
        }
        owner.writeText("synthetic-process-fixture-v1")
        val store = SqliteHistoryStore(context)
        store.replace(committed)
        launch { _, model ->
            val current = model()
            // Account-free callback fixture: use the actual opt-in and authorization-result
            // APIs, with only the external callback envelope supplied by instrumentation.
            // Neither beginAuthorization (Google launch) nor continueUpload is invoked.
            instrumentation.runOnMainSync { current.optIn(true) }
            assertTrue(current.state.value.uploadOptIn)
            val sessionField = SyncViewModel::class.java.getDeclaredField("historySession").apply { isAccessible = true }
            val session = sessionField.get(current) as HistorySession
            val ticket = runBlocking { checkNotNull(session.beginAuthorization(date, time)) }
            val ticketField = SyncViewModel::class.java.getDeclaredField("authorizationTicket").apply { isAccessible = true }
            val stateField = SyncViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(current) as kotlinx.coroutines.flow.MutableStateFlow<SyncUiState>
            instrumentation.runOnMainSync {
                ticketField.set(current, ticket)
                state.value = state.value.copy(pendingConsent = true)
                current.authorizationReturned(ticket, "synthetic-process-only-token", listOf(SyncViewModel.DRIVE_SCOPE))
            }
            compose.waitUntil(15_000) {
                current.state.value.readyToUpload && session.state.value.operation == HistoryOperation.IDLE
            }
            assertTrue(current.state.value.uploadOptIn)
            assertTrue(current.state.value.readyToUpload)
            assertEquals(ticket, current.authorizationTicket)
            val token = SyncViewModel::class.java.getDeclaredField("accessToken").apply { isAccessible = true }
            assertEquals("synthetic-process-only-token", token.get(current))
            compose.onNodeWithText("업로드 계속").performScrollTo().assertIsDisplayed()
            runBlocking {
                // Establish the independently selected crash fixture only after the live
                // Activity has proven nonempty volatile credentials/continuation state.
                if (kind() == "reset-after") {
                    store.replace(committed)
                    store.reset()
                } else store.replace(fixture())
                if (kind() in setOf("reset-before", "reset-empty-before")) {
                    // Real DELETE-journal transaction is open with a replaced empty envelope.
                    SqliteHistoryStore(database) { hold() }.reset()
                    error("The host must kill this process before commit")
                } else hold()
            }
        }
    }

    private fun hold(): Nothing {
        marker("HISTORY_READY")
        // A timeout is a failure, never an assertion-wait success. Host timeout is shorter.
        val until = android.os.SystemClock.elapsedRealtime() + 60_000
        while (android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        error("Host did not force-stop the fixture process within 60 seconds")
    }

    private fun launch(block: (ActivityScenario<MainActivity>, () -> SyncViewModel) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun model(): SyncViewModel {
                var result: SyncViewModel? = null
                scenario.onActivity { result = ViewModelProvider(it)[SyncViewModel::class.java] }
                return checkNotNull(result)
            }
            compose.waitUntil(15_000) {
                val state = model().state.value
                state.history?.operation == HistoryOperation.IDLE && !state.refreshing
            }
            block(scenario, ::model)
        }
    }

    private fun assertManual(model: SyncViewModel) {
        val state = model.state.value
        assertFalse(state.uploadOptIn)
        assertFalse(state.readyToUpload)
        assertFalse(state.pendingConsent)
        assertFalse(state.syncing)
        assertNull(model.authorizationTicket)
        assertNull(state.transientRemoteStatus)
        val token = SyncViewModel::class.java.getDeclaredField("accessToken").apply { isAccessible = true }
        assertNull(token.get(model))
        compose.onAllNodes(isToggleable())[0].performScrollTo().assertIsOff()
        compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
    }

    @Test fun reopenedActivityRestoresOnlyCommittedMetadata() {
        gate()
        assertOwned()
        assertNotEquals(args.getString("deadPid")!!.toInt(), Process.myPid())
        launch { _, model ->
            val expected = fixture().recover()
            assertEquals(expected, model().state.value.history!!.read.history)
            assertManual(model())
            runBlocking { assertEquals(expected, SqliteHistoryStore(context).load().history) }
            if (expected.success != null) {
                assertEquals(success.date, model().state.value.lastSuccessDate)
                assertEquals(success.confirmedAt, model().state.value.lastSuccess)
                assertEquals(success.coverage, model().state.value.lastSuccessCoverage)
                compose.onNodeWithText("확인된 파일 수: 7개").performScrollTo().assertIsDisplayed()
            } else compose.onNodeWithText("저장된 동기화 이력이 없습니다.").performScrollTo().assertIsDisplayed()
            val status = when (kind()) {
                "running" -> "실행 중 종료됨 · 업로드 결과를 확인할 수 없습니다."
                "authorizing" -> "인증 단계에서 종료됨 · 업로드 미시작"
                "awaiting" -> "업로드 시작 전 종료됨 · 다시 인증해 주세요."
                else -> null
            }
            status?.let { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            if (kind() in setOf("running", "authorizing", "awaiting")) {
                assertNull(expected.attempt!!.finishedAt)
                assertNull(expected.attempt!!.confirmedFiles)
                compose.onNodeWithText("상세·설정 펼치기").performScrollTo().performClick()
                compose.onNodeWithText("종료 시각: 확인 불가").performScrollTo().assertIsDisplayed()
                if (kind() != "running") compose.onNodeWithText("실행 시작 시각: 미시작").performScrollTo().assertIsDisplayed()
            }
            marker("HISTORY_REOPENED")
        }
    }

    @Test fun liveSystemThemeAndFontChangeReachActivityAndControls() {
        gate()
        assertOwned()
        val expectedNight = args.getString("night") == "yes"
        val expectedFont = args.getString("font")!!.toFloat()
        launch { scenario, model ->
            // Host starts at the opposite settings. Prove the Activity observes
            // them before allowing the host to issue the live configuration change.
            compose.waitUntil(20_000) {
                var initialMatches = false
                scenario.onActivity {
                    val initial = it.resources.configuration
                    initialMatches = (initial.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        (if (expectedNight) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES) &&
                        kotlin.math.abs(initial.fontScale - if (expectedFont == 1f) 2f else 1f) < 0.01f
                }
                initialMatches
            }
            scenario.onActivity {
                val initial = it.resources.configuration
                assertEquals("Initial system theme must be opposite before READY",
                    if (expectedNight) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES,
                    initial.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                assertEquals("Initial system font must be opposite before READY",
                    if (expectedFont == 1f) 2f else 1f, initial.fontScale, 0.01f)
            }
            marker("HISTORY_SETTINGS_READY")
            compose.waitUntil(20_000) {
                var matches = false
                scenario.onActivity {
                    val config = it.resources.configuration
                    matches = ((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        if (expectedNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO) &&
                        kotlin.math.abs(config.fontScale - expectedFont) < 0.01f
                }
                matches
            }
            compose.waitUntil(15_000) { model().state.value.history?.operation == HistoryOperation.IDLE && !model().state.value.refreshing }
            assertManual(model())
            compose.onNodeWithText("확인된 파일 수: 7개").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("상세·설정 펼치기").performScrollTo().performClick()
            compose.onNodeWithText("로컬 이력 삭제").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("이력 삭제").assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithText("취소").assertIsDisplayed().performClick()
            compose.onNodeWithText("Google 권한 확인").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val name = "v1-synthetic-process/system-${if (expectedNight) "dark" else "light"}-${expectedFont.toInt()}.png"
            val image = File(context.getExternalFilesDir(null), name)
            check(image.parentFile!!.mkdirs() || image.parentFile!!.isDirectory)
            image.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
    }

    @Test fun cleanupOwnedSyntheticFixture() {
        gate()
        assertOwned()
        check(SQLiteDatabase.deleteDatabase(database.databaseFile))
        check(owner.delete())
    }
}
