package com.ny.my.health.core

import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SyncCoordinator(
    private val reader: HealthReader,
    private val normalizer: Normalizer,
    private val exporter: CsvExporter,
    private val uploader: DriveUploader,
    private val clock: Clock,
    private val profiling: SyncProfiling? = null,
) {
    suspend fun syncYesterday(onDateSelected: (LocalDate) -> Unit = {}): SyncResult {
        currentCoroutineContext().ensureActive()
        val date = DayWindow.yesterday(clock)
        onDateSelected(date)
        return sync(date)
    }

    suspend fun sync(date: LocalDate): SyncResult {
        var uploadedFiles = 0
        var errorCode = "READ_FAILED"
        try {
            currentCoroutineContext().ensureActive()
            val snapshot = profiling?.measure(ProfileStage.HEALTH_READ) { reader.read(date) } ?: reader.read(date)
            currentCoroutineContext().ensureActive()
            if (snapshot.grantedTypes.isEmpty()) return SyncResult(SyncStatus.PERMISSION_REQUIRED)
            errorCode = "NORMALIZATION_FAILED"
            val day = profiling?.measureValue(ProfileStage.NORMALIZE) { normalizer.normalize(snapshot, clock.instant()) }
                ?: normalizer.normalize(snapshot, clock.instant())
            errorCode = "EXPORT_FAILED"
            val files = profiling?.measureValue(ProfileStage.CSV_EXPORT) { exporter.export(day) } ?: exporter.export(day)
            currentCoroutineContext().ensureActive()
            if (files.isEmpty()) return SyncResult(SyncStatus.EMPTY)
            errorCode = "UPLOAD_FAILED"
            profiling?.start(ProfileStage.DRIVE_TOTAL)
            var driveOutcome = ProfileOutcome.FAILED
            try {
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    val remoteId = uploader.upsert(file)
                    if (remoteId.isBlank()) return SyncResult(SyncStatus.FAILED, uploadedFiles, errorCode)
                    uploadedFiles++
                }
                currentCoroutineContext().ensureActive()
                driveOutcome = ProfileOutcome.COMPLETED
                return SyncResult(SyncStatus.SUCCESS, uploadedFiles)
            } catch (cancelled: CancellationException) {
                driveOutcome = ProfileOutcome.CANCELLED
                throw cancelled
            } finally {
                profiling?.finish(ProfileStage.DRIVE_TOTAL, driveOutcome)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: DriveUploadException) {
            return SyncResult(SyncStatus.FAILED, uploadedFiles, errorCode, safe.details)
        } catch (_: Exception) {
            return SyncResult(SyncStatus.FAILED, uploadedFiles, errorCode)
        }
    }
}
