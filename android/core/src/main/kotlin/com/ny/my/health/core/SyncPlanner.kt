package com.ny.my.health.core

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 저장소가 조회한 제한된 후보와 전체 범위 집계를 이용한다. */
object SyncPlanner {
    fun scopeKey(configuration: SyncConfiguration, access: ReadAccess): ReadScopeKey? {
        val scopes = activeScopes(configuration, access)
        if (scopes.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val components = listOf("sync-scope/1") + scopes.entries.sortedBy { it.key.name }
            .flatMap { listOf(it.key.name, it.value.name) }
        for (component in components) {
            val bytes = component.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return ReadScopeKey(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun plan(state: SyncState, todayInSeoul: LocalDate, access: ReadAccess): PlannedBatch {
        val configuration = state.configuration ?: return PlannedBatch(null, emptyList(), emptyMap(), 0)
        val total = ChronoUnit.DAYS.between(configuration.enrolledDay, todayInSeoul).coerceAtLeast(0)
        val recent = recent(configuration.enrolledDay, todayInSeoul)
        val key = scopeKey(configuration, access)
        if (key == null) return PlannedBatch(null, emptyList(),
            recent.associateWith { BlockReason.SOURCE_ACCESS_REQUIRED }, total)
        if (key != state.scopeKey || activeScopes(configuration, access) != state.requestedScopes) {
            return PlannedBatch(key, emptyList(), emptyMap(), total)
        }
        val floor = todayInSeoul.minusDays(30)
        val blocked = linkedMapOf<LocalDate, BlockReason>()
        fun eligible(date: LocalDate): Boolean {
            if (date < configuration.enrolledDay || date >= todayInSeoul) return false
            val day = state.days[date]
            val reason = when {
                date < floor -> BlockReason.HISTORY_ACCESS_REQUIRED
                day?.phase == DayPhase.REMOTE_STALE -> BlockReason.DESTINATION_BLOCKED
                day?.blocker in setOf(BlockReason.DESTINATION_BLOCKED, BlockReason.RETRY_EXHAUSTED,
                    BlockReason.STORE_UNAVAILABLE, BlockReason.DESTINATION_RECONCILIATION_REQUIRED,
                    BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED) -> day?.blocker
                else -> null
            }
            if (reason != null) blocked[date] = reason
            return reason == null
        }
        val dates = linkedSetOf<LocalDate>()
        recent.filter(::eligible).forEach(dates::add)
        val candidates = (state.oldestPendingDays + state.days.keys).distinct().sorted()
        for (date in candidates) {
            if (!eligible(date)) continue
            val day = state.days[date]
            if (day?.isUploaded() == true || (day?.phase == DayPhase.EMPTY && day.isChecked())) continue
            if (dates.size < 7) dates.add(date)
        }
        return PlannedBatch(key, dates.sorted(), blocked,
            state.coverage.takeIf { it.scopeKey == key }?.scopePendingCount ?: total)
    }

    fun coverage(state: SyncState, todayInSeoul: LocalDate): CoverageSummary {
        val configuration = state.configuration ?: return CoverageSummary(null, null, null, null, null,
            emptySet(), 0, 0, null, emptySet(), emptySet())
        val key = state.scopeKey
        if (key == null) return state.coverage.copy(scopeKey = null, scopeCheckedThrough = null,
            scopeUploadedThrough = null, scopePendingCount = null, scopeBlockedCount = null,
            recentCheckedDays = emptySet(), recentUploadedDays = emptySet())
        val rows = recent(configuration.enrolledDay, todayInSeoul).mapNotNull { state.days[it] }
            .filter { it.scopeKey == key && it.requestedScopes == state.requestedScopes }
        return state.coverage.copy(excludedTypes = configuration.categories - state.requestedScopes.keys,
            recentCheckedDays = rows.filter { it.isChecked() }.map { it.date }.toSet(),
            recentUploadedDays = rows.filter { it.isUploaded() }.map { it.date }.toSet())
    }

    private fun activeScopes(configuration: SyncConfiguration, access: ReadAccess): Map<DataType, ReadScope> =
        if (!access.available) emptyMap() else configuration.categories.mapNotNull { type ->
            access.scopeByType[type]?.takeIf { type in access.granted && it != ReadScope.BLOCKED }?.let { type to it }
        }.toMap()

    private fun recent(enrolled: LocalDate, today: LocalDate): List<LocalDate> =
        listOf(today.minusDays(2), today.minusDays(1)).filter { it >= enrolled }
}
