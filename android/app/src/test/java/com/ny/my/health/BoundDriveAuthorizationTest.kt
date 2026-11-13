package com.ny.my.health

import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.DriveResponse
import com.ny.my.health.core.DriveTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BoundDriveAuthorizationTest {
    private val account = BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")
    private val generation = DestinationGeneration("synthetic-generation")
    private val ticket = BoundDriveAuthorization.Ticket(7, generation, account)
    private val scopes = listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE)

    @Test
    fun `Picker cancellation malformed values and stale ticket never configure or authorize`() {
        fun candidate(name: String? = account.name, type: String? = account.type,
            ok: Boolean = true, current: Long? = 7, returned: Long? = 7, extras: Boolean = true) =
            BoundDriveAuthorization.pickerCandidate(ok, current, returned, extras, name, type)
        assertEquals(account, candidate())
        assertNotNull(candidate("a".repeat(1024)))
        assertNotNull(candidate("가".repeat(341) + "a"))
        for (name in listOf(null, "", " ", "a".repeat(1025), "가".repeat(342), "a\u0000b", "a\u0085b", "\uD800")) {
            assertNull(candidate(name))
        }
        for (type in listOf(null, "", "com.other", "com.google\n", "a".repeat(1025), "\uDFFF")) {
            assertNull(candidate(type = type))
        }
        assertNull(candidate(ok = false))
        assertNull(candidate(extras = false))
        assertNull(candidate(current = null))
        assertNull(candidate(returned = null))
        assertNull(candidate(returned = 6))
        assertNull(BoundDriveAuthorization.acceptResult(ticket, ticket.copy(id = 8), generation, false, "issued-token", scopes))
        assertNull(BoundDriveAuthorization.acceptResult(ticket, ticket, DestinationGeneration("late"), false, "issued-token", scopes))
        for (token in listOf(null, "", "bad token", "bad\nvalue")) {
            assertNull(BoundDriveAuthorization.acceptResult(ticket, ticket, generation, false, token, scopes))
        }
        assertNull(BoundDriveAuthorization.acceptResult(ticket, ticket, generation, false, "issued-token", emptyList()))
        assertNull(BoundDriveAuthorization.acceptResult(ticket, ticket, generation, true, "issued-token", scopes))
        assertNotNull(BoundDriveAuthorization.acceptResult(ticket, ticket, generation, false, "issued-token", scopes))
    }

    @Test
    fun `Same issued token about proof must exactly match selected account before any raw health read`() = runBlocking {
        val accepted = checkNotNull(BoundDriveAuthorization.acceptResult(ticket, ticket, generation, false, "issued-token", scopes))
        var reads = 0
        var requests = 0
        var current = true
        var response = DriveResponse(200, """{"user":{"emailAddress":"selected@example.invalid","me":true}}""")
        val transport = object : DriveTransport {
            override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                requests++
                assertEquals("GET", method)
                assertEquals(BoundDriveAuthorization.ABOUT_URL, url)
                assertNull(body)
                assertNull(contentType)
                return response
            }
        }
        val factory: (String) -> DriveTransport = { token -> assertEquals("issued-token", token); transport }
        accepted.withVerifiedTransport({ current }, factory) { assertSame(transport, it); reads++ }
        assertEquals(1, reads)
        assertEquals(1, requests)
        for (payload in listOf("invalid", "{}", """{"user":null}""", """{"user":{}}""",
            """{"user":{"emailAddress":"selected@example.invalid"}}""",
            """{"user":{"me":true}}""",
            """{"user":{"emailAddress":"selected@example.invalid","me":"true"}}""",
            """{"user":{"emailAddress":4,"me":true}}""",
            """{"user":{"emailAddress":"selected@example.invalid","me":false}}""",
            """{"user":{"emailAddress":"Selected@example.invalid","me":true}}""",
            """{"user":{"emailAddress":"selected+alias@example.invalid","me":true}}""")) {
            response = DriveResponse(200, payload)
            val failure = runCatching { accepted.withVerifiedTransport({ true }, factory) { reads++ } }.exceptionOrNull()
            assertTrue(failure is BoundDriveAuthorizationException)
            assertNull(failure?.cause)
            assertEquals(1, reads)
        }
        response = DriveResponse(200, """{"user":{"emailAddress":"selected@example.invalid","me":true}}""")
        val before = requests
        current = false
        assertTrue(runCatching { accepted.withVerifiedTransport({ current }, factory) { reads++ } }.isFailure)
        assertEquals(before, requests)
        var checks = 0
        assertTrue(runCatching { accepted.withVerifiedTransport({ ++checks == 1 }, factory) { reads++ } }.isFailure)
        assertEquals(1, reads)
        assertFalse(accepted.toString().contains("issued-token"))
    }
}
