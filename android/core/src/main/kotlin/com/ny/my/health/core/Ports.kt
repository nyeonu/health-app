package com.ny.my.health.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ensureActive

interface HealthReader {
    suspend fun read(date: LocalDate): HealthSnapshot
}

interface Normalizer {
    fun normalize(snapshot: HealthSnapshot, generatedAt: Instant): NormalizedDay
}

interface CsvExporter {
    fun export(day: NormalizedDay): List<CsvFile>
}

interface DriveUploader {
    suspend fun upsert(file: CsvFile): String

    suspend fun upsertBatch(files: List<CsvFile>, onConfirmed: suspend (String) -> Unit) {
        for (file in files) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val id = upsert(file)
            check(id.isNotBlank()) { "Unconfirmed Drive upload" }
            onConfirmed(id)
        }
    }
}

/**
 * 접근 메타데이터와 이미 승인한 읽기 범위를 분리한다.
 * MANUAL은 사용자가 요청한 제한된 과거 복구를 포함하는 명시적 전경 정책이다.
 * SCHEDULED와 CATCH_UP은 자동·백그라운드 정책이며 호출별 trigger로 선택한다.
 */
interface ScopedHealthReader {
    suspend fun access(trigger: SyncTrigger): ReadAccess
    suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot
}

interface SyncStateStore {
    suspend fun load(): SyncStateRead
    suspend fun transact(expected: DestinationGeneration?, mutation: SyncMutation): SyncCommit
    suspend fun reset(expected: DestinationGeneration?): SyncCommit
    suspend fun loadForScope(expected: DestinationGeneration, todayInSeoul: LocalDate, access: ReadAccess): SyncStateRead
    suspend fun loadDay(expected: DestinationGeneration, date: LocalDate, scopeKey: ReadScopeKey): DayState?
}

/** 파일 본문은 메모리에서만 전달하며 불명확한 기존 대상을 새로 만들지 않는다. */
interface StorageDestination {
    suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles
    suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit)
}

/** 제공자 메시지나 원인을 담지 않는 허용 목록 기반 목적지 실패다. */
class DestinationException(val reason: BlockReason) : IllegalStateException(reason.name) {
    init {
        require(reason in setOf(BlockReason.DESTINATION_TRANSIENT_FAILURE,
            BlockReason.DESTINATION_RECONCILIATION_REQUIRED, BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED,
            BlockReason.DESTINATION_BLOCKED))
    }
}
