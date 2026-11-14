package com.ny.my.health

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteException
import java.io.File
import java.nio.file.Files
import java.util.Locale

internal class UnsupportedSyncSchemaException : IllegalStateException("지원하지 않는 동기화 DB")

/** 내부 fault seam은 단계 enum 외의 저장소 정보를 전달하지 않는다. */
internal enum class SyncStatePhase {
    INITIAL_SCHEMA_READY, INITIAL_BEFORE_PUBLISH, INITIAL_PUBLISHED,
    RESET_OWNER_BEFORE, RESET_OWNER_WRITING, RESET_OWNER_WRITTEN,
    RESET_STAGE_SCHEMA_READY, RESET_PREPARED, RESET_MARKER_PUBLISHED,
    HOT_RAW_SAVED, HOT_CANARY_VALIDATED, HOT_BEFORE_ORIGINAL, HOT_ORIGINAL_ROLLED_BACK,
    MIGRATION_OWNER_READY, MIGRATION_COPY_COMPLETE, MIGRATION_TRANSFORMED,
    MIGRATION_STAGE_VALIDATED, MIGRATION_MARKER_PUBLISHED, MIGRATION_SOURCE_RETIRED,
    MIGRATION_PUBLISHED, MIGRATION_VERIFIED,
}

/** 일반 읽기는 생성하지 않으며, 검증된 hot journal만 한정 rollback한다. 자동 reset과 손상 원본 삭제는 하지 않는다. */
internal class SyncStateDatabase internal constructor(
    internal val databaseFile: File,
    private val phaseObserver: (SyncStatePhase) -> Unit = {},
    private val schemaReadyPhase: SyncStatePhase = SyncStatePhase.INITIAL_SCHEMA_READY,
) {
    internal fun observe(phase: SyncStatePhase) = phaseObserver(phase)
    constructor(context: Context) : this(File(context.noBackupFilesDir, "sync-state.db"))
    internal val resetDirectory = File(databaseFile.parentFile, "${databaseFile.name}.reset")
    internal val migrationDirectory = File(databaseFile.parentFile, "${databaseFile.name}.migration")
    internal val pathLock = synchronized(locks) { locks.getOrPut(databaseFile.canonicalPath) { Any() } }

    internal fun openForRead(allowMarker: Boolean = false): SQLiteDatabase? {
        checkPath(allowMarker)
        if (!databaseFile.exists()) return null
        return open(false, true)
    }
    internal fun openAdmittedRead(validateContent: (SQLiteDatabase) -> Unit,
        checkCancelled: () -> Unit): SQLiteDatabase? {
        check(Thread.holdsLock(pathLock))
        checkCancelled()
        return try { openForRead() } catch (error: SQLiteReadOnlyDatabaseException) {
            SyncStateHotJournalRecovery(this).recover(validateContent,checkCancelled)
            checkCancelled()
            openForRead()
        }
    }
    internal fun openExistingForRollback(): SQLiteDatabase {
        checkPath(false)
        SyncStateHotJournalRecovery.requireRollbackPair(databaseFile)
        return open(false,false)
    }
    internal fun openForWrite(): SQLiteDatabase {
        checkPath(false)
        if (databaseFile.exists()) open(false, true).close()
        return open(!databaseFile.exists(), false)
    }
    private fun checkPath(allowMarker: Boolean) {
        SyncStateHotJournalRecovery.guardSidecarPaths(databaseFile)
        if ((!allowMarker && (Files.exists(resetDirectory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(migrationDirectory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))) ||
            Files.isSymbolicLink(databaseFile.toPath()) || (databaseFile.exists() && !databaseFile.isFile)) {
            throw SQLiteException("동기화 DB를 사용할 수 없습니다")
        }
    }
    private fun open(create: Boolean, readOnly: Boolean): SQLiteDatabase {
        val flags = (if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE) or
            SQLiteDatabase.NO_LOCALIZED_COLLATORS or (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        val builder = SQLiteDatabase.OpenParams.Builder().setOpenFlags(flags).setErrorHandler(DatabaseErrorHandler {
            throw SQLiteDatabaseCorruptException("동기화 DB가 손상되었습니다")
        })
        if (!readOnly) builder.setJournalMode("DELETE").setSynchronousMode("FULL")
        val db = SQLiteDatabase.openDatabase(databaseFile, builder.build())
        try {
            db.setForeignKeyConstraintsEnabled(true)
            if (create) {
                transaction(db) {
                    schemaV2.values.forEach(db::execSQL)
                    db.version = 2
                }
                observe(schemaReadyPhase)
            }
            val expectedSchema = when (db.version) {
                1 -> schemaV1
                2 -> schemaV2
                else -> throw UnsupportedSyncSchemaException()
            }
            val found = mutableMapOf<String, String>()
            db.rawQuery("SELECT name, sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'", null).use { rows ->
                while (rows.moveToNext()) {
                    if (rows.isNull(1)) throw UnsupportedSyncSchemaException()
                    found[rows.getString(0)] = rows.getString(1)
                }
            }
            if (found.mapValues { normalize(it.value) } != expectedSchema.mapValues { normalize(it.value) }) throw UnsupportedSyncSchemaException()
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) }
            db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
            if (!readOnly) {
                db.rawQuery("PRAGMA journal_mode", null).use { check(it.moveToFirst() && it.getString(0).equals("delete", true)) }
                db.rawQuery("PRAGMA synchronous", null).use { check(it.moveToFirst() && it.getInt(0) == 2) }
            }
            return db
        } catch (error: Throwable) { db.close(); throw error }
    }
    internal fun <T> transaction(db: SQLiteDatabase, beforeCommit: () -> Unit = {}, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            beforeCommit()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    internal companion object {
        private val locks = mutableMapOf<String, Any>()
        private fun normalize(sql: String) = sql.filterNot(Char::isWhitespace).lowercase(Locale.ROOT)
        private fun text(name: String, max: Int, nullable: Boolean = false): String =
            "$name TEXT${if (nullable) "" else " NOT NULL"} CHECK(length(CAST($name AS BLOB)) <= $max)"
        private const val FK = "ON UPDATE RESTRICT ON DELETE RESTRICT"
        internal val schemaV1 = linkedMapOf(
            "generation" to "CREATE TABLE generation (id TEXT NOT NULL PRIMARY KEY CHECK(length(CAST(id AS BLOB))=36), ${text("destination_handle",4096,true)}, ${text("enrolled_day",10,true)}, ${text("categories_json",16384)})",
            "config" to "CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), format_version INTEGER NOT NULL CHECK(format_version=1), active_generation TEXT NOT NULL REFERENCES generation(id) $FK, automatic INTEGER NOT NULL DEFAULT 0 CHECK(automatic IN (0,1)), ${text("binding_kind",9,true)}, ${text("binding_payload",8192,true)}, reset_epoch INTEGER NOT NULL CHECK(reset_epoch>=0))",
            "day" to "CREATE TABLE day (generation TEXT NOT NULL REFERENCES generation(id) $FK, ${text("date",10)}, ${text("scope_key",64)}, ${text("requested_scopes_json",16384)}, ${text("revision",64,true)}, ${text("generated_at",40,true)}, ${text("phase",32)}, ${text("scopes_json",16384)}, ${text("checked_at",40,true)}, ${text("blocker",64,true)}, PRIMARY KEY(generation,date,scope_key))",
            "dataset" to "CREATE TABLE dataset (${text("generation",36)}, ${text("date",10)}, ${text("scope_key",64)}, ${text("dataset",16)}, ${text("revision",64)}, ${text("generated_at",40)}, ${text("phase",32)}, populated INTEGER NOT NULL CHECK(populated IN (0,1)), ${text("target_handle",4096,true)}, ${text("started_at",40,true)}, ${text("confirmed_at",40,true)}, ${text("prior_confirmation_json",16384,true)}, PRIMARY KEY(generation,date,scope_key,dataset), FOREIGN KEY(generation,date,scope_key) REFERENCES day(generation,date,scope_key) $FK)",
            "attempt" to "CREATE TABLE attempt (id TEXT NOT NULL PRIMARY KEY CHECK(length(CAST(id AS BLOB))=36), generation TEXT NOT NULL UNIQUE REFERENCES generation(id) $FK, ${text("scope_key",64)}, ${text("requested_scopes_json",16384)}, ${text("trigger",16)}, ${text("scheduled_at",40)}, ${text("started_at",40,true)}, ${text("finished_at",40,true)}, ${text("dates_json",16384)}, ${text("retry_budget_key",64)}, retries_used INTEGER NOT NULL CHECK(retries_used BETWEEN 0 AND 3), ${text("pending_triggers_json",16384)})",
            "day_scope_phase_date" to "CREATE INDEX day_scope_phase_date ON day(generation,scope_key,phase,date)",
            "dataset_scope_phase_date" to "CREATE INDEX dataset_scope_phase_date ON dataset(generation,scope_key,phase,date)",
            "dataset_date_dataset" to "CREATE INDEX dataset_date_dataset ON dataset(generation,date,dataset)",
        )
        // SQLite ALTER TABLE은 기존 CREATE TABLE 끝 괄호 앞에 열 정의를 삽입한다.
        internal const val attemptLineageColumn = "automatic_lineage_id TEXT REFERENCES retry_budget(lineage_id) ON UPDATE RESTRICT ON DELETE RESTRICT"
        internal val retryBudgetTable = "CREATE TABLE retry_budget (lineage_id TEXT NOT NULL PRIMARY KEY CHECK(length(CAST(lineage_id AS BLOB))=36), generation TEXT NOT NULL REFERENCES generation(id) $FK, ${text("scope_key",64)}, ${text("requested_scopes_json",16384)}, ${text("dates_json",16384)}, ${text("observed_revisions_json",16384)}, retries_used INTEGER NOT NULL CHECK(retries_used BETWEEN 0 AND 3), ${text("retry_not_before",40,true)}, retry_delay_millis INTEGER, claim_version INTEGER NOT NULL CHECK(claim_version>=0), ${text("owner_attempt_id",36,true)}, ${text("status",16)}, ${text("created_at",40)}, ${text("updated_at",40)})"
        internal const val retryBudgetIndex = "CREATE UNIQUE INDEX retry_budget_active ON retry_budget(generation,scope_key) WHERE status='ACTIVE'"
        internal val schemaV2 = LinkedHashMap(schemaV1).apply {
            this["attempt"] = schemaV1.getValue("attempt").removeSuffix(")") + ", $attemptLineageColumn)"
            this["retry_budget"] = retryBudgetTable
            this["retry_budget_active"] = retryBudgetIndex
        }
        internal val schema = schemaV1
    }
}
