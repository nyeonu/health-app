package com.ny.my.health

import com.ny.my.health.core.*
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** 고정 합성 입력과 실제 단조 시계로 측정한다. 실제 계정·건강 데이터는 사용하지 않는다. */
class SyncProfilingBaselineTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val instant = Instant.parse("2026-10-31T00:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val folderMime = "application/vnd.google-apps.folder"

    private data class Expected(val method: String, val path: String, val queryPart: String? = null)
    private data class Sample(val scenario: String, val index: Int, val summary: ProfileSummary)

    private class LocalTls : AutoCloseable {
        val server = MockWebServer()
        private val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client: OkHttpClient
        init {
            val identity = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            server.useHttps(identity.sslSocketFactory())
            server.start()
            client = GoogleDriveTransport.productionClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val local = server.url("/").newBuilder().encodedPath(original.url.encodedPath)
                        .encodedQuery(original.url.encodedQuery).build()
                    chain.proceed(original.newBuilder().url(local).build())
                }.build()
        }
        fun enqueue(body: String, status: Int = 200) = server.enqueue(MockResponse(code = status, body = body))
        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }

    private fun metadata(id: String, name: String, parent: String, mime: String) =
        """{"id":"$id","name":"$name","parents":["$parent"],"mimeType":"$mime","ownedByMe":true,"shared":false,"trashed":false,"appProperties":{"healthApp":"p0-v1"}}"""

    private fun fixture(three: Boolean) = HealthSnapshot(
        date, 42, emptyList(),
        if (three) listOf(HeartRateSample("synthetic-heart", "synthetic.source", instant.minusSeconds(86400), 70)) else emptyList(),
        if (three) listOf(SleepSession("synthetic-sleep", "synthetic.source", instant.minusSeconds(86400), instant.minusSeconds(86340))) else emptyList(),
        emptyList(), setOf(DataType.STEPS, DataType.HEART_RATE, DataType.SLEEP),
    )

    /** 원격 상태는 매회 응답 스크립트로 재설정하며 연결 풀과는 독립적이다. */
    private fun script(local: LocalTls, cold: Boolean, three: Boolean): List<Expected> = buildList {
        val root = metadata("app-root", "HealthApp", "my-drive", folderMime)
        fun lookup(body: String, query: String) {
            local.enqueue("""{"files":[$body],"incompleteSearch":false}""")
            add(Expected("GET", "/drive/v3/files", query))
        }
        for ((index, dataset) in (if (three) listOf("daily", "heart-rate", "sleep") else listOf("daily")).withIndex()) {
            lookup(if (cold) "" else root, "name = 'HealthApp'")
            if (cold) {
                local.enqueue(root)
                add(Expected("POST", "/drive/v3/files"))
                lookup(root, "name = 'HealthApp'")
            }
            val parent = "dataset-$index"
            val folder = metadata(parent, dataset, "app-root", folderMime)
            lookup(if (cold) "" else folder, "name = '$dataset'")
            if (cold) {
                local.enqueue(folder)
                add(Expected("POST", "/drive/v3/files"))
            }
            val remote = metadata("file-$index", "$date.csv", parent, "text/csv")
            lookup(if (cold) "" else remote, "'$parent' in parents")
            local.enqueue(remote)
            add(Expected(if (cold) "POST" else "PATCH", if (cold) "/upload/drive/v3/files" else "/upload/drive/v3/files/file-$index"))
        }
    }

    private fun assertRequests(local: LocalTls, expected: List<Expected>, before: Int, summary: ProfileSummary) {
        assertEquals(expected.size, local.server.requestCount - before)
        for (entry in expected) {
            val actual = checkNotNull(local.server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(entry.method, actual.method)
            assertEquals(entry.path, actual.url.encodedPath)
            entry.queryPart?.let { assertTrue(actual.url.queryParameter("q")!!.contains(it)) }
            assertNotNull(actual.handshake)
        }
        assertEquals(expected.size.toLong(), summary.requests.values.sumOf { it.started })
        assertTrue(summary.stages.values.none { it.active })
        assertTrue(summary.operations.values.none { it.active })
        assertTrue(summary.requests.values.none { it.active })
    }

    private fun nearestRank(values: List<Long>, percentile: Double): Long =
        values.sorted()[ceil(values.size * percentile).toInt() - 1]

    @Test
    fun `Local TLS baseline records repeatable scenarios sample counts and nearest rank percentiles`() = runBlocking {
        val known = listOf(10L, 2L, 8L, 4L, 6L, 1L, 9L, 3L, 7L, 5L)
        assertEquals(5L, nearestRank(known, 0.50))
        assertEquals(10L, nearestRank(known, 0.95))
        val samples = mutableListOf<Sample>()
        val outcomes = mutableListOf<String>()
        for (scenario in listOf("warm1CSV", "cold1CSV", "warm3CSV")) {
            LocalTls().use { local ->
                repeat(13) { iteration ->
                    val profiling = SyncProfiling()
                    val reader = object : HealthReader {
                        override suspend fun read(date: LocalDate): HealthSnapshot {
                            assertEquals(this@SyncProfilingBaselineTest.date, date)
                            return fixture(scenario == "warm3CSV")
                        }
                    }
                    val uploader = PrivateDriveUploader(GoogleDriveTransport("synthetic-test-token", local.client), profiling)
                    val serial = object : DriveUploader {
                        override suspend fun upsert(file: CsvFile): String = uploader.upsert(file)
                    }
                    val coordinator = SyncCoordinator(reader, DefaultNormalizer(), VersionedCsvExporter(), serial, clock, profiling)
                    val expected = script(local, scenario == "cold1CSV", scenario == "warm3CSV")
                    val before = local.server.requestCount
                    val result = profiling.measure(ProfileStage.EXECUTION) { coordinator.sync(date) }
                    assertEquals(SyncResult(SyncStatus.SUCCESS, if (scenario == "warm3CSV") 3 else 1), result)
                    val summary = profiling.snapshot()
                    assertRequests(local, expected, before, summary)
                    val count = if (scenario == "cold1CSV") 7L else if (scenario == "warm3CSV") 12L else 4L
                    assertEquals(count, summary.requests.values.sumOf { it.completed })
                    assertEquals(count, summary.operations.values.sumOf { it.completed })
                    assertTrue(summary.requests.values.all { it.failed == 0L && it.cancelled == 0L })
                    if (iteration >= 3) samples += Sample(scenario, iteration - 2, summary)
                }
            }
        }
        // 실패와 취소는 성공 표본에 섞지 않는다. 취소는 합성 reader 경계에서 발생한다.
        LocalTls().use { local ->
            for (cancel in listOf(false, true)) {
                val profiling = SyncProfiling()
                val cancellation = CancellationException("합성 취소")
                val reader = object : HealthReader {
                    override suspend fun read(date: LocalDate): HealthSnapshot {
                        if (cancel) throw cancellation
                        return fixture(false)
                    }
                }
                if (!cancel) local.enqueue("", 503)
                val coordinator = SyncCoordinator(reader, DefaultNormalizer(), VersionedCsvExporter(),
                    PrivateDriveUploader(GoogleDriveTransport("synthetic-test-token", local.client), profiling), clock, profiling)
                val before = local.server.requestCount
                profiling.start(ProfileStage.EXECUTION)
                if (cancel) {
                    try {
                        coordinator.sync(date)
                        fail("취소가 전파되어야 한다")
                    } catch (actual: CancellationException) {
                        profiling.finish(ProfileStage.EXECUTION, ProfileOutcome.CANCELLED)
                        assertSame(cancellation, actual)
                    }
                } else {
                    val result = coordinator.sync(date)
                    profiling.finish(ProfileStage.EXECUTION, ProfileOutcome.FAILED)
                    assertEquals(SyncStatus.FAILED, result.status)
                    assertEquals(0, result.uploadedFiles)
                    assertEquals(503, result.driveFailure!!.httpStatus)
                }
                val summary = profiling.snapshot()
                assertRequests(local, if (cancel) emptyList() else listOf(Expected("GET", "/drive/v3/files")), before, summary)
                val execution = summary.stages.getValue(ProfileStage.EXECUTION)
                assertEquals(1L, execution.started)
                assertEquals(0L, execution.completed)
                assertEquals(if (cancel) 0L else 1L, execution.failed)
                assertEquals(if (cancel) 1L else 0L, execution.cancelled)
                assertFalse(execution.active)
                outcomes += "${if (cancel) "readerCancelled" else "http503Failed"},${execution.elapsedNanos},${summary.requests.values.sumOf { it.started }},${execution.started},${execution.completed},${execution.failed},${execution.cancelled},${execution.active}"
            }
        }
        assertEquals(30, samples.size)
        val report = StringBuilder()
        report.appendLine("# 합성 기준선: 시간 단위 ns, 원시 본문·토큰·개인정보 없음")
        val root = File("..").canonicalFile.parentFile
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(root).start()
            val value = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0)
            return value
        }
        val source = File(root, "android/app/src/test/java/com/ny/my/health/SyncProfilingBaselineTest.kt")
        val sha = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        report.appendLine("revision=${git("rev-parse", "HEAD")}")
        report.appendLine("harness_sha256=$sha")
        report.appendLine("dirty=${git("status", "--porcelain").isNotEmpty()}")
        report.appendLine("java=${System.getProperty("java.runtime.version")};os=${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
        report.appendLine("scenario,sample,scope,key,started,completed,failed,cancelled,elapsed_ns")
        for (sample in samples) {
            fun rows(scope: String, stats: Map<out Enum<*>, ProfileStats>) {
                for ((key, value) in stats) report.appendLine("${sample.scenario},${sample.index},$scope,$key,${value.started},${value.completed},${value.failed},${value.cancelled},${value.elapsedNanos}")
            }
            rows("stage", sample.summary.stages)
            rows("operation", sample.summary.operations)
            rows("request", sample.summary.requests)
        }
        report.appendLine("scenario,scope,key,n,min_ns,p50_ns,p95_ns,max_ns")
        for ((scenario, group) in samples.groupBy { it.scenario }) {
            assertEquals(10, group.size)
            fun distribution(scope: String, keys: Set<out Enum<*>>, select: (Sample, Enum<*>) -> Long) {
                for (key in keys) {
                    val values = group.map { select(it, key) }
                    report.appendLine("$scenario,$scope,$key,10,${values.min()},${nearestRank(values, 0.50)},${nearestRank(values, 0.95)},${values.max()}")
                }
            }
            distribution("stage", group.first().summary.stages.keys) { sample, key -> sample.summary.stages.getValue(key as ProfileStage).elapsedNanos }
            distribution("operation", group.first().summary.operations.keys) { sample, key -> sample.summary.operations.getValue(key as DriveOperation).elapsedNanos }
            distribution("request", group.first().summary.requests.keys) { sample, key -> sample.summary.requests.getValue(key as DriveOperation).elapsedNanos }
        }
        report.appendLine("separate_outcome,execution_ns,requests,started,completed,failed,cancelled,active")
        outcomes.forEach { report.appendLine(it) }
        val directory = File(root, "android/app/build/reports/sync-profiling-baseline").apply { mkdirs() }
        File(directory, "summary.csv").writeText(report.toString())
    }
}
