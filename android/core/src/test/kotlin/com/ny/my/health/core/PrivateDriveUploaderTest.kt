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
    private fun myDrive() = response(buildJsonObject { put("id", "my-drive") })
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
    private fun rejected(transport: Fake, export: CsvFile = file): Throwable {
        val result = runCatching { runBlocking { PrivateDriveUploader(transport).upsert(export) } }
        assertTrue("Upload must fail", result.isFailure)
        return result.exceptionOrNull()!!
    }

    @Test
    fun `Existing private date file is updated without creating duplicates`() = runBlocking {
        val fake = Fake(myDrive(), listed(root), listed(dataset), listed(remote), response(remote))
        assertEquals("date-file", PrivateDriveUploader(fake).upsert(file))
        assertEquals(listOf("GET", "GET", "GET", "GET", "PATCH"), fake.calls.map { it.method })
        val update = fake.calls.last()
        assertTrue(update.url.startsWith("https://www.googleapis.com/upload/drive/v3/files/date-file?uploadType=media&"))
        assertEquals("text/csv; charset=UTF-8", update.type)
        assertArrayEquals(file.content.toByteArray(Charsets.UTF_8), update.bytes)
        val lookup = URLDecoder.decode(fake.calls[3].url, "UTF-8")
        assertTrue(lookup.contains("'daily-folder' in parents and name = '2026-10-30.csv' and trashed = false"))
        assertTrue(lookup.contains("appProperties has { key='healthApp' and value='p0-v1' }"))
    }

    @Test
    fun `Missing private folders and file are created with deterministic metadata`() = runBlocking {
        val fake = Fake(myDrive(), listed(), response(root), listed(), response(dataset), listed(), response(remote))
        assertEquals("date-file", PrivateDriveUploader(fake).upsert(file))
        val creates = fake.calls.filter { it.method == "POST" }
        assertEquals(3, creates.size)
        for ((index, expected) in listOf(root, dataset).withIndex()) {
            val metadata = Json.parseToJsonElement(creates[index].bytes!!.toString(Charsets.UTF_8)).jsonObject
            for (key in listOf("name", "mimeType", "parents", "appProperties")) assertEquals(expected[key], metadata[key])
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
        val ambiguous = Fake(myDrive(), listed(root, page = "synthetic next"), listed(root))
        rejected(ambiguous)
        assertTrue(ambiguous.calls.last().url.contains("pageToken=synthetic+next"))
        assertTrue(ambiguous.calls.all { it.method == "GET" })
        for (unsafe in listOf(
            JsonObject(root + ("shared" to JsonPrimitive(true))),
            JsonObject(root + ("ownedByMe" to JsonPrimitive(false))),
            JsonObject(root + ("parents" to buildJsonArray { add("other-parent") })),
            JsonObject(root + ("driveId" to JsonPrimitive("shared-drive"))),
        )) {
            val fake = Fake(myDrive(), listed(unsafe))
            rejected(fake)
            assertEquals(2, fake.calls.size)
            assertTrue(fake.calls.all { it.method == "GET" })
        }
        val sharedFile = Fake(myDrive(), listed(root), listed(dataset), listed(JsonObject(remote + ("shared" to JsonPrimitive(true)))))
        rejected(sharedFile)
        assertTrue(sharedFile.calls.all { it.method == "GET" })
    }

    @Test
    fun `HTTP failures and malformed responses never report confirmed upload`() {
        for (bad in listOf(DriveResponse(403, "sensitive-body"), DriveResponse(200, "invalid sensitive-body"), response(buildJsonObject { put("id", "bad/id") }))) {
            val fake = Fake(myDrive(), listed(root), listed(dataset), listed(remote), bad)
            val error = rejected(fake)
            assertFalse(error.toString().contains("sensitive-body"))
            assertNull(error.cause)
            assertEquals(5, fake.calls.size)
        }
        val failedCreate = Fake(myDrive(), listed(root), listed(dataset), listed(), DriveResponse(503, "sensitive-body"))
        rejected(failedCreate)
        assertEquals(1, failedCreate.calls.count { it.method == "POST" })
        val malformedLookup = Fake(myDrive(), DriveResponse(200, "{}"))
        rejected(malformedLookup)
        assertEquals(2, malformedLookup.calls.size)
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
