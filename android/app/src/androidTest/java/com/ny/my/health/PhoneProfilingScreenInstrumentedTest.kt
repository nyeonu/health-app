package com.ny.my.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ny.my.health.core.ProfileStage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 합성 상태만 사용하며 외부 효과는 복사 콜백 호출 수로 확인한다. */
@RunWith(AndroidJUnit4::class)
class PhoneProfilingScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val ui = mutableStateOf(PhoneProfilingUiState())
    private val dark = mutableStateOf(false)
    private val textScale = mutableStateOf(1f)
    private val fixtureKey = mutableStateOf(0)
    private var copyCalls = 0
    private val copyLabel = "안전한 진단 요약 복사"
    private val report = PhoneProfilingReport(
        ProfileStage.entries.map { PhoneProfilingRow(it, "12.345 ms") },
        "합성 안전 요약",
    )

    private fun render(state: PhoneProfilingUiState = PhoneProfilingUiState(enabled = true, report = report)) {
        ui.value = state
        restoration.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, textScale.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        key(fixtureKey.value) {
                            Column(Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState())) {
                                PhoneProfilingSection(ui.value) { copyCalls++ }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shown(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun open() = click("성능 진단 보기")

    @Test fun opensDiagnosticsExplicitlyAndCallsCopyOnlyOnClick() {
        render()
        shown("성능 진단 보기")
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.onNodeWithText("건강 자료 읽기: 12.345 ms").assertDoesNotExist()
        open()
        listOf("인증 요청 이력 저장", "인증 대기", "업로드 계속 대기", "전체 실행", "실행 시작 이력 저장",
            "건강 자료 읽기", "자료 정형", "CSV 생성", "Drive 처리 전체", "종료 이력 저장").forEach {
            shown("$it: 12.345 ms")
        }
        assertEquals(0, copyCalls)
        compose.onNodeWithText(copyLabel).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, copyCalls)
        click("성능 진단 접기")
        open()
        assertEquals(1, copyCalls)
    }

    @Test fun missingAndCollectingDiagnosticsNeverShowPreviousMeasurementsOrEnableCopy() {
        render()
        open()
        shown("건강 자료 읽기: 12.345 ms")
        compose.runOnIdle { ui.value = PhoneProfilingUiState(enabled = true) }
        shown("표시할 진단이 없습니다.")
        compose.onNodeWithText("건강 자료 읽기: 12.345 ms").assertDoesNotExist()
        compose.onNodeWithText(copyLabel).performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle {
            ui.value = PhoneProfilingUiState(enabled = true, collecting = true, report = report, copyState = PhoneCopyState.COPIED)
        }
        shown("진단 수집 중… 이전 측정은 표시하지 않습니다.")
        compose.onNodeWithText("건강 자료 읽기: 12.345 ms").assertDoesNotExist()
        compose.onNodeWithText("진단 요약을 복사했습니다.").assertDoesNotExist()
        compose.onNodeWithText(copyLabel).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, copyCalls)
    }

    @Test fun nonDebugDiagnosticsExposeNoViewOrCopyControls() {
        render(PhoneProfilingUiState(enabled = false, report = report))
        compose.onNodeWithText("성능 진단 보기").assertDoesNotExist()
        compose.onNodeWithText("최근 수동 시도 성능 진단").assertDoesNotExist()
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        assertEquals(0, copyCalls)
    }

    @Test fun restorationDoesNotRestoreDiagnosticPayload() {
        render()
        open()
        shown("건강 자료 읽기: 12.345 ms")
        compose.runOnIdle { ui.value = PhoneProfilingUiState(enabled = true) }
        restoration.emulateSavedInstanceStateRestore()
        shown("성능 진단 접기")
        shown("표시할 진단이 없습니다.")
        compose.onNodeWithText("건강 자료 읽기: 12.345 ms").assertDoesNotExist()
        compose.onNodeWithText(copyLabel).performScrollTo().assertIsNotEnabled()
        assertEquals(0, copyCalls)
    }

    @Test fun diagnosticsExplainSafeSharingAndSeparateClipboardLifetime() {
        render()
        for (useDark in listOf(false, true)) {
            for (scale in listOf(1f, 2f)) {
                compose.runOnIdle {
                    dark.value = useDark
                    textScale.value = scale
                    fixtureKey.value++
                }
                compose.onNodeWithText("성능 진단 보기").performScrollTo().assertIsDisplayed().assertHasClickAction()
                open()
                shown("복사 요약에는 고정 단계·작업 분류와 시간·호출 횟수만 포함됩니다. 건강 값·건강 기록 건수·날짜·계정·식별자·원시 오류는 포함하지 않습니다.")
                shown("앱의 진단은 메모리에만 보관됩니다. 앱에서 진단을 지워도 이미 복사한 시스템 클립보드는 별도로 남을 수 있습니다. 공유 전 내용을 확인해 주세요.")
                shown("종료 이력 저장: 12.345 ms")
                compose.onNodeWithText(copyLabel).performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                assertEquals(0, copyCalls)
            }
        }
        click(copyLabel)
        assertEquals(1, copyCalls)
        compose.runOnIdle { ui.value = ui.value.copy(copyState = PhoneCopyState.COPIED) }
        shown("진단 요약을 복사했습니다.")
        compose.runOnIdle { ui.value = ui.value.copy(copyState = PhoneCopyState.FAILED) }
        shown("진단 요약을 복사하지 못했습니다. 다시 눌러 주세요.")
        assertEquals(1, copyCalls)
    }
}
