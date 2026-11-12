package com.ny.my.health

import android.os.Bundle
import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 일반 탐색에서 제외하며, 실제 강제 종료와 새 프로세스 검증은 전용 host만 수행한다. */
@RunWith(AndroidJUnit4::class)
class SyncStateProcessRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val root = instrumentation.targetContext.noBackupFilesDir.canonicalFile
    private val file = File(root, "sync-state-process-fixture.db")
    private val owner = File(root, "sync-state-process-fixture-owner")
    private val today = LocalDate.of(2026, 11, 5)
    private val date = today.minusDays(1)
    private val time = Instant.parse("2026-11-05T00:00:00Z")
    private val config = SyncConfiguration("synthetic-process-destination", date, false, setOf(DataType.STEPS))
    private val binding = PrivateDestinationBinding("SYNTHETIC")
    private val scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
    private val access = ReadAccess(true, scopes.keys, scopes)
    private val key = SyncPlanner.scopeKey(config, access)!!
    private val revision = RevisionKey("a".repeat(64), time)
    private val id = "00000000-0000-4000-8000-000000000001"
    private val fixtures = setOf("pending-before", "pending-after", "confirm-before", "confirm-after", "running",
        "reset-before", "reset-after", "initial-schema-ready", "initial-before-publish", "initial-published",
        "reset-owner-before", "reset-owner-writing", "reset-owner-written", "reset-stage-schema-ready",
        "reset-prepared", "reset-marker-published")
    private val phases = mapOf("initial-schema-ready" to SyncStatePhase.INITIAL_SCHEMA_READY,
        "initial-before-publish" to SyncStatePhase.INITIAL_BEFORE_PUBLISH,
        "initial-published" to SyncStatePhase.INITIAL_PUBLISHED,
        "reset-owner-before" to SyncStatePhase.RESET_OWNER_BEFORE,
        "reset-owner-writing" to SyncStatePhase.RESET_OWNER_WRITING,
        "reset-owner-written" to SyncStatePhase.RESET_OWNER_WRITTEN,
        "reset-stage-schema-ready" to SyncStatePhase.RESET_STAGE_SCHEMA_READY,
        "reset-prepared" to SyncStatePhase.RESET_PREPARED,
        "reset-marker-published" to SyncStatePhase.RESET_MARKER_PUBLISHED)

    private fun kind(): String = requireNotNull(args.getString("fixture")).also { check(it in fixtures) }
    private fun gate() { check(args.getString("syncStateProcessHost") == "true") { "전용 host가 필요합니다" }; kind() }
    private fun store(observer: (SyncStatePhase) -> Unit = {}, hook: () -> Unit = {}) =
        SqliteSyncStateStore(SyncStateDatabase(file, observer), { today }, hook)
    private fun present(entry: File) = Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun safe(entry: File, directory: Boolean = false) {
        check(!Files.isSymbolicLink(entry.toPath())) { "심볼릭 링크 fixture 거부" }
        check(entry.canonicalFile == entry.absoluteFile && entry.canonicalPath.startsWith(root.path + File.separator))
        check(if (directory) entry.isDirectory else entry.isFile) { "fixture 종류 불일치" }
    }
    private fun owned(): JSONObject {
        safe(owner)
        return JSONObject(owner.readText()).also {
            check(it.getString("protocol") == "sync-state-process-v1" && it.getString("fixture") == kind())
        }
    }
    private fun update(change: (JSONObject) -> Unit) {
        val value = owned(); change(value); owner.writeText(value.toString())
    }
    private fun marker(name: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\n$name pid=${Process.myPid()}\n")
    })
    private fun artifactRoots() = root.listFiles()!!.filter { it.name.startsWith(file.name) }
    private fun digest(entry: File): String = MessageDigest.getInstance("SHA-256").digest(entry.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun snapshot(): JSONObject = JSONObject().also { result ->
        artifactRoots().sortedBy { it.name }.forEach { entry ->
            if (entry.isDirectory) {
                safe(entry, true); result.put(entry.name, "directory")
                entry.listFiles()!!.sortedBy { it.name }.forEach { child -> safe(child); result.put("${entry.name}/${child.name}", digest(child)) }
            } else { safe(entry); result.put(entry.name, digest(entry)) }
        }
    }
    private fun hold(): Nothing {
        // 단계 observer 호출 스택에서 latch를 기다리므로 시간 지연으로 commit 경계를 추정하지 않는다.
        update { it.put("snapshot", snapshot()) }
        marker("SYNC_STATE_READY")
        check(CountDownLatch(1).await(60, TimeUnit.SECONDS)) { "host 강제 종료 시간 초과" }
        error("host는 hold를 반환시키지 않아야 합니다")
    }
    private fun mutation(start: AttemptState? = null, day: DayState? = null,
        confirmation: FileConfirmation? = null, finish: Instant? = null, attemptId: String = id) =
        SyncMutation(attemptId, start, day, confirmation, null, finish)
    private suspend fun configure(expected: DestinationGeneration? = null): DestinationGeneration =
        store().configure(expected, config, binding).also { assertEquals(CommitStatus.COMMITTED, it.status) }.generation!!
    private suspend fun commit(generation: DestinationGeneration, mutation: SyncMutation) {
        assertEquals(CommitStatus.COMMITTED, store().transact(generation, mutation).status)
    }
    private fun attempt(attemptId: String = id) = AttemptState(attemptId, key, scopes, SyncTrigger.MANUAL,
        time, time, null, listOf(date), "b".repeat(64), 0, setOf(SyncTrigger.MANUAL))
    private fun day(phase: FilePhase) = DayState(date, key, scopes, revision,
        if (phase == FilePhase.RUNNING) DayPhase.RUNNING else DayPhase.PENDING, scopes, time,
        setOf(Dataset.DAILY, Dataset.WORKOUTS).associateWith {
            DatasetState(it, revision, phase, true, null, if (phase == FilePhase.RUNNING) time else null, null, null)
        }, null)
    private fun confirmation(generation: DestinationGeneration, dataset: Dataset = Dataset.DAILY) =
        FileConfirmation(FileKey(generation, date, key, dataset, revision.digest), "synthetic-target-${dataset.name}", time.plusSeconds(1))
    private suspend fun seed(generation: DestinationGeneration, complete: Boolean = false, attemptId: String = id) {
        commit(generation, mutation(start = attempt(attemptId), attemptId = attemptId))
        commit(generation, mutation(day = day(FilePhase.RUNNING), attemptId = attemptId))
        if (complete) {
            for (dataset in setOf(Dataset.DAILY, Dataset.WORKOUTS))
                commit(generation, mutation(confirmation = confirmation(generation, dataset), attemptId = attemptId))
            commit(generation, mutation(finish = time.plusSeconds(2), attemptId = attemptId))
        }
    }

    @Test fun seedAndHoldUntilHostKillsProcess(): Unit = runBlocking {
        gate()
        check(!present(owner) && artifactRoots().isEmpty()) { "기존 fixture를 보존합니다" }
        owner.writeText(JSONObject().put("protocol", "sync-state-process-v1").put("fixture", kind()).toString())
        if (kind().startsWith("initial-")) {
            store(observer = { if (it == phases.getValue(kind())) hold() }).configure(null, config, binding)
            error("최초 구성 observer에 도달하지 않았습니다")
        }
        var generation = configure()
        if (kind() in setOf("reset-before", "reset-after")) {
            seed(generation, true)
            update { it.put("archived", generation.value) }
            generation = configure(generation)
            seed(generation, true, "00000000-0000-4000-8000-000000000002")
            SyncStateDatabase(file).openForWrite().use { it.execSQL("UPDATE config SET automatic=1") }
            update { it.put("generation", generation.value) }
            if (kind() == "reset-before") store(hook = { hold() }).reset(generation)
            else { assertEquals(CommitStatus.COMMITTED, store().reset(generation).status); hold() }
            error("reset hold에 도달하지 않았습니다")
        }
        if (kind() in phases) {
            SyncStateDatabase(file).openForWrite().use { it.version = 99 }
            update { it.put("baseline", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)) }
            store(observer = { if (it == phases.getValue(kind())) hold() }).reset(null)
            error("reset observer에 도달하지 않았습니다")
        }
        update { it.put("generation", generation.value) }
        commit(generation, mutation(start = attempt()))
        if (kind().startsWith("pending-")) {
            if (kind() == "pending-before") store(hook = { hold() }).transact(generation, mutation(day = day(FilePhase.PENDING)))
            else { commit(generation, mutation(day = day(FilePhase.PENDING))); hold() }
        } else {
            commit(generation, mutation(day = day(FilePhase.RUNNING)))
            when (kind()) {
                "confirm-before" -> store(hook = { hold() }).transact(generation, mutation(confirmation = confirmation(generation)))
                "confirm-after" -> { commit(generation, mutation(confirmation = confirmation(generation))); hold() }
                "running" -> hold()
                else -> error("알 수 없는 fixture")
            }
        }
        error("commit hold에 도달하지 않았습니다")
    }

    private suspend fun ready() = store().load().also { assertEquals(StateStatus.READY, it.status) }.state!!
    private fun count(table: String): Int = SyncStateDatabase(file).openForRead()!!.use { db ->
        db.rawQuery("SELECT count(*) FROM $table", null).use { assertTrue(it.moveToFirst()); it.getInt(0) }
    }
    private suspend fun assertEmpty(tombstone: Boolean) {
        val state = ready()
        assertEquals(if (tombstone) null else config, state.configuration)
        assertEquals(if (tombstone) null else binding, store().readBinding(state.generation))
        assertNull(state.attempt); assertTrue(state.days.isEmpty()); assertTrue(state.oldestPendingDays.isEmpty())
        assertNull(state.coverage.lastUploadAt); assertNull(state.coverage.checkedThrough); assertNull(state.coverage.uploadedThrough)
        assertTrue(state.coverage.recentCheckedDays.isEmpty()); assertTrue(state.coverage.recentUploadedDays.isEmpty())
        for (table in listOf("day", "dataset", "attempt")) assertEquals(0, count(table))
        assertEquals(1, count("generation"))
        SyncStateDatabase(file).openForRead()!!.use { db ->
            db.rawQuery("SELECT automatic FROM config", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun reopeningAroundAtomicCommitsNeverConfirmsAnUnacknowledgedFileOrDate(): Unit = runBlocking {
        gate(); val ownership = owned()
        assertNotEquals(requireNotNull(args.getString("deadPid")).toInt(), Process.myPid())
        // 먼저 변경 없는 일반 읽기를 검증하고, 그 뒤에만 명시 retry 또는 process-first 복구를 허용한다.
        val first = store().load()
        if (kind().startsWith("initial-")) {
            assertEquals(if (kind() == "initial-published") StateStatus.READY else StateStatus.ABSENT, first.status)
            if (first.status == StateStatus.ABSENT) assertNull(first.state)
            assertEquals(ownership.getJSONObject("snapshot").toString(), snapshot().toString())
            if (kind() == "initial-published") {
                assertEmpty(false)
                assertEquals(CommitStatus.STALE, store().configure(null, config, binding).status)
                assertEquals(CommitStatus.INVALID, store().reset(null).status)
            } else {
                configure(); assertEmpty(false)
                val original = ownership.getJSONObject("snapshot")
                original.keys().forEach { name -> assertEquals(original.getString(name), digest(File(root, name))) }
            }
        } else if (kind() in phases) {
            val published = kind() == "reset-marker-published"
            assertEquals(if (published) StateStatus.UNAVAILABLE else StateStatus.UNSUPPORTED, first.status)
            assertNull(first.state)
            assertArrayEquals(Base64.decode(ownership.getString("baseline"), Base64.NO_WRAP), file.readBytes())
            assertEquals(published, present(SyncStateDatabase(file).resetDirectory))
            assertEquals(ownership.getJSONObject("snapshot").toString(), snapshot().toString())
            val oldOwner = if (published) File(SyncStateDatabase(file).resetDirectory, "owner").readText() else null
            assertEquals(CommitStatus.COMMITTED, store().reset(null).status)
            assertEmpty(true)
            assertFalse(present(SyncStateDatabase(file).resetDirectory))
            val completed = artifactRoots().filter { it.name.startsWith("${file.name}.reset.completed-") }.single()
            val completedOwner = File(completed, "owner").readText()
            if (published) assertEquals(oldOwner, completedOwner)
            assertEquals(completedOwner, File(completed, "installed").readText())
            assertEquals(setOf("owner", "installed"), completed.listFiles()!!.map { it.name }.toSet())
            assertArrayEquals(Base64.decode(ownership.getString("baseline"), Base64.NO_WRAP),
                File(root, "${file.name}.old-$completedOwner").readBytes())
            if (!published) {
                val original = ownership.getJSONObject("snapshot")
                original.keys().forEach { name ->
                    if (name.startsWith("${file.name}.reset.prepare-")) {
                        val entry = File(root, name)
                        assertEquals(original.getString(name), if (entry.isDirectory) "directory" else digest(entry))
                    }
                }
            }
        } else if (kind() == "reset-after") {
            assertEquals(StateStatus.READY, first.status); assertEmpty(true)
            assertNotEquals(ownership.getString("generation"), first.state!!.generation.value)
            assertNotEquals(ownership.getString("archived"), first.state!!.generation.value)
        } else {
            assertEquals(StateStatus.READY, first.status)
            val generation = DestinationGeneration(ownership.getString("generation"))
            assertEquals(generation, first.state!!.generation)
            if (kind() == "reset-before") {
                assertTrue(first.state!!.configuration!!.automatic)
                assertEquals(2, count("generation")); assertEquals(2, count("day"))
                assertEquals(4, count("dataset")); assertEquals(2, count("attempt"))
                assertEquals(time.plusSeconds(1), first.state!!.coverage.lastUploadAt)
                assertEquals(binding, store().readBinding(generation))
                assertEquals(DayPhase.CONFIRMED, store().loadDay(generation, date, key)!!.phase)
                SyncStateDatabase(file).openForRead()!!.use { db ->
                    db.rawQuery("SELECT count(*) FROM day WHERE generation=? AND phase='CONFIRMED'",
                        arrayOf(ownership.getString("archived"))).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                }
            } else {
                assertEquals(attempt(), first.state!!.attempt)
                val actual = store().loadDay(generation, date, key)
                when (kind()) {
                    "pending-before" -> { assertNull(actual); assertEquals(0, count("day")); assertEquals(0, count("dataset")) }
                    "pending-after" -> assertEquals(day(FilePhase.PENDING), actual)
                    "confirm-before", "running" -> assertEquals(day(FilePhase.RUNNING), actual)
                    "confirm-after" -> {
                        assertNotNull(actual)
                        val confirmed = confirmation(generation)
                        assertEquals(day(FilePhase.RUNNING).copy(datasets = day(FilePhase.RUNNING).datasets +
                            (Dataset.DAILY to day(FilePhase.RUNNING).datasets.getValue(Dataset.DAILY).copy(
                                phase = FilePhase.CONFIRMED, targetHandle = confirmed.targetHandle, confirmedAt = confirmed.confirmedAt))), actual)
                    }
                }
                assertNull(first.state!!.coverage.uploadedThrough)
                assertTrue(first.state!!.coverage.recentUploadedDays.isEmpty())
                assertEquals(if (kind() == "confirm-after") time.plusSeconds(1) else null, first.state!!.coverage.lastUploadAt)
                if (kind() == "running") recoverRunning(generation, actual!!)
            }
        }
        marker("SYNC_STATE_REOPENED")
    }

    private suspend fun recoverRunning(generation: DestinationGeneration, before: DayState) {
        var reads = 0; var remote = 0
        val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger) = access
            override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
                reads++; error("복구는 건강 기록을 읽으면 안 됩니다")
            }
        }
        val destination = object : StorageDestination {
            override suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles { remote++; error("복구 원격 조회 금지") }
            override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) {
                remote++; error("복구 원격 쓰기 금지")
            }
        }
        val clock = Clock.fixed(time.plusSeconds(3), ZoneOffset.UTC)
        val store = store()
        SyncExecutionSession(store, reader, DestinationSync(store, reader, destination, clock), clock,
            { UUID.randomUUID().toString() }).detectPending()
        val after = store.loadDay(generation, date, key)!!
        assertEquals(before.copy(phase = DayPhase.INDETERMINATE,
            datasets = before.datasets.mapValues { (_, value) -> value.copy(phase = FilePhase.INDETERMINATE) }), after)
        assertEquals(attempt().copy(finishedAt = time.plusSeconds(3)), ready().attempt)
        assertNull(ready().coverage.uploadedThrough); assertNull(ready().coverage.lastUploadAt)
        assertEquals(0, reads); assertEquals(0, remote)
    }

    @Test fun cleanupOwnedSyntheticFixture() {
        gate(); owned()
        val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val base = Regex.escape(file.name)
        val ordinary = Regex("$base(?:-journal|-wal|-shm)?|$base\\.init-$uuid(?:-journal|-wal|-shm)?|$base\\.old-$uuid(?:-journal|-wal|-shm)?")
        val directories = Regex("$base\\.reset(?:\\.prepare-$uuid|\\.completed-$uuid)?")
        val deletion = mutableListOf<File>()
        // 전체 목록을 먼저 입증한다. 알 수 없는 entry가 있으면 어떤 fixture도 삭제하지 않는다.
        for (entry in artifactRoots()) {
            if (ordinary.matches(entry.name)) { safe(entry); deletion += entry; continue }
            check(directories.matches(entry.name)) { "알 수 없는 fixture entry" }; safe(entry, true)
            val children = entry.listFiles()!!.toList()
            val completed = entry.name.contains(".reset.completed-")
            val allowed = if (completed) setOf("owner", "installed") else setOf("owner", "stage", "stage-journal", "stage-wal", "stage-shm", "old", "old-journal", "old-wal", "old-shm", "installed")
            children.forEach { check(it.name in allowed); safe(it) }
            val evidence = File(entry, "owner")
            if (evidence.exists() && evidence.length() > 0) {
                val value = evidence.readText(); check(Regex(uuid).matches(value))
                if (entry.name != "${file.name}.reset") check(entry.name.endsWith(value))
                if (completed) check(File(entry, "installed").readText() == value)
            } else check(!completed && kind() in setOf("reset-owner-before", "reset-owner-writing"))
            deletion += children; deletion += entry
        }
        deletion.forEach { check(it.delete()) { "소유 fixture 삭제 실패" } }
        check(artifactRoots().isEmpty()); check(owner.delete())
    }
}
