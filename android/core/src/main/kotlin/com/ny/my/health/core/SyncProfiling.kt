package com.ny.my.health.core

import java.util.EnumMap
import kotlinx.coroutines.CancellationException

fun interface ProfilingClock { fun nowNanos(): Long }

enum class ProfileStage {
    AUTHORIZATION_HISTORY, AUTHORIZATION_WAIT, CONTINUE_WAIT,
    EXECUTION, START_HISTORY, HEALTH_READ, NORMALIZE, CSV_EXPORT,
    DRIVE_TOTAL, TERMINAL_HISTORY,
}

enum class ProfileOutcome { COMPLETED, FAILED, CANCELLED }

data class ProfileStats(
    val started: Long, val completed: Long, val failed: Long,
    val cancelled: Long, val elapsedNanos: Long, val active: Boolean,
)

data class ProfileSummary(
    val stages: Map<ProfileStage, ProfileStats>,
    val operations: Map<DriveOperation, ProfileStats>,
    val requests: Map<DriveOperation, ProfileStats>,
)

/** 고정 범주의 누적값만 보관하며 원시 이벤트나 작업 결과는 보관하지 않는다. */
class SyncProfiling(private val clock: ProfilingClock = ProfilingClock(System::nanoTime)) {
    private class Totals {
        var completed = 0L
        var failed = 0L
        var cancelled = 0L
        var elapsed = 0L
        var active = 0L
        var stageStart: Long? = null

        fun snapshot() = ProfileStats(
            add(add(add(completed, failed), cancelled), active),
            completed, failed, cancelled, elapsed, active > 0L,
        )
    }

    private val lock = Any()
    private val stages = EnumMap<ProfileStage, Totals>(ProfileStage::class.java)
    private val operations = EnumMap<DriveOperation, Totals>(DriveOperation::class.java)
    private val requests = EnumMap<DriveOperation, Totals>(DriveOperation::class.java)

    fun start(stage: ProfileStage) { beginStage(stage) }

    fun finish(stage: ProfileStage, outcome: ProfileOutcome) {
        synchronized(lock) {
            val totals = stages[stage] ?: return
            val started = totals.stageStart ?: return
            totals.stageStart = null
            close(stages, stage, started, outcome)
        }
    }

    fun snapshot(): ProfileSummary = synchronized(lock) {
        ProfileSummary(
            stages.mapValues { it.value.snapshot() },
            operations.mapValues { it.value.snapshot() },
            requests.mapValues { it.value.snapshot() },
        )
    }

    suspend fun <T> measure(stage: ProfileStage, block: suspend () -> T): T {
        val started = beginStage(stage)
        var outcome = ProfileOutcome.COMPLETED
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = outcome(failure)
            throw failure
        } finally {
            if (started) finish(stage, outcome)
        }
    }

    fun <T> measureValue(stage: ProfileStage, block: () -> T): T {
        val started = beginStage(stage)
        var outcome = ProfileOutcome.COMPLETED
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = outcome(failure)
            throw failure
        } finally {
            if (started) finish(stage, outcome)
        }
    }

    suspend fun <T> operation(kind: DriveOperation, block: suspend () -> T): T =
        track(operations, kind, block)

    suspend fun <T> request(kind: DriveOperation, block: suspend () -> T): T =
        track(requests, kind, block)

    private fun beginStage(stage: ProfileStage): Boolean = synchronized(lock) {
        if ((stages[stage]?.active ?: 0L) > 0L) return false
        val tick = tick() ?: return false
        val totals = stages.getOrPut(stage) { Totals() }
        totals.active = 1L
        totals.stageStart = tick
        true
    }

    private suspend fun <T> track(
        map: EnumMap<DriveOperation, Totals>, kind: DriveOperation, block: suspend () -> T,
    ): T {
        val started = synchronized(lock) {
            tick()?.also { map.getOrPut(kind) { Totals() }.apply { active = add(active, 1L) } }
        }
        var outcome = ProfileOutcome.COMPLETED
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = outcome(failure)
            throw failure
        } finally {
            if (started != null) synchronized(lock) { close(map, kind, started, outcome) }
        }
    }

    private fun <K : Enum<K>> close(map: EnumMap<K, Totals>, key: K, start: Long, outcome: ProfileOutcome) {
        val totals = map[key] ?: return
        totals.active = (totals.active - 1L).coerceAtLeast(0L)
        val end = tick()
        val duration = end?.let { it - start }?.takeIf { it >= 0L }
        if (duration != null) {
            totals.elapsed = add(totals.elapsed, duration)
            when (outcome) {
                ProfileOutcome.COMPLETED -> totals.completed = add(totals.completed, 1L)
                ProfileOutcome.FAILED -> totals.failed = add(totals.failed, 1L)
                ProfileOutcome.CANCELLED -> totals.cancelled = add(totals.cancelled, 1L)
            }
        }
        // 시계 결함으로 무효인 호출은 관찰된 0으로 남기지 않는다.
        if (totals.snapshot().started == 0L) map.remove(key)
    }

    private fun tick(): Long? = try { clock.nowNanos() } catch (_: Throwable) { null }

    private fun outcome(failure: Throwable) =
        if (failure is CancellationException) ProfileOutcome.CANCELLED else ProfileOutcome.FAILED

    private companion object {
        fun add(left: Long, right: Long): Long =
            if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
