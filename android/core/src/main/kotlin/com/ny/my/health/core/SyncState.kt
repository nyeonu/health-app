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
    SOURCE_LIMIT_EXCEEDED, DESTINATION_BLOCKED, STORE_UNAVAILABLE, RETRY_EXHAUSTED }
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
data class PlannedFile(val key: FileKey, val file: CsvFile, val byteSha256: String,
    val previousTargetHandle: String?)
data class ReconciledFiles(val confirmed: List<FileConfirmation>,
    val pending: List<PlannedFile>, val blocked: Set<FileKey>)
enum class CommitStatus { COMMITTED, STALE, INVALID, UNAVAILABLE }
data class SyncCommit(val status: CommitStatus, val generation: DestinationGeneration?)
data class SyncMutation(val attemptId: String?, val start: AttemptState?,
    val day: DayState?, val confirmation: FileConfirmation?,
    val pendingTriggers: Set<SyncTrigger>?, val finishAt: Instant?)
enum class BatchStatus { COMPLETED, PENDING, BLOCKED, MERGED, STALE }
data class BatchOutcome(val status: BatchStatus, val confirmedFiles: Int,
    val coverage: CoverageSummary?)

/** 입출력 없이 범위와 확인 메타데이터의 관계만 검사한다. */
fun SyncState.isValid(): Boolean {
    if (version != 1 || generation.value.isBlank() || days.size > 9 || oldestPendingDays.size > 7) return false
    if (oldestPendingDays != oldestPendingDays.distinct().sorted()) return false
    if (requestedScopes.values.any { it == ReadScope.BLOCKED }) return false
    if ((scopeKey == null) != requestedScopes.isEmpty()) return false
    if (coverage.scopeKey != scopeKey) return false
    if (listOfNotNull(coverage.scopePendingCount, coverage.scopeBlockedCount).any { it < 0 }) return false
    if (configuration == null) {
        return attempt == null && scopeKey == null && days.isEmpty() && oldestPendingDays.isEmpty() &&
            coverage == CoverageSummary(null, null, null, null, null, emptySet(), 0, 0, null, emptySet(), emptySet())
    }
    val config = configuration
    if (config.destinationHandle.isBlank() || !config.categories.containsAll(requestedScopes.keys)) return false
    if (scopeKey == null && (coverage.scopePendingCount != null || coverage.scopeBlockedCount != null)) return false
    if (oldestPendingDays.any { it < config.enrolledDay }) return false
    if (attempt?.let { a ->
            a.id.isBlank() || a.scopeKey != scopeKey || a.requestedScopes != requestedScopes ||
                a.retryBudgetKey.isBlank() || a.retriesUsed !in 0..3 || a.dates.size > 7 ||
                a.dates != a.dates.distinct().sorted() || a.dates.any { it < config.enrolledDay } ||
                (a.startedAt != null && a.startedAt < a.scheduledAt) ||
                (a.finishedAt != null && (a.startedAt == null || a.finishedAt < a.startedAt))
        } == true) return false
    return days.all { (date, day) ->
        date == day.date && date >= config.enrolledDay && day.scopeKey == scopeKey &&
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
