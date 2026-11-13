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
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.DestinationGeneration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 합성 메타데이터와 콜백만 검증한다. 실제 권한·폴더·TalkBack 검증은 별도다. */
@RunWith(AndroidJUnit4::class)
class SafManualUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun safPrivacyTreeContinueRetryAndResetRemainReachableInLightDarkAndLargeTextWithoutUriDisclosure() {
        val state = mutableStateOf(ManualSafState())
        val dark = mutableStateOf(false)
        val font = mutableStateOf(1f)
        val fixture = mutableStateOf(0)
        val calls = mutableListOf<String>()
        val resets = mutableListOf<DestinationGeneration>()
        val generation = DestinationGeneration("synthetic-generation-one")
        val nextGeneration = DestinationGeneration("synthetic-generation-two")
        val all = DataType.entries.toSet()
        val today = LocalDate.now(DayWindow.zone)
        val older = today.minusDays(2)
        val yesterday = today.minusDays(1)
        val ready = ManualSafState(phase = ManualSafPhase.READY, generation = generation,
            configured = true, foreground = true, privacyConsent = true,
            intendedCategories = all, categories = all, grantedCategories = setOf(DataType.STEPS), enrolledDay = older)
        val actions = SafManualActions(
            changeConsent = { calls += "consent"; state.value = state.value.copy(privacyConsent = it) },
            changeCategories = { calls += "categories"; state.value = state.value.copy(intendedCategories = it) },
            requestHealthConsent = { calls += "health" }, refreshGrants = { calls += "grants" },
            selectTree = { calls += "tree" }, continueManual = { calls += "continue" },
            retry = { calls += "retry" }, resetConfiguration = { resets += it },
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        key(fixture.value) {
                            Column(Modifier.verticalScroll(rememberScrollState())) { SafManualUi(state.value, actions) }
                        }
                    }
                }
            }
        }
        fun shown(text: String) { compose.onNodeWithText(text).performScrollTo().assertIsDisplayed() }
        fun button(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        fun checkbox(text: String) = compose.onNodeWithContentDescription(text).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        fun replace(value: ManualSafState) { compose.runOnIdle { state.value = value } }
        for (isDark in listOf(false, true)) for (scale in listOf(1f, 1.8f)) {
            compose.runOnIdle {
                dark.value = isDark; font.value = scale; fixture.value++
                state.value = ManualSafState(foreground = true); calls.clear(); resets.clear()
            }
            shown("저장 목적지: 선택한 로컬 폴더")
            shown("미구성 · 저장 동의와 항목 선택 후 로컬 폴더를 선택해 주세요.")
            for (name in listOf("걸음", "체중", "심박", "수면", "운동")) checkbox("로컬 동기화 항목: $name").assertIsOff().assertIsEnabled()
            checkbox("로컬 폴더 저장 동의").assertIsOff()
            button("로컬 폴더 선택").assertIsNotEnabled()
            button("로컬 업로드 계속").assertIsNotEnabled()
            button("로컬 동기화 재시도").assertIsNotEnabled()
            button("로컬 구성·확인 범위 초기화").assertIsNotEnabled()
            // 최초 구성·테마 변경·상태 교체 자체는 외부 동작을 요청하지 않는다.
            replace(ready)
            shown("선택 범위: 5/5개 · 저장된 구성: 5/5개 · 현재 읽기 권한: 1/5개")
            for (name in listOf("걸음", "체중", "심박", "수면", "운동")) checkbox("로컬 동기화 항목: $name").assertIsOn()
            compose.runOnIdle { assertEquals(emptyList<String>(), calls); assertEquals(emptyList<DestinationGeneration>(), resets) }
            button("Health Connect 권한 요청").performClick()
            button("권한 다시 확인").performClick()
            checkbox("로컬 동기화 항목: 체중").performClick().assertIsOff()
            button("로컬 업로드 계속").assertIsNotEnabled()
            button("로컬 동기화 재시도").assertIsNotEnabled()
            shown("선택 범위가 저장된 구성과 다릅니다. 폴더를 다시 선택해 전체 선택 범위를 적용해 주세요.")
            checkbox("로컬 동기화 항목: 체중").performClick().assertIsOn()
            checkbox("로컬 폴더 저장 동의").performClick().assertIsOff()
            button("로컬 폴더 선택").assertIsNotEnabled()
            button("로컬 업로드 계속").assertIsNotEnabled()
            checkbox("로컬 폴더 저장 동의").performClick().assertIsOn()
            shown("선택한 로컬 폴더에 건강 CSV를 저장·갱신하는 데 동의합니다. 폴더에 접근하는 다른 앱이나 사람이 파일을 볼 수 있으며 기기·폴더 백업에도 포함될 수 있습니다.")
            button("로컬 폴더 선택").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf("health", "grants", "categories", "categories", "consent", "consent", "tree"), calls) }
            button("로컬 업로드 계속").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(1, calls.count { it == "continue" }); calls.clear() }
            for (blocked in listOf(
                ready.copy(intendedCategories = emptySet()), ready.copy(grantedCategories = emptySet()),
                ready.copy(privacyConsent = false), ready.copy(foreground = false),
                ready.copy(phase = ManualSafPhase.RUNNING), ready.copy(categories = setOf(DataType.WEIGHT)),
                ready.copy(generation = null), ready.copy(configured = false),
            )) {
                replace(blocked)
                button("로컬 업로드 계속").assertIsNotEnabled()
                button("로컬 동기화 재시도").assertIsNotEnabled()
            }
            replace(ready.copy(foreground = false))
            button("Health Connect 권한 요청").assertIsNotEnabled()
            button("권한 다시 확인").assertIsNotEnabled()
            checkbox("로컬 폴더 저장 동의").assertIsNotEnabled()
            checkbox("로컬 동기화 항목: 걸음").assertIsNotEnabled()
            val coverage = CoverageSummary(null, null, null, older, null, setOf(DataType.WEIGHT),
                1, 1, Instant.parse("2026-01-03T18:04:05Z"), setOf(older), emptySet())
            replace(ready.copy(outcome = BatchStatus.COMPLETED, coverage = coverage))
            shown("현재 범위에 확인이 끝나지 않은 날짜가 남아 있습니다.")
            shown("과거 업로드 확인이 있습니다. 현재 대기·차단·오래된 결과와 별개입니다.")
            shown("현재 범위 대기 날짜: 1 · 차단 날짜: 1")
            shown("전체 항목 확인 날짜: 확인되지 않음")
            shown("현재 범위 확인 날짜: $older")
            shown("최근 대상 $older · 읽기 확인됨 · 업로드 미확인")
            shown("최근 대상 $yesterday · 읽기 미확인 · 업로드 미확인")
            button("로컬 동기화 재시도").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf("retry"), calls); calls.clear() }
            for ((outcome, label) in listOf(
                BatchStatus.BLOCKED to "로컬 동기화 차단 · 권한과 구성을 확인해 주세요.",
                BatchStatus.STALE to "현재 결과가 오래되었거나 구성이 바뀌었습니다.",
                BatchStatus.RETRYABLE to "일시적인 실패 · 명시적으로 재시도해 주세요.",
                BatchStatus.PENDING to "현재 범위에 확인이 끝나지 않은 날짜가 남아 있습니다.",
            )) {
                replace(ready.copy(phase = ManualSafPhase.BLOCKED, outcome = outcome).let {
                    if (outcome == BatchStatus.BLOCKED) it else it.copy(phase = ManualSafPhase.READY)
                })
                shown(label)
                button("로컬 동기화 재시도").assertIsEnabled()
            }
            replace(ready.copy(outcome = BatchStatus.COMPLETED, coverage = coverage.copy(scopePendingCount = 0, scopeBlockedCount = 0, lastUploadAt = null)))
            shown("현재 범위 읽기 확인 완료 · 업로드 확인 없음. 빈 데이터는 업로드 성공이 아닙니다.")
            shown("마지막 업로드 확인 시각: 확인되지 않음")
            button("로컬 동기화 재시도").assertIsNotEnabled()
            replace(state.value.copy(coverage = state.value.coverage!!.copy(scopeUploadedThrough = older, recentUploadedDays = setOf(older))))
            shown("일부 허용 범위 확인 완료 · 전체 항목 완료가 아닙니다.")
            shown("최근 대상 $older · 읽기 확인됨 · 업로드 확인됨")
            replace(state.value.copy(coverage = state.value.coverage!!.copy(excludedTypes = emptySet(), checkedThrough = older, uploadedThrough = older, recentCheckedDays = emptySet(), recentUploadedDays = emptySet())))
            shown("이번 수동 확인이 끝났습니다. 날짜별 증거를 확인해 주세요.")
            shown("전체 항목 업로드 날짜: $older")
            shown("최근 대상 $older · 읽기 미확인 · 업로드 미확인")
            button("로컬 구성·확인 범위 초기화").performClick()
            compose.onNodeWithText("초기화 취소").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(0, resets.size) }
            button("로컬 구성·확인 범위 초기화").performClick()
            compose.onNodeWithText("로컬 초기화 확인").assertIsDisplayed().performClick()
            compose.onNodeWithText("로컬 초기화 확인").assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf(generation), resets) }
            button("로컬 구성·확인 범위 초기화").performClick()
            replace(ready.copy(generation = nextGeneration))
            compose.onNodeWithText("로컬 초기화 확인").assertDoesNotExist()
            for (paused in listOf(ready.copy(foreground = false), ready.copy(phase = ManualSafPhase.RUNNING))) {
                replace(ready)
                button("로컬 구성·확인 범위 초기화").performClick()
                replace(paused)
                compose.onNodeWithText("로컬 초기화 확인").assertDoesNotExist()
                replace(ready)
                compose.onNodeWithText("로컬 초기화 확인").assertDoesNotExist()
            }
            compose.runOnIdle { assertEquals(listOf(generation), resets); assertEquals(emptyList<String>(), calls) }
            shown("로컬 폴더 자동·백그라운드 동기화는 지원하지 않습니다.")
        }
    }
}
