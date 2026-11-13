package com.ny.my.health.core

import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateDriveReconciliationTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val now = Instant.parse("2026-10-31T00:00:00Z")
    private val folder = "application/vnd.google-apps.folder"
    private fun digest(algorithm: String, text: String) = MessageDigest.getInstance(algorithm)
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun planned(dataset: Dataset = Dataset.DAILY, unknown: Boolean = false): PlannedFile {
        val path = when (dataset) { Dataset.DAILY -> "daily"; Dataset.WORKOUTS -> "workouts"; Dataset.HEART_RATE -> "heart-rate"; Dataset.SLEEP -> "sleep" }
        val file = CsvFile("$path/$date.csv", "synthetic\r\n")
        return PlannedFile(FileKey(DestinationGeneration("synthetic-generation"), date, ReadScopeKey("synthetic-scope"), dataset, "synthetic-revision"), file, digest("SHA-256", file.content), null, unknown)
    }
    private fun entry(id: String, name: String, parent: String, mime: String = folder) = buildJsonObject {
        put("id", id); put("name", name); put("mimeType", mime)
        putJsonArray("parents") { add(parent) }
        put("ownedByMe", true); put("shared", false); put("trashed", false)
        putJsonObject("appProperties") { put("healthApp", "p0-v1") }
    }
    private inner class Fake : DriveTransport {
        val entries = mutableListOf(entry("app-root", "HealthApp", "my-drive"),
            entry("daily-folder", "daily", "app-root"), entry("daily-file", "$date.csv", "daily-folder", "text/csv"))
        val calls = mutableListOf<Pair<String, String>>()
        var incomplete = false
        var hook: suspend (String, String) -> DriveResponse? = { _, _ -> null }
        override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
            val q = URLDecoder.decode(url.substringAfter("q=", "").substringBefore('&'), "UTF-8")
            val name = if (method == "GET") q.substringAfter("name = '").substringBefore("'") else if (method == "PATCH") "$date.csv" else {
                val text = body!!.toString(Charsets.UTF_8)
                Json.parseToJsonElement(if (contentType!!.startsWith("multipart")) text.substringAfter("\r\n\r\n").substringBefore("\r\n--") else text).jsonObject.getValue("name").jsonPrimitive.content
            }
            calls += method to name
            hook(method, name)?.let { return it }
            if (method == "GET") {
                val parent = q.substringAfter("'").substringBefore("'")
                return DriveResponse(200, buildJsonObject {
                    put("incompleteSearch", incomplete)
                    put("files", JsonArray(entries.filter { it["name"] == JsonPrimitive(name) &&
                        it["trashed"] == JsonPrimitive(false) && it["parents"] == buildJsonArray { add(if (parent == "root") "my-drive" else parent) } }))
                }.toString())
            }
            check(method == "PATCH")
            return DriveResponse(200, entries.single { it["name"] == JsonPrimitive(name) }.toString())
        }
    }
    private suspend fun reason(expected: BlockReason, block: suspend () -> Unit) {
        try { block(); fail("목적지 차단 필요") } catch (error: DestinationException) { assertEquals(expected, error.reason) }
    }
    private fun uploader(fake: Fake) = PrivateDriveUploader(fake, clock = Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `Drive reconciliation never writes and unknown folder or file creation never blind posts`() = runBlocking {
        for (missing in listOf("HealthApp", "daily", "$date.csv")) {
            val fake = Fake()
            fake.entries.removeAll { it["name"] == JsonPrimitive(missing) }
            val uploader = uploader(fake)
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.reconcileTracked(listOf(planned(unknown = true)), emptyMap()) }
            assertTrue(fake.calls.all { it.first == "GET" })
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.writeTrackedPending(listOf(planned(unknown = true))) { _, _ -> fail() } }
            assertTrue(fake.calls.all { it.first == "GET" })
        }
        for (stage in listOf("HealthApp", "daily", "$date.csv")) {
            val fake = Fake()
            if (stage == "HealthApp") fake.entries.clear()
            else if (stage == "daily") fake.entries.removeAll { it["name"] != JsonPrimitive("HealthApp") }
            else fake.entries.removeAt(2)
            val uploader = uploader(fake)
            val initial = planned()
            assertEquals(listOf(initial), uploader.reconcileTracked(listOf(initial), emptyMap()).pending)
            assertTrue(fake.calls.all { it.first == "GET" })
            fake.hook = { method, name -> if (method == "POST" && name == stage) throw IOException("합성 실패") else null }
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.writeTrackedPending(listOf(initial)) { _, _ -> fail() } }
            val posts = fake.calls.count { it.first == "POST" }
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.reconcileTracked(listOf(planned(unknown = true)), emptyMap()) }
            assertEquals(posts, fake.calls.count { it.first == "POST" })
        }
    }

    @Test
    fun `Fresh Drive ACK permits absent checksum but rejects wrong checksum and changed target identity`() = runBlocking {
        for (change in listOf("absent", "wrong", "identity")) {
            val fake = Fake()
            val uploader = uploader(fake)
            val file = planned()
            val result = uploader.reconcileTracked(listOf(file), mapOf(Dataset.DAILY to setOf("daily-file")))
            assertEquals(listOf(file), result.pending)
            val calls = fake.calls.size
            if (change == "wrong") fake.entries[2] = JsonObject(fake.entries[2] + ("md5Checksum" to JsonPrimitive("0".repeat(32))))
            if (change == "identity") fake.entries[2] = JsonObject(fake.entries[2] + ("id" to JsonPrimitive("changed-id")))
            val confirmed = mutableListOf<Pair<CsvFile, String>>()
            if (change == "absent") {
                uploader.writeTrackedPending(result.pending) { csv, id -> confirmed += csv to id }
                assertEquals(listOf(file.file to "daily-file"), confirmed)
            } else reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.writeTrackedPending(result.pending) { csv, id -> confirmed += csv to id } }
            assertEquals(listOf("PATCH"), fake.calls.drop(calls).map { it.first })
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.writeTrackedPending(result.pending) { _, _ -> fail() } }
        }
    }

    @Test
    fun `Exact checksum adopts fresh safe metadata and absent malformed or mismatched checksum preserves exact pending`() = runBlocking {
        for (checksum in listOf("exact", "md5-only", "absent", "malformed", "wrong")) {
            val fake = Fake()
            val file = planned()
            if (checksum != "absent") fake.entries[2] = JsonObject(fake.entries[2] +
                ((if (checksum == "md5-only") "md5Checksum" else "sha256Checksum") to JsonPrimitive(when (checksum) {
                    "exact" -> digest("SHA-256", file.file.content)
                    "md5-only" -> digest("MD5", file.file.content)
                    "malformed" -> "bad"
                    else -> "0".repeat(64)
                })))
            val uploader = uploader(fake)
            val result = uploader.reconcileTracked(listOf(file), emptyMap())
            if (checksum == "exact") {
                assertEquals(listOf(FileConfirmation(file.key, "daily-file", now)), result.confirmed)
                assertTrue(result.pending.isEmpty())
            } else { assertEquals(listOf(file), result.pending); assertTrue(result.confirmed.isEmpty()) }
            assertEquals(3, fake.calls.size)
            assertTrue(fake.calls.all { it.first == "GET" })
            if (checksum == "md5-only") {
                var target: String? = null
                uploader.writeTrackedPending(result.pending) { _, id -> target = id }
                assertEquals("daily-file", target)
                assertEquals(listOf("PATCH"), fake.calls.drop(3).map { it.first })
            }
        }
    }

    @Test
    fun `Known missing moved deleted shared duplicate incomplete or conflicting targets block every batch before writes`() = runBlocking {
        for (change in listOf("missing", "moved", "deleted", "shared", "duplicate", "incomplete", "conflict", "other-dataset")) {
            val fake = Fake()
            when (change) {
                "missing" -> fake.entries.removeAt(2)
                "moved" -> fake.entries[2] = JsonObject(fake.entries[2] + ("parents" to buildJsonArray { add("moved-parent") }))
                "deleted" -> fake.entries[2] = JsonObject(fake.entries[2] + ("trashed" to JsonPrimitive(true)))
                "shared" -> fake.entries[2] = JsonObject(fake.entries[2] + ("shared" to JsonPrimitive(true)))
                "duplicate" -> fake.entries += fake.entries[2]
                "incomplete" -> fake.incomplete = true
            }
            val known = when (change) {
                "conflict" -> mapOf(Dataset.DAILY to setOf("daily-file", "old-id"))
                "other-dataset" -> mapOf(Dataset.SLEEP to setOf("sleep-file"))
                else -> mapOf(Dataset.DAILY to setOf("daily-file"))
            }
            val uploader = uploader(fake)
            reason(BlockReason.DESTINATION_BLOCKED) { uploader.reconcileTracked(listOf(planned()), known) }
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.writeTrackedPending(listOf(planned())) { _, _ -> fail() } }
            assertTrue(fake.calls.all { it.first == "GET" })
        }
    }

    @Test
    fun `Lookup and PATCH transient failures remain retryable while authorization and unsafe writes block`() = runBlocking {
        for (method in listOf("GET", "PATCH")) for (status in listOf(0, 429, 500, 502, 503, 504, 401, 403, 404)) {
            val fake = Fake()
            val uploader = uploader(fake)
            val file = planned()
            if (method == "PATCH") uploader.reconcileTracked(listOf(file), emptyMap())
            fake.hook = { call, _ -> if (call == method) {
                if (status == 0) throw IOException("합성 실패")
                DriveResponse(status, "합성 실패")
            } else null }
            val expected = when (status) {
                401, 403 -> BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED
                404 -> BlockReason.DESTINATION_BLOCKED
                else -> BlockReason.DESTINATION_TRANSIENT_FAILURE
            }
            reason(expected) {
                if (method == "GET") uploader.reconcileTracked(listOf(file), emptyMap())
                else uploader.writeTrackedPending(listOf(file)) { _, _ -> fail() }
            }
        }
    }

    @Test
    fun `Prepared three file writes keep root once bounded two serial callbacks and joined cancellation`() = runBlocking {
        for (cancel in listOf(false, true)) withTimeout(5_000) {
            val fake = Fake()
            for (name in listOf("workouts", "heart-rate")) {
                fake.entries += entry("$name-folder", name, "app-root")
                fake.entries += entry("$name-file", "$date.csv", "$name-folder", "text/csv")
            }
            val uploader = uploader(fake)
            val files = listOf(Dataset.DAILY, Dataset.WORKOUTS, Dataset.HEART_RATE).map { planned(it) }
            val result = uploader.reconcileTracked(files, emptyMap())
            assertEquals(files, result.pending)
            assertEquals(1, fake.calls.count { it.second == "HealthApp" })
            assertEquals(7, fake.calls.size)
            var active = 0
            var maximum = 0
            var cleaned = 0
            val two = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fake.hook = { method, _ ->
                if (method == "PATCH") {
                    active++; maximum = maxOf(maximum, active)
                    if (active == 2) two.complete(Unit)
                    try { release.await() } finally { active--; cleaned++ }
                    // URL의 대상은 fixture가 전체 메타데이터로 별도 응답한다.
                }
                null
            }
            var callbacks = 0
            var callbackActive = 0
            var maximumCallbacks = 0
            // 같은 이름의 파일은 ID별로 반환하여 ACK identity를 검증한다.
            val transport = object : DriveTransport {
                override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                    if (method != "PATCH") return fake.execute(method, url, contentType, body)
                    fake.calls += method to "$date.csv"
                    fake.hook(method, "$date.csv")?.let { return it }
                    val id = url.substringBefore('?').substringAfterLast('/')
                    return DriveResponse(200, fake.entries.single { it["id"] == JsonPrimitive(id) }.toString())
                }
            }
            val writer = PrivateDriveUploader(transport)
            val prepared = writer.reconcileTracked(files, emptyMap())
            val beforeWrite = fake.calls.size
            val task = async {
                writer.writeTrackedPending(prepared.pending) { _, _ ->
                    callbackActive++; maximumCallbacks = maxOf(maximumCallbacks, callbackActive)
                    yield(); callbacks++; callbackActive--
                }
            }
            two.await()
            assertEquals(2, maximum)
            if (cancel) task.cancelAndJoin() else { release.complete(Unit); task.await() }
            assertEquals(0, active)
            assertEquals(if (cancel) 2 else 3, cleaned)
            assertEquals(if (cancel) 0 else 3, callbacks)
            assertEquals(if (cancel) 0 else 1, maximumCallbacks)
            assertEquals(if (cancel) 2 else 3, fake.calls.drop(beforeWrite).size)
            assertTrue(fake.calls.drop(beforeWrite).all { it.first == "PATCH" })
            reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { writer.writeTrackedPending(prepared.pending) { _, _ -> fail() } }
        }
    }

    @Test
    fun `Changed or replaced preparation fails closed without a remote mutation`() = runBlocking {
        val fake = Fake()
        val uploader = uploader(fake)
        val file = planned()
        uploader.reconcileTracked(listOf(file), emptyMap())
        val changed = file.copy(file = file.file.copy(content = "changed"), byteSha256 = digest("SHA-256", "changed"))
        reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.writeTrackedPending(listOf(changed)) { _, _ -> fail() } }
        reason(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) { uploader.writeTrackedPending(listOf(file)) { _, _ -> fail() } }
        assertTrue(fake.calls.all { it.first == "GET" })
    }
}
