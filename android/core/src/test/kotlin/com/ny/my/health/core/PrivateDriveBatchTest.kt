package com.ny.my.health.core

import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateDriveBatchTest {
    private val folder = "application/vnd.google-apps.folder"
    private val files = listOf("daily", "workouts", "heart-rate").map { CsvFile("$it/2026-10-30.csv", "synthetic\r\n") }
    private fun entry(id: String, name: String, parent: String, mime: String = folder) = buildJsonObject {
        put("id", id); put("name", name); put("mimeType", mime)
        putJsonArray("parents") { add(parent) }
        put("ownedByMe", true); put("shared", false); put("trashed", false)
        putJsonObject("appProperties") { put("healthApp", "p0-v1") }
    }
    private fun response(value: JsonObject) = DriveResponse(200, value.toString())
    private fun listed(items: List<JsonObject>) = response(buildJsonObject {
        put("files", JsonArray(items)); put("incompleteSearch", false)
    })
    private data class Call(val method: String, val name: String, val parent: String, val file: Boolean)
    private inner class Fake(warm: Boolean = true) : DriveTransport {
        val entries = mutableListOf<JsonObject>()
        val calls = mutableListOf<Call>()
        var active = 0
        var maximum = 0
        var cancelled = 0
        var hook: suspend (Call) -> DriveResponse? = { null }
        init {
            if (warm) {
                entries += entry("app-root", "HealthApp", "my-drive")
                for (dataset in listOf("daily", "workouts", "heart-rate", "sleep")) {
                    entries += entry("$dataset-folder", dataset, "app-root")
                    entries += entry("$dataset-file", "2026-10-30.csv", "$dataset-folder", "text/csv")
                }
            }
        }
        override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
            val q = url.substringAfter("q=", "").substringBefore('&').let { URLDecoder.decode(it, "UTF-8") }
            val target = if (method == "GET") {
                val name = q.substringAfter("name = '").substringBefore("'")
                val parent = q.substringAfter("'").substringBefore("'")
                Call(method, name, parent, name.endsWith(".csv"))
            } else if (method == "PATCH") {
                val id = url.substringBefore('?').substringAfterLast('/')
                val item = entries.single { it["id"] == JsonPrimitive(id) }
                Call(method, item.getValue("name").jsonPrimitive.content, item.getValue("parents").jsonArray.single().jsonPrimitive.content, true)
            } else {
                val text = body!!.toString(Charsets.UTF_8)
                val metadata = if (contentType!!.startsWith("multipart")) text.substringAfter("\r\n\r\n").substringBefore("\r\n--") else text
                val item = Json.parseToJsonElement(metadata).jsonObject
                Call(method, item.getValue("name").jsonPrimitive.content, item["parents"]?.jsonArray?.single()?.jsonPrimitive?.content ?: "my-drive", item.getValue("mimeType") == JsonPrimitive("text/csv"))
            }
            calls += target
            active++
            maximum = maxOf(maximum, active)
            try {
                hook(target)?.let { return it }
                if (method == "GET") {
                    return listed(entries.filter {
                        it["name"] == JsonPrimitive(target.name) &&
                            (it["parents"] == buildJsonArray { add(target.parent) } || target.parent == "root")
                    })
                }
                if (method == "PATCH") {
                    return response(entries.single { it["name"] == JsonPrimitive(target.name) && it["parents"] == buildJsonArray { add(target.parent) } })
                }
                val created = entry("created-${entries.size}", target.name, target.parent, if (target.file) "text/csv" else folder)
                entries += created
                return response(created)
            } catch (cancel: CancellationException) {
                cancelled++
                throw cancel
            } finally { active-- }
        }
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (failure: Throwable) { return failure }
        error("배치가 실패해야 한다")
    }
    private fun coordinator(uploader: DriveUploader, profiling: SyncProfiling? = null): SyncCoordinator {
        val date = LocalDate.of(2026, 10, 30)
        val reader = object : HealthReader {
            override suspend fun read(date: LocalDate) = HealthSnapshot(date, 42, emptyList(), emptyList(), emptyList(), emptyList(), setOf(DataType.STEPS))
        }
        val exporter = object : CsvExporter { override fun export(day: NormalizedDay) = files }
        return SyncCoordinator(reader, DefaultNormalizer(), exporter, uploader, Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC), profiling)
    }

    @Test
    fun `Warm batch of three datasets confirms three files with ten requests`() = runBlocking {
        val fake = Fake()
        val confirmed = mutableListOf<String>()
        PrivateDriveUploader(fake).upsertBatch(files) { confirmed += it }
        assertEquals(10, fake.calls.size)
        assertEquals(3, confirmed.distinct().size)
        assertEquals(listOf("HealthApp", "daily", "workouts", "heart-rate"), fake.calls.take(4).map { it.name })
        assertEquals(0, fake.active)
        val same = Fake()
        PrivateDriveUploader(same).upsertBatch(listOf(files[0], CsvFile("daily/2026-10-29.csv", "synthetic"))) {}
        assertEquals(1, same.calls.count { it.name == "daily" })
        assertEquals(6, same.calls.size)
    }

    @Test
    fun `Single warm and cold uploads retain four and seven requests`() = runBlocking {
        for ((warm, expected) in listOf(true to 4, false to 7)) {
            for (batch in listOf(false, true)) {
                val fake = Fake(warm)
                val uploader = PrivateDriveUploader(fake)
                if (batch) uploader.upsertBatch(files.take(1)) {} else assertTrue(uploader.upsert(files[0]).isNotBlank())
                assertEquals(expected, fake.calls.size)
            }
        }
        val fake = Fake()
        PrivateDriveUploader(fake).upsertBatch(emptyList()) { fail("빈 배치 확인 금지") }
        assertTrue(fake.calls.isEmpty())
        for (invalid in listOf(CsvFile("../2026-10-30.csv", ""), CsvFile("daily/2026-02-30.csv", ""), CsvFile("daily/2026-10-30.csv", "x".repeat(5_000_001)))) {
            assertTrue(rejected { PrivateDriveUploader(fake).upsertBatch(listOf(files[0], invalid)) {} } is IllegalArgumentException)
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test
    fun `Delayed file requests overlap with a maximum of two active requests`() = runBlocking {
        withTimeout(5_000) {
            val fake = Fake()
            val two = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fake.hook = { call ->
                if (call.file && call.method == "GET") {
                    if (fake.active == 2) two.complete(Unit)
                    release.await()
                }
                null
            }
            var activeCallbacks = 0
            var maximumCallbacks = 0
            val callbackEntered = CompletableDeferred<Unit>()
            val callbackRelease = CompletableDeferred<Unit>()
            val task = async {
                PrivateDriveUploader(fake).upsertBatch(files) {
                    activeCallbacks++
                    maximumCallbacks = maxOf(maximumCallbacks, activeCallbacks)
                    callbackEntered.complete(Unit)
                    callbackRelease.await()
                    activeCallbacks--
                }
            }
            two.await()
            assertEquals(files.take(2).map { it.relativePath.substringBefore('/') + "-folder" }, fake.calls.filter { it.file }.map { it.parent })
            assertEquals(2, fake.maximum)
            release.complete(Unit)
            callbackEntered.await()
            yield()
            assertEquals(1, activeCallbacks)
            assertEquals(2, fake.calls.count { it.file && it.method == "GET" })
            callbackRelease.complete(Unit)
            task.await()
            assertEquals(1, maximumCallbacks)
            assertEquals(2, fake.maximum)
            assertEquals(0, fake.active)
        }
    }

    @Test
    fun `Concurrent cold batches and single calls serialize without duplicate folders`() = runBlocking {
        for (single in listOf(false, true)) withTimeout(5_000) {
            val fake = Fake(false)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fake.hook = { call ->
                if (call.name == "HealthApp" && call.method == "GET" && !entered.isCompleted) {
                    entered.complete(Unit); release.await()
                }
                null
            }
            val uploader = PrivateDriveUploader(fake)
            val first = async { uploader.upsertBatch(files) {} }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                if (single) uploader.upsert(files[0]) else uploader.upsertBatch(files) {}
            }
            assertEquals(1, fake.calls.size)
            release.complete(Unit)
            first.await(); second.await()
            assertEquals(1, fake.calls.count { it.name == "HealthApp" && it.method == "POST" })
            for (dataset in listOf("daily", "workouts", "heart-rate")) assertEquals(1, fake.calls.count { it.name == dataset && it.method == "POST" })
            assertEquals(0, fake.active)
        }
    }

    @Test
    fun `Each later batch rejects changed unsafe root before child writes`() = runBlocking {
        for (change in listOf("shared", "moved", "deleted", "duplicate")) {
            val fake = Fake()
            val uploader = PrivateDriveUploader(fake)
            uploader.upsertBatch(files) {}
            val original = fake.entries.first()
            fake.calls.clear()
            when (change) {
                "shared" -> fake.entries[0] = JsonObject(original + ("shared" to JsonPrimitive(true)))
                "moved" -> fake.entries[0] = JsonObject(original + ("parents" to buildJsonArray { add("root") }))
                "deleted" -> fake.entries[0] = JsonObject(original + ("trashed" to JsonPrimitive(true)))
                "duplicate" -> fake.entries += original
            }
            var confirmed = 0
            assertTrue(rejected { uploader.upsertBatch(files) { confirmed++ } } is DriveUploadException)
            assertEquals(0, confirmed)
            assertEquals(listOf("HealthApp"), fake.calls.map { it.name })
        }
    }

    @Test
    fun `Duplicate relative paths are rejected before any remote request or confirmation`() = runBlocking {
        for (warm in listOf(false, true)) {
            val fake = Fake(warm)
            var confirmed = 0
            val duplicate = listOf(files[0], files[1], files[0].copy(content = "synthetic replacement"))
            assertTrue(rejected { PrivateDriveUploader(fake).upsertBatch(duplicate) { confirmed++ } } is IllegalArgumentException)
            assertTrue(fake.calls.isEmpty())
            assertEquals(0, confirmed)
        }
    }

    @Test
    fun `Later batches replace filtered moved or trashed roots without reusing prior target IDs`() = runBlocking {
        for (change in listOf("moved", "trashed")) {
            val fake = Fake()
            val uploader = PrivateDriveUploader(fake)
            uploader.upsertBatch(files) {}
            val original = fake.entries.first()
            val oldIds = fake.entries.map { it.getValue("id").jsonPrimitive.content }.toSet()
            fake.entries[0] = if (change == "moved") {
                JsonObject(original + ("parents" to buildJsonArray { add("another-parent") }))
            } else {
                JsonObject(original + ("trashed" to JsonPrimitive(true)))
            }
            fake.calls.clear()
            // 이 fixture만 서버의 root 멤버십·휴지통 조회 조건을 그대로 적용한다.
            fake.hook = { call ->
                if (call.method == "GET" && call.name == "HealthApp") {
                    listed(fake.entries.filter {
                        it["name"] == JsonPrimitive("HealthApp") &&
                            it["parents"] == buildJsonArray { add("my-drive") } &&
                            it["trashed"] == JsonPrimitive(false)
                    })
                } else null
            }
            val confirmed = mutableListOf<String>()
            uploader.upsertBatch(files) { confirmed += it }
            assertEquals(3, confirmed.size)
            assertTrue(confirmed.none { it in oldIds })
            assertTrue(fake.calls.none { it.parent in oldIds })
            assertTrue(fake.calls.none { it.method == "PATCH" })
            assertEquals(1, fake.calls.count { it.name == "HealthApp" && it.method == "POST" })
            assertEquals(listOf("GET", "POST", "GET"), fake.calls.take(3).map { it.method })
            val newRoots = fake.entries.filter {
                it["name"] == JsonPrimitive("HealthApp") && it.getValue("id").jsonPrimitive.content !in oldIds
            }
            assertEquals(1, newRoots.size)
            assertEquals(JsonPrimitive(false), newRoots.single()["shared"])
            assertEquals(JsonPrimitive(true), newRoots.single()["ownedByMe"])
            assertEquals(0, fake.active)
        }
    }

    @Test
    fun `Default batch uploader preserves single upsert API and file order`() = runBlocking {
        val calls = mutableListOf<CsvFile>()
        val confirmations = mutableListOf<String>()
        val uploader = object : DriveUploader {
            override suspend fun upsert(file: CsvFile): String { calls += file; return "id-${calls.size}" }
        }
        uploader.upsertBatch(files) { confirmations += it }
        assertEquals(files, calls)
        assertEquals(listOf("id-1", "id-2", "id-3"), confirmations)
        assertEquals("id-4", uploader.upsert(files[0]))
        calls.clear()
        rejected { uploader.upsertBatch(files) { error("합성 콜백 실패") } }
        assertEquals(files.take(1), calls)
        var count = 0
        val blank = object : DriveUploader { override suspend fun upsert(file: CsvFile): String { count++; return " " } }
        rejected { blank.upsertBatch(files) { fail("공백 ID 확인 금지") } }
        assertEquals(1, count)
    }

    @Test
    fun `Batch failure stops queued files and reports only confirmed files after children finish`() = runBlocking {
        for (failingIndex in listOf(0, 1)) withTimeout(5_000) {
            val fake = Fake()
            val both = CompletableDeferred<Unit>()
            val siblingStarted = CompletableDeferred<Unit>()
            var cleaned = false
            fake.hook = { call ->
                if (call.file && call.method == "GET") {
                    if (fake.active == 2) both.complete(Unit)
                    if (call.parent == files[failingIndex].relativePath.substringBefore('/') + "-folder") {
                        both.await(); siblingStarted.await()
                        DriveResponse(503, "synthetic")
                    } else {
                        siblingStarted.complete(Unit)
                        try { awaitCancellation() } finally { cleaned = true }
                    }
                } else null
            }
            val result = coordinator(PrivateDriveUploader(fake)).sync(LocalDate.of(2026, 10, 30))
            assertEquals(SyncStatus.FAILED, result.status)
            assertEquals(0, result.uploadedFiles)
            assertEquals(DriveFailureDetails(DriveOperation.FILE_LOOKUP, DriveFailureKind.HTTP, 503), result.driveFailure)
            assertTrue(cleaned)
            assertEquals(2, fake.calls.count { it.file })
            assertEquals(0, fake.active)
        }
        val fake = Fake()
        var confirmed = 0
        fake.hook = { call -> if (call.parent == "workouts-folder" && call.file) DriveResponse(403, "synthetic") else null }
        val result = coordinator(PrivateDriveUploader(fake)).sync(LocalDate.of(2026, 10, 30))
        assertEquals(1, result.uploadedFiles)
        assertFalse(fake.calls.any { it.file && it.parent == "heart-rate-folder" })
        fake.hook = { null }
        PrivateDriveUploader(fake).upsertBatch(files) { confirmed++ }
        assertEquals(3, confirmed)
    }

    @Test
    fun `In flight unconfirmed writes are excluded and safe Drive failure details survive`() = runBlocking {
        for (failingIndex in listOf(0, 1)) withTimeout(5_000) {
            val fake = Fake()
            val writing = CompletableDeferred<Unit>()
            var cleaned = false
            fake.hook = { call ->
                when {
                    call.file && call.parent == files[failingIndex].relativePath.substringBefore('/') + "-folder" -> {
                        writing.await(); DriveResponse(429, "synthetic sensitive body")
                    }
                    call.method == "PATCH" -> {
                        writing.complete(Unit)
                        try { awaitCancellation() } finally { cleaned = true }
                    }
                    else -> null
                }
            }
            val result = coordinator(PrivateDriveUploader(fake)).sync(LocalDate.of(2026, 10, 30))
            assertEquals(SyncResult(SyncStatus.FAILED, 0, "UPLOAD_FAILED", DriveFailureDetails(DriveOperation.FILE_LOOKUP, DriveFailureKind.HTTP, 429)), result)
            assertTrue(cleaned)
            assertEquals(0, fake.active)
            assertEquals(1, fake.cancelled)
            val calls = fake.calls.size
            yield()
            assertEquals(calls, fake.calls.size)
        }
    }

    @Test
    fun `Cancellation joins children cancels transport and isolates the next invocation`() = runBlocking {
        withTimeout(5_000) {
            val fake = Fake()
            val two = CompletableDeferred<Unit>()
            var confirmed = 0
            fake.hook = { call ->
                if (call.file) {
                    if (fake.active == 2) two.complete(Unit)
                    awaitCancellation()
                }
                null
            }
            val profiling = SyncProfiling()
            val uploader = PrivateDriveUploader(fake, profiling)
            val coordinator = coordinator(uploader, profiling)
            val task = async { coordinator.sync(LocalDate.of(2026, 10, 30)) }
            two.await(); task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertEquals(2, fake.cancelled)
            assertEquals(0, fake.active)
            assertFalse(profiling.snapshot().stages.getValue(ProfileStage.DRIVE_TOTAL).active)
            assertEquals(1L, profiling.snapshot().stages.getValue(ProfileStage.DRIVE_TOTAL).cancelled)
            fake.hook = { null }
            assertEquals(SyncResult(SyncStatus.SUCCESS, 3), coordinator.sync(LocalDate.of(2026, 10, 30)))
            uploader.upsertBatch(files) { confirmed++ }
            assertEquals(3, confirmed)
            yield(); assertEquals(3, confirmed)
        }
        withTimeout(5_000) {
            val fake = Fake()
            val uploader = PrivateDriveUploader(fake)
            val entered = CompletableDeferred<Unit>()
            var completed = 0
            val task = async {
                uploader.upsertBatch(files) {
                    if (completed == 1) { entered.complete(Unit); awaitCancellation() }
                    completed++
                }
            }
            entered.await(); task.cancelAndJoin()
            assertEquals(1, completed)
            yield(); assertEquals(1, completed)
            var next = 0
            uploader.upsertBatch(files) { next++ }
            assertEquals(3, next)
        }
        withTimeout(5_000) {
            val fake = Fake()
            val both = CompletableDeferred<Unit>()
            fake.hook = { call ->
                if (call.file) {
                    if (fake.active == 2) both.complete(Unit)
                    both.await()
                    if (call.parent == "daily-folder") throw CancellationException("합성 transport 취소")
                    awaitCancellation()
                }
                null
            }
            val uploader = PrivateDriveUploader(fake)
            val failure = rejected { uploader.upsertBatch(files) { fail("미확인 파일 확인 금지") } }
            assertTrue(failure is CancellationException)
            assertEquals(0, fake.active)
            assertEquals(2, fake.calls.count { it.file })
            fake.hook = { null }
            var next = 0
            uploader.upsertBatch(files) { next++ }
            assertEquals(3, next)
        }
    }

    @Test
    fun `Batch preserves private target validation and rejects incomplete duplicate or malformed pages`() = runBlocking {
        val mutations: List<(JsonObject) -> JsonObject> = listOf(
            { JsonObject(it + ("shared" to JsonPrimitive(true))) },
            { JsonObject(it + ("ownedByMe" to JsonPrimitive(false))) },
            { JsonObject(it + ("trashed" to JsonPrimitive(true))) },
            { JsonObject(it + ("driveId" to JsonPrimitive("synthetic-drive"))) },
            { JsonObject(it + ("parents" to buildJsonArray { add(if (it["name"] == JsonPrimitive("HealthApp")) "root" else "wrong-parent") })) },
            { JsonObject(it + ("id" to JsonPrimitive(" "))) },
            { JsonObject(it + ("appProperties" to buildJsonObject {})) },
        )
        for (stage in listOf("root", "dataset", "lookup", "confirmation")) {
            for (mutation in mutations) {
                val fake = Fake()
                fake.hook = { call ->
                    val matches = when (stage) {
                        "root" -> call.name == "HealthApp"
                        "dataset" -> call.name == "daily"
                        "lookup" -> call.file && call.method == "GET"
                        else -> call.method == "PATCH"
                    }
                    if (matches) {
                        val original = when (stage) {
                            "root" -> fake.entries[0]
                            "dataset" -> fake.entries[1]
                            else -> fake.entries[2]
                        }
                        val changed = mutation(original)
                        if (call.method == "GET") listed(listOf(changed)) else response(changed)
                    } else null
                }
                var count = 0
                assertTrue(rejected { PrivateDriveUploader(fake).upsertBatch(files.take(1)) { count++ } } is DriveUploadException)
                assertEquals(0, count)
                assertEquals(if (stage == "confirmation") 1 else 0, fake.calls.count { it.method == "PATCH" })
            }
        }
        for (body in listOf(
            "{\"files\":[],\"incompleteSearch\":true}",
            "{\"files\":{}}", "{\"files\":[null]}",
            "{\"files\":[],\"nextPageToken\":1}",
            "{\"files\":[],\"nextPageToken\":\"loop\"}",
        )) {
            val fake = Fake()
            fake.hook = { DriveResponse(200, body) }
            assertTrue(rejected { PrivateDriveUploader(fake).upsertBatch(files) {} } is DriveUploadException)
            assertTrue(fake.calls.all { it.method == "GET" && it.name == "HealthApp" })
            assertTrue(fake.calls.size <= 2)
        }
        val fake = Fake()
        fake.hook = { call ->
            if (call.name == "HealthApp") {
                if (fake.calls.size == 1) response(buildJsonObject {
                    put("files", JsonArray(listOf(fake.entries[0]))); put("nextPageToken", "next")
                }) else listed(listOf(fake.entries[0]))
            } else null
        }
        assertTrue(rejected { PrivateDriveUploader(fake).upsertBatch(files) {} } is DriveUploadException)
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `Overlapping completed failed and cancelled requests close all profiling counters`() = runBlocking {
        for (outcome in listOf("completed", "failed", "cancelled")) withTimeout(5_000) {
            val fake = Fake()
            val two = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var tick = 0L
            val profiling = SyncProfiling(ProfilingClock { ++tick })
            fake.hook = { call ->
                if (call.file && call.method == "GET") {
                    if (fake.active == 2) two.complete(Unit)
                    release.await()
                    if (outcome == "failed" && call.parent == "daily-folder") DriveResponse(503, "synthetic")
                    else null
                } else null
            }
            val task = async { coordinator(PrivateDriveUploader(fake, profiling), profiling).sync(LocalDate.of(2026, 10, 30)) }
            two.await()
            assertTrue(profiling.snapshot().requests.getValue(DriveOperation.FILE_LOOKUP).active)
            if (outcome == "cancelled") task.cancelAndJoin() else { release.complete(Unit); task.await() }
            val summary = profiling.snapshot()
            for (stats in summary.stages.values + summary.requests.values + summary.operations.values) {
                assertFalse(stats.active)
                assertEquals(stats.started, stats.completed + stats.failed + stats.cancelled)
            }
            val drive = summary.stages.getValue(ProfileStage.DRIVE_TOTAL)
            assertEquals(if (outcome == "completed") 1L else 0L, drive.completed)
            assertEquals(if (outcome == "failed") 1L else 0L, drive.failed)
            assertEquals(if (outcome == "cancelled") 1L else 0L, drive.cancelled)
            val lookups = summary.requests.getValue(DriveOperation.FILE_LOOKUP)
            if (outcome == "cancelled") assertEquals(2L, lookups.cancelled)
            if (outcome == "failed") assertEquals(1L, lookups.failed)
            if (outcome == "completed") assertEquals(3L, lookups.completed)
            assertEquals(0, fake.active)
        }
    }
}
