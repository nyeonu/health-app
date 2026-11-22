package com.ny.my.health

import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.DriveResponse
import com.ny.my.health.core.DriveTransport
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun `Suspending durable checks block stale callbacks and stale proof before reads and propagate cancellation`() = runBlocking {
        val account = BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")
        val generation = DestinationGeneration("synthetic-generation")
        val result = BackgroundDriveAuthorization.Result(false, "fresh-token", listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE))
        var proofs = 0
        var reads = 0
        val adapter = BackgroundDriveAuthorization.Adapter { _, deliver -> deliver(result) }
        val authorizer = BackgroundDriveAuthorization(adapter)
        val factory: (String) -> DriveTransport = { token ->
            assertEquals("fresh-token", token)
            object : DriveTransport {
                override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                    proofs++
                    return DriveResponse(200, """{"user":{"emailAddress":"selected@example.invalid","me":true}}""")
                }
            }
        }
        suspend fun staleAt(target: Int) {
            var checks = 0
            val pending = CompletableDeferred<Boolean>()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    authorizer.authorize(account, generation, {
                        checks++
                        if (checks == target) pending.await() else true
                    }, factory) { reads++ }
                }.exceptionOrNull()
            }
            assertEquals(target, checks)
            assertFalse(job.isCompleted)
            pending.complete(false)
            assertTrue(job.await() is BoundDriveAuthorizationException)
        }
        staleAt(2)
        assertEquals(0, proofs)
        assertEquals(0, reads)
        staleAt(3)
        assertEquals(1, proofs)
        assertEquals(0, reads)
        var checks = 0
        val pending = CompletableDeferred<Boolean>()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            authorizer.authorize(account, generation, {
                checks++
                if (checks == 2) pending.await() else true
            }, factory) { reads++ }
        }
        assertEquals(2, checks)
        assertFalse(cancelled.isCompleted)
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertEquals(1, proofs)
        assertEquals(0, reads)
    }

}
