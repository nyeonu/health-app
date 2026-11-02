package com.ny.my.health

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.health.connect.client.HealthConnectClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ny.my.health.core.DataType
import com.ny.my.health.core.HistoryAttempt
import com.ny.my.health.core.HistoryPhase
import com.ny.my.health.core.HistoryRead
import com.ny.my.health.core.HistoryReadStatus
import com.ny.my.health.core.HistorySuccess
import com.ny.my.health.core.SyncHistory
import com.ny.my.health.core.SyncStatus
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 계정과 실제 건강 데이터 없이 화면의 표시와 명시적 사용자 이벤트만 검증한다. */
@RunWith(AndroidJUnit4::class)
class HealthHomeScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val date = LocalDate.parse("2026-01-02")
    private val requested = Instant.parse("2026-01-03T00:00:00Z")
    private val started = requested.plusSeconds(60)
    private val finished = requested.plusSeconds(120)
    private val success = HistorySuccess(date.minusDays(1), requested.minusSeconds(86400), setOf(DataType.WEIGHT), 7)
    private val calls = mutableListOf<String>()
    private val ui = mutableStateOf(SyncUiState())
    private val dark = mutableStateOf(false)
    private val textScale = mutableStateOf(1f)
    private val fixtureKey = mutableStateOf(0)

    private fun session(attempt: HistoryAttempt? = null, previous: HistorySuccess? = success) = HistorySessionState(
        HistoryRead(HistoryReadStatus.READY, SyncHistory(1, attempt, previous)),
        HistoryOperation.IDLE, false, false, false, null,
    )

    private fun attempt(phase: HistoryPhase = HistoryPhase.FAILED) = HistoryAttempt(
        date, requested, started, finished, phase, setOf(DataType.STEPS, DataType.SLEEP), 2,
        if (phase == HistoryPhase.FAILED) "UPLOAD_FAILED" else null,
    )

    private fun render(history: HistorySessionState? = session(attempt()), state: SyncUiState = SyncUiState()) {
        ui.value = state.copy(date = date, availability = HealthConnectClient.SDK_AVAILABLE, history = history)
        val actions = HistoryUiActions(
            requestHealthConsent = { calls += "health" }, refreshPermissions = { calls += "permissions" },
            changeOptIn = { calls += "optIn"; ui.value = ui.value.copy(uploadOptIn = it) }, authorize = { calls += "authorize" },
            continueUpload = { calls += "upload" }, requestDelete = {
                calls += "requestDelete"
                ui.value = ui.value.copy(history = ui.value.history!!.copy(deleteDialog = true))
            }, cancelDelete = {
                calls += "cancelDelete"
                ui.value = ui.value.copy(history = ui.value.history!!.copy(deleteDialog = false))
            }, confirmDelete = { calls += "confirmDelete" }, retryDelete = { calls += "retryDelete" },
            keepExisting = { calls += "keepExisting" }, reload = { calls += "reload" },
        )
        restoration.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, textScale.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        key(fixtureKey.value) { HealthHomeScreen(ui.value, actions) }
                    }
                }
            }
        }
    }

    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun details() = click("상세·설정 펼치기")
    private fun shown(text: String) { compose.onNodeWithText(text).performScrollTo().assertIsDisplayed() }

    @Test fun detailsDistinguishAuthorizationExecutionAndResultTimesWithPartialScope() {
        render()
        details()
        shown("인증 요청 시각: 2026-01-03 09:00:00 (한국 시간)")
        shown("실행 시작 시각: 2026-01-03 09:01:00 (한국 시간)")
        shown("종료 시각: 2026-01-03 09:02:00 (한국 시간)")
        shown("최근 시도 허용 항목: 걸음, 수면")
        shown("오류 분류: UPLOAD_FAILED")
    }

    @Test fun restoredSuccessIsLabelledHistoricalAndNeverBypassesManualExecution() {
        render(session(previous = success))
        shown("이 기기의 과거 기록이며 현재 계정·원본 전체의 완료 여부는 확인되지 않았습니다.")
        compose.onNodeWithText("Google 권한 확인").assertIsNotEnabled()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun collapsedHomeShowsCurrentErrorPriorSuccessTargetDayAndNextAction() {
        render(session(attempt()).copy(error = HistoryError.WRITE))
        shown("이번 동기화 대상: 어제, 2026-01-02 (한국 시간)")
        shown("이력 저장에 실패했습니다. 다시 불러오거나 로컬 이력을 삭제해 주세요.")
        shown("대상 날짜: 2026-01-01")
        shown("다시 불러오기")
        compose.onNodeWithText("인증 요청 시각:", substring = true).assertDoesNotExist()
    }

    @Test fun successCardCountDateTimeAndCoverageComeFromOneCommittedSuccess() {
        render(history = session(attempt()).copy(error = HistoryError.WRITE), state = SyncUiState(uploadedFiles = 99, transientConfirmedFiles = 88, transientRemoteStatus = SyncStatus.SUCCESS,
            lastSuccessDate = date.plusDays(1), lastSuccess = finished, lastSuccessCoverage = setOf(DataType.EXERCISE)))
        shown("업로드는 확인됐지만 이력 저장에 실패했습니다.")
        shown("대상 날짜: 2026-01-01")
        shown("확인 시각: 2026-01-02 09:00:00 (한국 시간)")
        shown("당시 허용 항목: 체중")
        shown("확인된 파일 수: 7개")
        compose.onNodeWithText("확인된 파일 수: 88개").assertDoesNotExist()
        compose.onNodeWithText("확인된 파일 수: 99개").assertDoesNotExist()
    }

    @Test fun detailsRenderMissingExecutionAndTerminalTimesWithoutInventedTimestamps() {
        render(session(attempt(HistoryPhase.UPLOAD_NOT_STARTED).copy(startedAt = null, finishedAt = null, confirmedFiles = null)))
        details()
        shown("실행 시작 시각: 미시작")
        shown("종료 시각: 확인 불가")
        shown("최근 시도에서 확인된 업로드: 확인되지 않음")
    }

    @Test fun latestFailureAndEmptyOutcomeRemainDistinctFromPriorSuccessfulUpload() {
        render()
        shown("업로드 실패 · 일부 파일이 저장됐을 수 있습니다.")
        shown("확인된 파일 수: 7개")
        compose.runOnIdle { ui.value = ui.value.copy(history = session(attempt(HistoryPhase.EMPTY).copy(confirmedFiles = 0))) }
        shown("허용된 항목에 업로드할 데이터가 없습니다.")
        shown("확인된 파일 수: 7개")
    }

    @Test fun expandingDetailsOrRestoringUiNeverAuthorizesReadsOrUploads() {
        render(session(attempt(HistoryPhase.INTERRUPTED).copy(finishedAt = null, confirmedFiles = null)))
        shown("실행 중 종료됨 · 업로드 결과를 확인할 수 없습니다.")
        details()
        click("상세·설정 접기")
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun cancellingDeleteConfirmationPreservesCredentialsAndDurableHistory() {
        render(state = SyncUiState(uploadOptIn = true, readyToUpload = true))
        details()
        click("로컬 이력 삭제")
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle {
            assertEquals(true, ui.value.uploadOptIn)
            assertEquals(true, ui.value.readyToUpload)
            assertEquals(success, ui.value.history!!.read.history!!.success)
            assertEquals(listOf("requestDelete", "cancelDelete"), calls)
        }
        shown("확인된 파일 수: 7개")
    }

    @Test fun busySessionsRejectDeleteRequestsAndExplainDisabledDeletion() {
        render(state = SyncUiState(syncing = true))
        details()
        compose.onNodeWithText("로컬 이력 삭제").performScrollTo().assertIsNotEnabled()
        shown("작업 중에는 로컬 이력을 삭제할 수 없습니다. 작업이 끝난 뒤 다시 시도해 주세요.")
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun failedDeletionOffersRetryWithoutClaimingCompletionOrRestoringCredentials() {
        render(session().copy(deleteFailed = true, retainedAfterDeleteFailure = true, error = HistoryError.DELETE))
        shown("이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.")
        click("삭제 다시 시도")
        compose.runOnIdle {
            assertEquals(false, ui.value.uploadOptIn)
            assertEquals(false, ui.value.readyToUpload)
            assertEquals(listOf("retryDelete"), calls)
        }
        compose.onNodeWithText("Google 권한 확인").assertIsNotEnabled()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
    }

    @Test fun failedDeletionLabelsRetainedHistoryWhileUnreadableHistoryRemainsUnknown() {
        render(session().copy(deleteFailed = true, retainedAfterDeleteFailure = true, error = HistoryError.DELETE))
        shown("삭제되지 않은 기존 이력")
        click("기존 이력 유지 · 다시 확인")
        assertEquals(listOf("keepExisting"), calls)
        compose.runOnIdle { ui.value = ui.value.copy(history = session().copy(
            read = HistoryRead(HistoryReadStatus.CORRUPT, null), deleteFailed = true,
            retainedAfterDeleteFailure = false, error = HistoryError.DELETE)) }
        shown("기존 로컬 이력의 보존 여부를 확인할 수 없습니다.")
        shown("과거 업로드 이력을 확인할 수 없습니다.")
        compose.onNodeWithText("확인된 파일 수: 7개").assertDoesNotExist()
    }

    @Test fun deleteConfirmationStatesLocalOnlyScopeAndTriggersNoExternalDeletion() {
        render()
        details()
        click("로컬 이력 삭제")
        compose.onNodeWithText("이 기기의 동기화 이력과 현재 업로드 동의를 지웁니다. Drive 파일과 Health Connect·Google 권한은 유지됩니다.").assertIsDisplayed()
        compose.onNodeWithText("이력 삭제").performClick()
        assertEquals(listOf("requestDelete", "confirmDelete"), calls)
    }

    @Test fun initialAndLoadingHistoryShowLoadingUntilLocalReadCompletes() {
        render(history = null)
        shown("로컬 이력 불러오는 중…")
        shown("과거 업로드 이력을 불러오고 있습니다.")
        compose.onNodeWithText("저장된 동기화 이력이 없습니다.").assertDoesNotExist()
        compose.onNodeWithText("이력을 읽을 수 없습니다. 다시 불러오거나 로컬 이력을 삭제해 주세요.").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(history = session(previous = null).copy(
            read = HistoryRead(HistoryReadStatus.UNAVAILABLE, null), operation = HistoryOperation.LOADING)) }
        shown("로컬 이력 불러오는 중…")
        compose.onNodeWithText("이력을 읽을 수 없습니다. 다시 불러오거나 로컬 이력을 삭제해 주세요.").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(history = session(previous = null)) }
        shown("저장된 동기화 이력이 없습니다.")
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun pendingPermissionAndConsentOutrankHistoricalSuccessfulPhase() {
        render(history = session(attempt(HistoryPhase.SUCCESS)), state = SyncUiState(pendingConsent = true))
        shown("동의 결과 대기 중…")
        compose.onNodeWithText("최근 시도의 업로드가 확인되었습니다.").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(pendingConsent = false, refreshing = true) }
        shown("현재 권한 확인 중…")
        compose.onNodeWithText("최근 시도의 업로드가 확인되었습니다.").assertDoesNotExist()
        shown("확인된 파일 수: 7개")
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun unreadableAndLoadingExecutionStartRemainsUnknownWhileKnownUnstartedHistoryShowsNotStarted() {
        render(history = session().copy(read = HistoryRead(HistoryReadStatus.CORRUPT, null), error = HistoryError.READ))
        details()
        for (status in listOf(HistoryReadStatus.CORRUPT, HistoryReadStatus.UNAVAILABLE, HistoryReadStatus.UNSUPPORTED)) {
            compose.runOnIdle { ui.value = ui.value.copy(history = session().copy(
                read = HistoryRead(status, null), error = HistoryError.READ)) }
            shown("실행 시작 시각: 확인 불가")
            shown("종료 시각: 확인 불가")
            compose.onNodeWithText("실행 시작 시각: 미시작").assertDoesNotExist()
            compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("실행 시작 시각: 2026", substring = true).assertDoesNotExist()
        }
        compose.runOnIdle { ui.value = ui.value.copy(history = session(previous = null).copy(
            read = HistoryRead(HistoryReadStatus.UNAVAILABLE, null), operation = HistoryOperation.LOADING)) }
        shown("실행 시작 시각: 확인 불가")
        compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(history = session(
            attempt(HistoryPhase.UPLOAD_NOT_STARTED).copy(startedAt = null, finishedAt = null, confirmedFiles = null), previous = null)) }
        shown("실행 시작 시각: 미시작")
        shown("종료 시각: 확인 불가")
        compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun privateDriveConsentCheckboxHasAccessibleNameAndExplicitClickUpdatesCheckedState() {
        render(history = session(previous = null))
        val consent = compose.onNodeWithContentDescription("비공개 Drive 업로드 동의")
        consent.performScrollTo().assertHasClickAction().assertIsOff().performClick()
        consent.assertIsOn()
        compose.runOnIdle { assertEquals(listOf("optIn"), calls) }
    }

    @Test fun localReloadFromUnreadableHistoryInvokesNoExternalEffects() {
        render(session().copy(read = HistoryRead(HistoryReadStatus.UNAVAILABLE, null), error = HistoryError.READ))
        shown("이력을 읽을 수 없습니다. 다시 불러오거나 로컬 이력을 삭제해 주세요.")
        click("다시 불러오기")
        assertEquals(listOf("reload"), calls)
    }

    /** 실제 TalkBack 서비스 대신 이름·역할·스크롤 도달성을 검사한다. */
    @Test fun bothThemesAtDoubleTextScaleKeepManualAndRecoveryControlsNamedAndReachable() {
        render(history = session(previous = null), state = SyncUiState(uploadOptIn = true, readyToUpload = true))
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark; textScale.value = 2f; fixtureKey.value++ }
            for (label in listOf("Health Connect 권한 요청", "Google 권한 확인", "업로드 계속", "상세·설정 펼치기")) {
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
            compose.onNodeWithContentDescription("비공개 Drive 업로드 동의").performScrollTo()
                .assertIsDisplayed().assertHasClickAction().assertIsOn()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            details()
            for (label in listOf("권한 다시 확인", "다시 불러오기", "로컬 이력 삭제")) {
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
            click("로컬 이력 삭제")
            for (label in listOf("취소", "이력 삭제")) {
                compose.onNodeWithText(label).assertIsDisplayed().assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
            compose.onNodeWithText("취소").performClick()
        }
        assertEquals(listOf("requestDelete", "cancelDelete", "requestDelete", "cancelDelete"), calls)
    }

    /** 고정 합성 메타데이터만 PNG로 내보내며 앱 저장소나 화면 외부는 수집하지 않는다. */
    @Test fun syntheticGalleryCapturesSevenStatesInBothThemesAndDoubleTextScale() {
        render(history = session(previous = null))
        val fixtures = listOf(
            Triple("no-history", session(previous = null), SyncUiState()),
            Triple("success", session(attempt(HistoryPhase.SUCCESS)), SyncUiState()),
            Triple("authorization", session(attempt(HistoryPhase.AUTHORIZING).copy(startedAt = null, finishedAt = null, confirmedFiles = null)), SyncUiState(pendingConsent = true)),
            Triple("running", session(attempt(HistoryPhase.RUNNING).copy(finishedAt = null, confirmedFiles = null)), SyncUiState(syncing = true)),
            Triple("interrupted", session(attempt(HistoryPhase.INTERRUPTED).copy(finishedAt = null, confirmedFiles = null)), SyncUiState()),
            Triple("read-failure", session(previous = null).copy(read = HistoryRead(HistoryReadStatus.UNAVAILABLE, null), error = HistoryError.READ), SyncUiState()),
            Triple("delete-failure", session().copy(deleteFailed = true, retainedAfterDeleteFailure = true, error = HistoryError.DELETE), SyncUiState()),
        )
        for (isDark in listOf(false, true)) for (scale in listOf(1f, 2f)) for ((name, history, state) in fixtures) {
            compose.runOnIdle {
                dark.value = isDark; textScale.value = scale; fixtureKey.value++
                ui.value = state.copy(date = date, availability = HealthConnectClient.SDK_AVAILABLE, history = history)
            }
            compose.waitForIdle()
            val prefix = "$name-${if (isDark) "dark" else "light"}-${if (scale == 2f) "text2x" else "text1x"}"
            captureSynthetic("$prefix-top")
            val action = when (name) {
                "read-failure" -> "다시 불러오기"
                "delete-failure" -> "삭제 다시 시도"
                else -> "상세·설정 펼치기"
            }
            compose.onNodeWithText(action).performScrollTo().assertIsDisplayed().assertHasClickAction()
            captureSynthetic("$prefix-action")
            if (name == "running") shown("화면을 벗어나면 중단됩니다")
            if (name == "interrupted") shown("실행 중 종료됨 · 업로드 결과를 확인할 수 없습니다.")
        }
        assertEquals(emptyList<String>(), calls)
    }

    private fun captureSynthetic(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("v1-synthetic-ui/$name.png").use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "합성 PNG 저장 실패" }
        }
    }

}
