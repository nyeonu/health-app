package com.ny.my.health.core

enum class DriveOperation {
    ROOT_LOOKUP, APP_FOLDER_LOOKUP, APP_FOLDER_CREATE,
    DATASET_FOLDER_LOOKUP, DATASET_FOLDER_CREATE, FILE_LOOKUP, FILE_CREATE, FILE_UPDATE,
}

enum class DriveFailureKind { HTTP, NETWORK, RESPONSE_INVALID, TARGET_REJECTED }

/** Fixed categories only; never store provider messages, identifiers or response bodies. */
data class DriveFailureDetails(
    val operation: DriveOperation,
    val kind: DriveFailureKind,
    val httpStatus: Int? = null,
) {
    init { require(httpStatus == null || (kind == DriveFailureKind.HTTP && httpStatus in 100..599)) }
    val code: String get() = "DRIVE_${operation.name}:${kind.name}" + (httpStatus?.let { "_$it" } ?: "")
}

class DriveUploadException(val details: DriveFailureDetails) : IllegalStateException(
    when (details.kind) {
        DriveFailureKind.HTTP -> "Drive request failed (HTTP ${details.httpStatus ?: "unknown"})"
        DriveFailureKind.RESPONSE_INVALID -> "Malformed Drive response"
        DriveFailureKind.NETWORK -> "Drive transport failed"
        DriveFailureKind.TARGET_REJECTED -> "Unsafe or malformed Drive target"
    },
) {
    // Coroutine stack-trace recovery must not reattach a sensitive source exception.
    override val cause: Throwable? get() = null
}

object SyncDiagnostics {
    fun failureMessage(result: SyncResult): String {
        val phase = result.errorCode?.takeIf {
            it in setOf("READ_FAILED", "NORMALIZATION_FAILED", "EXPORT_FAILED", "UPLOAD_FAILED")
        } ?: "SYNC_FAILED"
        val failure = result.driveFailure
        val code = phase + (failure?.let { ":${it.code}" } ?: "")
        val progress = if (result.uploadedFiles > 0) {
            "부분 업로드 후 실패: ${result.uploadedFiles}개 파일만 확인됨. 전체 작업은 성공하지 않았습니다."
        } else "실패: 동기화를 완료하지 못했습니다."
        val hint = when {
            failure?.httpStatus == 401 -> "Google 승인을 다시 진행해 주세요."
            failure?.httpStatus == 403 -> "같은 Google Cloud 프로젝트의 Google Drive API 사용 설정과 계정 접근 권한을 확인해 주세요. 403만으로 원인을 확정할 수는 없습니다."
            failure?.httpStatus == 429 || (failure?.httpStatus ?: 0) >= 500 -> "잠시 후 수동으로 다시 시도해 주세요."
            failure?.kind == DriveFailureKind.NETWORK -> "네트워크 연결을 확인한 뒤 수동으로 다시 시도해 주세요."
            failure?.kind == DriveFailureKind.TARGET_REJECTED -> "비공개 저장 위치 또는 응답을 확인하지 못해 중단했습니다."
            else -> "아래 진단 코드만 공유해 주세요."
        }
        return "$progress\n진단: $code\n$hint"
    }
}
