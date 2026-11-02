package com.ny.my.health

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import com.ny.my.health.core.HistoryRead
import com.ny.my.health.core.HistoryReadStatus
import com.ny.my.health.core.HistoryStore
import com.ny.my.health.core.SyncHistory
import com.ny.my.health.core.SyncHistoryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 앱 전용 메타데이터를 한 행과 한 트랜잭션으로 교체한다. */
class SqliteHistoryStore internal constructor(
    private val database: HistoryDatabase,
    private val beforeCommit: () -> Unit = {},
) : HistoryStore {
    constructor(context: Context, databaseName: String = "sync-history.db") :
        this(HistoryDatabase(context, databaseName))

    override suspend fun load(): HistoryRead = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            synchronized(database.pathLock) {
                database.openForRead()?.use(::read) ?: HistoryRead(HistoryReadStatus.ABSENT, null)
            }
        } catch (_: UnsupportedHistorySchemaException) {
            HistoryRead(HistoryReadStatus.UNSUPPORTED, null)
        } catch (_: SQLiteDatabaseCorruptException) {
            HistoryRead(HistoryReadStatus.CORRUPT, null)
        } catch (_: SQLiteException) {
            HistoryRead(HistoryReadStatus.UNAVAILABLE, null)
        } catch (_: SecurityException) {
            HistoryRead(HistoryReadStatus.UNAVAILABLE, null)
        }
    }

    override suspend fun replace(history: SyncHistory) = write(history, explicitReset = false)

    // 명시적 삭제만 알 수 없는 스키마/손상된 SQLite 파일도 빈 DB로 교체한다.
    override suspend fun reset() = write(SyncHistory(SyncHistoryCodec.VERSION, null, null), explicitReset = true)

    private suspend fun write(history: SyncHistory, explicitReset: Boolean) {
        val payload = SyncHistoryCodec.encode(history)
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            try {
                val checkBeforeCommit = {
                    coroutine.ensureActive()
                    beforeCommit()
                    coroutine.ensureActive()
                }
                synchronized(database.pathLock) {
                    coroutine.ensureActive()
                    val needsReplacement = explicitReset && (database.resetDirectory.exists() || try {
                        database.openForRead()?.use { connection ->
                            connection.rawQuery("PRAGMA integrity_check", null).use { rows ->
                                if (!rows.moveToFirst() || rows.getString(0) != "ok" || rows.moveToNext()) {
                                    throw SQLiteDatabaseCorruptException("기록 DB가 손상되었습니다")
                                }
                            }
                        }
                        false
                    } catch (_: SQLiteException) {
                        true
                    })
                    if (needsReplacement) {
                        HistoryResetRecovery(database).reset(
                            beforeInstall = checkBeforeCommit,
                            writeEmpty = { insert(it, payload) },
                            validateEmpty = { check(read(it) == HistoryRead(HistoryReadStatus.READY, history)) },
                        )
                    } else database.transaction(beforeCommit = checkBeforeCommit) { db ->
                        if (explicitReset) {
                            db.execSQL("DELETE FROM history")
                        } else {
                            check(read(db).status in setOf(HistoryReadStatus.READY, HistoryReadStatus.ABSENT)) {
                                "기록 저장소를 읽을 수 없습니다"
                            }
                        }
                        insert(db, payload)
                        if (explicitReset) {
                            check(read(db) == HistoryRead(HistoryReadStatus.READY, history)) {
                                "빈 기록 저장을 확인할 수 없습니다"
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 커밋 응답 오류는 롤백을 뜻하지 않는다. 호출자가 다시 읽어 확정해야 한다.
                throw IllegalStateException("기록 저장을 확인할 수 없습니다. 다시 읽어 확인해 주세요")
            }
        }
    }

    private fun insert(db: SQLiteDatabase, payload: String) {
        db.compileStatement(
            "INSERT OR REPLACE INTO history (id, payload_format_version, payload) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.bindLong(1, 1L)
            statement.bindLong(2, SyncHistoryCodec.VERSION.toLong())
            statement.bindString(3, payload)
            check(statement.executeInsert() == 1L) { "기록 저장을 확인할 수 없습니다" }
        }
    }

    private fun read(db: SQLiteDatabase): HistoryRead {
        // 큰 문자열을 앱 메모리로 읽기 전에 SQLite의 바이트 길이로 제한한다.
        db.rawQuery(
            "SELECT id, payload_format_version, length(CAST(payload AS BLOB)), " +
                "CASE WHEN length(CAST(payload AS BLOB)) <= CAST(? AS INTEGER) THEN payload ELSE NULL END FROM history LIMIT 2",
            arrayOf(SyncHistoryCodec.MAX_BYTES.toString()),
        ).use { rows ->
            if (!rows.moveToFirst()) return HistoryRead(HistoryReadStatus.ABSENT, null)
            if (rows.getInt(0) != 1 || rows.isNull(1) || rows.isNull(2)) return HistoryRead(HistoryReadStatus.CORRUPT, null)
            val version = rows.getInt(1)
            val size = rows.getLong(2)
            val payload = if (rows.isNull(3)) null else rows.getString(3)
            if (rows.moveToNext()) return HistoryRead(HistoryReadStatus.CORRUPT, null)
            if (version != SyncHistoryCodec.VERSION) return HistoryRead(HistoryReadStatus.UNSUPPORTED, null)
            if (size > SyncHistoryCodec.MAX_BYTES || size < 0 || payload == null) return HistoryRead(HistoryReadStatus.CORRUPT, null)
            return SyncHistoryCodec.decode(payload)
        }
    }
}
