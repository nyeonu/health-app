package com.ny.my.health

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ny.my.health.core.ProfileOutcome
import com.ny.my.health.core.ProfileStage
import com.ny.my.health.core.ProfilingClock
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 계정 없는 합성 전용 에뮬레이터에서 실제 OS 쓰기 경계를 검증한다. */
@RunWith(AndroidJUnit4::class)
class PhoneProfilingClipboardInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun explicitForegroundWritePlacesTheSameSafeSummaryOnSystemClipboard() {
        // 난수는 건강 자료나 식별자가 아닌 테스트 소유권 표식이다.
        val marker = UUID.randomUUID().toString()
        val markerLabel = "합성 클립보드 테스트 $marker"
        val syntheticElapsed = UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE).coerceAtLeast(1L)
        var tick = 0L
        val profile = ManualSyncProfiling(ProfilingClock { tick })
        profile.collector.start(ProfileStage.HEALTH_READ)
        tick = syntheticElapsed
        profile.collector.finish(ProfileStage.HEALTH_READ, ProfileOutcome.COMPLETED)
        profile.finish(ProfileOutcome.COMPLETED)
        val session = PhoneProfilingSession(true)
        session.begin(profile)
        session.complete(profile)
        val expected = requireNotNull(session.state.report).safeText
        val expectedLabel = "수동 동기화 성능 진단"

        // Compose 유휴 상태와 별개로 OS 클립보드 접근에 필요한 전경 포커스를 기다린다.
        compose.waitUntil(10_000) {
            val hasFocus = compose.runOnUiThread { compose.activity.hasWindowFocus() }
            if (!hasFocus) NativeWindowEnvironment.dismissKnownExternalAnr()
            compose.runOnUiThread {
                compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    compose.activity.hasWindowFocus()
            }
        }
        compose.runOnIdle {
            assertTrue("클립보드 작업 시 Activity가 재개 상태여야 합니다",
                compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED)
            assertTrue("클립보드 작업 시 창 입력 포커스가 있어야 합니다",
                compose.activity.hasWindowFocus())
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            // 기존 클립보드는 읽거나 복원하지 않고 소유한 합성 자료로 시작한다.
            clipboard.setPrimaryClip(ClipData.newPlainText(markerLabel, marker))
            try {
                session.copy { writePhoneProfilingClipboard(compose.activity, it) }
                assertTrue("명시적 복사가 성공해야 합니다", session.state.copyState == PhoneCopyState.COPIED)
                assertTrue("고정 진단 라벨이어야 합니다", clipboard.primaryClipDescription?.label == expectedLabel)
                val clip = clipboard.primaryClip
                assertTrue("합성 요약 한 항목이어야 합니다", clip?.itemCount == 1)
                // assertEquals는 실패 메시지에 외부 클립보드 원문을 노출할 수 있다.
                assertTrue("동일한 안전 요약이어야 합니다", clip?.getItemAt(0)?.text?.toString() == expected)
            } finally {
                // 라벨을 먼저 확인하며 URI 변환/외부 자료 수집 없이 아직 소유한 자료만 지운다.
                val label = clipboard.primaryClipDescription?.label
                if (label == expectedLabel || label == markerLabel) {
                    val owned = clipboard.primaryClip
                    if (owned?.itemCount == 1) {
                        val text = owned.getItemAt(0).text?.toString()
                        if ((label == expectedLabel && text == expected) ||
                            (label == markerLabel && text == marker)) {
                            clipboard.clearPrimaryClip()
                        }
                    }
                }
            }
        }
    }
}
