package com.ny.my.health.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Collections

private const val INVALID = "유효하지 않은 자동 재시도 상태"
private fun checkValue(value: Boolean) { require(value) { INVALID } }
private fun <K, V> frozenMap(value: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(value))
private fun <T> frozenList(value: List<T>): List<T> = Collections.unmodifiableList(ArrayList(value))
private fun digest(parts: List<String>): String {
    val hash = MessageDigest.getInstance("SHA-256")
    parts.forEach { val bytes = it.toByteArray(Charsets.UTF_8); hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }
    return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
internal fun automaticRetryBudgetKey(lineageId: String): String = digest(listOf("automatic-retry/1", lineageId))
private fun validScopes(key: ReadScopeKey, scopes: Map<DataType, ReadScope>) =
    scopes.isNotEmpty() && scopes.size <= 5 && scopes.values.none { it == ReadScope.BLOCKED } && scopeFingerprint(scopes) == key
private fun validDates(dates: List<LocalDate>) = dates.isNotEmpty() && dates.size <= 7 && dates == dates.distinct().sorted() && dates.all(::validDate)
private fun automatic(trigger: SyncTrigger) = trigger == SyncTrigger.SCHEDULED || trigger == SyncTrigger.CATCH_UP

enum class RetryBudgetStatus { ACTIVE, COMPLETED, BLOCKED, EXHAUSTED }
class RetryBudget private constructor(
    val lineageId: String, val generation: DestinationGeneration, val scopeKey: ReadScopeKey,
    val requestedScopes: Map<DataType, ReadScope>, val dates: List<LocalDate>,
    val observedRevisions: Map<LocalDate, String?>, val retriesUsed: Int,
    val retryNotBefore: Instant?, val retryDelayMillis: Long?, val claimVersion: Long,
    val ownerAttemptId: String?, val status: RetryBudgetStatus, val createdAt: Instant, val updatedAt: Instant,
) {
    val retryBudgetKey: String = automaticRetryBudgetKey(lineageId)
    companion object {
        fun validated(lineageId: String, generation: DestinationGeneration, scopeKey: ReadScopeKey,
            requestedScopes: Map<DataType, ReadScope>, dates: List<LocalDate>, observedRevisions: Map<LocalDate, String?>,
            retriesUsed: Int, retryNotBefore: Instant?, retryDelayMillis: Long?, claimVersion: Long,
            ownerAttemptId: String?, status: RetryBudgetStatus, createdAt: Instant, updatedAt: Instant): RetryBudget {
            val safeScopes = frozenMap(requestedScopes)
            val safeDates = frozenList(dates)
            val safeRevisions = frozenMap(observedRevisions)
            checkValue(validUuid(lineageId) && validUuid(generation.value) && validScopes(scopeKey, safeScopes) &&
                validDates(safeDates) && safeRevisions.keys == safeDates.toSet() && safeRevisions.values.all { it == null || validHex(it) } &&
                retriesUsed in 0..3 && claimVersion >= 0 && (ownerAttemptId == null || validUuid(ownerAttemptId)) &&
                validInstant(createdAt) && validInstant(updatedAt) && createdAt <= updatedAt)
            checkValue(if (status == RetryBudgetStatus.ACTIVE) {
                if (ownerAttemptId != null) retryNotBefore == null && retryDelayMillis == null
                else retriesUsed < 3 && retryNotBefore != null && validInstant(retryNotBefore) &&
                    retryDelayMillis == listOf(30000L, 60000L, 120000L)[retriesUsed] &&
                    try { updatedAt.plusMillis(retryDelayMillis) == retryNotBefore } catch (_: RuntimeException) { false }
            } else ownerAttemptId == null && retryNotBefore == null && retryDelayMillis == null &&
                (status != RetryBudgetStatus.EXHAUSTED || retriesUsed == 3))
            return RetryBudget(lineageId, generation, scopeKey, safeScopes, safeDates,
                safeRevisions, retriesUsed, retryNotBefore, retryDelayMillis, claimVersion,
                ownerAttemptId, status, createdAt, updatedAt)
        }
    }
}
class InitialAutomaticProposal private constructor(
    val expectedGeneration: DestinationGeneration, val scopeKey: ReadScopeKey,
    val requestedScopes: Map<DataType, ReadScope>, val frozenDates: List<LocalDate>,
    val attemptId: String, val trigger: SyncTrigger, val plannedAt: Instant, val accessFingerprint: String,
) {
    companion object {
        fun validated(expectedGeneration: DestinationGeneration, scopeKey: ReadScopeKey,
            requestedScopes: Map<DataType, ReadScope>, frozenDates: List<LocalDate>, attemptId: String,
            trigger: SyncTrigger, plannedAt: Instant, accessFingerprint: String, enrolledDay: LocalDate,
            todayInSeoul: LocalDate): InitialAutomaticProposal {
            val safeScopes = frozenMap(requestedScopes)
            val safeDates = frozenList(frozenDates)
            checkValue(validUuid(expectedGeneration.value) && validScopes(scopeKey, safeScopes) && validDates(safeDates) &&
                validDate(enrolledDay) && validDate(todayInSeoul) && safeDates.all { it >= enrolledDay && it < todayInSeoul } &&
                validUuid(attemptId) && automatic(trigger) && validInstant(plannedAt) && validHex(accessFingerprint))
            return InitialAutomaticProposal(expectedGeneration, scopeKey, safeScopes, safeDates,
                attemptId, trigger, plannedAt, accessFingerprint)
        }
    }
}
data class AutomaticOwner(val lineageId: String, val attemptId: String, val claimVersion: Long)
sealed interface AutomaticBudgetRead { data class Ready(val budget: RetryBudget?): AutomaticBudgetRead; data object Stale: AutomaticBudgetRead }
sealed interface RetryClaim {
    data class Claimed(val attempt: AttemptState, val budget: RetryBudget): RetryClaim
    data class Deferred(val retryNotBefore: Instant): RetryClaim
    data object Busy: RetryClaim; data object Stale: RetryClaim; data object Blocked: RetryClaim; data object Exhausted: RetryClaim
}
sealed interface RetryValidation<out T> { data class Valid<T>(val value: T): RetryValidation<T>; data object Invalid: RetryValidation<Nothing> }
enum class AutomaticFinishOutcome { COMPLETED, RETRYABLE, BLOCKED }
sealed interface AutomaticPreflight { data object Ready: AutomaticPreflight; data class Deferred(val retryNotBefore: Instant): AutomaticPreflight; data object RecoveryRequired: AutomaticPreflight; data object Stale: AutomaticPreflight }

object AutomaticRetryModel {
    fun accessFingerprint(access: ReadAccess): String {
        val granted = access.granted.sortedBy { it.name }
        val scopes = access.scopeByType.entries.sortedBy { it.key.name }
        return digest(listOf("automatic-access/1", if (access.available) "1" else "0", granted.size.toString()) +
            granted.map { it.name } + listOf(scopes.size.toString()) + scopes.flatMap { listOf(it.key.name, it.value.name) })
    }
    private fun copy(b: RetryBudget, count: Int = b.retriesUsed, due: Instant? = b.retryNotBefore,
        delay: Long? = b.retryDelayMillis, version: Long = b.claimVersion, owner: String? = b.ownerAttemptId,
        status: RetryBudgetStatus = b.status, revisions: Map<LocalDate, String?> = b.observedRevisions,
        now: Instant): RetryBudget = RetryBudget.validated(b.lineageId, b.generation, b.scopeKey, b.requestedScopes,
        b.dates, revisions, count, due, delay, version, owner, status, b.createdAt, now)
    private fun attempt(b: RetryBudget, id: String, trigger: SyncTrigger, scheduled: Instant, now: Instant) =
        AttemptState(id, b.scopeKey, b.requestedScopes, trigger, scheduled, now, null, b.dates, b.retryBudgetKey, b.retriesUsed, emptySet())
    fun initialClaim(proposal: InitialAutomaticProposal, lineageId: String, currentGeneration: DestinationGeneration,
        configuration: SyncConfiguration, currentScopeKey: ReadScopeKey, currentRequestedScopes: Map<DataType, ReadScope>,
        enrolledDay: LocalDate, todayInSeoul: LocalDate, now: Instant): RetryValidation<RetryClaim> {
        checkValue(validUuid(lineageId) && validUuid(currentGeneration.value) && configuration.isValid() && validInstant(now) &&
            validDate(enrolledDay) && validDate(todayInSeoul) && proposal.frozenDates.all { it >= enrolledDay && it < todayInSeoul } &&
            proposal.plannedAt <= now && enrolledDay == configuration.enrolledDay)
        if (proposal.expectedGeneration != currentGeneration) return RetryValidation.Valid(RetryClaim.Stale)
        if (!configuration.automatic || proposal.scopeKey != currentScopeKey || proposal.requestedScopes != currentRequestedScopes ||
            !configuration.categories.containsAll(proposal.requestedScopes.keys)) return RetryValidation.Invalid
        val b = RetryBudget.validated(lineageId, currentGeneration, proposal.scopeKey, proposal.requestedScopes, proposal.frozenDates,
            proposal.frozenDates.associateWith { null }, 0, null, null, 0, proposal.attemptId, RetryBudgetStatus.ACTIVE, now, now)
        return RetryValidation.Valid(RetryClaim.Claimed(attempt(b, proposal.attemptId, proposal.trigger, proposal.plannedAt, now), b))
    }
    fun reClaim(budget: RetryBudget, expectedGeneration: DestinationGeneration, expectedLineageId: String,
        expectedClaimVersion: Long, expectedScopeKey: ReadScopeKey, expectedRequestedScopes: Map<DataType, ReadScope>,
        expectedFrozenDates: List<LocalDate>, attemptId: String, trigger: SyncTrigger, now: Instant): RetryValidation<RetryClaim> {
        checkValue(validUuid(expectedGeneration.value) && validUuid(expectedLineageId) && expectedClaimVersion >= 0 &&
            validScopes(expectedScopeKey, expectedRequestedScopes) && validDates(expectedFrozenDates) && validUuid(attemptId) && automatic(trigger) && validInstant(now))
        if (budget.generation != expectedGeneration || budget.lineageId != expectedLineageId || budget.claimVersion != expectedClaimVersion ||
            budget.scopeKey != expectedScopeKey || budget.requestedScopes != expectedRequestedScopes || budget.dates != expectedFrozenDates)
            return RetryValidation.Valid(RetryClaim.Stale)
        when (budget.status) {
            RetryBudgetStatus.BLOCKED -> return RetryValidation.Valid(RetryClaim.Blocked)
            RetryBudgetStatus.EXHAUSTED -> return RetryValidation.Valid(RetryClaim.Exhausted)
            RetryBudgetStatus.COMPLETED -> return RetryValidation.Valid(RetryClaim.Stale)
            RetryBudgetStatus.ACTIVE -> Unit
        }
        if (budget.ownerAttemptId != null) return RetryValidation.Valid(RetryClaim.Busy)
        val due = budget.retryNotBefore!!
        if (now < due) return RetryValidation.Valid(RetryClaim.Deferred(due))
        if (now < budget.updatedAt || budget.claimVersion == Long.MAX_VALUE) return RetryValidation.Invalid
        val b = copy(budget, count = budget.retriesUsed + 1, due = null, delay = null, version = budget.claimVersion + 1, owner = attemptId, now = now)
        return RetryValidation.Valid(RetryClaim.Claimed(attempt(b, attemptId, trigger, now, now), b))
    }
    private fun matches(b: RetryBudget, generation: DestinationGeneration, owner: AutomaticOwner) =
        b.status == RetryBudgetStatus.ACTIVE && b.generation == generation && b.lineageId == owner.lineageId &&
            b.ownerAttemptId == owner.attemptId && b.claimVersion == owner.claimVersion
    fun observeRevision(budget: RetryBudget, expectedGeneration: DestinationGeneration, owner: AutomaticOwner,
        date: LocalDate, revisionDigest: String, now: Instant): RetryValidation<RetryBudget> {
        if (!matches(budget, expectedGeneration, owner) || date !in budget.dates || !validHex(revisionDigest) || !validInstant(now) || now < budget.updatedAt) return RetryValidation.Invalid
        return RetryValidation.Valid(copy(budget, revisions = budget.observedRevisions + (date to revisionDigest), now = now))
    }
    fun finish(budget: RetryBudget, expectedGeneration: DestinationGeneration, owner: AutomaticOwner,
        outcome: AutomaticFinishOutcome, now: Instant): RetryValidation<RetryBudget> {
        if (!matches(budget, expectedGeneration, owner) || !validInstant(now) || now < budget.updatedAt) return RetryValidation.Invalid
        val status = when (outcome) { AutomaticFinishOutcome.COMPLETED -> RetryBudgetStatus.COMPLETED; AutomaticFinishOutcome.BLOCKED -> RetryBudgetStatus.BLOCKED; AutomaticFinishOutcome.RETRYABLE -> if (budget.retriesUsed == 3) RetryBudgetStatus.EXHAUSTED else RetryBudgetStatus.ACTIVE }
        val delay = if (status == RetryBudgetStatus.ACTIVE) listOf(30000L, 60000L, 120000L)[budget.retriesUsed] else null
        val due = if (delay != null) try { now.plusMillis(delay) } catch (_: RuntimeException) { return RetryValidation.Invalid } else null
        if (due != null && !validInstant(due)) return RetryValidation.Invalid
        return RetryValidation.Valid(copy(budget, due = due, delay = delay, owner = null, status = status, now = now))
    }
}
