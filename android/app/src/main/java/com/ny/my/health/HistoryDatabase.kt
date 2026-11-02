package com.ny.my.health

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** 제공자 오류 문자열을 해석하지 않고 지원하지 않는 버전을 구분한다. */
internal class UnsupportedHistorySchemaException :
    SQLiteException("지원하지 않는 기록 DB 스키마입니다")

/**
 * 백업 대상에서 제외된 앱 전용 기록 DB.
 *
 * 스키마 버전과 페이로드 형식 버전은 별개다. 기존 DB의 알 수 없는 스키마나
 * 손상은 읽기/일반 쓰기에서 오류로 반환한다. 파일 교체는 확인된 reset의 전용 경계다.
 */
internal class HistoryDatabase internal constructor(internal val databaseFile: File) {
    constructor(context: Context, databaseName: String = "sync-history.db") :
        this(databaseFile(context, databaseName))

    internal val pathLock: Any = synchronized(locks) {
        locks.getOrPut(databaseFile.canonicalPath) { Any() }
    }
    internal val resetDirectory = File(databaseFile.parentFile, "${databaseFile.name}.reset")

    internal fun requireNoPendingReset() {
        if (resetDirectory.exists()) throw SQLiteException("기록 초기화를 다시 시도해 주세요")
    }

    /** 없는 DB를 읽으면 null이며, 파일이나 스키마를 생성하지 않는다. */
    internal fun openForRead(): SQLiteDatabase? {
        requireNoPendingReset()
        if (!databaseFile.exists()) return null
        return open(create = false, readOnly = true)
    }

    /** Only explicit reset validates the newly installed file while its marker still blocks admission. */
    internal fun openInstalledAfterReset(): SQLiteDatabase = open(create = false, readOnly = true)

    internal fun openForWrite(): SQLiteDatabase {
        requireNoPendingReset()
        // 알 수 없는 WAL 스키마를 DELETE 모드로 전환하기 전에 읽기 전용으로 확인한다.
        if (databaseFile.exists()) open(create = false, readOnly = true).close()
        return open(create = !databaseFile.exists())
    }

    /**
     * 반환 시점은 endTransaction이 성공한 뒤다. FULL 동기화와 DELETE 저널로
     * 커밋 전 중단은 이전 행으로, 커밋 완료 후 재열기는 새 행으로 복구된다.
     * 초기화도 같은 경계를 사용하며, 초기화 전 중단된 버전 0 DB는 오류로 남긴다.
     * beforeCommit은 테스트에서 커밋 직전 실패를 주입하는 내부 경계다.
     * 초기화/초기화된 빈 봉투 쓰기 모두 파일 삭제 없이 이 트랜잭션을 사용한다.
     */
    internal fun <T> transaction(
        beforeCommit: () -> Unit = {},
        block: (SQLiteDatabase) -> T,
    ): T = synchronized(pathLock) {
        openForWrite().use { database -> commit(database, beforeCommit, block) }
    }

    private fun open(create: Boolean, readOnly: Boolean = false): SQLiteDatabase {
        if (Files.isSymbolicLink(databaseFile.toPath()) || (databaseFile.exists() && !databaseFile.isFile)) {
            throw SQLiteException("기록 DB 파일을 사용할 수 없습니다")
        }
        val flags = (if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE) or
            SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0
        val builder = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(flags)
            // 기본 손상 처리기의 파일 삭제를 금지하고 저장소 오류를 유지한다.
            .setErrorHandler(DatabaseErrorHandler {
                throw SQLiteDatabaseCorruptException("기록 DB가 손상되었습니다")
            })
        if (!readOnly) builder.setJournalMode("DELETE").setSynchronousMode("FULL")
        val parameters = builder.build()
        val database = SQLiteDatabase.openDatabase(databaseFile, parameters)
        try {
            if (create && database.version == 0) {
                commit(database) { db ->
                    db.execSQL(CREATE_HISTORY_TABLE)
                    db.version = SCHEMA_VERSION
                }
            }
            if (database.version != SCHEMA_VERSION) {
                throw UnsupportedHistorySchemaException()
            }
            validateHistorySchema(database)
            // 버전만 맞고 실제 테이블이 없거나 읽을 수 없는 DB도 쓰기 전에 거부한다.
            database.rawQuery(
                "SELECT id, payload_format_version, payload FROM history LIMIT 1",
                null,
            ).use { it.moveToFirst() }
            if (!readOnly) database.rawQuery("PRAGMA journal_mode", null).use {
                if (!it.moveToFirst() || !it.getString(0).equals("delete", ignoreCase = true)) {
                    throw SQLiteException("기록 DB 롤백 저널을 사용할 수 없습니다")
                }
            }
            if (!readOnly) database.rawQuery("PRAGMA synchronous", null).use {
                if (!it.moveToFirst() || it.getInt(0) != 2) {
                    throw SQLiteException("기록 DB FULL 동기화를 사용할 수 없습니다")
                }
            }
            return database
        } catch (error: Throwable) {
            database.close()
            throw error
        }
    }

    private fun validateHistorySchema(database: SQLiteDatabase) {
        database.rawQuery(
            "SELECT type, sql FROM sqlite_master WHERE name = ? COLLATE NOCASE AND type IN ('table', 'view')",
            arrayOf("history"),
        ).use { rows ->
            // Preserve the existing distinction between an absent table and an unsupported definition.
            if (!rows.moveToFirst()) throw SQLiteException("기록 DB 테이블을 읽을 수 없습니다")
            if (rows.getString(0) != "table" || rows.isNull(1) ||
                normalizeSchema(rows.getString(1)) != normalizeSchema(CREATE_HISTORY_TABLE) || rows.moveToNext()
            ) throw UnsupportedHistorySchemaException()
        }
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND tbl_name = ? COLLATE NOCASE LIMIT 1",
            arrayOf("history"),
        ).use { rows ->
            if (rows.moveToFirst()) throw UnsupportedHistorySchemaException()
        }
    }

    private fun <T> commit(
        database: SQLiteDatabase,
        beforeCommit: () -> Unit = {},
        block: (SQLiteDatabase) -> T,
    ): T {
        database.beginTransaction()
        try {
            val result = block(database)
            beforeCommit()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    private companion object {
        val locks = mutableMapOf<String, Any>()
        const val SCHEMA_VERSION = 1
        const val CREATE_HISTORY_TABLE = "CREATE TABLE history (" +
            "id INTEGER PRIMARY KEY CHECK (id = 1), " +
            "payload_format_version INTEGER NOT NULL, " +
            "payload TEXT NOT NULL)"

        fun normalizeSchema(sql: String): String = sql.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

        fun databaseFile(context: Context, name: String): File {
            require(name.isNotBlank() && File(name).name == name) {
                "기록 DB에는 파일 이름만 사용할 수 있습니다"
            }
            return File(context.noBackupFilesDir, name)
        }
    }
}
