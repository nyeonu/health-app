package com.ny.my.health

import com.ny.my.health.core.CsvFile
import com.ny.my.health.core.DriveFailureKind
import com.ny.my.health.core.DriveOperation
import com.ny.my.health.core.DriveUploadException
import com.ny.my.health.core.ProfileStats
import com.ny.my.health.core.PrivateDriveUploader
import com.ny.my.health.core.SyncProfiling
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** Real OkHttp and local TLS only: these tests do not validate a Google account. */
class GoogleDriveTransportTest {
    private val endpoint = "https://www.googleapis.com/drive/v3/files"
    private val file = CsvFile("daily/2026-10-30.csv", "schema_version,date\r\n0.1,2026-10-30\r\n")
    private val root = entry("app-root", "HealthApp", "my-drive", "application/vnd.google-apps.folder")
    private val dataset = entry("daily-folder", "daily", "app-root", "application/vnd.google-apps.folder")
    private val remote = entry("date-file", "2026-10-30.csv", "daily-folder", "text/csv")

    private fun entry(id: String, name: String, parent: String, mime: String) =
        """{"id":"$id","name":"$name","parents":["$parent"],"mimeType":"$mime","ownedByMe":true,"shared":false,"trashed":false,"appProperties":{"healthApp":"p0-v1"}}"""

    private class LocalTls : AutoCloseable {
        val server = MockWebServer()
        private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        init {
            val identity = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            server.useHttps(identity.sslSocketFactory())
            server.start()
        }
        fun client(listener: EventListener = EventListener.NONE): OkHttpClient =
            GoogleDriveTransport.productionClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .eventListener(listener)
                .addInterceptor { chain ->
                    // The production transport has already validated this original Google URL.
                    val original = chain.request()
                    val local = server.url("/").newBuilder()
                        .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
                    chain.proceed(original.newBuilder().url(local).build())
                }.build()
        fun transport(client: OkHttpClient = client()) = GoogleDriveTransport("synthetic-test-token", client)
        fun enqueue(body: String, status: Int = 200) = server.enqueue(MockResponse(code = status, body = body))
        fun request(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Expected local request" }
        override fun close() = server.close()
    }

    private fun assertStats(
        stats: ProfileStats?, completed: Long = 0, failed: Long = 0, cancelled: Long = 0,
    ) {
        val actual = checkNotNull(stats)
        assertEquals(completed + failed + cancelled, actual.started)
        assertEquals(completed, actual.completed)
        assertEquals(failed, actual.failed)
        assertEquals(cancelled, actual.cancelled)
        assertFalse(actual.active)
        assertTrue(actual.elapsedNanos >= 0)
    }

    private fun assertRequestCount(local: LocalTls, profiling: SyncProfiling) {
        val summary = profiling.snapshot()
        assertEquals(local.server.requestCount.toLong(), summary.requests.values.sumOf { it.started })
        assertTrue(summary.requests.values.none { it.active })
        assertTrue(summary.operations.values.none { it.active })
    }

    private fun lookup(local: LocalTls, exists: Boolean) {
        local.enqueue("""{"files":[$root],"incompleteSearch":false}""")
        local.enqueue("""{"files":[$dataset],"incompleteSearch":false}""")
        local.enqueue("""{"files":[${if (exists) remote else ""}],"incompleteSearch":false}""")
    }

    @Test
    fun `Local TLS create then PATCH updates the same Drive file without duplicates`() = runBlocking {
        LocalTls().use { local ->
            val profiling = SyncProfiling()
            val uploader = PrivateDriveUploader(local.transport(), profiling)
            lookup(local, false)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file))
            lookup(local, true)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file.copy(content = file.content + "\r\n")))
            val requests = List(8) { local.request() }
            assertEquals(listOf("GET", "GET", "GET", "POST", "GET", "GET", "GET", "PATCH"), requests.map { it.method })
            assertTrue(requests.all { it.handshake != null })
            assertTrue(requests.all { it.headers["Authorization"] == "Bearer synthetic-test-token" })
            assertTrue(requests[3].url.queryParameter("uploadType") == "multipart")
            assertTrue(requests[3].body!!.utf8().contains(file.content))
            assertEquals("/upload/drive/v3/files/date-file", requests.last().url.encodedPath)
            assertEquals(file.content + "\r\n", requests.last().body!!.utf8())
            assertEquals(1, requests.count { it.method == "POST" })
            assertEquals(8, local.server.requestCount)
            assertRequestCount(local, profiling)
            val summary = profiling.snapshot()
            for (kind in listOf(DriveOperation.APP_FOLDER_LOOKUP, DriveOperation.DATASET_FOLDER_LOOKUP, DriveOperation.FILE_LOOKUP)) {
                assertStats(summary.operations[kind], completed = 2)
                assertStats(summary.requests[kind], completed = 2)
            }
            for (kind in listOf(DriveOperation.FILE_CREATE, DriveOperation.FILE_UPDATE)) {
                assertStats(summary.operations[kind], completed = 1)
                assertStats(summary.requests[kind], completed = 1)
            }
        }
    }

    @Test
    fun `Revoked authorization fails safely and a later explicit retry updates without duplicates`() = runBlocking {
        LocalTls().use { local ->
            val profiling = SyncProfiling()
            val uploader = PrivateDriveUploader(local.transport(), profiling)
            lookup(local, false)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file))
            lookup(local, true)
            local.enqueue("sensitive-provider-body", 401)
            val error = runCatching { uploader.upsert(file) }.exceptionOrNull()!!
            assertEquals("Drive request failed (HTTP 401)", error.message)
            assertNull(error.cause)
            assertEquals(8, local.server.requestCount)
            assertStats(profiling.snapshot().requests[DriveOperation.FILE_UPDATE], failed = 1)
            assertStats(profiling.snapshot().operations[DriveOperation.FILE_UPDATE], failed = 1)
            // The caller explicitly retries; the transport never refreshes or repeats a write.
            lookup(local, true)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file))
            val requests = List(12) { local.request() }
            assertEquals(1, requests.count { it.method == "POST" })
            assertEquals(2, requests.count { it.method == "PATCH" })
            assertTrue(requests.filter { it.method == "PATCH" }.all { it.url.encodedPath.endsWith("/date-file") })
            assertEquals(listOf("GET", "GET", "GET", "POST", "GET", "GET", "GET", "PATCH", "GET", "GET", "GET", "PATCH"), requests.map { it.method })
            assertRequestCount(local, profiling)
            assertStats(profiling.snapshot().requests[DriveOperation.FILE_CREATE], completed = 1)
            assertStats(profiling.snapshot().requests[DriveOperation.FILE_UPDATE], completed = 1, failed = 1)
            assertStats(profiling.snapshot().operations[DriveOperation.FILE_UPDATE], completed = 1, failed = 1)
        }
    }

    @Test
    fun `Redirects and disallowed endpoints never receive authorization`() = runBlocking {
        LocalTls().use { local ->
            LocalTls().use { destination ->
                val transport = local.transport()
                val profiling = SyncProfiling()
                for (code in listOf(301, 302, 307, 308)) {
                    local.server.enqueue(MockResponse.Builder().code(code).addHeader("Location", destination.server.url("/capture")).body("sensitive-redirect-body").build())
                    val response = profiling.request(DriveOperation.APP_FOLDER_LOOKUP) { transport.execute("GET", endpoint) }
                    assertEquals(code, response.status)
                    assertEquals("", response.body)
                    local.request()
                }
                for (bad in listOf(
                    destination.server.url("/drive/v3/files").toString(),
                    "http://www.googleapis.com/drive/v3/files",
                    "https://www.googleapis.com.evil.example/drive/v3/files",
                    "https://user@www.googleapis.com/drive/v3/files",
                    "https://www.googleapis.com:443/drive/v3/files",
                    "$endpoint#fragment", "$endpoint/../permissions", "$endpoint/%2e%2e", "$endpoint/id/permissions",
                )) {
                    assertTrue(runCatching { transport.execute("GET", bad) }.exceptionOrNull() is IllegalArgumentException)
                }
                assertEquals(4, local.server.requestCount)
                assertRequestCount(local, profiling)
                assertStats(profiling.snapshot().requests[DriveOperation.APP_FOLDER_LOOKUP], completed = 4)
                assertNull(destination.server.takeRequest(200, TimeUnit.MILLISECONDS))
                assertEquals(0, destination.server.requestCount)
            }
        }
    }

    @Test
    fun `Transport failures and oversized responses expose no sensitive body or cause`() = runBlocking {
        LocalTls().use { local ->
            val transport = local.transport()
            val profiling = SyncProfiling()
            local.enqueue("x".repeat(1_048_576))
            assertEquals(1_048_576, profiling.request(DriveOperation.APP_FOLDER_LOOKUP) { transport.execute("GET", endpoint) }.body.length)
            local.request()
            local.enqueue("sensitive-body" + "x".repeat(1_048_576))
            val oversized = runCatching { profiling.request(DriveOperation.APP_FOLDER_LOOKUP) { transport.execute("GET", endpoint) } }.exceptionOrNull()!!
            assertEquals("Drive transport failed", oversized.message)
            assertNull(oversized.cause)
            local.request()
            local.enqueue("sensitive-provider-body", 503)
            val failedWrite = profiling.request(DriveOperation.FILE_CREATE) { transport.execute("POST", endpoint, "text/csv", file.content.toByteArray()) }
            assertEquals(503, failedWrite.status)
            assertEquals("", failedWrite.body)
            local.request()
            assertEquals(3, local.server.requestCount)
            local.enqueue("invalid-sensitive-body")
            val malformed = runCatching { PrivateDriveUploader(transport, profiling).upsert(file) }.exceptionOrNull()!!
            assertEquals("Malformed Drive response", malformed.message)
            assertNull(malformed.cause)
            local.request()
            assertRequestCount(local, profiling)
            assertStats(profiling.snapshot().requests[DriveOperation.APP_FOLDER_LOOKUP], completed = 1, failed = 2)
            assertStats(profiling.snapshot().requests[DriveOperation.FILE_CREATE], completed = 1)
            // System trust rejects the self-signed server: TLS failure is redacted, without a cause.
            val untrusted = GoogleDriveTransport.productionClient().newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(local.server.url("/drive/v3/files")).build())
            }.build()
            val tls = runCatching { local.transport(untrusted).execute("GET", endpoint) }.exceptionOrNull()!!
            assertTrue(tls is IOException)
            assertEquals("Drive transport failed", tls.message)
            assertNull(tls.cause)
        }
    }

    @Test
    fun `Server retry after zero never replays POST or PATCH without an explicit new attempt`() = runBlocking {
        for (method in listOf("POST", "PATCH")) {
            for (body in listOf(file.content.toByteArray(), null)) {
                LocalTls().use { local ->
                    val transport = local.transport()
                    val profiling = SyncProfiling()
                    val kind = if (method == "POST") DriveOperation.FILE_CREATE else DriveOperation.FILE_UPDATE
                    local.server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("sensitive-provider-body").build())
                    local.enqueue("{}")
                    // 원시 transport는 HTTP 응답을 반환하며 실패 판정은 uploader 경계에서 한다.
                    val first = profiling.request(kind) { transport.execute(method, endpoint, "text/csv", body) }
                    assertEquals(503, first.status)
                    assertEquals("", first.body)
                    assertEquals(1, local.server.requestCount)
                    val firstRequest = local.request()
                    assertEquals(method, firstRequest.method)
                    assertEquals(body?.toString(Charsets.UTF_8) ?: "", firstRequest.body?.utf8() ?: "")
                    assertRequestCount(local, profiling)
                    assertStats(profiling.snapshot().requests[kind], completed = 1)
                    val retry = profiling.request(kind) { transport.execute(method, endpoint, "text/csv", body) }
                    assertEquals(200, retry.status)
                    assertEquals("{}", retry.body)
                    assertEquals(2, local.server.requestCount)
                    val retryRequest = local.request()
                    assertEquals(method, retryRequest.method)
                    assertEquals(body?.toString(Charsets.UTF_8) ?: "", retryRequest.body?.utf8() ?: "")
                    assertRequestCount(local, profiling)
                    assertStats(profiling.snapshot().requests[kind], completed = 2)
                }
            }
        }
    }

    @Test
    fun `Instrumented local TLS keeps request order private validation and cancellation without replay`() = runBlocking {
        LocalTls().use { local ->
            val profiling = SyncProfiling()
            val uploader = PrivateDriveUploader(local.transport(), profiling)
            local.enqueue("""{"files":[${root.replace("\"shared\":false", "\"shared\":true")}],"incompleteSearch":false}""")
            val rejected = runCatching { uploader.upsert(file) }.exceptionOrNull() as DriveUploadException
            assertEquals(DriveFailureKind.TARGET_REJECTED, rejected.details.kind)
            assertEquals("GET", local.request().method)
            assertEquals(1, local.server.requestCount)
            assertRequestCount(local, profiling)
            assertStats(profiling.snapshot().requests[DriveOperation.APP_FOLDER_LOOKUP], completed = 1)
            assertStats(profiling.snapshot().operations[DriveOperation.APP_FOLDER_LOOKUP], failed = 1)
            assertFalse(profiling.snapshot().requests.containsKey(DriveOperation.DATASET_FOLDER_LOOKUP))
        }
        LocalTls().use { local ->
            val profiling = SyncProfiling()
            local.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            val disconnected = runCatching {
                PrivateDriveUploader(local.transport(), profiling).upsert(file)
            }.exceptionOrNull() as DriveUploadException
            assertEquals(DriveFailureKind.NETWORK, disconnected.details.kind)
            assertNull(disconnected.cause)
            assertEquals("GET", local.request().method)
            assertEquals(1, local.server.requestCount)
            assertRequestCount(local, profiling)
            assertStats(profiling.snapshot().requests[DriveOperation.APP_FOLDER_LOOKUP], failed = 1)
            assertStats(profiling.snapshot().operations[DriveOperation.APP_FOLDER_LOOKUP], failed = 1)
        }
        for (exists in listOf(false, true)) {
            LocalTls().use { local ->
                val profiling = SyncProfiling()
                val uploader = PrivateDriveUploader(local.transport(), profiling)
                val kind = if (exists) DriveOperation.FILE_UPDATE else DriveOperation.FILE_CREATE
                val method = if (exists) "PATCH" else "POST"
                lookup(local, exists)
                local.server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("sensitive-provider-body").build())
                // 남아 있는 성공 응답을 자동 재생이 소비하지 않는지 확인한다.
                local.enqueue(remote)
                val failed = runCatching { uploader.upsert(file) }.exceptionOrNull() as DriveUploadException
                assertEquals(DriveFailureKind.HTTP, failed.details.kind)
                assertEquals(503, failed.details.httpStatus)
                assertNull(failed.cause)
                val requests = List(4) { local.request() }
                assertEquals(listOf("GET", "GET", "GET", method), requests.map { it.method })
                assertEquals(listOf("/drive/v3/files", "/drive/v3/files", "/drive/v3/files",
                    if (exists) "/upload/drive/v3/files/date-file" else "/upload/drive/v3/files"), requests.map { it.url.encodedPath })
                assertEquals(listOf(
                    "'root' in parents and name = 'HealthApp' and trashed = false and appProperties has { key='healthApp' and value='p0-v1' }",
                    "'app-root' in parents and name = 'daily' and trashed = false and appProperties has { key='healthApp' and value='p0-v1' }",
                    "'daily-folder' in parents and name = '2026-10-30.csv' and trashed = false and appProperties has { key='healthApp' and value='p0-v1' }",
                ), requests.take(3).map { it.url.queryParameter("q") })
                assertTrue(requests.all { it.handshake != null })
                assertTrue(requests.all { it.headers["Authorization"] == "Bearer synthetic-test-token" })
                assertEquals(4, local.server.requestCount)
                assertNull(local.server.takeRequest(200, TimeUnit.MILLISECONDS))
                assertRequestCount(local, profiling)
                for (lookup in listOf(DriveOperation.APP_FOLDER_LOOKUP, DriveOperation.DATASET_FOLDER_LOOKUP, DriveOperation.FILE_LOOKUP)) {
                    assertStats(profiling.snapshot().requests[lookup], completed = 1)
                    assertStats(profiling.snapshot().operations[lookup], completed = 1)
                }
                assertStats(profiling.snapshot().requests[kind], failed = 1)
                assertStats(profiling.snapshot().operations[kind], failed = 1)
            }
        }
        LocalTls().use { local ->
            val profiling = SyncProfiling()
            lookup(local, true)
            local.server.enqueue(MockResponse.Builder().body(remote).headersDelay(30, TimeUnit.SECONDS).build())
            val pending = async(Dispatchers.Default) { PrivateDriveUploader(local.transport(), profiling).upsert(file) }
            val requests = List(4) { local.request() }
            assertEquals(listOf("GET", "GET", "GET", "PATCH"), requests.map { it.method })
            val active = profiling.snapshot()
            assertTrue(checkNotNull(active.requests[DriveOperation.FILE_UPDATE]).active)
            assertTrue(checkNotNull(active.operations[DriveOperation.FILE_UPDATE]).active)
            pending.cancelAndJoin()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(4, local.server.requestCount)
            assertRequestCount(local, profiling)
            assertStats(profiling.snapshot().requests[DriveOperation.FILE_UPDATE], cancelled = 1)
            assertStats(profiling.snapshot().operations[DriveOperation.FILE_UPDATE], cancelled = 1)
            assertNull(local.server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `Cancellation cancels the in flight HTTP call without reporting success`() = runBlocking {
        LocalTls().use { local ->
            val call = AtomicReference<Call>()
            val failed = CountDownLatch(1)
            val listener = object : EventListener() {
                override fun callStart(value: Call) { call.set(value) }
                override fun callFailed(value: Call, ioe: IOException) { failed.countDown() }
            }
            local.server.enqueue(MockResponse.Builder().body("{}").headersDelay(30, TimeUnit.SECONDS).build())
            val profiling = SyncProfiling()
            var success = false
            val pending = async(Dispatchers.Default) {
                PrivateDriveUploader(local.transport(local.client(listener)), profiling).upsert(file)
                success = true
            }
            local.request()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(call.get().isCanceled())
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertFalse(success)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1, local.server.requestCount)
            assertRequestCount(local, profiling)
            assertStats(profiling.snapshot().requests[DriveOperation.APP_FOLDER_LOOKUP], cancelled = 1)
            assertStats(profiling.snapshot().operations[DriveOperation.APP_FOLDER_LOOKUP], cancelled = 1)
        }
    }
    @Test
    fun `About proof uses same bearer exact encoded fields and no GET body over local TLS`() = runBlocking {
        LocalTls().use { local ->
            val account = BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")
            val generation = com.ny.my.health.core.DestinationGeneration("synthetic-generation")
            val ticket = BoundDriveAuthorization.Ticket(1, generation, account)
            val credential = checkNotNull(BoundDriveAuthorization.acceptResult(ticket, ticket, generation,
                false, "synthetic-test-token", listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE)))
            val good = """{"user":{"emailAddress":"selected@example.invalid","me":true}}"""
            var reads = 0
            local.enqueue(good)
            credential.withVerifiedTransport({ true }, { local.transport() }) { reads++ }
            val request = local.request()
            assertEquals("GET", request.method)
            assertEquals("/drive/v3/about", request.url.encodedPath)
            assertEquals("fields=user%28emailAddress%2Cme%29", request.url.encodedQuery)
            assertEquals("Bearer synthetic-test-token", request.headers["Authorization"])
            assertEquals("", request.body?.utf8() ?: "")
            assertNotNull(request.handshake)
            for ((status, body) in listOf(401 to good, 403 to good, 429 to good, 500 to good,
                502 to good, 503 to good, 504 to good, 200 to "malformed", 200 to "{}",
                200 to """{"user":{"me":true}}""", 200 to """{"user":{"emailAddress":"selected@example.invalid"}}""",
                200 to (good + " ".repeat(1_048_576)))) {
                local.enqueue(body, status)
                val error = runCatching {
                    credential.withVerifiedTransport({ true }, { local.transport() }) { reads++ }
                }.exceptionOrNull()
                assertTrue(error is BoundDriveAuthorizationException)
                assertNull(error?.cause)
                local.request()
                assertEquals(1, reads)
            }
            LocalTls().use { destination ->
                local.server.enqueue(MockResponse.Builder().code(302)
                    .addHeader("Location", destination.server.url("/capture")).build())
                assertTrue(runCatching {
                    credential.withVerifiedTransport({ true }, { local.transport() }) { reads++ }
                }.isFailure)
                local.request()
                assertEquals(0, destination.server.requestCount)
            }
            local.server.enqueue(MockResponse.Builder().body(good).headersDelay(30, TimeUnit.SECONDS).build())
            val pending = async(Dispatchers.Default) {
                credential.withVerifiedTransport({ true }, { local.transport() }) { reads++ }
            }
            local.request()
            pending.cancelAndJoin()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1, reads)
        }
    }

    @Test
    fun `About rejects nonexact host path method query and body before authorization request`() = runBlocking {
        LocalTls().use { local ->
            val transport = local.transport()
            val about = BoundDriveAuthorization.ABOUT_URL
            for (bad in listOf(
                about.replace("www.googleapis.com", "other.example.invalid"),
                about.replace("https:", "http:"), about.replace("/about?", "/about/extra?"),
                "https://www.googleapis.com/drive/v3/about", "$about&extra=true",
                about.replace("user%28emailAddress%2Cme%29", "user%28emailAddress%29"),
                about.replace("user%28emailAddress%2Cme%29", "user(emailAddress,me)"),
                about.replace("www.googleapis.com", "user@www.googleapis.com"), "$about#fragment",
            )) {
                assertTrue(runCatching { transport.execute("GET", bad) }.exceptionOrNull() is IllegalArgumentException)
            }
            for (method in listOf("POST", "PATCH", "DELETE")) {
                assertTrue(runCatching { transport.execute(method, about) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(runCatching { transport.execute("GET", about, "text/plain", byteArrayOf()) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, local.server.requestCount)
        }
    }

}
