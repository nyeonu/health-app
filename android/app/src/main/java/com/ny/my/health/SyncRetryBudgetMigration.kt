package com.ny.my.health

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ny.my.health.core.*
import java.time.LocalDate
import org.json.JSONObject

/** 저장된 ledger는 모든 세대에 대해 제한된 cursor로 검증한다. */
internal object SyncRetryBudgetLedger {
    private fun Cursor.text(name: String): String {
        val index = getColumnIndexOrThrow(name)
        require(getType(index) == Cursor.FIELD_TYPE_STRING)
        return getString(index)
    }
    private fun Cursor.optional(name: String): String? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else text(name)
    }
    private fun Cursor.number(name: String): Long {
        val index = getColumnIndexOrThrow(name)
        require(getType(index) == Cursor.FIELD_TYPE_INTEGER)
        return getLong(index)
    }
    private fun Cursor.optionalNumber(name: String): Long? =
        if (isNull(getColumnIndexOrThrow(name))) null else number(name)

    private fun revisions(json: String, dates: List<LocalDate>): Map<LocalDate, String?> {
        val value = JSONObject(json)
        require(value.keys().asSequence().toSet() == dates.map { it.toString() }.toSet())
        val result = dates.associateWith { date ->
            val key = date.toString()
            if (value.isNull(key)) null else (value.get(key) as? String)?.let(SyncStateCodec::decodeDigest)
                ?: throw IllegalArgumentException("잘못된 재시도 상태")
        }
        val canonical = result.entries.joinToString(",", "{", "}") { (date, revision) ->
            JSONObject.quote(date.toString()) + ":" + (revision?.let(JSONObject::quote) ?: "null")
        }
        require(canonical == json)
        return result
    }

    fun validate(db: SQLiteDatabase, today: LocalDate, checkCancelled: () -> Unit) {
        require(db.version == 2)
        db.rawQuery("SELECT count(*) FROM retry_budget", null).use { require(it.moveToFirst() && it.getLong(0) <= 100000L) }
        db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) }
        // 외래 키 검사가 꺼진 연결에서도 빠진 양방향 링크를 거부한다.
        db.rawQuery("SELECT 1 FROM attempt a LEFT JOIN retry_budget b ON b.lineage_id=a.automatic_lineage_id WHERE a.automatic_lineage_id IS NOT NULL AND b.lineage_id IS NULL LIMIT 1", null).use { require(!it.moveToFirst()) }
        val sql = """SELECT b.*, g.destination_handle AS g_destination, g.enrolled_day AS g_enrolled,
            g.categories_json AS g_categories, a.id AS a_id, a.generation AS a_generation,
            a.scope_key AS a_scope, a.requested_scopes_json AS a_scopes, a.trigger AS a_trigger,
            a.scheduled_at AS a_scheduled, a.started_at AS a_started, a.finished_at AS a_finished,
            a.dates_json AS a_dates, a.retry_budget_key AS a_key, a.retries_used AS a_retries,
            a.pending_triggers_json AS a_pending
            FROM retry_budget b JOIN generation g ON g.id=b.generation
            LEFT JOIN attempt a ON a.automatic_lineage_id=b.lineage_id ORDER BY b.lineage_id"""
        db.rawQuery(sql, null).use { row ->
            while (row.moveToNext()) {
                checkCancelled()
                val scopesJson = row.text("requested_scopes_json")
                val datesJson = row.text("dates_json")
                val revisionsJson = row.text("observed_revisions_json")
                require(listOf(scopesJson, datesJson, revisionsJson).sumOf { it.toByteArray(Charsets.UTF_8).size } <= 16384)
                val dates = SyncStateCodec.decodeDates(datesJson, 1)
                val count = row.number("retries_used")
                require(count in 0L..3L)
                val budget = RetryBudget.validated(
                    SyncStateCodec.decodeUuid(row.text("lineage_id")),
                    DestinationGeneration(SyncStateCodec.decodeUuid(row.text("generation"))),
                    ReadScopeKey(SyncStateCodec.decodeDigest(row.text("scope_key"))),
                    SyncStateCodec.decodeScopes(scopesJson, 1), dates, revisions(revisionsJson, dates), count.toInt(),
                    row.optional("retry_not_before")?.let(SyncStateCodec::decodeInstant), row.optionalNumber("retry_delay_millis"),
                    row.number("claim_version"), row.optional("owner_attempt_id")?.let(SyncStateCodec::decodeUuid),
                    enumValueOf(row.text("status")), SyncStateCodec.decodeInstant(row.text("created_at")),
                    SyncStateCodec.decodeInstant(row.text("updated_at")))
                val configuration = SyncConfiguration(SyncStateCodec.decodeHandle(row.text("g_destination")),
                    SyncStateCodec.decodeDate(row.text("g_enrolled")), false,
                    SyncStateCodec.decodeCategories(row.text("g_categories"), 1))
                require(configuration.isValid() && configuration.categories.containsAll(budget.requestedScopes.keys))
                require(budget.dates.all { it >= configuration.enrolledDay && it < today })
                val id = row.optional("a_id")
                if (id == null) {
                    require(budget.ownerAttemptId == null)
                } else {
                    val attemptCount = row.number("a_retries")
                    require(attemptCount in 0L..3L)
                    val attempt = AttemptState(SyncStateCodec.decodeUuid(id),
                        ReadScopeKey(SyncStateCodec.decodeDigest(row.text("a_scope"))),
                        SyncStateCodec.decodeScopes(row.text("a_scopes"), 1), enumValueOf(row.text("a_trigger")),
                        SyncStateCodec.decodeInstant(row.text("a_scheduled")), row.optional("a_started")?.let(SyncStateCodec::decodeInstant),
                        row.optional("a_finished")?.let(SyncStateCodec::decodeInstant), SyncStateCodec.decodeDates(row.text("a_dates"), 1),
                        SyncStateCodec.decodeDigest(row.text("a_key")), attemptCount.toInt(), SyncStateCodec.decodeTriggers(row.text("a_pending"), 1))
                    require(attempt.isValid(configuration.enrolledDay, today))
                    require(row.text("a_generation") == budget.generation.value && attempt.scopeKey == budget.scopeKey &&
                        attempt.requestedScopes == budget.requestedScopes && attempt.dates == budget.dates &&
                        attempt.retryBudgetKey == budget.retryBudgetKey && attempt.retriesUsed == budget.retriesUsed &&
                        attempt.trigger in setOf(SyncTrigger.SCHEDULED, SyncTrigger.CATCH_UP))
                    val started = requireNotNull(attempt.startedAt)
                    require(started >= budget.createdAt && budget.updatedAt >= started)
                    val finished = attempt.finishedAt
                    if (finished == null) require(budget.status == RetryBudgetStatus.ACTIVE && budget.ownerAttemptId == attempt.id)
                    else {
                        require(budget.ownerAttemptId == null && finished <= budget.updatedAt)
                        if (budget.status == RetryBudgetStatus.ACTIVE) require(budget.updatedAt == finished)
                    }
                }
            }
        }
        checkCancelled()
    }
}

/** 닫힌 v1의 증명된 복제본만 변환하고 원본은 완료 기록과 함께 보존한다. */
internal class SyncRetryBudgetMigration(private val database: SyncStateDatabase) {
    companion object {
        fun hasLegacyPreparation(source: java.io.File): Boolean =
            checkNotNull(checkNotNull(source.parentFile).list()).any { it.startsWith("${source.name}.migration.prepare-") }
    }
    private val source = database.databaseFile
    private val marker = database.migrationDirectory
    private val parent = checkNotNull(source.parentFile)
    private data class Proof(val device: Long, val inode: Long, val size: Long, val hash: String) {
        fun content() = "$size:$hash"
        fun encoded() = "$device:$inode:$size:$hash"
    }
    private fun exists(file: java.io.File) = java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    private fun syncDirectory(file: java.io.File) {
        val fd = android.system.Os.open(file.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
        try { require(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)); android.system.Os.fsync(fd) }
        finally { android.system.Os.close(fd) }
    }
    private fun proof(file: java.io.File, cancel: () -> Unit, output: java.io.FileDescriptor? = null): Proof {
        val flags = android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC
        val fd = android.system.Os.open(file.path, flags, 0)
        try {
            val before = android.system.Os.fstat(fd)
            SyncStateHotJournalRecovery.regular(before)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(65536)
            var size = 0L
            while (true) {
                cancel()
                val n = android.system.Os.read(fd, bytes, 0, bytes.size)
                if (n == 0) break
                require(n > 0)
                digest.update(bytes, 0, n)
                if (output != null) {
                    var offset = 0
                    while (offset < n) { val written = android.system.Os.write(output, bytes, offset, n - offset); require(written > 0); offset += written }
                }
                size += n
            }
            fun same(stat: android.system.StructStat) = stat.st_dev == before.st_dev && stat.st_ino == before.st_ino &&
                stat.st_size == before.st_size && stat.st_nlink == before.st_nlink && stat.st_uid == before.st_uid
            require(size == before.st_size && same(android.system.Os.fstat(fd)) && same(android.system.Os.lstat(file.path)))
            return Proof(before.st_dev, before.st_ino, size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        } finally { android.system.Os.close(fd) }
    }
    private fun newFile(file: java.io.File, block: (java.io.FileDescriptor) -> Unit) {
        val flags = android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL or
            android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC
        val fd = android.system.Os.open(file.path, flags, 384)
        try { SyncStateHotJournalRecovery.regular(android.system.Os.fstat(fd)); block(fd); android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
    private fun write(file: java.io.File, text: String) = newFile(file) { fd ->
        val bytes = text.toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset < bytes.size) { val n = android.system.Os.write(fd, bytes, offset, bytes.size - offset); require(n > 0); offset += n }
    }
    private fun sidecars() {
        if (exists(source)) SyncStateHotJournalRecovery.guardSidecarPaths(source)
        require(!exists(java.io.File(source.path + "-wal")) && !exists(java.io.File(source.path + "-shm")))
        val journal = java.io.File(source.path + "-journal")
        if (exists(journal)) require(proof(journal, {}).size == 0L)
    }
    private fun directory(dir: java.io.File) {
        val stat = android.system.Os.lstat(dir.path)
        require(android.system.OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == android.system.Os.getuid() && stat.st_mode and 0x3f == 0)
    }
    private fun readRecord(file: java.io.File): String {
        val fd = android.system.Os.open(file.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
        try {
            val before = android.system.Os.fstat(fd)
            SyncStateHotJournalRecovery.regular(before)
            require(before.st_size in 1..512)
            val bytes = ByteArray(before.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = android.system.Os.read(fd, bytes, offset, bytes.size - offset)
                require(count > 0); offset += count
            }
            val after = android.system.Os.fstat(fd)
            val path = android.system.Os.lstat(file.path)
            require(before.st_dev == after.st_dev && before.st_ino == after.st_ino && before.st_size == after.st_size &&
                before.st_dev == path.st_dev && before.st_ino == path.st_ino && before.st_size == path.st_size)
            return String(bytes, Charsets.US_ASCII)
        } finally { android.system.Os.close(fd) }
    }
    private fun parseProof(value: String): Proof {
        val fields = value.split(':')
        require(fields.size == 4 && fields[3].matches(Regex("[0-9a-f]{64}")))
        val result = Proof(fields[0].toLong(), fields[1].toLong(), fields[2].toLong(), fields[3])
        require(result.size >= 0 && result.encoded() == value)
        return result
    }
    private fun checkLegacyPreparations() {
        require(!hasLegacyPreparation(source))
    }
    private fun owner(): Pair<String, Proof> {
        directory(marker)
        val names = checkNotNull(marker.list()).toSet()
        require("owner" in names && names.all { it in setOf("owner", "stage", "copied", "transformed", "ready", "old") })
        val fields = readRecord(java.io.File(marker, "owner")).split('\n')
        require(fields.size == 4 && fields[0] == "sync-retry-migration/2" && fields[3].isEmpty())
        require(java.util.UUID.fromString(fields[1]).toString() == fields[1])
        return fields[1] to parseProof(fields[2])
    }
    private fun storedProof(name: String): Proof = parseProof(readRecord(java.io.File(marker, name)).also { require(!it.contains('\n')) })
    fun resume(validateV1: (SQLiteDatabase) -> Unit, validateV2: (SQLiteDatabase) -> Unit, cancel: () -> Unit) {
        check(Thread.holdsLock(database.pathLock))
        checkLegacyPreparations()
        if (!exists(marker)) return
        require(!exists(database.resetDirectory))
        val (id, sourceProof) = owner()
        val stage = java.io.File(marker, "stage")
        val old = java.io.File(marker, "old")
        val copied = java.io.File(marker, "copied")
        val transformed = java.io.File(marker, "transformed")
        val ready = java.io.File(marker, "ready")
        val completed = java.io.File(parent, "${source.name}.migration.completed-$id")
        require(!exists(completed))
        sidecars()
        if (!exists(stage) && !exists(copied) && !exists(transformed) && !exists(ready) && !exists(old)) {
            require(exists(source) && proof(source, cancel) == sourceProof)
            newFile(stage) { out -> require(proof(source, cancel, out) == sourceProof) }
            val copiedProof = proof(stage, cancel)
            require(proof(source, cancel) == sourceProof && copiedProof.content() == sourceProof.content())
            write(copied, copiedProof.encoded())
            syncDirectory(marker)
            database.observe(SyncStatePhase.MIGRATION_COPY_COMPLETE)
        }
        if (!exists(ready)) {
            // 복사 완료 증명이 없는 partial file은 알 수 없는 바이트로 보존한다.
            require(!exists(old) && exists(source) && exists(stage) && exists(copied))
            require(proof(source, cancel) == sourceProof)
            val staged = SyncStateDatabase(stage)
            if (!exists(transformed)) {
                require(proof(stage, cancel) == storedProof("copied"))
                staged.openForRead().use { validateV1(checkNotNull(it)) }
                staged.openForWrite().use { db -> staged.transaction(db) {
                    db.execSQL(SyncStateDatabase.retryBudgetTable)
                    db.execSQL("ALTER TABLE attempt ADD COLUMN ${SyncStateDatabase.attemptLineageColumn}")
                    db.execSQL(SyncStateDatabase.retryBudgetIndex)
                    db.version = 2
                } }
                val converted = proof(stage, cancel)
                val copiedProof = storedProof("copied")
                require(converted.device == copiedProof.device && converted.inode == copiedProof.inode)
                val fd = android.system.Os.open(stage.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
                try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
                write(transformed, converted.encoded())
                syncDirectory(marker)
                database.observe(SyncStatePhase.MIGRATION_TRANSFORMED)
            }
            require(proof(stage, cancel) == storedProof("transformed"))
            staged.openForRead().use { validateV2(checkNotNull(it)) }
            val stageProof = proof(stage, cancel)
            val copiedProof = storedProof("copied")
            require(stageProof.device == copiedProof.device && stageProof.inode == copiedProof.inode)
            val fd = android.system.Os.open(stage.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
            require(proof(source, cancel) == sourceProof && proof(stage, cancel) == stageProof)
            write(ready, stageProof.encoded())
            syncDirectory(marker)
            database.observe(SyncStatePhase.MIGRATION_STAGE_VALIDATED)
            database.observe(SyncStatePhase.MIGRATION_MARKER_PUBLISHED)
        }
        val stageProof = storedProof("ready")
        require(exists(copied) && exists(transformed) && storedProof("transformed") == stageProof)
        val copiedProof = storedProof("copied")
        require(stageProof.device == copiedProof.device && stageProof.inode == copiedProof.inode)
        if (!exists(old)) {
            require(exists(source) && exists(stage) && proof(source, cancel) == sourceProof && proof(stage, cancel) == stageProof)
            SyncStateDatabase(stage).openForRead().use { validateV2(checkNotNull(it)) }
            require(proof(source, cancel) == sourceProof && proof(stage, cancel) == stageProof)
            cancel()
            android.system.Os.rename(source.path, old.path)
            syncDirectory(marker); syncDirectory(parent)
            database.observe(SyncStatePhase.MIGRATION_SOURCE_RETIRED)
        }
        require(proof(old, cancel) == sourceProof)
        if (exists(stage)) {
            require(!exists(source) && proof(stage, cancel) == stageProof)
            SyncStateDatabase(stage).openForRead().use { validateV2(checkNotNull(it)) }
            require(proof(old, cancel) == sourceProof && proof(stage, cancel) == stageProof)
            cancel()
            android.system.Os.rename(stage.path, source.path)
            syncDirectory(marker); syncDirectory(parent)
            database.observe(SyncStatePhase.MIGRATION_PUBLISHED)
        }
        require(exists(source) && proof(source, cancel) == stageProof && proof(old, cancel) == sourceProof)
        database.openForRead(allowMarker = true).use { validateV2(checkNotNull(it)) }
        require(proof(source, cancel) == stageProof && proof(old, cancel) == sourceProof)
        database.observe(SyncStatePhase.MIGRATION_VERIFIED)
        require(owner() == (id to sourceProof) && storedProof("ready") == stageProof &&
            checkNotNull(marker.list()).toSet() == setOf("owner", "copied", "transformed", "ready", "old"))
        android.system.Os.rename(marker.path, completed.path)
        syncDirectory(parent)
    }
    fun migrate(validateV1: (SQLiteDatabase) -> Unit, validateV2: (SQLiteDatabase) -> Unit, cancel: () -> Unit) {
        check(Thread.holdsLock(database.pathLock))
        checkLegacyPreparations()
        require(!exists(marker) && !exists(database.resetDirectory))
        sidecars()
        val before = proof(source, cancel)
        val id = java.util.UUID.randomUUID().toString()
        android.system.Os.mkdir(marker.path, 448)
        directory(marker); syncDirectory(parent)
        write(java.io.File(marker, "owner"), "sync-retry-migration/2\n$id\n${before.encoded()}\n")
        syncDirectory(marker)
        database.observe(SyncStatePhase.MIGRATION_OWNER_READY)
        resume(validateV1, validateV2, cancel)
    }
}
