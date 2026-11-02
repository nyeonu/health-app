package com.ny.my.health

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.DataType
import com.ny.my.health.core.HistoryAttempt
import com.ny.my.health.core.HistoryPhase
import com.ny.my.health.core.HistoryRead
import com.ny.my.health.core.HistoryReadStatus
import com.ny.my.health.core.HistorySuccess
import com.ny.my.health.core.SyncHistory
import com.ny.my.health.core.SyncHistoryCodec
import com.ny.my.health.core.recover
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 계정과 건강 데이터 없이 연결을 닫고 새 저장소로 커밋 결과를 검증한다. */
@RunWith(AndroidJUnit4::class)
class SqliteHistoryStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2025, 1, 2)
    private val time = Instant.parse("2025-01-03T00:00:00Z")
    private val coverage = setOf(DataType.STEPS, DataType.SLEEP)
    private val success = HistorySuccess(date, time, coverage, 2)
    private val committed = SyncHistory(1, attempt(HistoryPhase.SUCCESS), success)
    private val empty = SyncHistory(1, null, null)

    @Test
    fun committedManualSuccessSurvivesFreshStoreReopenWithCoherentFields() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        assertEquals(HistoryRead(HistoryReadStatus.READY, committed), reopen(db).load())
    }

    @Test
    fun failedResultTransactionReopensPriorCommittedSuccess() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        val failed = committed.copy(attempt = attempt(HistoryPhase.FAILED).copy(failureCode = "UPLOAD_FAILED"))
        expectFailure { SqliteHistoryStore(db) { error("합성 커밋 전 실패") }.replace(failed) }
        assertEquals(committed, reopen(db).load().history)
    }

    @Test
    fun absentUnsupportedCorruptAndUnavailableStoresHaveDistinctReopenResults() = fixture { db ->
        assertEquals(HistoryReadStatus.ABSENT, reopen(db).load().status)
        assertFalse(db.databaseFile.exists())
        seed(db, 2, "{\"version\":2}")
        assertEquals(HistoryReadStatus.UNSUPPORTED, reopen(db).load().status)
        seed(db, 1, "invalid synthetic json")
        assertEquals(HistoryReadStatus.CORRUPT, reopen(db).load().status)
        SQLiteDatabase.deleteDatabase(db.databaseFile)
        assertTrue(db.databaseFile.mkdir())
        assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
    }

    @Test
    fun acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        SqliteHistoryStore(db).reset()
        assertEquals(HistoryRead(HistoryReadStatus.READY, empty), reopen(db).load())
        assertTrue(db.databaseFile.exists())
    }

    @Test
    fun interruptedResetBeforeCommitReopensOldStateAndAfterCommitReopensEmpty() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        try {
            SqliteHistoryStore(db) { throw CancellationException("합성 중단") }.reset()
            fail("중단이 전달되어야 합니다")
        } catch (_: CancellationException) {
            assertEquals(committed, reopen(db).load().history)
        }
        SqliteHistoryStore(db).reset()
        assertEquals(empty, reopen(db).load().history)
    }

    @Test
    fun repeatedEmptyDeletionIsLocalAndPersistenceFailureNeverBecomesSuccess() = fixture { db ->
        SqliteHistoryStore(db).reset()
        SqliteHistoryStore(db).reset()
        assertEquals(empty, reopen(db).load().history)
        expectFailure { SqliteHistoryStore(db) { error("합성 삭제 실패") }.reset() }
        assertEquals(empty, reopen(db).load().history)
    }

    @Test
    fun terminalRestorationPreservesActualOutcomeAndPriorIndependentSuccess() = fixture { db ->
        val terminal = HistoryPhase.entries.filterNot {
            it in setOf(HistoryPhase.AUTHORIZING, HistoryPhase.AWAITING_UPLOAD, HistoryPhase.RUNNING, HistoryPhase.SUCCESS)
        }
        for (phase in terminal) {
            val history = committed.copy(attempt = attempt(phase))
            SqliteHistoryStore(db).replace(history)
            assertEquals(history, reopen(db).load().history?.recover())
        }
    }

    @Test
    fun readingIncompatibleOrCorruptPayloadNeverOverwritesItAndExplicitResetCommitsEmpty() = fixture { db ->
        for ((version, payload) in listOf(2 to "{\"version\":2}", 1 to "invalid synthetic json", 1 to "x".repeat(SyncHistoryCodec.MAX_BYTES + 1))) {
            seed(db, version, payload)
            val status = if (version == 2) HistoryReadStatus.UNSUPPORTED else HistoryReadStatus.CORRUPT
            assertEquals(status, reopen(db).load().status)
            expectFailure { reopen(db).replace(committed) }
            assertEquals(status, reopen(db).load().status)
            checkNotNull(db.openForRead()).use { connection ->
                connection.rawQuery("SELECT payload_format_version, payload FROM history", null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals(version, row.getInt(0))
                    assertEquals(payload, row.getString(1))
                }
            }
            reopen(db).reset()
            assertEquals(empty, reopen(db).load().history)
        }
    }

    @Test
    fun unsupportedSchemaAndCorruptDatabaseRefuseWritesAndResetWithoutReplacement() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        checkNotNull(db.openForRead()).use { it.version = 2 }
        val schemaBytes = db.databaseFile.readBytes()
        assertEquals(HistoryReadStatus.UNSUPPORTED, reopen(db).load().status)
        expectFailure { reopen(db).replace(committed) }
        expectFailure { reopen(db).reset() }
        assertTrue(schemaBytes.contentEquals(db.databaseFile.readBytes()))
        SQLiteDatabase.deleteDatabase(db.databaseFile)
        db.databaseFile.writeText("synthetic corrupt sqlite")
        val corruptBytes = db.databaseFile.readBytes()
        assertEquals(HistoryReadStatus.CORRUPT, reopen(db).load().status)
        expectFailure { reopen(db).replace(committed) }
        expectFailure { reopen(db).reset() }
        assertTrue(corruptBytes.contentEquals(db.databaseFile.readBytes()))
    }

    @Test
    fun unavailableDatabaseReadsAndFailedResetLeaveTheStoreUnavailable() = fixture { db ->
        assertTrue(db.databaseFile.mkdir())
        assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
        expectFailure { reopen(db).replace(committed) }
        expectFailure { reopen(db).reset() }
        assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
        assertTrue(db.databaseFile.isDirectory)
    }

    @Test
    fun recoverySaveFailureLeavesPersistedRunningAttemptAndDerivableInterruption() = fixture { db ->
        val running = committed.copy(attempt = attempt(HistoryPhase.RUNNING).copy(finishedAt = null, confirmedFiles = null))
        SqliteHistoryStore(db).replace(running)
        val recovered = checkNotNull(reopen(db).load().history).recover()
        assertEquals(HistoryPhase.INTERRUPTED, recovered.attempt?.phase)
        assertEquals(success, recovered.success)
        assertNull(recovered.attempt?.confirmedFiles)
        expectFailure { SqliteHistoryStore(db) { error("합성 복구 저장 실패") }.replace(recovered) }
        assertEquals(running, reopen(db).load().history)
        assertEquals(recovered, reopen(db).load().history?.recover())
    }

    private fun attempt(phase: HistoryPhase): HistoryAttempt {
        val beforeStart = phase in setOf(HistoryPhase.AUTHORIZATION_ABANDONED, HistoryPhase.UPLOAD_NOT_STARTED)
        return HistoryAttempt(
            date, time, if (beforeStart) null else time, if (beforeStart) null else time,
            phase, coverage, if (beforeStart) null else 2, null,
        )
    }

    private fun reopen(db: HistoryDatabase) = SqliteHistoryStore(HistoryDatabase(context, db.databaseFile.name))

    private fun seed(db: HistoryDatabase, version: Int, payload: String) {
        db.transaction { connection ->
            val values = ContentValues().apply {
                put("id", 1)
                put("payload_format_version", version)
                put("payload", payload)
            }
            connection.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("저장 실패가 전달되어야 합니다")
        } catch (error: IllegalStateException) {
            assertEquals("기록 저장을 확인할 수 없습니다. 다시 읽어 확인해 주세요", error.message)
            assertNull(error.cause)
        }
    }

    private fun fixture(block: suspend (HistoryDatabase) -> Unit) = runBlocking {
        val db = HistoryDatabase(context, "history-store-${UUID.randomUUID()}.db")
        try {
            block(db)
        } finally {
            // 합성 테스트 파일에만 적용한다. 제품의 초기화는 빈 봉투 커밋이다.
            if (db.databaseFile.isDirectory) db.databaseFile.delete()
            else SQLiteDatabase.deleteDatabase(db.databaseFile)
        }
    }
}
