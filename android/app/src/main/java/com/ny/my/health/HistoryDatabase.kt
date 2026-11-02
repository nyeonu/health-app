package com.ny.my.health

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File

/**
 * 백업 대상에서 제외된 앱 전용 기록 DB. 앱 실행 경로에는 아직 연결하지 않는다.
 *
 * 스키마 버전과 페이로드 형식 버전은 별개다. 기존 DB의 알 수 없는 스키마나
 * 손상은 그대로 오류로 반환하며 파일을 삭제하거나 새 DB로 대체하지 않는다.
 */
internal class HistoryDatabase(
    context: Context,
    databaseName: String = "sync-history.db",
) {
    init {
        require(databaseName.isNotBlank() && File(databaseName).name == databaseName) {
            "기록 DB에는 파일 이름만 사용할 수 있습니다"
        }
    }

    internal val databaseFile = File(context.noBackupFilesDir, databaseName)

    /** 없는 DB를 읽으면 null이며, 파일이나 스키마를 생성하지 않는다. */
    internal fun openForRead(): SQLiteDatabase? {
        if (!databaseFile.exists()) return null
        return open(create = false)
    }

    internal fun openForWrite(): SQLiteDatabase = open(create = !databaseFile.exists())

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
    ): T = openForWrite().use { database ->
        commit(database, beforeCommit, block)
    }

    private fun open(create: Boolean): SQLiteDatabase {
        val flags = SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0
        val parameters = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(flags)
            .setJournalMode("DELETE")
            .setSynchronousMode("FULL")
            // 기본 손상 처리기의 파일 삭제를 금지하고 저장소 오류를 유지한다.
            .setErrorHandler(DatabaseErrorHandler {
                throw SQLiteException("기록 DB가 손상되었습니다")
            })
            .build()
        val database = SQLiteDatabase.openDatabase(databaseFile, parameters)
        try {
            if (create && database.version == 0) {
                commit(database) { db ->
                    db.execSQL(
                        "CREATE TABLE history (" +
                            "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                            "payload_format_version INTEGER NOT NULL, " +
                            "payload TEXT NOT NULL)",
                    )
                    db.version = SCHEMA_VERSION
                }
            }
            if (database.version != SCHEMA_VERSION) {
                throw SQLiteException("지원하지 않는 기록 DB 스키마입니다")
            }
            // 버전만 맞고 실제 테이블이 없거나 읽을 수 없는 DB도 쓰기 전에 거부한다.
            database.rawQuery(
                "SELECT id, payload_format_version, payload FROM history LIMIT 1",
                null,
            ).use { it.moveToFirst() }
            database.rawQuery("PRAGMA journal_mode", null).use {
                if (!it.moveToFirst() || !it.getString(0).equals("delete", ignoreCase = true)) {
                    throw SQLiteException("기록 DB 롤백 저널을 사용할 수 없습니다")
                }
            }
            database.rawQuery("PRAGMA synchronous", null).use {
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
        const val SCHEMA_VERSION = 1
    }
}
