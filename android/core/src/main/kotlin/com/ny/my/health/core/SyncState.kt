package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate

@JvmInline value class DestinationGeneration(val value: String)
@JvmInline value class ReadScopeKey(val value: String)
enum class SyncTrigger { MANUAL, SCHEDULED, CATCH_UP }
enum class SyncStrategy { DAILY_SNAPSHOT }
enum class Dataset { DAILY, WORKOUTS, HEART_RATE, SLEEP }
enum class ReadScope { EXPLICIT_DAY, SUMMARY, WORKOUT_SCOPED, BLOCKED }
enum class StateStatus { ABSENT, READY, CORRUPT, UNSUPPORTED, UNAVAILABLE }
enum class DayPhase { PENDING, RUNNING, EMPTY, CONFIRMED, INDETERMINATE, BLOCKED, REMOTE_STALE }
enum class BlockReason { HISTORY_ACCESS_REQUIRED, SOURCE_ACCESS_REQUIRED, SOURCE_READ_FAILED,
    SOURCE_LIMIT_EXCEEDED, DESTINATION_BLOCKED, STORE_UNAVAILABLE, RETRY_EXHAUSTED,
    DESTINATION_TRANSIENT_FAILURE, DESTINATION_RECONCILIATION_REQUIRED, ACCOUNT_AUTHORIZATION_BLOCKED }
enum class FilePhase { PENDING, RUNNING, CONFIRMED, INDETERMINATE, REMOTE_STALE, BLOCKED }
data class RevisionKey(val digest: String, val generatedAt: Instant)
data class FileKey(val generation: DestinationGeneration, val date: LocalDate,
    val scopeKey: ReadScopeKey, val dataset: Dataset, val revision: String)
data class FileConfirmation(val key: FileKey, val targetHandle: String, val confirmedAt: Instant)
data class ScopedSnapshot(val snapshot: HealthSnapshot, val scopeByType: Map<DataType, ReadScope>,
    val blockedTypes: Set<DataType>)
data class SyncConfiguration(val destinationHandle: String, val enrolledDay: LocalDate,
    val automatic: Boolean, val categories: Set<DataType>)
data class ReadAccess(val available: Boolean, val granted: Set<DataType>,
    val scopeByType: Map<DataType, ReadScope>)
data class DatasetState(val dataset: Dataset, val revision: RevisionKey, val phase: FilePhase,
    val populated: Boolean, val targetHandle: String?, val startedAt: Instant?,
    val confirmedAt: Instant?, val priorConfirmation: FileConfirmation?)
data class DayState(val date: LocalDate, val scopeKey: ReadScopeKey,
    val requestedScopes: Map<DataType, ReadScope>, val revision: RevisionKey?, val phase: DayPhase,
    val scopes: Map<DataType, ReadScope>, val checkedAt: Instant?,
    val datasets: Map<Dataset, DatasetState>, val blocker: BlockReason?)
data class CoverageSummary(val checkedThrough: LocalDate?, val uploadedThrough: LocalDate?,
    val scopeKey: ReadScopeKey?, val scopeCheckedThrough: LocalDate?,
    val scopeUploadedThrough: LocalDate?, val excludedTypes: Set<DataType>,
    val scopePendingCount: Long?, val scopeBlockedCount: Long?,
    val lastUploadAt: Instant?, val recentCheckedDays: Set<LocalDate>,
    val recentUploadedDays: Set<LocalDate>)
data class AttemptState(val id: String, val scopeKey: ReadScopeKey,
    val requestedScopes: Map<DataType, ReadScope>, val trigger: SyncTrigger, val scheduledAt: Instant,
    val startedAt: Instant?, val finishedAt: Instant?, val dates: List<LocalDate>,
    val retryBudgetKey: String, val retriesUsed: Int, val pendingTriggers: Set<SyncTrigger>)
data class SyncState(val version: Int, val generation: DestinationGeneration,
    val configuration: SyncConfiguration?, val attempt: AttemptState?,
    val scopeKey: ReadScopeKey?, val requestedScopes: Map<DataType, ReadScope>,
    val days: Map<LocalDate, DayState>, val oldestPendingDays: List<LocalDate>,
    val coverage: CoverageSummary)
data class SyncStateRead(val status: StateStatus, val state: SyncState?)
data class PlannedBatch(val scopeKey: ReadScopeKey?, val dates: List<LocalDate>,
    val blocked: Map<LocalDate, BlockReason>, val remainingPending: Long)
data class DayRevision(val key: RevisionKey, val scopeKey: ReadScopeKey,
    val scopes: Map<DataType, ReadScope>, val files: Map<Dataset, CsvFile>,
    val remoteStale: Set<Dataset>)
/** Memory-only projection: without a freshly proven safe target, uncertainty forbids create. */
data class PlannedFile(val key: FileKey, val file: CsvFile, val byteSha256: String,
    val previousTargetHandle: String?, val createMayHaveSucceeded: Boolean = false)
data class ReconciledFiles(val confirmed: List<FileConfirmation>,
    val pending: List<PlannedFile>, val blocked: Set<FileKey>)
enum class CommitStatus { COMMITTED, STALE, INVALID, UNAVAILABLE }
data class SyncCommit(val status: CommitStatus, val generation: DestinationGeneration?)
data class SyncMutation(val attemptId: String?, val start: AttemptState?,
    val day: DayState?, val confirmation: FileConfirmation?,
    val pendingTriggers: Set<SyncTrigger>?, val finishAt: Instant?, val automaticOwner: AutomaticOwner? = null)
enum class BatchStatus { COMPLETED, PENDING, BLOCKED, MERGED, STALE, RETRYABLE }
data class BatchOutcome(val status: BatchStatus, val confirmedFiles: Int,
    val coverage: CoverageSummary?)

/** 입출력 없이 범위와 확인 메타데이터의 관계만 검사한다. */
fun SyncState.isValid(): Boolean {
    if (version != 1 || !validUuid(generation.value) || days.size > 9 || oldestPendingDays.size > 7) return false
    if (oldestPendingDays != oldestPendingDays.distinct().sorted()) return false
    if (requestedScopes.values.any { it == ReadScope.BLOCKED }) return false
    if ((scopeKey == null) != requestedScopes.isEmpty()) return false
    if (coverage.scopeKey != scopeKey) return false
    if (coverage.scopeKey?.let { !validHex(it.value) } == true ||
        listOfNotNull(coverage.checkedThrough, coverage.uploadedThrough, coverage.scopeCheckedThrough,
            coverage.scopeUploadedThrough).any { !validDate(it) } ||
        coverage.lastUploadAt?.let { !validInstant(it) } == true ||
        coverage.recentCheckedDays.size > 2 || coverage.recentUploadedDays.size > 2 ||
        (coverage.recentCheckedDays + coverage.recentUploadedDays + oldestPendingDays).any { !validDate(it) }) return false
    if (listOfNotNull(coverage.scopePendingCount, coverage.scopeBlockedCount).any { it < 0 }) return false
    if (configuration == null) {
        return attempt == null && scopeKey == null && days.isEmpty() && oldestPendingDays.isEmpty() &&
            coverage == CoverageSummary(null, null, null, null, null, emptySet(), 0, 0, null, emptySet(), emptySet())
    }
    val config = configuration
    if (!config.isValid() || (scopeKey != null && scopeFingerprint(requestedScopes) != scopeKey)) return false
    if (attempt?.isValid(config.enrolledDay, LocalDate.MAX) == false) return false
    if (config.destinationHandle.isBlank() || !config.categories.containsAll(requestedScopes.keys)) return false
    if (scopeKey == null && (days.isNotEmpty() || oldestPendingDays.isNotEmpty() ||
            coverage.scopePendingCount != null || coverage.scopeBlockedCount != null ||
            coverage.scopeCheckedThrough != null || coverage.scopeUploadedThrough != null ||
            coverage.recentCheckedDays.isNotEmpty() || coverage.recentUploadedDays.isNotEmpty())) return false
    if (oldestPendingDays.any { it < config.enrolledDay }) return false
    if (attempt?.let { a ->
            a.id.isBlank() || !config.categories.containsAll(a.requestedScopes.keys) ||
                (scopeKey != null && a.finishedAt == null && (a.scopeKey != scopeKey || a.requestedScopes != requestedScopes)) ||
                a.retryBudgetKey.isBlank() || a.retriesUsed !in 0..3 || a.dates.size > 7 ||
                a.dates != a.dates.distinct().sorted() || a.dates.any { it < config.enrolledDay } ||
                (a.startedAt != null && a.startedAt < a.scheduledAt) ||
                (a.finishedAt != null && (a.startedAt == null || a.finishedAt < a.startedAt))
        } == true) return false
    return days.all { (date, day) ->
        day.isValid(generation, config.enrolledDay, LocalDate.MAX) && date == day.date && date >= config.enrolledDay && day.scopeKey == scopeKey &&
            day.requestedScopes == requestedScopes && day.requestedScopes.isNotEmpty() &&
            day.scopes.keys.all { it in day.requestedScopes } &&
            (day.phase != DayPhase.BLOCKED || day.blocker != null) &&
            (day.phase != DayPhase.EMPTY || (day.datasets.isEmpty() && day.isChecked())) &&
            (day.phase != DayPhase.CONFIRMED || day.isUploaded()) &&
            day.datasets.all { (dataset, file) ->
                dataset == file.dataset && day.revision == file.revision && file.revision.digest.isNotBlank() &&
                    (file.phase != FilePhase.CONFIRMED ||
                        (file.populated && !file.targetHandle.isNullOrBlank() && file.confirmedAt != null)) &&
                    (file.confirmedAt == null || file.startedAt == null || file.confirmedAt >= file.startedAt) &&
                    (file.priorConfirmation?.let { prior ->
                        prior.key.generation == generation && prior.key.date == date &&
                            prior.key.scopeKey == day.scopeKey && prior.key.dataset == dataset &&
                            prior.key.revision.isNotBlank() && prior.targetHandle.isNotBlank()
                    } != false)
            }
    }
}

/** 원격 저장 결과와 독립적으로 요청 범위를 읽었는지 확인한다. */
fun DayState.isChecked(): Boolean = revision?.digest?.isNotBlank() == true && checkedAt != null &&
    requestedScopes.isNotEmpty() && requestedScopes.values.none { it == ReadScope.BLOCKED } &&
    scopes == requestedScopes && blocker !in setOf(BlockReason.HISTORY_ACCESS_REQUIRED,
        BlockReason.SOURCE_ACCESS_REQUIRED, BlockReason.SOURCE_READ_FAILED, BlockReason.SOURCE_LIMIT_EXCEEDED) &&
    (phase != DayPhase.EMPTY || datasets.isEmpty())

/** 모든 현재 리비전 파일에 실제 원격 확인이 있어야 업로드 완료다. */
fun DayState.isUploaded(): Boolean = isChecked() && phase == DayPhase.CONFIRMED && datasets.isNotEmpty() &&
    datasets.values.all { it.phase == FilePhase.CONFIRMED && it.revision == revision && it.populated &&
        it.confirmedAt != null && !it.targetHandle.isNullOrBlank() }

fun SyncConfiguration.isValid(): Boolean = validHandle(destinationHandle) && validDate(enrolledDay) && categories.isNotEmpty() && categories.size <= 5

fun FileConfirmation.isValid(): Boolean = validUuid(key.generation.value) && validDate(key.date) &&
    validHex(key.scopeKey.value) && validHex(key.revision) && validHandle(targetHandle) && validInstant(confirmedAt)

private fun validRequested(key: ReadScopeKey, scopes: Map<DataType, ReadScope>): Boolean =
    scopes.isNotEmpty() && scopes.size <= 5 && scopes.values.none { it == ReadScope.BLOCKED } && scopeFingerprint(scopes) == key

fun AttemptState.isValid(enrolledDay: LocalDate, todayInSeoul: LocalDate): Boolean =
    validUuid(id) && validRequested(scopeKey, requestedScopes) && validHex(retryBudgetKey) && retriesUsed in 0..3 &&
        dates.isNotEmpty() && dates.size <= 7 && dates == dates.distinct().sorted() &&
        dates.all { validDate(it) && it >= enrolledDay && it < todayInSeoul } && validInstant(scheduledAt) &&
        (startedAt == null || (validInstant(startedAt) && startedAt >= scheduledAt)) &&
        (finishedAt == null || (validInstant(finishedAt) && startedAt != null && finishedAt >= startedAt)) && pendingTriggers.size <= 3

fun DayState.isValid(generation: DestinationGeneration, enrolledDay: LocalDate, todayInSeoul: LocalDate): Boolean {
    if (!validUuid(generation.value) || !validDate(date) || date < enrolledDay || date >= todayInSeoul ||
        !validRequested(scopeKey, requestedScopes) || scopes.any { (type, scope) ->
            type !in requestedScopes || (scope != ReadScope.BLOCKED && scope != requestedScopes[type])
        } || datasets.size > 4 || checkedAt?.let { !validInstant(it) } == true) return false
    if (blocker == BlockReason.DESTINATION_TRANSIENT_FAILURE &&
        phase !in setOf(DayPhase.PENDING, DayPhase.INDETERMINATE)) return false
    if (blocker in setOf(BlockReason.DESTINATION_RECONCILIATION_REQUIRED,
            BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED) && phase != DayPhase.BLOCKED) return false
    if (revision == null) return phase in setOf(DayPhase.PENDING, DayPhase.BLOCKED) &&
        datasets.isEmpty() && checkedAt == null && (phase != DayPhase.BLOCKED || blocker != null)
    if (!validHex(revision.digest) || !validInstant(revision.generatedAt)) return false
    if (phase == DayPhase.BLOCKED && blocker == null) return false
    if (phase == DayPhase.EMPTY && (datasets.isNotEmpty() || !isChecked())) return false
    if (phase == DayPhase.CONFIRMED && !isUploaded()) return false
    return datasets.all { (dataset, file) ->
        val prior = file.priorConfirmation
        dataset == file.dataset && file.revision == revision &&
            (file.targetHandle == null || validHandle(file.targetHandle)) &&
            (file.startedAt == null || validInstant(file.startedAt)) &&
            (file.phase != FilePhase.RUNNING || (file.populated && file.startedAt != null)) &&
            (if (file.phase == FilePhase.CONFIRMED) file.populated && file.targetHandle != null &&
                file.confirmedAt != null && validInstant(file.confirmedAt) &&
                (file.startedAt == null || file.confirmedAt >= file.startedAt) else file.confirmedAt == null) &&
            (prior == null || (prior.isValid() && prior.key.generation == generation && prior.key.date == date &&
                prior.key.scopeKey == scopeKey && prior.key.dataset == dataset))
    }
}

/** 반환값은 후보의 유효성일 뿐 영속 커밋을 보증하지 않는다. */
fun SyncState.validateMutation(expected: DestinationGeneration?, mutation: SyncMutation,
    currentDay: DayState?, todayInSeoul: LocalDate, automaticOwnerRequired: Boolean = false): CommitStatus {
    val invalid = CommitStatus.INVALID
    val stale = CommitStatus.STALE
    val payloads = listOf(mutation.start, mutation.day, mutation.confirmation, mutation.pendingTriggers, mutation.finishAt)
    if (!isValid() || payloads.count { it != null } != 1 || mutation.attemptId?.let(::validUuid) != true) return invalid
    val config = configuration
    if (mutation.start?.let { config == null || !it.isValid(config.enrolledDay, todayInSeoul) } == true ||
        mutation.day?.let { config == null || !it.isValid(generation, config.enrolledDay, todayInSeoul) } == true ||
        mutation.confirmation?.isValid() == false || mutation.finishAt?.let { !validInstant(it) } == true) return invalid
    if (config != null && (attempt?.isValid(config.enrolledDay, todayInSeoul) == false ||
            days.values.any { !it.isValid(generation, config.enrolledDay, todayInSeoul) })) return invalid
    if (mutation.automaticOwner?.let { !validUuid(it.lineageId) || !validUuid(it.attemptId) || it.claimVersion < 0 } == true) return invalid
    if (expected != generation) return stale
    if (config == null) return invalid
    mutation.start?.let { start ->
        if (mutation.automaticOwner != null || (automaticOwnerRequired && start.trigger != SyncTrigger.MANUAL)) return invalid
        if (start.id != mutation.attemptId || start.scopeKey != scopeKey || start.requestedScopes != requestedScopes ||
            start.finishedAt != null || (attempt != null && (attempt.finishedAt == null || attempt.id == start.id))) return invalid
        return CommitStatus.COMMITTED
    }
    val active = attempt ?: return stale
    if (active.id != mutation.attemptId) return stale
    val automaticAttempt = active.trigger != SyncTrigger.MANUAL
    val owner = mutation.automaticOwner
    if (owner != null && owner.attemptId != active.id) return stale
    if (owner != null && (!automaticAttempt || active.retryBudgetKey != automaticRetryBudgetKey(owner.lineageId))) return invalid
    val bound = automaticAttempt && (automaticOwnerRequired || owner != null)
    if (mutation.pendingTriggers != null) return if ((!bound || active.finishedAt == null) &&
        mutation.pendingTriggers.containsAll(active.pendingTriggers)) CommitStatus.COMMITTED else invalid
    if (bound && (owner == null || mutation.finishAt != null)) return invalid
    if (!automaticAttempt && owner != null) return invalid
    if (active.finishedAt != null) return invalid
    if (mutation.finishAt != null) return if (active.startedAt != null && mutation.finishAt >= active.startedAt) CommitStatus.COMMITTED else invalid
    if (currentDay != null && !currentDay.isValid(generation, config.enrolledDay, todayInSeoul)) return invalid
    fun matches(date: LocalDate, key: ReadScopeKey): Boolean = date in active.dates && key == active.scopeKey &&
        (currentDay == null || (currentDay.date == date && currentDay.scopeKey == key && currentDay.requestedScopes == active.requestedScopes))
    mutation.confirmation?.let { confirmation ->
        val key = confirmation.key
        if (key.generation != generation || !matches(key.date, key.scopeKey)) return stale
        val day = currentDay ?: return invalid
        if (day.revision?.digest != key.revision) return stale
        val file = day.datasets[key.dataset] ?: return stale
        if (file.revision.digest != key.revision) return stale
        if (file.phase == FilePhase.CONFIRMED) return if (file.targetHandle == confirmation.targetHandle && file.confirmedAt == confirmation.confirmedAt) CommitStatus.COMMITTED else invalid
        if (file.phase !in setOf(FilePhase.RUNNING, FilePhase.INDETERMINATE) || !file.populated ||
            file.startedAt == null || confirmation.confirmedAt < file.startedAt ||
            (file.targetHandle != null && file.targetHandle != confirmation.targetHandle)) return invalid
        return CommitStatus.COMMITTED
    }
    val next = requireNotNull(mutation.day)
    if (!matches(next.date, next.scopeKey) || next.requestedScopes != active.requestedScopes) return stale
    val changed = currentDay?.revision != next.revision
    if (changed && next.revision != null && next.phase !in setOf(DayPhase.PENDING, DayPhase.RUNNING)) return invalid
    for ((dataset, before) in currentDay?.datasets.orEmpty()) {
        val after = next.datasets[dataset]
        val prior = if (before.phase == FilePhase.CONFIRMED) FileConfirmation(
            FileKey(generation, next.date, next.scopeKey, dataset, before.revision.digest),
            before.targetHandle!!, before.confirmedAt!!) else before.priorConfirmation
        if (prior != null && (after == null || (after.phase != FilePhase.CONFIRMED && after.priorConfirmation != prior))) return invalid
        if (!changed && before.phase == FilePhase.CONFIRMED && after?.phase != FilePhase.CONFIRMED &&
            after?.phase !in setOf(FilePhase.RUNNING, FilePhase.INDETERMINATE, FilePhase.REMOTE_STALE)) return invalid
        if (after?.phase == FilePhase.REMOTE_STALE && (after.populated || after.targetHandle != before.targetHandle || !next.isChecked())) return invalid
        if (!changed && before.phase == FilePhase.CONFIRMED && after?.phase == FilePhase.CONFIRMED && after != before) return invalid
    }
    if (next.datasets.any { (dataset, file) -> file.phase == FilePhase.CONFIRMED &&
            currentDay?.datasets?.get(dataset) != file }) return invalid
    return CommitStatus.COMMITTED
}

/** 중단 복구는 실행 중 파일만 보수적으로 바꾸며 기존 확인과 차단 사유를 보존한다. */
fun DayState.afterInterruption(): DayState {
    val files = datasets.mapValues { (_, file) ->
        if (file.phase == FilePhase.RUNNING) file.copy(phase = FilePhase.INDETERMINATE) else file
    }
    val phase = if (this.phase == DayPhase.RUNNING) DayPhase.INDETERMINATE else this.phase
    return copy(phase = phase, datasets = files)
}
