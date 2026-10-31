package com.ny.my.health.core

/** Fixed diagnostics only: never pass exception text, accounts or granted-scope lists here. */
object AuthorizationDiagnostics {
    enum class Failure {
        CONSENT_CANCELED, TASK_CANCELED, GOOGLE_API, UNKNOWN,
        MISSING_INTENT, UNEXPECTED_RESULT, MISSING_RESULT, MISSING_RESOLUTION,
        RESOLUTION_LAUNCH_FAILED, FOREGROUND_DEPARTURE,
        MISSING_TOKEN, INVALID_TOKEN, MISSING_DRIVE_SCOPE,
    }

    fun resultFailure(token: String?, hasDriveScope: Boolean): Failure? = when {
        token.isNullOrEmpty() -> Failure.MISSING_TOKEN
        token.any { it.code !in 33..126 } -> Failure.INVALID_TOKEN
        !hasDriveScope -> Failure.MISSING_DRIVE_SCOPE
        else -> null
    }

    fun message(failure: Failure, statusCode: Int? = null): String {
        val detail = when (failure) {
            Failure.CONSENT_CANCELED -> "OAUTH_CONSENT_CANCELED: Google 동의 화면이 취소되었습니다."
            Failure.TASK_CANCELED -> "OAUTH_TASK_CANCELED: Google 권한 확인 작업이 취소되었습니다."
            Failure.GOOGLE_API -> if (statusCode == 10) {
                "OAUTH_GOOGLE_API_STATUS_10: 앱 패키지·서명 인증서·테스트 사용자 설정을 확인해 주세요. 원인은 아직 확인되지 않았습니다."
            } else {
                "OAUTH_GOOGLE_API_STATUS_${statusCode ?: "UNKNOWN"}: Google 권한 확인에 실패했습니다."
            }
            Failure.UNKNOWN -> "OAUTH_UNKNOWN_FAILURE: Google 권한 확인 중 알 수 없는 오류가 발생했습니다."
            Failure.MISSING_INTENT -> "OAUTH_MISSING_INTENT: Google 동의 응답 데이터가 없습니다."
            Failure.UNEXPECTED_RESULT -> "OAUTH_UNEXPECTED_RESULT: Google 동의 화면에서 예상하지 못한 결과가 반환되었습니다."
            Failure.MISSING_RESULT -> "OAUTH_MISSING_RESULT: Google 권한 확인 결과가 없습니다."
            Failure.MISSING_RESOLUTION -> "OAUTH_MISSING_RESOLUTION: Google 동의 화면을 열기 위한 정보가 없습니다."
            Failure.RESOLUTION_LAUNCH_FAILED -> "OAUTH_RESOLUTION_LAUNCH_FAILED: Google 동의 화면을 열지 못했습니다."
            Failure.FOREGROUND_DEPARTURE -> "OAUTH_FOREGROUND_DEPARTURE: 앱이 전경을 벗어나 Google 동의 화면을 열지 않았습니다."
            Failure.MISSING_TOKEN -> "OAUTH_MISSING_TOKEN: Google 액세스 토큰이 없습니다."
            Failure.INVALID_TOKEN -> "OAUTH_INVALID_TOKEN: Google 액세스 토큰 형식을 확인할 수 없습니다."
            Failure.MISSING_DRIVE_SCOPE -> "OAUTH_MISSING_DRIVE_SCOPE: 필요한 Drive 파일 권한이 없습니다."
        }
        return "$detail 성공한 동기화가 아닙니다. 다시 수동으로 시도해 주세요."
    }
}
