package com.ny.my.health

import com.ny.my.health.core.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlinx.coroutines.*
import mockwebserver3.Dispatcher
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

/** 합성 private tree만 사용한다. 두 경로의 연결 풀은 분리하고 측정 동안 유지한다. */
class DriveBatchPerformanceTest {
    private val datasets = listOf("daily", "heart-rate", "sleep")
    private val files = datasets.map { CsvFile("$it/2026-10-30.csv", "synthetic,column\r\n1,2\r\n") }
    private fun metadata(id: String, name: String, parent: String, mime: String) =
        """{"id":"$id","name":"$name","parents":["$parent"],"mimeType":"$mime","ownedByMe":true,"shared":false,"trashed":false,"appProperties":{"healthApp":"p0-v1"}}"""
    private fun remote(dataset: String) = metadata("file-$dataset", "2026-10-30.csv", "folder-$dataset", "text/csv")

    private inner class Tls(val parallel: Boolean) : AutoCloseable {
        val server = MockWebServer()
        val active = ConcurrentHashMap.newKeySet<Call>()
        val maximum = AtomicInteger()
        val targets = Collections.synchronizedList(mutableListOf<String>())
        var mode = "success"
        var pair = CountDownLatch(2)
        var gate = false
        val client: OkHttpClient
        init {
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    checkNotNull(request.handshake)
                    val path = request.url.encodedPath
                    val query = request.url.queryParameter("q").orEmpty()
                    targets += "${request.method}:$path:$query"
                    val write = request.method == "PATCH"
                    val dataset = datasets.firstOrNull { path.endsWith("/file-$it") || query.contains("'folder-$it' in parents") }
                    if (write && mode != "success") {
                        pair.countDown()
                        check(pair.await(5, TimeUnit.SECONDS)) { "두 쓰기가 겹쳐야 한다" }
                        if (mode == "cancel" || dataset != "daily") {
                            return MockResponse.Builder().body(remote(checkNotNull(dataset))).headersDelay(2, TimeUnit.SECONDS).build()
                        }
                        return when (mode) {
                            "http" -> MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("sensitive-provider-body").build()
                            "network" -> MockResponse.Builder().body(remote("daily")).onResponseBody(SocketEffect.CloseStream(2)).build()
                            "body" -> MockResponse(body = "sensitive-malformed-body")
                            "metadata" -> MockResponse(body = remote("daily").replace("\"shared\":false", "\"shared\":true"))
                            else -> error("알 수 없는 합성 모드")
                        }
                    }
                    if (gate && !write && dataset in listOf("daily", "heart-rate")) {
                        pair.countDown()
                        check(pair.await(5, TimeUnit.SECONDS)) { "두 조회가 겹쳐야 한다" }
                    }
                    val body = when {
                        write && dataset != null -> remote(dataset)
                        request.method == "GET" && path == "/drive/v3/files" -> {
                            val value = when {
                                query.contains("name = 'HealthApp'") -> metadata("app-root", "HealthApp", "my-drive", "application/vnd.google-apps.folder")
                                dataset != null -> remote(dataset)
                                else -> {
                                    val folder = checkNotNull(datasets.firstOrNull { query.contains("name = '$it'") })
                                    metadata("folder-$folder", folder, "app-root", "application/vnd.google-apps.folder")
                                }
                            }
                            """{"files":[$value],"incompleteSearch":false}"""
                        }
                        else -> error("승인되지 않은 합성 요청")
                    }
                    return MockResponse.Builder().body(body).headersDelay(40, TimeUnit.MILLISECONDS).build()
                }
            }
            server.start()
            client = GoogleDriveTransport.productionClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) {
                        active.add(call)
                        maximum.accumulateAndGet(active.size, ::maxOf)
                    }
                    override fun callEnd(call: Call) { active.remove(call) }
                    override fun callFailed(call: Call, ioe: IOException) { active.remove(call) }
                })
                .addInterceptor { chain ->
                    val original = chain.request()
                    val url = server.url("/").newBuilder().encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
                    chain.proceed(original.newBuilder().url(url).build())
                }.build()
        }
        fun uploader(profiling: SyncProfiling): PrivateDriveUploader =
            PrivateDriveUploader(GoogleDriveTransport("synthetic-test-token", client), profiling)
        suspend fun settled() {
            withTimeout(5000) { while (active.isNotEmpty()) delay(5) }
        }
        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }

    private data class Sample(val route: String, val index: Int, val summary: ProfileSummary, val requests: Int, val maximum: Int)
    private data class Evidence(val mode: String, val summary: ProfileSummary, val requests: Int, val kind: DriveFailureKind?)
    private data class Campaign(val samples: List<Sample>, val failures: List<Evidence>)

    private fun closed(summary: ProfileSummary) {
        for (stats in summary.stages.values + summary.operations.values + summary.requests.values) {
            assertFalse(stats.active)
            assertEquals(stats.started, stats.completed + stats.failed + stats.cancelled)
        }
    }
    private suspend fun execute(uploader: DriveUploader, profiling: SyncProfiling, confirmed: MutableList<String>) {
        profiling.measure(ProfileStage.EXECUTION) {
            profiling.measure(ProfileStage.DRIVE_TOTAL) { uploader.upsertBatch(files) { confirmed += it } }
        }
    }
    private fun rank(values: List<Long>, percentile: Double) = values.sorted()[ceil(values.size * percentile).toInt() - 1]

    private suspend fun collect(): Campaign {
        val samples = mutableListOf<Sample>()
        Tls(false).use { serial -> Tls(true).use { optimized ->
            // 각 연결 풀에서 같은 세 번의 전체 배치를 예열한다. 이후 paired 교대 순서를 고정한다.
            repeat(13) { index ->
                val order = if (index % 2 == 0) listOf(serial, optimized) else listOf(optimized, serial)
                for (local in order) {
                    local.settled()
                    local.maximum.set(0)
                    local.pair = CountDownLatch(2)
                    local.gate = local.parallel
                    val before = local.server.requestCount
                    val profiling = SyncProfiling()
                    val actual = local.uploader(profiling)
                    val uploader = if (local.parallel) actual else object : DriveUploader {
                        override suspend fun upsert(file: CsvFile) = actual.upsert(file)
                    }
                    val confirmed = mutableListOf<String>()
                    execute(uploader, profiling, confirmed)
                    local.settled()
                    val count = local.server.requestCount - before
                    assertEquals(if (local.parallel) 10 else 12, count)
                    assertEquals(if (local.parallel) 2 else 1, local.maximum.get())
                    assertEquals(datasets.map { "file-$it" }.toSet(), confirmed.toSet())
                    assertEquals(3, confirmed.size)
                    if (!local.parallel) assertEquals(datasets.map { "file-$it" }, confirmed)
                    val summary = profiling.snapshot()
                    closed(summary)
                    assertEquals(count.toLong(), summary.requests.values.sumOf { it.started })
                    assertEquals(0L, summary.requests.values.sumOf { it.failed + it.cancelled })
                    if (index >= 3) samples += Sample(if (local.parallel) "optimized" else "serial", index - 2, summary, count, local.maximum.get())
                }
            }
        } }
        val failures = mutableListOf<Evidence>()
        for (mode in listOf("http", "network", "body", "metadata", "cancel")) {
            Tls(true).use { local ->
                local.mode = mode
                val profiling = SyncProfiling()
                val uploader = local.uploader(profiling)
                val confirmed = mutableListOf<String>()
                val error = supervisorScope {
                    val pending = async(Dispatchers.Default) { execute(uploader, profiling, confirmed) }
                    withContext(Dispatchers.IO) { assertTrue(local.pair.await(5, TimeUnit.SECONDS)) }
                    assertEquals(2, local.maximum.get())
                    if (mode == "cancel") pending.cancel()
                    runCatching { pending.await() }.exceptionOrNull()
                }
                assertNotNull(error)
                local.settled()
                assertEquals(8, local.server.requestCount)
                assertTrue(confirmed.isEmpty())
                val snapshot = profiling.snapshot()
                closed(snapshot)
                assertEquals(8L, snapshot.requests.values.sumOf { it.started })
                assertEquals(2L, snapshot.requests.getValue(DriveOperation.FILE_UPDATE).started)
                assertFalse(local.targets.any { it.contains("'folder-sleep' in parents") || it.contains("/file-sleep") })
                val expected = when (mode) {
                    "http" -> DriveFailureKind.HTTP
                    "network" -> DriveFailureKind.NETWORK
                    "body" -> DriveFailureKind.RESPONSE_INVALID
                    "metadata" -> DriveFailureKind.TARGET_REJECTED
                    else -> null
                }
                if (mode == "cancel") {
                    assertTrue(error is CancellationException)
                    assertEquals(2L, snapshot.requests.getValue(DriveOperation.FILE_UPDATE).cancelled)
                    assertEquals(1L, snapshot.stages.getValue(ProfileStage.EXECUTION).cancelled)
                } else {
                    assertTrue(error is DriveUploadException)
                    val safe = error as DriveUploadException
                    assertEquals(expected, safe.details.kind)
                    if (mode == "http") assertEquals(503, safe.details.httpStatus)
                    assertNull(safe.cause)
                    assertFalse(safe.toString().contains("sensitive"))
                    assertEquals(1L, snapshot.stages.getValue(ProfileStage.EXECUTION).failed)
                    assertEquals(1L, snapshot.operations.getValue(DriveOperation.FILE_UPDATE).failed)
                    assertEquals(1L, snapshot.operations.getValue(DriveOperation.FILE_UPDATE).cancelled)
                }
                failures += Evidence(mode, snapshot, local.server.requestCount, expected)
                // 취소된 소켓의 서버 응답 종료를 주장하지 않는다. 클라이언트 종료 후 명시적 재호출만 한다.
                local.mode = "success"
                val before = local.server.requestCount
                uploader.upsertBatch(files) { confirmed += it }
                local.settled()
                assertEquals(10, local.server.requestCount - before)
                assertEquals(3, confirmed.size)
                closed(profiling.snapshot())
            }
        }
        return Campaign(samples, failures).also(::report)
    }

    private fun report(campaign: Campaign) {
        val root = File("..").canonicalFile.parentFile
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(root).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0)
            return output
        }
        val source = File(root, "android/app/src/test/java/com/ny/my/health/DriveBatchPerformanceTest.kt")
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        val text = buildString {
            appendLine("# 합성 TLS 지연 비교: 실제 계정·기기 성능 근거가 아님; 무지연 기준선과 분리")
            appendLine("revision=${git("rev-parse", "HEAD")};dirty=${git("status", "--porcelain").isNotEmpty()};harness_sha256=$hash")
            appendLine("java=${System.getProperty("java.runtime.version")};os=${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
            appendLine("delay_ms=40;warmup_per_route=3;samples_per_route=10;clock=System.nanoTime;percentile=sorted-nearest-rank")
            appendLine("connections=separate-retained-pools;client=production-with-local-TLS-trust-and-routing;order=paired-alternating;fixture=warm-private-3CSV")
            appendLine("route,stage,n,p50_ns,p95_ns,requests_per_sample,max_client_active,failures")
            for ((route, group) in campaign.samples.groupBy { it.route }) {
                for (stage in listOf(ProfileStage.EXECUTION, ProfileStage.DRIVE_TOTAL)) {
                    val values = group.map { it.summary.stages.getValue(stage).elapsedNanos }
                    appendLine("$route,$stage,${values.size},${rank(values, .5)},${rank(values, .95)},${group.first().requests},${group.maxOf { it.maximum }},0")
                }
            }
            appendLine("separate_outcome,requests,kind,execution_failed,execution_cancelled,write_completed,write_failed,write_cancelled")
            for (entry in campaign.failures) {
                val execution = entry.summary.stages.getValue(ProfileStage.EXECUTION)
                val writes = entry.summary.operations.getValue(DriveOperation.FILE_UPDATE)
                appendLine("${entry.mode},${entry.requests},${entry.kind},${execution.failed},${execution.cancelled},${writes.completed},${writes.failed},${writes.cancelled}")
            }
        }
        val directory = File(root, "android/app/build/reports/drive-batch-performance").apply { mkdirs() }
        File(directory, "summary.csv").writeText(text)
    }
    private fun campaign(): Campaign = synchronized(cacheLock) {
        (cached ?: runCatching { runBlocking { collect() } }.also { cached = it }).getOrThrow()
    }
    companion object {
        private val cacheLock = Any()
        private var cached: Result<Campaign>? = null
    }

    @Test fun `TLS HTTP network malformed and cancelled writes do not replay or expose sensitive errors`() {
        assertEquals(listOf("http", "network", "body", "metadata", "cancel"), campaign().failures.map { it.mode })
        assertTrue(campaign().failures.all { it.requests == 8 })
    }
    @Test fun `Overlapping completed failed and cancelled requests close all profiling counters`() {
        campaign().samples.forEach { closed(it.summary) }
        campaign().failures.forEach { closed(it.summary) }
    }
    @Test fun `Paired delayed TLS samples report execution and Drive distributions with request concurrency`() {
        val values = listOf(10L, 2L, 8L, 4L, 6L, 1L, 9L, 3L, 7L, 5L)
        assertEquals(5L, rank(values, .5))
        assertEquals(10L, rank(values, .95))
        assertEquals(20, campaign().samples.size)
        campaign().samples.groupBy { it.route }.values.forEach { assertEquals((1..10).toList(), it.map { sample -> sample.index }) }
    }
    @Test fun `Serial adapter uses ordered single upserts and parallel TLS routes by request target`() {
        campaign().samples.forEach {
            assertEquals(if (it.route == "serial") 12 else 10, it.requests)
            assertEquals(if (it.route == "serial") 1 else 2, it.maximum)
        }
    }
    @Test fun `Delayed optimized warm batch lowers both execution and Drive p50 and p95 without failures`() {
        for (stage in listOf(ProfileStage.EXECUTION, ProfileStage.DRIVE_TOTAL)) {
            fun values(route: String) = campaign().samples.filter { it.route == route }.map { it.summary.stages.getValue(stage).elapsedNanos }
            for (percentile in listOf(.5, .95)) assertTrue("$stage p$percentile", rank(values("optimized"), percentile) < rank(values("serial"), percentile))
        }
    }
    @Test fun `Failure and cancellation evidence stays separate from successful measured samples`() {
        assertEquals(5, campaign().failures.size)
        assertEquals(20, campaign().samples.size)
        assertTrue(campaign().samples.all { sample -> sample.summary.stages.values.all { it.failed == 0L && it.cancelled == 0L } })
    }
}
