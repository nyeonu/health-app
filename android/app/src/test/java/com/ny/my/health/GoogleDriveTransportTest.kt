package com.ny.my.health

import com.ny.my.health.core.CsvFile
import com.ny.my.health.core.PrivateDriveUploader
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

    private fun lookup(local: LocalTls, exists: Boolean) {
        local.enqueue("""{"id":"my-drive"}""")
        local.enqueue("""{"files":[$root],"incompleteSearch":false}""")
        local.enqueue("""{"files":[$dataset],"incompleteSearch":false}""")
        local.enqueue("""{"files":[${if (exists) remote else ""}],"incompleteSearch":false}""")
    }

    @Test
    fun `Local TLS create then PATCH updates the same Drive file without duplicates`() = runBlocking {
        LocalTls().use { local ->
            val uploader = PrivateDriveUploader(local.transport())
            lookup(local, false)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file))
            lookup(local, true)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file.copy(content = file.content + "\r\n")))
            val requests = List(10) { local.request() }
            assertEquals(listOf("GET", "GET", "GET", "GET", "POST", "GET", "GET", "GET", "GET", "PATCH"), requests.map { it.method })
            assertTrue(requests.all { it.handshake != null })
            assertTrue(requests.all { it.headers["Authorization"] == "Bearer synthetic-test-token" })
            assertTrue(requests[4].url.queryParameter("uploadType") == "multipart")
            assertTrue(requests[4].body!!.utf8().contains(file.content))
            assertEquals("/upload/drive/v3/files/date-file", requests.last().url.encodedPath)
            assertEquals(file.content + "\r\n", requests.last().body!!.utf8())
            assertEquals(1, requests.count { it.method == "POST" })
        }
    }

    @Test
    fun `Revoked authorization fails safely and a later explicit retry updates without duplicates`() = runBlocking {
        LocalTls().use { local ->
            val uploader = PrivateDriveUploader(local.transport())
            lookup(local, false)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file))
            lookup(local, true)
            local.enqueue("sensitive-provider-body", 401)
            val error = runCatching { uploader.upsert(file) }.exceptionOrNull()!!
            assertEquals("Drive request failed (HTTP 401)", error.message)
            assertNull(error.cause)
            assertEquals(10, local.server.requestCount)
            // The caller explicitly retries; the transport never refreshes or repeats a write.
            lookup(local, true)
            local.enqueue(remote)
            assertEquals("date-file", uploader.upsert(file))
            val requests = List(15) { local.request() }
            assertEquals(1, requests.count { it.method == "POST" })
            assertEquals(2, requests.count { it.method == "PATCH" })
            assertTrue(requests.filter { it.method == "PATCH" }.all { it.url.encodedPath.endsWith("/date-file") })
        }
    }

    @Test
    fun `Redirects and disallowed endpoints never receive authorization`() = runBlocking {
        LocalTls().use { local ->
            LocalTls().use { destination ->
                val transport = local.transport()
                for (code in listOf(301, 302, 307, 308)) {
                    local.server.enqueue(MockResponse.Builder().code(code).addHeader("Location", destination.server.url("/capture")).body("sensitive-redirect-body").build())
                    val response = transport.execute("GET", endpoint)
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
                assertNull(destination.server.takeRequest(200, TimeUnit.MILLISECONDS))
                assertEquals(0, destination.server.requestCount)
            }
        }
    }

    @Test
    fun `Transport failures and oversized responses expose no sensitive body or cause`() = runBlocking {
        LocalTls().use { local ->
            val transport = local.transport()
            local.enqueue("x".repeat(1_048_576))
            assertEquals(1_048_576, transport.execute("GET", endpoint).body.length)
            local.request()
            local.enqueue("sensitive-body" + "x".repeat(1_048_576))
            val oversized = runCatching { transport.execute("GET", endpoint) }.exceptionOrNull()!!
            assertEquals("Drive transport failed", oversized.message)
            assertNull(oversized.cause)
            local.request()
            local.enqueue("sensitive-provider-body", 503)
            val failedWrite = transport.execute("POST", endpoint, "text/csv", file.content.toByteArray())
            assertEquals(503, failedWrite.status)
            assertEquals("", failedWrite.body)
            local.request()
            assertEquals(3, local.server.requestCount)
            local.enqueue("invalid-sensitive-body")
            val malformed = runCatching { PrivateDriveUploader(transport).upsert(file) }.exceptionOrNull()!!
            assertEquals("Malformed Drive response", malformed.message)
            assertNull(malformed.cause)
            local.request()
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
                    local.server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("sensitive-provider-body").build())
                    local.enqueue("{}")
                    val first = transport.execute(method, endpoint, "text/csv", body)
                    assertEquals(503, first.status)
                    assertEquals("", first.body)
                    assertEquals(1, local.server.requestCount)
                    val firstRequest = local.request()
                    assertEquals(method, firstRequest.method)
                    assertEquals(body?.toString(Charsets.UTF_8) ?: "", firstRequest.body?.utf8() ?: "")
                    val retry = transport.execute(method, endpoint, "text/csv", body)
                    assertEquals(200, retry.status)
                    assertEquals("{}", retry.body)
                    assertEquals(2, local.server.requestCount)
                    val retryRequest = local.request()
                    assertEquals(method, retryRequest.method)
                    assertEquals(body?.toString(Charsets.UTF_8) ?: "", retryRequest.body?.utf8() ?: "")
                }
            }
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
            var success = false
            val pending = async(Dispatchers.Default) {
                local.transport(local.client(listener)).execute("GET", endpoint)
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
        }
    }
}
