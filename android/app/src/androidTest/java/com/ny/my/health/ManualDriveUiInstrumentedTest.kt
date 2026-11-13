package com.ny.my.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.CoverageSummary
import com.ny.my.health.core.DataType
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 합성 상태·콜백만 사용한다. 실제 계정·권한·TalkBack 수용 검증은 별도다. */
@RunWith(AndroidJUnit4::class)
class ManualDriveUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualConsentPickerAuthorizationAndExplicitContinueRemainReachableInLightDarkAndLargeText() {
        val state = mutableStateOf(ManualDriveState(foreground = true, grantedCategories = setOf(DataType.STEPS)))
        val selected = mutableStateOf(DataType.entries.toSet())
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val fixture = mutableStateOf(0)
        val calls = mutableListOf<String>()
        val today = LocalDate.parse("2026-01-04")
        val enrolled = LocalDate.parse("2025-12-31")
        val older = today.minusDays(2)
        val yesterday = today.minusDays(1)
        val actions = ManualDriveActions(
            changeConsent = { calls += "consent"; state.value = state.value.copy(privateFolderConsent = it) },
            changeCategories = { calls += "categories"; selected.value = it },
            requestHealthConsent = { calls += "health" }, refreshGrants = { calls += "grants" },
            selectAccount = { calls += "picker"; state.value = state.value.copy(configured = true, categories = selected.value, phase = ManualDrivePhase.AUTHORIZATION_REQUIRED) },
            authorize = { calls += "authorize"; state.value = state.value.copy(phase = ManualDrivePhase.READY) },
            continueManual = { calls += "continue" }, resetConfiguration = { calls += "reset" },
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        key(fixture.value) {
                            Column(Modifier.verticalScroll(rememberScrollState())) { ManualDriveUi(state.value, actions, selected.value, today) }
                        }
                    }
                }
            }
        }
        fun shown(label: String) { compose.onNodeWithText(label).performScrollTo().assertIsDisplayed() }
        fun button(label: String) = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        for (isDark in listOf(false, true)) for (font in listOf(1f, 1.8f)) {
            compose.runOnIdle {
                dark.value = isDark; scale.value = font; fixture.value++
                state.value = ManualDriveState(foreground = true, grantedCategories = setOf(DataType.STEPS))
                selected.value = DataType.entries.toSet(); calls.clear()
            }
            shown("미구성 · 업로드에 동의하고 Google 계정을 선택해 주세요.")
            shown("최근 대상 날짜: 확인되지 않음 · 구성을 확인해 주세요.")
            shown("마지막 V1 업로드 확인 시각: 확인되지 않음")
            button("Google 계정 선택").assertIsNotEnabled()
            button("Google 권한 확인").assertIsNotEnabled()
            button("업로드 계속").assertIsNotEnabled()
            for (label in listOf("Health Connect 권한 요청", "권한 다시 확인")) button(label).assertIsEnabled().performClick()
            for (name in listOf("걸음", "체중", "심박", "수면", "운동")) {
                compose.onNodeWithContentDescription("동기화 항목: $name").performScrollTo().assertIsDisplayed().assertIsOn()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            }
            val weight = compose.onNodeWithContentDescription("동기화 항목: 체중")
            weight.performScrollTo().performClick().assertIsOff()
            weight.performClick().assertIsOn()
            compose.onNodeWithContentDescription("V1 비공개 Drive 업로드 동의").performScrollTo().assertIsOff().performClick().assertIsOn()
            button("Google 계정 선택").assertIsEnabled().performClick()
            shown("읽기 권한: 1/5개 · 선택 범위: 5/5개")
            button("Google 권한 확인").assertIsEnabled().performClick()
            shown("Google 권한 확인됨 · 업로드 계속을 눌러 주세요.")
            compose.runOnIdle { assertEquals(listOf("health", "grants", "categories", "categories", "consent", "picker", "authorize"), calls) }
            button("업로드 계속").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals("continue", calls.last()) }
            weight.performScrollTo().performClick().assertIsOff()
            shown("선택 항목이 저장된 구성과 다릅니다. Google 계정 변경에서 계정을 선택해야 변경 범위가 적용됩니다.")
            button("Google 권한 확인").assertIsNotEnabled()
            button("업로드 계속").assertIsNotEnabled()
            weight.performScrollTo().performClick().assertIsOn()
            compose.runOnIdle { state.value = state.value.copy(phase = ManualDrivePhase.BLOCKED, outcome = BatchStatus.BLOCKED) }
            shown("동기화가 차단되었습니다. 권한·계정을 확인하고 다시 인증한 뒤 계속해 주세요.")
            button("Google 권한 확인").assertIsEnabled().performClick()
            shown("Google 권한 확인됨 · 업로드 계속을 눌러 주세요.")
            shown("이전 시도는 완료되지 않았습니다. 새 인증은 날짜별 차단·누락이 해결되었다는 뜻이 아닙니다.")
            button("업로드 계속").assertIsEnabled()
            button("V1 구성·확인 범위 초기화").performClick()
            compose.onNodeWithText("초기화 취소").assertIsDisplayed().assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(false, calls.contains("reset")) }
            button("V1 구성·확인 범위 초기화").performClick()
            compose.onNodeWithText("이 기기의 V1 목적지 구성과 날짜별 확인 범위만 지웁니다. 이전 동기화 이력과 Drive 파일은 삭제하지 않으며 Health Connect·Google 권한은 철회하지 않습니다. 다시 동의하고 계정을 선택해야 합니다.").assertIsDisplayed()
            compose.onNodeWithText("V1 초기화 확인").assertIsDisplayed().assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(1, calls.count { it == "reset" }); state.value = state.value.copy(grantedCategories = emptySet()) }
            button("Google 권한 확인").assertIsNotEnabled()
            button("업로드 계속").assertIsNotEnabled()
            button("Google 계정 변경").assertIsNotEnabled()
            compose.runOnIdle {
                state.value = state.value.copy(grantedCategories = setOf(DataType.STEPS), phase = ManualDrivePhase.AUTHORIZATION_REQUIRED,
                    outcome = BatchStatus.COMPLETED, enrolledDay = enrolled,
                    coverage = CoverageSummary(null, null, null, enrolled, null,
                        emptySet(), 0, 0, null, setOf(older), emptySet()))
            }
            shown("현재 범위 확인 완료 · 업로드 확인은 없습니다. 빈 데이터는 업로드 성공이 아닙니다.")
            shown("최근 대상 $older · 읽기 확인됨 · 업로드 미확인")
            shown("최근 대상 $yesterday · 읽기 미확인 · 업로드 미확인")
            shown("마지막 V1 업로드 확인 시각: 확인되지 않음")
            compose.runOnIdle { state.value = state.value.copy(coverage = state.value.coverage!!.copy(
                scopeUploadedThrough = enrolled, excludedTypes = setOf(DataType.WEIGHT),
                lastUploadAt = Instant.parse("2026-01-03T18:04:05Z"),
                recentCheckedDays = setOf(older, yesterday), recentUploadedDays = setOf(older, yesterday))) }
            shown("허용된 일부 범위만 확인되었습니다. 전체 항목 완료가 아닙니다.")
            shown("이전 V1 업로드 확인이 있습니다. 현재 누락·차단·최신성과 별도로 확인하세요.")
            shown("마지막 V1 업로드 확인 시각: 2026-01-04 03:04:05 (한국 시간)")
            shown("현재 목적지 구성의 모든 범위 중 마지막 업로드 확인 시각입니다. 현재 범위를 다시 확인한 시각은 아닙니다.")
            shown("최근 대상 날짜 · 현재 허용 범위 (제외 항목 수: 1)")
            shown("최근 대상 $older · 읽기 확인됨 · 업로드 확인됨")
            shown("최근 대상 $yesterday · 읽기 확인됨 · 업로드 확인됨")
            shown("최근 대상 날짜의 읽기·업로드 확인 기록이며 오늘 다시 갱신되었다는 뜻은 아닙니다. 빈 데이터는 읽기 확인만 있고 업로드 확인은 없을 수 있습니다.")
            // 연속 확인 날짜와 마지막 업로드 시각이 같아도 최근 날짜별 증거는 다를 수 있다.
            compose.runOnIdle { state.value = state.value.copy(coverage = state.value.coverage!!.copy(
                recentCheckedDays = emptySet(), recentUploadedDays = emptySet())) }
            shown("현재 범위 확인 날짜: 2025-12-31")
            shown("현재 범위 업로드 날짜: 2025-12-31")
            shown("마지막 V1 업로드 확인 시각: 2026-01-04 03:04:05 (한국 시간)")
            shown("최근 대상 $older · 읽기 미확인 · 업로드 미확인")
            shown("최근 대상 $yesterday · 읽기 미확인 · 업로드 미확인")
            compose.runOnIdle { state.value = state.value.copy(outcome = BatchStatus.STALE) }
            shown("현재 결과가 오래되었거나 구성이 바뀌었습니다. 다시 확인하고 인증해 주세요.")
            shown("최근 대상 $yesterday · 읽기 미확인 · 업로드 미확인")
            compose.runOnIdle { state.value = state.value.copy(enrolledDay = yesterday) }
            shown("최근 대상 $yesterday · 읽기 미확인 · 업로드 미확인")
            compose.onNodeWithText("최근 대상 $older · 읽기 미확인 · 업로드 미확인").assertDoesNotExist()
            shown("자동 동기화와 SAF 저장은 이 화면에서 지원하지 않습니다.")
        }
    }
}
