package com.ny.my.health

import com.ny.my.health.core.DriveOperation
import com.ny.my.health.core.ProfileOutcome
import com.ny.my.health.core.ProfileStage
import com.ny.my.health.core.ProfileStats
import com.ny.my.health.core.ProfileSummary
import com.ny.my.health.core.ProfilingClock
import com.ny.my.health.core.SyncProfiling

/** 단일 수동 시도의 고정 범주만 보관한다. 인증 정보와 업무 결과는 받지 않는다. */
internal class ManualSyncProfiling(clock: ProfilingClock = ProfilingClock(System::nanoTime)) {
    val collector = SyncProfiling(clock)
    private enum class Phase { NEW, AUTHORIZATION, CONTINUE, EXECUTION, FINISHED }
    private var phase = Phase.NEW
    private var published = false

    fun authorizationStarted() {
        if (phase != Phase.NEW) return
        phase = Phase.AUTHORIZATION
        collector.start(ProfileStage.AUTHORIZATION_WAIT)
    }

    fun authorizationReturned(accepted: Boolean) {
        if (phase != Phase.AUTHORIZATION) return
        collector.finish(ProfileStage.AUTHORIZATION_WAIT,
            if (accepted) ProfileOutcome.COMPLETED else ProfileOutcome.FAILED)
        if (accepted) {
            phase = Phase.CONTINUE
            collector.start(ProfileStage.CONTINUE_WAIT)
        } else finish(ProfileOutcome.FAILED)
    }

    fun executionStarted() {
        if (phase != Phase.CONTINUE) return
        collector.finish(ProfileStage.CONTINUE_WAIT, ProfileOutcome.COMPLETED)
        phase = Phase.EXECUTION
        collector.start(ProfileStage.EXECUTION)
    }

    fun finish(outcome: ProfileOutcome): ProfileSummary {
        if (phase != Phase.FINISHED) {
            collector.finish(ProfileStage.AUTHORIZATION_WAIT, outcome)
            collector.finish(ProfileStage.CONTINUE_WAIT, outcome)
            collector.finish(ProfileStage.EXECUTION, outcome)
            phase = Phase.FINISHED
        }
        return collector.snapshot()
    }

    /** 라벨도 고정 enum으로 제한하며 active는 정수로 출력한다. */
    fun safeSummary(): String {
        val summary = collector.snapshot()
        return buildString {
            fun appendStats(name: String, stats: ProfileStats?) {
                append(name).append(' ')
                append(stats?.started ?: 0L).append(' ')
                append(stats?.completed ?: 0L).append(' ')
                append(stats?.failed ?: 0L).append(' ')
                append(stats?.cancelled ?: 0L).append(' ')
                append(stats?.elapsedNanos ?: 0L).append(' ')
                append(if (stats?.active == true) 1 else 0).append('\n')
            }
            ProfileStage.entries.forEach { appendStats(it.name, summary.stages[it]) }
            DriveOperation.entries.forEach { appendStats(it.name, summary.operations[it]) }
            DriveOperation.entries.forEach { appendStats(it.name, summary.requests[it]) }
        }.take(8192)
    }

    fun publish(debugEnabled: Boolean, emit: (String) -> Unit) {
        if (phase != Phase.FINISHED || published) return
        published = true
        if (!debugEnabled) return
        try { emit(safeSummary()) } catch (_: Throwable) {
            // 진단 출력 실패는 업무 결과와 취소를 변경하지 않는다.
        }
    }
}
