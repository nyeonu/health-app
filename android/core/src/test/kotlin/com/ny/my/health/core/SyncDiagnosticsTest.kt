package com.ny.my.health.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncDiagnosticsTest {
    @Test
    fun `HTTP failure survives coordinator and appears in the user message without provider data`() = runBlocking {
        val date = LocalDate.of(2026, 10, 30)
        val reader = object : HealthReader {
            override suspend fun read(date: LocalDate) = HealthSnapshot(date, 42, emptyList(), emptyList(), emptyList(), emptyList(), setOf(DataType.STEPS))
        }
        val transport = object : DriveTransport {
            override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?) = DriveResponse(403, "sensitive-provider-body")
        }
        val result = SyncCoordinator(reader, DefaultNormalizer(), VersionedCsvExporter(), PrivateDriveUploader(transport),
            Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC)).sync(date)
        assertEquals(SyncStatus.FAILED, result.status)
        assertEquals(0, result.uploadedFiles)
        assertEquals("UPLOAD_FAILED", result.errorCode)
        assertEquals(DriveFailureDetails(DriveOperation.APP_FOLDER_LOOKUP, DriveFailureKind.HTTP, 403), result.driveFailure)
        val message = SyncDiagnostics.failureMessage(result)
        assertTrue(message.contains("UPLOAD_FAILED:DRIVE_APP_FOLDER_LOOKUP:HTTP_403"))
        assertTrue(message.contains("Google Drive API"))
        assertTrue(message.contains("원인을 확정할 수는 없습니다"))
        assertFalse(message.contains("sensitive"))
    }

    @Test
    fun `Partial progress and fixed phase diagnostics preserve failure without leaking arbitrary codes`() {
        val partial = SyncResult(SyncStatus.FAILED, 2, "UPLOAD_FAILED", DriveFailureDetails(DriveOperation.FILE_UPDATE, DriveFailureKind.HTTP, 503))
        val message = SyncDiagnostics.failureMessage(partial)
        assertTrue(message.contains("2개 파일만 확인됨"))
        assertTrue(message.contains("전체 작업은 성공하지 않았습니다"))
        assertTrue(message.contains("DRIVE_FILE_UPDATE:HTTP_503"))
        for (phase in listOf("READ_FAILED", "NORMALIZATION_FAILED", "EXPORT_FAILED", "UPLOAD_FAILED")) {
            assertTrue(SyncDiagnostics.failureMessage(SyncResult(SyncStatus.FAILED, errorCode = phase)).contains(phase))
        }
        val unsafe = SyncDiagnostics.failureMessage(SyncResult(SyncStatus.FAILED, errorCode = "sensitive-exception-message"))
        assertTrue(unsafe.contains("SYNC_FAILED"))
        assertFalse(unsafe.contains("sensitive"))
    }
}
