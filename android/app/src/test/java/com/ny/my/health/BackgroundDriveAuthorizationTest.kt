package com.ny.my.health

import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.DriveResponse
import com.ny.my.health.core.DriveTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackgroundDriveAuthorizationTest {
    @Test
    fun `Background authorization pins the selected account and proves the same fresh token before any health read without launching resolution`() = runBlocking {
        val account = BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")
        val generation = DestinationGeneration("synthetic-generation")
        val scopes = listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE)
        var callback: ((BackgroundDriveAuthorization.Result?) -> Unit)? = null
        var calls = 0
        var reads = 0
        var proofs = 0
        var current = true
        var response = """{"user":{"emailAddress":"selected@example.invalid","me":true}}"""
        val adapter = BackgroundDriveAuthorization.Adapter { selected, deliver ->
            assertEquals(account, selected)
            calls++
            callback = deliver
        }
        val authorizer = BackgroundDriveAuthorization(adapter)
        val factory: (String) -> DriveTransport = { token ->
            assertEquals("fresh-token", token)
            object : DriveTransport {
                override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                    assertEquals("GET", method)
                    assertEquals(BoundDriveAuthorization.ABOUT_URL, url)
                    proofs++
                    return DriveResponse(200, response)
                }
            }
        }
        suspend fun attempt(result: BackgroundDriveAuthorization.Result?): Boolean {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { authorizer.authorize(account, generation, { current }, factory) { reads++ } }.isSuccess
            }
            checkNotNull(callback)(result)
            return job.await()
        }
        val good = BackgroundDriveAuthorization.Result(false, "fresh-token", scopes)
        assertTrue(attempt(good))
        assertEquals(1, reads)
        assertEquals(1, proofs)
        for (bad in listOf(null, BackgroundDriveAuthorization.Result(true, "fresh-token", scopes),
            BackgroundDriveAuthorization.Result(false, null, scopes),
            BackgroundDriveAuthorization.Result(false, "fresh-token", emptyList()))) {
            assertFalse(attempt(bad))
        }
        assertEquals(1, proofs)
        response = """{"user":{"emailAddress":"other@example.invalid","me":true}}"""
        assertFalse(attempt(good))
        assertEquals(1, reads)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            authorizer.authorize(account, generation, { current }, factory) { reads++ }
        }
        val late = checkNotNull(callback)
        cancelled.cancelAndJoin()
        late(good)
        assertEquals(1, reads)
        val stale = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { authorizer.authorize(account, generation, { current }, factory) { reads++ } }.isSuccess
        }
        current = false
        checkNotNull(callback)(good)
        assertFalse(stale.await())
        assertEquals(1, reads)
        assertEquals(8, calls)
        assertFalse(authorizer.toString().contains("fresh-token"))
    }
}
