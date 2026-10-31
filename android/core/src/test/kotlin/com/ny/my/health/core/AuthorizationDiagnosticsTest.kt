package com.ny.my.health.core

import com.ny.my.health.core.AuthorizationDiagnostics.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationDiagnosticsTest {
    @Test
    fun `Cancelled consent and Google API status produce distinct redacted diagnostics`() {
        val canceled = AuthorizationDiagnostics.message(Failure.CONSENT_CANCELED)
        val taskCanceled = AuthorizationDiagnostics.message(Failure.TASK_CANCELED)
        val apiFailure = AuthorizationDiagnostics.message(Failure.GOOGLE_API, 10)
        assertTrue(canceled.startsWith("OAUTH_CONSENT_CANCELED:"))
        assertTrue(taskCanceled.startsWith("OAUTH_TASK_CANCELED:"))
        assertTrue(apiFailure.startsWith("OAUTH_GOOGLE_API_STATUS_10:"))
        assertTrue(apiFailure.contains("패키지·서명 인증서·테스트 사용자"))
        assertTrue(apiFailure.contains("원인은 아직 확인되지 않았습니다"))
        assertTrue(AuthorizationDiagnostics.message(Failure.GOOGLE_API, 7).startsWith("OAUTH_GOOGLE_API_STATUS_7:"))
        val otherFailures = mapOf(
            Failure.UNKNOWN to "OAUTH_UNKNOWN_FAILURE:",
            Failure.MISSING_INTENT to "OAUTH_MISSING_INTENT:",
            Failure.UNEXPECTED_RESULT to "OAUTH_UNEXPECTED_RESULT:",
            Failure.MISSING_RESULT to "OAUTH_MISSING_RESULT:",
            Failure.MISSING_RESOLUTION to "OAUTH_MISSING_RESOLUTION:",
            Failure.RESOLUTION_LAUNCH_FAILED to "OAUTH_RESOLUTION_LAUNCH_FAILED:",
            Failure.FOREGROUND_DEPARTURE to "OAUTH_FOREGROUND_DEPARTURE:",
        )
        for ((failure, prefix) in otherFailures) {
            assertTrue(AuthorizationDiagnostics.message(failure).startsWith(prefix))
        }
    }

    @Test
    fun `Missing token invalid token and missing Drive scope are distinguished`() {
        for (token in listOf(null, "")) {
            assertEquals(Failure.MISSING_TOKEN, AuthorizationDiagnostics.resultFailure(token, true))
        }
        for (token in listOf(" ", "synthetic\nvalue", "synthetic\u007fvalue", "synthetic한글")) {
            assertEquals(Failure.INVALID_TOKEN, AuthorizationDiagnostics.resultFailure(token, true))
        }
        assertEquals(Failure.MISSING_DRIVE_SCOPE, AuthorizationDiagnostics.resultFailure("synthetic-token", false))
        assertNull(AuthorizationDiagnostics.resultFailure("!synthetic-token~", true))
        assertEquals(Failure.MISSING_TOKEN, AuthorizationDiagnostics.resultFailure(null, false))
        assertEquals(Failure.INVALID_TOKEN, AuthorizationDiagnostics.resultFailure("\n", false))
    }

    @Test
    fun `OAuth diagnostics never expose token scope lists or exception details`() {
        val token = "synthetic-secret-token\n"
        val scopes = listOf("https://www.googleapis.com/auth/drive.file", "synthetic-private-scope")
        val rawFailure = IllegalStateException("synthetic-private-account and exception detail")
        val resultFailure = AuthorizationDiagnostics.resultFailure(token, true)
        assertEquals(Failure.INVALID_TOKEN, resultFailure)
        val messages = Failure.entries.map { AuthorizationDiagnostics.message(it, 10) } +
            AuthorizationDiagnostics.message(requireNotNull(resultFailure))
        for (message in messages) {
            assertFalse(message.contains(token.trim()))
            scopes.forEach { assertFalse(message.contains(it)) }
            assertFalse(message.contains(requireNotNull(rawFailure.message)))
            assertFalse(message.contains(rawFailure.javaClass.name))
            assertFalse(message.contains("SUCCESS"))
            assertTrue(message.contains("성공한 동기화가 아닙니다"))
        }
        // Formatting accepts only a closed category and numeric status, never Throwable or text.
        assertEquals(
            AuthorizationDiagnostics.message(Failure.MISSING_TOKEN),
            AuthorizationDiagnostics.message(Failure.MISSING_TOKEN, Int.MIN_VALUE),
        )
    }
}
