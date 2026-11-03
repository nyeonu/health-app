package com.ny.my.health.core

import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateDriveUploaderTest {
    private val file = CsvFile("daily/2026-10-30.csv", "schema_version,date\r\n0.1,2026-10-30\r\n")
    private val folder = "application/vnd.google-apps.folder"
    private fun entry(id: String, name: String, parent: String, mime: String = folder) = buildJsonObject {
        put("id", id); put("name", name); put("mimeType", mime)
        putJsonArray("parents") { add(parent) }
        put("ownedByMe", true); put("shared", false); put("trashed", false)
        putJsonObject("appProperties") { put("healthApp", "p0-v1") }
    }
    private val root get() = entry("app-root", "HealthApp", "my-drive")
    private val dataset get() = entry("daily-folder", "daily", "app-root")
    private val remote get() = entry("date-file", "2026-10-30.csv", "daily-folder", "text/csv")
    private fun response(value: JsonObject) = DriveResponse(200, value.toString())
    private fun listed(vararg entries: JsonObject, page: String? = null) = response(buildJsonObject {
        put("files", JsonArray(entries.toList()))
        put("incompleteSearch", false)
        if (page != null) put("nextPageToken", page)
    })
    private data class Call(val method: String, val url: String, val type: String?, val bytes: ByteArray?)
    private class Fake(vararg responses: DriveResponse) : DriveTransport {
        val calls = mutableListOf<Call>()
        private val queue = ArrayDeque(responses.toList())
        override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
            calls += Call(method, url, contentType, body)
            check(queue.isNotEmpty()) { "Unexpected request" }
            return queue.removeFirst()
        }
    }
    private fun rejected(transport: Fake, export: CsvFile = file, profiling: SyncProfiling = SyncProfiling()): Throwable {
        val result = runCatching { runBlocking { PrivateDriveUploader(transport, profiling).upsert(export) } }
        assertTrue("Upload must fail", result.isFailure)
        return result.exceptionOrNull()!!
    }

    @Test
    fun `Drive request counts match warm cold and paginated calls`() = runBlocking {
        data class Scenario(
            val responses: List<DriveResponse>,
            val operations: Map<DriveOperation, Long>,
            val requests: Map<DriveOperation, Long>,
            val methods: List<String>,
        )
        val warmCounts = mapOf(
            DriveOperation.APP_FOLDER_LOOKUP to 1L,
            DriveOperation.DATASET_FOLDER_LOOKUP to 1L,
            DriveOperation.FILE_LOOKUP to 1L,
            DriveOperation.FILE_UPDATE to 1L,
        )
        val warmResponses = listOf(listed(root), listed(dataset), listed(remote), response(remote))
        val coldCounts = mapOf(
            DriveOperation.APP_FOLDER_LOOKUP to 2L,
            DriveOperation.APP_FOLDER_CREATE to 1L,
            DriveOperation.DATASET_FOLDER_LOOKUP to 1L,
            DriveOperation.DATASET_FOLDER_CREATE to 1L,
            DriveOperation.FILE_LOOKUP to 1L,
            DriveOperation.FILE_CREATE to 1L,
        )
        val scenarios = listOf(
            Scenario(warmResponses, warmCounts, warmCounts, listOf("GET", "GET", "GET", "PATCH")),
            Scenario(
                listOf(listed(), response(root), listed(root), listed(), response(dataset), listed(), response(remote)),
                coldCounts, coldCounts, listOf("GET", "POST", "GET", "GET", "POST", "GET", "POST"),
            ),
            Scenario(
                listOf(listed(page = "synthetic next"), listed(root), listed(dataset), listed(remote), response(remote)),
                warmCounts, warmCounts + (DriveOperation.APP_FOLDER_LOOKUP to 2L),
                listOf("GET", "GET", "GET", "GET", "PATCH"),
            ),
        )
        for (scenario in scenarios) {
            var tick = 0L
            val profiling = SyncProfiling(ProfilingClock { tick++ })
            val fake = Fake(*scenario.responses.toTypedArray())
            assertEquals("date-file", PrivateDriveUploader(fake, profiling).upsert(file))
            assertEquals(scenario.methods, fake.calls.map { it.method })
            val summary = profiling.snapshot()
            assertTrue(summary.stages.isEmpty())
            assertEquals(scenario.operations.keys, summary.operations.keys)
            assertEquals(scenario.requests.keys, summary.requests.keys)
            for ((kind, count) in scenario.requests) {
                assertEquals(ProfileStats(count, count, 0, 0, count, false), summary.requests[kind])
            }
            for ((kind, count) in scenario.operations) {
                assertEquals(ProfileStats(count, count, 0, 0, count + 2 * scenario.requests.getValue(kind), false), summary.operations[kind])
            }
        }
        var tick = 0L
        val profiling = SyncProfiling(ProfilingClock { tick++ })
        val exports = listOf("daily", "workouts", "sleep").map { file.copy(relativePath = "$it/2026-10-30.csv") }
        val responses = exports.flatMap { export ->
            val name = export.relativePath.substringBefore('/')
            listOf(listed(root), listed(entry("daily-folder", name, "app-root")), listed(remote), response(remote))
        }
        val fake = Fake(*responses.toTypedArray())
        val uploader = PrivateDriveUploader(fake, profiling)
        for (export in exports) assertEquals("date-file", uploader.upsert(export))
        assertEquals(12, fake.calls.size)
        assertEquals(9, fake.calls.count { it.method == "GET" })
        assertEquals(3, fake.calls.count { it.method == "PATCH" })
        val summary = profiling.snapshot()
        assertEquals(warmCounts.keys, summary.operations.keys)
        assertEquals(warmCounts.keys, summary.requests.keys)
        for (kind in warmCounts.keys) {
            assertEquals(ProfileStats(3, 3, 0, 0, 3, false), summary.requests[kind])
            assertEquals(ProfileStats(3, 3, 0, 0, 9, false), summary.operations[kind])
        }
    }

    @Test
    fun `Existing app tree syncs when direct My Drive root metadata is inaccessible`() = runBlocking {
        val calls = mutableListOf<Call>()
        val transport = object : DriveTransport {
            override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                calls += Call(method, url, contentType, body)
                if (url.contains("/files/root?")) return DriveResponse(404, "sensitive-not-found")
                val decoded = URLDecoder.decode(url, "UTF-8")
                return when {
                    method == "PATCH" -> response(remote)
                    decoded.contains("'root' in parents") -> listed(root)
                    decoded.contains("'app-root' in parents") -> listed(dataset)
                    decoded.contains("'daily-folder' in parents") -> listed(remote)
                    else -> error("Unexpected endpoint")
                }
            }
        }
        assertEquals("date-file", PrivateDriveUploader(transport).upsert(file))
        assertEquals(listOf("GET", "GET", "GET", "PATCH"), calls.map { it.method })
        assertTrue(calls.none { it.url.contains("/files/root?") })
        assertTrue(URLDecoder.decode(calls.first().url, "UTF-8").contains("'root' in parents"))
    }

    @Test
    fun `Existing private date file is updated without creating duplicates`() = runBlocking {
        val fake = Fake(listed(root), listed(dataset), listed(remote), response(remote))
        assertEquals("date-file", PrivateDriveUploader(fake).upsert(file))
        assertEquals(listOf("GET", "GET", "GET", "PATCH"), fake.calls.map { it.method })
        val update = fake.calls.last()
        assertTrue(update.url.startsWith("https://www.googleapis.com/upload/drive/v3/files/date-file?uploadType=media&"))
        assertEquals("text/csv; charset=UTF-8", update.type)
        assertArrayEquals(file.content.toByteArray(Charsets.UTF_8), update.bytes)
        val lookup = URLDecoder.decode(fake.calls[2].url, "UTF-8")
        assertTrue(lookup.contains("'daily-folder' in parents and name = '2026-10-30.csv' and trashed = false"))
        assertTrue(lookup.contains("appProperties has { key='healthApp' and value='p0-v1' }"))
    }

    @Test
    fun `Missing private folders and file are created with deterministic metadata`() = runBlocking {
        val fake = Fake(listed(), response(root), listed(root), listed(), response(dataset), listed(), response(remote))
        assertEquals("date-file", PrivateDriveUploader(fake).upsert(file))
        val creates = fake.calls.filter { it.method == "POST" }
        assertEquals(3, creates.size)
        for ((index, expected) in listOf(root, dataset).withIndex()) {
            val metadata = Json.parseToJsonElement(creates[index].bytes!!.toString(Charsets.UTF_8)).jsonObject
            for (key in listOf("name", "mimeType", "appProperties")) assertEquals(expected[key], metadata[key])
            if (index == 0) assertFalse(metadata.containsKey("parents"))
            else assertEquals(expected["parents"], metadata["parents"])
            assertTrue(creates[index].url.contains("ignoreDefaultVisibility=true"))
        }
        val upload = creates.last()
        assertTrue(upload.url.contains("uploadType=multipart&ignoreDefaultVisibility=true"))
        assertTrue(upload.type!!.startsWith("multipart/related; boundary="))
        val multipart = upload.bytes!!.toString(Charsets.UTF_8)
        assertTrue(multipart.contains("\"name\":\"2026-10-30.csv\""))
        assertTrue(multipart.contains("\"parents\":[\"daily-folder\"]"))
        assertTrue(multipart.contains("\"appProperties\":{\"healthApp\":\"p0-v1\"}"))
        assertTrue(multipart.contains(file.content))
        assertTrue(fake.calls.none { it.url.contains("permissions") })
    }

    @Test
    fun `Ambiguous or shared targets are rejected before upload`() {
        val ambiguous = Fake(listed(root, page = "synthetic next"), listed(root))
        val ambiguousProfiling = SyncProfiling()
        rejected(ambiguous, profiling = ambiguousProfiling)
        assertEquals(2L, ambiguousProfiling.snapshot().requests.getValue(DriveOperation.APP_FOLDER_LOOKUP).completed)
        assertEquals(1L, ambiguousProfiling.snapshot().operations.getValue(DriveOperation.APP_FOLDER_LOOKUP).failed)
        assertTrue(ambiguous.calls.last().url.contains("pageToken=synthetic+next"))
        assertTrue(ambiguous.calls.all { it.method == "GET" })
        for (unsafe in listOf(
            JsonObject(root + ("shared" to JsonPrimitive(true))),
            JsonObject(root + ("ownedByMe" to JsonPrimitive(false))),
            JsonObject(root - "parents"),
            JsonObject(root + ("parents" to buildJsonArray { add("bad/id") })),
            JsonObject(root + ("parents" to buildJsonArray { add("first"); add("second") })),
            JsonObject(root + ("driveId" to JsonPrimitive("shared-drive"))),
        )) {
            val fake = Fake(listed(unsafe))
            val profiling = SyncProfiling()
            rejected(fake, profiling = profiling)
            assertEquals(1L, profiling.snapshot().requests.getValue(DriveOperation.APP_FOLDER_LOOKUP).completed)
            assertEquals(1L, profiling.snapshot().operations.getValue(DriveOperation.APP_FOLDER_LOOKUP).failed)
            assertEquals(1, fake.calls.size)
            assertTrue(fake.calls.all { it.method == "GET" })
        }
        val sharedFile = Fake(listed(root), listed(dataset), listed(JsonObject(remote + ("shared" to JsonPrimitive(true)))))
        rejected(sharedFile)
        assertTrue(sharedFile.calls.all { it.method == "GET" })
        val misplacedChild = Fake(listed(root), listed(JsonObject(dataset + ("parents" to buildJsonArray { add("other-parent") }))))
        rejected(misplacedChild)
        assertEquals(2, misplacedChild.calls.size)
        assertTrue(misplacedChild.calls.all { it.method == "GET" })
    }

    @Test
    fun `New app folder must be confirmed in My Drive before any dataset or health file write`() = runBlocking {
        for (confirmation in listOf(
            listed(),
            listed(JsonObject(root + ("id" to JsonPrimitive("other-folder")))),
            listed(JsonObject(root + ("parents" to buildJsonArray { add("other-canonical-root") }))),
            listed(JsonObject(root + ("shared" to JsonPrimitive(true)))),
            DriveResponse(404, "sensitive-provider-body"),
        )) {
            val fake = Fake(listed(), response(root), confirmation)
            val error = rejected(fake) as DriveUploadException
            assertEquals(DriveOperation.APP_FOLDER_LOOKUP, error.details.operation)
            assertEquals(listOf("GET", "POST", "GET"), fake.calls.map { it.method })
            assertTrue(URLDecoder.decode(fake.calls.last().url, "UTF-8").contains("'root' in parents"))
            val metadata = Json.parseToJsonElement(fake.calls[1].bytes!!.toString(Charsets.UTF_8)).jsonObject
            assertEquals(JsonPrimitive("HealthApp"), metadata["name"])
            assertFalse(metadata.containsKey("parents"))
            assertFalse(error.toString().contains("sensitive"))
        }
    }

    @Test
    fun `HTTP failures and malformed responses never report confirmed upload`() {
        for (bad in listOf(DriveResponse(403, "sensitive-body"), DriveResponse(200, "invalid sensitive-body"), response(buildJsonObject { put("id", "bad/id") }))) {
            val fake = Fake(listed(root), listed(dataset), listed(remote), bad)
            val profiling = SyncProfiling()
            val error = rejected(fake, profiling = profiling)
            val summary = profiling.snapshot()
            val request = summary.requests.getValue(DriveOperation.FILE_UPDATE)
            assertEquals(1L, request.started)
            assertEquals(if (bad.status == 200 && bad.body.startsWith("{")) 1L else 0L, request.completed)
            assertEquals(if (bad.status == 200 && bad.body.startsWith("{")) 0L else 1L, request.failed)
            assertEquals(1L, summary.operations.getValue(DriveOperation.FILE_UPDATE).failed)
            assertFalse(error.toString().contains("sensitive-body"))
            assertNull(error.cause)
            assertEquals(4, fake.calls.size)
        }
        val failedCreate = Fake(listed(root), listed(dataset), listed(), DriveResponse(503, "sensitive-body"))
        rejected(failedCreate)
        assertEquals(1, failedCreate.calls.count { it.method == "POST" })
        val malformedLookup = Fake(DriveResponse(200, "{}"))
        rejected(malformedLookup)
        assertEquals(1, malformedLookup.calls.size)
    }

    @Test
    fun `HTTP failures identify the exact Drive operation without exposing provider data or retrying`() = runBlocking {
        val responses = listOf(listed(), response(root), listed(root), listed(), response(dataset), listed(), response(remote))
        val stages = listOf(DriveOperation.APP_FOLDER_LOOKUP, DriveOperation.APP_FOLDER_CREATE, DriveOperation.APP_FOLDER_LOOKUP,
            DriveOperation.DATASET_FOLDER_LOOKUP, DriveOperation.DATASET_FOLDER_CREATE, DriveOperation.FILE_LOOKUP, DriveOperation.FILE_CREATE)
        for ((index, stage) in stages.withIndex()) {
            val fake = Fake(*(responses.take(index) + DriveResponse(403, "sensitive-provider-body")).toTypedArray())
            val error = rejected(fake) as DriveUploadException
            assertEquals(DriveFailureDetails(stage, DriveFailureKind.HTTP, 403), error.details)
            assertEquals(index + 1, fake.calls.size)
            assertFalse(error.toString().contains("sensitive-provider-body"))
            assertNull(error.cause)
        }
        val fake = Fake(listed(root), listed(dataset), listed(remote), DriveResponse(401, "sensitive-provider-body"))
        assertEquals(DriveFailureDetails(DriveOperation.FILE_UPDATE, DriveFailureKind.HTTP, 401), (rejected(fake) as DriveUploadException).details)
        assertEquals(4, fake.calls.size)
    }

    @Test
    fun `Network invalid response and rejected private target have distinct safe diagnostics`() = runBlocking {
        val transport = object : DriveTransport {
            override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                throw java.io.IOException("sensitive-token-url", IllegalStateException("sensitive-cause"))
            }
        }
        val networkProfiling = SyncProfiling()
        val network = runCatching { PrivateDriveUploader(transport, networkProfiling).upsert(file) }.exceptionOrNull() as DriveUploadException
        assertEquals(1L, networkProfiling.snapshot().requests.getValue(DriveOperation.APP_FOLDER_LOOKUP).failed)
        assertEquals(1L, networkProfiling.snapshot().operations.getValue(DriveOperation.APP_FOLDER_LOOKUP).failed)
        assertEquals(DriveFailureDetails(DriveOperation.APP_FOLDER_LOOKUP, DriveFailureKind.NETWORK), network.details)
        assertNull(network.cause)
        assertFalse(network.toString().contains("sensitive"))
        val malformed = rejected(Fake(DriveResponse(200, "sensitive-invalid-json"))) as DriveUploadException
        assertEquals(DriveFailureDetails(DriveOperation.APP_FOLDER_LOOKUP, DriveFailureKind.RESPONSE_INVALID), malformed.details)
        val shared = JsonObject(root + ("shared" to JsonPrimitive(true)))
        val unsafe = rejected(Fake(listed(shared))) as DriveUploadException
        assertEquals(DriveFailureDetails(DriveOperation.APP_FOLDER_LOOKUP, DriveFailureKind.TARGET_REJECTED), unsafe.details)
        val cancelled = kotlinx.coroutines.CancellationException("synthetic")
        val cancelledTransport = object : DriveTransport {
            override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse = throw cancelled
        }
        val cancelledProfiling = SyncProfiling()
        assertSame(cancelled, runCatching { PrivateDriveUploader(cancelledTransport, cancelledProfiling).upsert(file) }.exceptionOrNull())
        val summary = cancelledProfiling.snapshot()
        for (stats in listOf(summary.requests.getValue(DriveOperation.APP_FOLDER_LOOKUP), summary.operations.getValue(DriveOperation.APP_FOLDER_LOOKUP))) {
            assertEquals(1L, stats.started)
            assertEquals(1L, stats.cancelled)
            assertEquals(0L, stats.completed)
            assertEquals(0L, stats.failed)
            assertFalse(stats.active)
        }
    }

    @Test
    fun `Invalid export paths are rejected before network access`() {
        for (path in listOf("../2026-10-30.csv", "daily/2026-02-30.csv", "daily/2026-1-01.csv", "unknown/2026-10-30.csv", "/daily/2026-10-30.csv", "daily/2026-10-30.csv?x=1", "daily/2026-10-30.csv/extra")) {
            val fake = Fake()
            rejected(fake, file.copy(relativePath = path))
            assertTrue(fake.calls.isEmpty())
        }
    }
}
