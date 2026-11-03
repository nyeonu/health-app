package com.ny.my.health

import com.ny.my.health.core.ProfileStage
import com.ny.my.health.core.ProfileStats
import com.ny.my.health.core.ProfileSummary
import java.util.Collections

internal enum class PhoneCopyState { NONE, COPIED, FAILED }
internal data class PhoneProfilingRow(val stage: ProfileStage, val value: String)
internal data class PhoneProfilingReport(val rows: List<PhoneProfilingRow>, val safeText: String)
internal data class PhoneProfilingUiState(
    val enabled: Boolean = false,
    val collecting: Boolean = false,
    val report: PhoneProfilingReport? = null,
    val copyState: PhoneCopyState = PhoneCopyState.NONE,
)

/** 최근 수동 시도의 불변 진단만 메모리에 보관한다. */
internal class PhoneProfilingSession(private val enabled: Boolean) {
    var state = PhoneProfilingUiState(enabled = enabled)
        private set
    private var current: ManualSyncProfiling? = null
    private var generation = Any()

    fun begin(profile: ManualSyncProfiling) {
        if (!enabled) return
        generation = Any()
        current = profile
        state = PhoneProfilingUiState(enabled = true, collecting = true)
    }

    fun complete(profile: ManualSyncProfiling) {
        if (!enabled || current !== profile) return
        current = null
        val report = try {
            phoneProfilingReport(profile.collector.snapshot())
        } catch (_: Throwable) {
            // 관찰/정형 실패는 업무 결과에 전파하지 않는다.
            null
        }
        state = PhoneProfilingUiState(enabled = true, report = report)
    }

    fun clear() {
        generation = Any()
        current = null
        state = PhoneProfilingUiState(enabled = enabled)
    }

    fun copy(write: (String) -> Unit) {
        if (!enabled) return
        val report = state.report ?: return
        val copyingGeneration = generation
        val result = try {
            write(report.safeText)
            PhoneCopyState.COPIED
        } catch (_: Throwable) {
            PhoneCopyState.FAILED
        }
        // 출력기 재진입으로 폐기된 시도의 결과는 새 상태에 반영하지 않는다.
        if (generation === copyingGeneration && state.report === report) {
            state = state.copy(copyState = result)
        }
    }
}

/** 외부의 가변 맵/리스트를 보관하지 않고 단일 관찰값을 고정 표시로 바꾼다. */
internal fun phoneProfilingReport(summary: ProfileSummary): PhoneProfilingReport =
    PhoneProfilingReport(
        rows = Collections.unmodifiableList(ProfileStage.entries.map { stage ->
            PhoneProfilingRow(stage, phoneProfilingValue(summary.stages[stage]))
        }),
        safeText = safeProfilingSummary(summary),
    )

private fun phoneProfilingValue(stats: ProfileStats?): String {
    if (stats == null) return "측정 없음"
    if (stats.active) return "미완료"
    if (stats.completed == 0L && stats.failed == 0L && stats.cancelled == 0L) return "측정 없음"
    val nanos = stats.elapsedNanos
    val elapsed = when {
        nanos == 0L -> "0 ms"
        nanos < 1_000_000L -> "1 ms 미만"
        else -> {
            val whole = nanos / 1_000_000L
            val fraction = (nanos % 1_000_000L / 1_000L).toString().padStart(3, '0').trimEnd('0')
            if (fraction.isEmpty()) "$whole ms" else "$whole.$fraction ms"
        }
    }
    return elapsed + (if (stats.failed > 0L) " (실패)" else "") +
        (if (stats.cancelled > 0L) " (취소)" else "")
}
