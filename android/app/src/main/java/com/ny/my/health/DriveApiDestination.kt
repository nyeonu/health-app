package com.ny.my.health

import com.ny.my.health.core.*
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/** 현재 세대의 안전한 Drive 대상과 정확한 파일별 확인을 연결한다. 본문은 메모리에서만 쓴다. */
internal class DriveApiDestination(
    private val uploader: PrivateDriveUploader,
    private val generation: DestinationGeneration,
    private val knownTargets: suspend (DestinationGeneration, LocalDate) -> Map<Dataset, Set<String>>,
    private val clock: Clock = Clock.systemUTC(),
) : StorageDestination {
    constructor(uploader: PrivateDriveUploader, generation: DestinationGeneration,
        store: SqliteSyncStateStore, clock: Clock = Clock.systemUTC()) :
        this(uploader, generation, store::loadKnownDriveTargets, clock)

    override suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles = boundary {
        validate(files)
        val known = files.firstOrNull()?.let { knownTargets(generation, it.key.date) }.orEmpty()
        uploader.reconcileTracked(files, known)
    }

    override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) {
        validate(files)
        val intended = files.associateBy { it.file }
        val acknowledged = mutableSetOf<FileKey>()
        uploader.writeTrackedPending(files) { csv, id ->
            val planned = intended[csv] ?: throw DestinationException(BlockReason.DESTINATION_BLOCKED)
            if (!Regex("[A-Za-z0-9_-]{1,200}").matches(id) || !acknowledged.add(planned.key)) {
                throw DestinationException(BlockReason.DESTINATION_BLOCKED)
            }
            onConfirmed(FileConfirmation(planned.key, id, clock.instant()))
        }
    }

    private fun validate(files: List<PlannedFile>) {
        if (files.any { it.key.generation != generation } ||
            files.map { Triple(it.key.date, it.key.scopeKey, it.key.revision) }.distinct().size > 1 ||
            files.map { it.key }.distinct().size != files.size ||
            files.map { it.file.relativePath }.distinct().size != files.size) {
            throw DestinationException(BlockReason.DESTINATION_BLOCKED)
        }
    }

    private suspend fun <T> boundary(action: suspend () -> T): T = try {
        action()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (safe: DestinationException) {
        throw safe
    } catch (_: Exception) {
        throw DestinationException(BlockReason.DESTINATION_BLOCKED)
    }
}
