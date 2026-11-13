package com.ny.my.health

import com.ny.my.health.core.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 실제 transport를 합성 TLS에만 연결한다. 실계정·실기기 증거가 아니다. */
class DriveApiDestinationTest {
    private val date = LocalDate.parse("2026-11-04")
    private val generation = DestinationGeneration("synthetic-generation")
    private val now = Instant.parse("2026-11-05T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun sha(content: String) = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun planned(dataset: Dataset = Dataset.DAILY): PlannedFile {
        val path = if (dataset == Dataset.DAILY) "daily" else "sleep"
        val csv = CsvFile("$path/$date.csv", if (dataset == Dataset.DAILY)
            "schema_version,date,timezone,steps,weight_kg,resting_hr_bpm,sleep_min,exercise_min,generated_at\r\n0.1,$date,Asia/Seoul,,,,,,$now\r\n"
            else "schema_version,record_id,source_package,start_time,end_time,duration_min\r\n")
        return PlannedFile(FileKey(generation, date, ReadScopeKey("synthetic-scope"), dataset, "revision"),
            csv, sha(csv.content), null)
    }
    private fun metadata(id: String, name: String, parent: String, folder: Boolean, checksum: String? = null): String =
        """{"id":"$id","name":"$name","parents":["$parent"],"mimeType":"${if (folder) "application/vnd.google-apps.folder" else "text/csv"}","ownedByMe":true,"shared":false,"trashed":false,"appProperties":{"healthApp":"p0-v1"}${checksum?.let { ",\"sha256Checksum\":\"$it\"" }.orEmpty()}}"""

    private inner class Tls : AutoCloseable {
        val server = MockWebServer()
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val lookups = mutableListOf<Pair<DestinationGeneration, LocalDate>>()
        var known: Map<Dataset, Set<String>> = emptyMap()
        var checksum: String? = null
        var missing: String? = null
        var statusMethod: String? = null
        var status = 503
        var malformedAck = false
        var changedAck = false
        var unsafe = false
        var delayedSleep = false
        val client: okhttp3.OkHttpClient
        init {
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    checkNotNull(request.handshake)
                    val method = request.method
                    val query = request.url.queryParameter("q").orEmpty()
                    val path = request.url.encodedPath
                    calls += method to if (method == "GET") query else path
                    if (method != "GET") bodies += checkNotNull(request.body).utf8()
                    if (method == statusMethod) return MockResponse.Builder().code(status)
                        .addHeader("Retry-After", "0").body("synthetic-private-provider-error").build()
                    val sleep = query.contains("'folder-sleep' in parents") || path.endsWith("/file-sleep")
                    val dataset = if (sleep) "sleep" else "daily"
                    var value = when {
                        method == "PATCH" -> metadata(if (changedAck) "wrong-id" else "file-$dataset", "$date.csv", "folder-$dataset", false)
                        query.contains("name = 'HealthApp'") -> metadata("app-root", "HealthApp", "my-drive", true)
                        query.contains("name = 'daily'") -> metadata("folder-daily", "daily", "app-root", true)
                        query.contains("name = 'sleep'") -> metadata("folder-sleep", "sleep", "app-root", true)
                        else -> metadata("file-$dataset", "$date.csv", "folder-$dataset", false, checksum)
                    }
                    if (unsafe) value = value.replace("\"shared\":false", "\"shared\":true")
                    val body = if (method == "GET") {
                        val absent = missing != null && query.contains("name = '$missing'")
                        """{"files":[${if (absent) "" else value}],"incompleteSearch":false}"""
                    } else if (malformedAck) "synthetic-private-malformed" else value
                    return MockResponse.Builder().body(body).apply {
                        if (delayedSleep && sleep && method == "PATCH") headersDelay(300, TimeUnit.MILLISECONDS)
                    }.build()
                }
            }
            server.start()
            client = GoogleDriveTransport.productionClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder()
                        .host("localhost").port(server.port).build()).build())
                }.build()
        }
        fun destination() = DriveApiDestination(PrivateDriveUploader(GoogleDriveTransport("synthetic-token", client), clock = clock),
            generation, { g, d -> lookups += g to d; known }, clock)
        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }
    private suspend fun reason(expected: BlockReason, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("고정 목적지 오류여야 한다", error is DestinationException)
        val safe = error as DestinationException
        assertEquals(expected, safe.reason)
        assertEquals(expected.name, safe.message)
        assertNull(safe.cause)
        assertFalse(safe.toString().contains("synthetic-private"))
    }

    @Test fun `Drive tracked batches preserve private target validation deterministic same file and CSV schema`() = runBlocking {
        val file = planned()
        Tls().use { tls ->
            val destination = tls.destination()
            tls.checksum = file.byteSha256
            tls.known = mapOf(Dataset.DAILY to setOf("file-daily"))
            val reused = destination.reconcile(listOf(file))
            assertEquals(listOf(FileConfirmation(file.key, "file-daily", now)), reused.confirmed)
            assertTrue(reused.pending.isEmpty())
            assertTrue(tls.calls.all { it.first == "GET" })
            assertEquals(listOf(generation to date), tls.lookups)
            tls.checksum = null
            val pending = destination.reconcile(listOf(file))
            assertEquals(listOf(file), pending.pending)
            val before = tls.calls.size
            val confirmed = mutableListOf<FileConfirmation>()
            destination.writePending(pending.pending) { confirmed += it }
            assertEquals(listOf("PATCH"), tls.calls.drop(before).map { it.first })
            assertEquals(listOf(file.file.content), tls.bodies)
            assertEquals(listOf(FileConfirmation(file.key, "file-daily", now)), confirmed)
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { destination.writePending(pending.pending) { fail() } }
            val calls = tls.calls.size
            reason(BlockReason.DESTINATION_BLOCKED) { destination.reconcile(listOf(file, file.copy(key = file.key.copy(date = date.minusDays(1))))) }
            assertEquals(calls, tls.calls.size)
            tls.known = mapOf(Dataset.DAILY to setOf("file-daily", "prior-other-profile"))
            reason(BlockReason.DESTINATION_BLOCKED) { destination.reconcile(listOf(file)) }
            assertEquals(1, tls.calls.count { it.first == "PATCH" })
        }
        for (unsafe in listOf(false, true)) Tls().use { tls ->
            tls.known = mapOf(Dataset.DAILY to setOf("prior-unsafe"))
            tls.unsafe = unsafe
            if (!unsafe) tls.missing = "$date.csv"
            reason(BlockReason.DESTINATION_BLOCKED) { tls.destination().reconcile(listOf(file.copy(previousTargetHandle = "prior-unsafe"))) }
            assertTrue(tls.calls.all { it.first == "GET" })
        }
        for (ack in listOf("malformed", "changed")) Tls().use { tls ->
            val destination = tls.destination()
            destination.reconcile(listOf(file))
            tls.malformedAck = ack == "malformed"
            tls.changedAck = ack == "changed"
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { destination.writePending(listOf(file)) { fail() } }
        }
        for (method in listOf("GET", "PATCH")) for (status in listOf(401, 403, 404, 429, 500, 502, 503, 504)) Tls().use { tls ->
            val destination = tls.destination()
            if (method == "PATCH") destination.reconcile(listOf(file))
            tls.statusMethod = method; tls.status = status
            val expected = when (status) {
                401, 403 -> BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED
                404 -> BlockReason.DESTINATION_BLOCKED
                else -> BlockReason.DESTINATION_TRANSIENT_FAILURE
            }
            val before = tls.calls.size
            reason(expected) { if (method == "GET") destination.reconcile(listOf(file)) else destination.writePending(listOf(file)) { fail() } }
            assertEquals(1, tls.calls.size - before)
        }
    }

    @Test fun `After partial failure cancellation or unknown create reconciliation retries only pending files`() = runBlocking {
        val files = listOf(planned(), planned(Dataset.SLEEP))
        for (cancel in listOf(false, true)) Tls().use { tls ->
            val destination = tls.destination()
            tls.delayedSleep = true
            val pending = destination.reconcile(files).pending
            val confirmed = mutableListOf<FileConfirmation>()
            val error = runCatching {
                destination.writePending(pending) {
                    confirmed += it
                    if (cancel) throw CancellationException("합성 중단")
                    else throw IllegalStateException("합성 커밋 중단")
                }
            }.exceptionOrNull()
            assertTrue(if (cancel) error is CancellationException else error is DestinationException)
            assertEquals(listOf(files.first().key), confirmed.map { it.key })
            val remaining = files.filter { f -> confirmed.none { it.key == f.key } }
            tls.delayedSleep = false
            val before = tls.calls.size
            val next = tls.destination()
            val reconciled = next.reconcile(remaining)
            assertEquals(remaining, reconciled.pending)
            val retried = mutableListOf<FileConfirmation>()
            next.writePending(reconciled.pending) { retried += it }
            assertEquals(listOf(files.last().key), retried.map { it.key })
            assertFalse(tls.calls.drop(before).any { it.second.contains("file-daily") || it.second.contains("'folder-daily' in parents") })
            assertEquals(1, tls.calls.count { it.first == "PATCH" && it.second.endsWith("/file-daily") })
        }
        for (missing in listOf("HealthApp", "daily", "$date.csv")) Tls().use { tls ->
            tls.missing = missing
            val destination = tls.destination()
            val file = planned()
            assertEquals(listOf(file), destination.reconcile(listOf(file)).pending)
            assertTrue(tls.calls.all { it.first == "GET" })
            tls.statusMethod = "POST"
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { destination.writePending(listOf(file)) { fail() } }
            assertEquals(1, tls.calls.count { it.first == "POST" })
            val before = tls.calls.size
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { tls.destination().reconcile(listOf(file.copy(createMayHaveSucceeded = true))) }
            assertTrue(tls.calls.drop(before).all { it.first == "GET" })
            assertEquals(1, tls.calls.count { it.first == "POST" })
        }
    }
}
