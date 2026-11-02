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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    fun unsupportedSchemaAndCorruptDatabasePreserveReadsAndWritesUntilExplicitReset() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        db.transaction { it.version = 2 }
        val schemaBytes = db.databaseFile.readBytes()
        assertEquals(HistoryReadStatus.UNSUPPORTED, reopen(db).load().status)
        expectFailure { reopen(db).replace(committed) }
        assertTrue(schemaBytes.contentEquals(db.databaseFile.readBytes()))
        reopen(db).reset()
        assertEquals(empty, reopen(db).load().history)
        assertFalse(db.resetDirectory.exists())
        reopen(db).replace(committed)
        assertEquals(committed, reopen(db).load().history)
        SQLiteDatabase.deleteDatabase(db.databaseFile)
        db.databaseFile.writeText("synthetic corrupt sqlite")
        val corruptBytes = db.databaseFile.readBytes()
        assertEquals(HistoryReadStatus.CORRUPT, reopen(db).load().status)
        expectFailure { reopen(db).replace(committed) }
        assertTrue(corruptBytes.contentEquals(db.databaseFile.readBytes()))
        reopen(db).reset()
        assertEquals(empty, reopen(db).load().history)
        assertFalse(db.resetDirectory.exists())
        reopen(db).replace(committed)
        assertEquals(committed, reopen(db).load().history)
    }

    @Test
    fun missingHistoryTableRequiresExplicitResetBeforeManualAdmission() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        db.transaction { it.execSQL("DROP TABLE history") }
        val bytes = db.databaseFile.readBytes()
        assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
        expectFailure { reopen(db).replace(committed) }
        assertTrue(bytes.contentEquals(db.databaseFile.readBytes()))
        val session = HistorySession(reopen(db), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())) {}
        session.restore()
        assertNull(session.beginAuthorization(date, time))
        reopen(db).reset()
        session.reload()
        assertTrue(session.beginAuthorization(date, time) != null)
        assertFalse(db.resetDirectory.exists())
    }

    @Test
    fun malformedVersionOneConstraintsAndHistoryTriggersRequireExplicitReplacement() = fixture { db ->
        val definitions = listOf(
            "CREATE TABLE history (id INTEGER PRIMARY KEY, payload_format_version INTEGER NOT NULL, payload TEXT NOT NULL)" to 2,
            "CREATE TABLE history (id INTEGER CHECK (id = 1), payload_format_version INTEGER NOT NULL, payload TEXT NOT NULL)" to 1,
            "CREATE TABLE history (id INTEGER PRIMARY KEY CHECK (id = 1), payload_format_version INTEGER NOT NULL, payload TEXT NOT NULL)" to 1,
        )
        for ((index, definition) in definitions.withIndex()) {
            SQLiteDatabase.deleteDatabase(db.databaseFile)
            SQLiteDatabase.openOrCreateDatabase(db.databaseFile, null).use { connection ->
                connection.execSQL(definition.first)
                connection.version = 1
                connection.execSQL(
                    "INSERT INTO history (id, payload_format_version, payload) VALUES (?, ?, ?)",
                    arrayOf<Any>(definition.second, 1, SyncHistoryCodec.encode(committed)),
                )
                if (index == 2) {
                    connection.execSQL(
                        "CREATE TRIGGER retain_history BEFORE INSERT ON history BEGIN SELECT RAISE(IGNORE); END",
                    )
                }
                connection.rawQuery("PRAGMA integrity_check", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("ok", rows.getString(0))
                    assertFalse(rows.moveToNext())
                }
            }
            val originalBytes = db.databaseFile.readBytes()
            assertEquals(HistoryReadStatus.UNSUPPORTED, reopen(db).load().status)
            expectFailure { reopen(db).replace(empty) }
            assertTrue(originalBytes.contentEquals(db.databaseFile.readBytes()))
            reopen(db).reset()
            assertEquals(HistoryRead(HistoryReadStatus.READY, empty), reopen(db).load())
            assertFalse(db.resetDirectory.exists())
            listOf("-journal", "-wal", "-shm").forEach { assertFalse(File(db.databaseFile.path + it).exists()) }
            for (history in listOf(committed, committed.copy(attempt = attempt(HistoryPhase.FAILED)))) {
                reopen(db).replace(history)
                assertEquals(HistoryRead(HistoryReadStatus.READY, history), reopen(db).load())
                checkNotNull(db.openForRead()).use { connection ->
                    connection.rawQuery("SELECT id FROM history", null).use { rows ->
                        assertTrue(rows.moveToFirst())
                        assertEquals(1, rows.getInt(0))
                        assertFalse(rows.moveToNext())
                    }
                }
            }
        }
    }

    @Test
    fun canonicalSchemaWithPreviouslyIgnoredCheckRequiresConfirmedResetToClearEveryRow() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        db.openForWrite().use { connection ->
            connection.execSQL("PRAGMA ignore_check_constraints=ON")
            connection.execSQL(
                "INSERT INTO history (id, payload_format_version, payload) VALUES (?, ?, ?)",
                arrayOf<Any>(2, 1, SyncHistoryCodec.encode(committed)),
            )
            connection.execSQL("PRAGMA ignore_check_constraints=OFF")
        }
        val originalBytes = db.databaseFile.readBytes()
        assertEquals(HistoryReadStatus.CORRUPT, reopen(db).load().status)
        expectFailure { reopen(db).replace(empty) }
        assertTrue(originalBytes.contentEquals(db.databaseFile.readBytes()))
        reopen(db).reset()
        assertEquals(HistoryRead(HistoryReadStatus.READY, empty), reopen(db).load())
        assertFalse(db.resetDirectory.exists())
        for (history in listOf(committed, empty)) {
            reopen(db).replace(history)
            assertEquals(HistoryRead(HistoryReadStatus.READY, history), reopen(db).load())
            checkNotNull(db.openForRead()).use { connection ->
                connection.rawQuery("SELECT id FROM history", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(1, rows.getInt(0))
                    assertFalse(rows.moveToNext())
                }
            }
        }
        listOf("-journal", "-wal", "-shm").forEach { assertFalse(File(db.databaseFile.path + it).exists()) }
    }

    @Test
    fun unknownWalSchemaReadsAndOrdinaryWritesDoNotConvertOrOverwriteItsDatabaseFamily() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        SQLiteDatabase.openDatabase(db.databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { foreign ->
            assertTrue(foreign.enableWriteAheadLogging())
            foreign.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0, rows.getInt(0))
            }
            foreign.version = 99
            val wal = File(db.databaseFile.path + "-wal")
            assertTrue(wal.isFile)
            val mainBytes = db.databaseFile.readBytes()
            val walBytes = wal.readBytes()
            assertEquals(HistoryReadStatus.UNSUPPORTED, reopen(db).load().status)
            expectFailure { reopen(db).replace(committed) }
            assertTrue(mainBytes.contentEquals(db.databaseFile.readBytes()))
            assertTrue(walBytes.contentEquals(wal.readBytes()))
            foreign.rawQuery("PRAGMA journal_mode", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("wal", rows.getString(0))
            }
        }
        reopen(db).reset()
        assertEquals(empty, reopen(db).load().history)
        assertFalse(db.resetDirectory.exists())
    }

    @Test
    fun genuineHotRollbackJournalStaysExplicitlyUnavailableUntilConfirmedReset() = fixture { source ->
        SqliteHistoryStore(source).replace(committed)
        source.transaction { connection ->
            connection.execSQL("CREATE TABLE reset_padding (id INTEGER PRIMARY KEY, payload BLOB)")
            connection.execSQL("INSERT INTO reset_padding VALUES (1, zeroblob(262144))")
        }
        val snapshot = HistoryDatabase(context, "history-hot-${UUID.randomUUID()}.db")
        try {
            source.openForWrite().use { connection ->
                connection.execSQL("PRAGMA cache_size=5")
                connection.execSQL("PRAGMA cache_spill=ON")
                connection.beginTransaction()
                try {
                    // Spill dirty pages to the main file while an actual rollback is still required.
                    connection.execSQL("UPDATE reset_padding SET payload=zeroblob(1048576)")
                    val journal = File(source.databaseFile.path + "-journal")
                    assertTrue(journal.length() > 512)
                    val magic = ByteArray(8)
                    journal.inputStream().use { assertEquals(8, it.read(magic)) }
                    assertTrue(byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 5, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte()).contentEquals(magic))
                    source.databaseFile.copyTo(snapshot.databaseFile)
                    journal.copyTo(File(snapshot.databaseFile.path + "-journal"))
                } finally {
                    connection.endTransaction() // Roll back source; the independent snapshot retains the hot journal.
                }
            }
            val mainBytes = snapshot.databaseFile.readBytes()
            val journal = File(snapshot.databaseFile.path + "-journal")
            val journalBytes = journal.readBytes()
            assertEquals(HistoryRead(HistoryReadStatus.UNAVAILABLE, null), reopen(snapshot).load())
            expectFailure { reopen(snapshot).replace(committed) }
            assertTrue(mainBytes.contentEquals(snapshot.databaseFile.readBytes()))
            assertTrue(journalBytes.contentEquals(journal.readBytes()))
            reopen(snapshot).reset()
            assertEquals(empty, reopen(snapshot).load().history)
            assertFalse(snapshot.resetDirectory.exists())
            assertFalse(journal.exists())
            reopen(snapshot).replace(committed)
            assertEquals(committed, reopen(snapshot).load().history)
        } finally {
            snapshot.resetDirectory.deleteRecursively()
            SQLiteDatabase.deleteDatabase(snapshot.databaseFile)
        }
    }

    @Test
    fun failedOrCancelledReplacementPreservesOriginalFamilyUntilExplicitRetry() = fixture { db ->
        for (cancel in listOf(false, true)) {
            db.databaseFile.writeText("synthetic corrupt sqlite")
            val family = listOf("", "-journal", "-wal", "-shm").associateWith { suffix ->
                val file = File(db.databaseFile.path + suffix)
                if (suffix.isNotEmpty()) file.writeText("synthetic sidecar $suffix")
                file.readBytes()
            }
            val broken = SqliteHistoryStore(db) {
                if (cancel) throw CancellationException("synthetic cancellation")
                error("synthetic preinstall failure")
            }
            if (cancel) {
                try { broken.reset(); fail("cancellation must propagate") } catch (_: CancellationException) { }
            } else expectFailure { broken.reset() }
            family.forEach { (suffix, bytes) ->
                assertTrue(bytes.contentEquals(File(db.databaseFile.path + suffix).readBytes()))
            }
            assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
            expectFailure { reopen(db).replace(committed) }
            reopen(db).reset()
            assertEquals(empty, reopen(db).load().history)
            assertFalse(db.resetDirectory.exists())
            listOf("-journal", "-wal", "-shm").forEach { assertFalse(File(db.databaseFile.path + it).exists()) }
        }
    }

    @Test
    fun interruptedStagingAndInstalledCleanupFailureRequireRetryAndNeverRestoreForgottenHistory() = fixture { db ->
        SqliteHistoryStore(db).replace(committed)
        assertTrue(db.resetDirectory.mkdir())
        File(db.resetDirectory, "empty.db").writeText("interrupted candidate")
        assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
        expectFailure { reopen(db).replace(committed) }
        reopen(db).reset()
        assertEquals(empty, reopen(db).load().history)
        assertFalse(db.resetDirectory.exists())

        // Process loss after installation but before snapshot cleanup leaves explicit unknown state.
        reopen(db).replace(committed)
        val oldBytes = db.databaseFile.readBytes()
        reopen(db).reset()
        assertTrue(db.resetDirectory.mkdir())
        val snapshot = File(db.resetDirectory, "old-0")
        snapshot.writeBytes(oldBytes)
        File(db.resetDirectory, "prepared").writeBytes(byteArrayOf())
        assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
        assertTrue(oldBytes.contentEquals(snapshot.readBytes()))
        expectFailure { reopen(db).replace(committed) }
        reopen(db).reset()
        assertEquals(empty, reopen(db).load().history)
        assertFalse(db.resetDirectory.exists())

        // A real nonempty directory makes post-install unlink fail without a production hook.
        db.databaseFile.writeText("synthetic corrupt sqlite")
        val blocked = File(db.resetDirectory, "cleanup-blocker")
        expectFailure {
            SqliteHistoryStore(db) {
                check(blocked.mkdir())
                File(blocked, "child").writeText("synthetic blocker")
            }.reset()
        }
        assertEquals(HistoryReadStatus.UNAVAILABLE, reopen(db).load().status)
        // Inspect actual installed DB without ordinary read-time recovery.
        db.openInstalledAfterReset().use { connection ->
            connection.rawQuery("SELECT payload FROM history", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(HistoryRead(HistoryReadStatus.READY, empty), SyncHistoryCodec.decode(rows.getString(0)))
            }
        }
        assertTrue(blocked.deleteRecursively())
        reopen(db).reset()
        assertEquals(empty, reopen(db).load().history)
        assertFalse(db.resetDirectory.exists())
    }

    @Test
    fun separateStoreInstancesWaitForReplacementBeforeReadingOrWriting() = fixture { db -> coroutineScope {
        db.databaseFile.writeText("synthetic corrupt sqlite")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val reset = async(Dispatchers.IO) {
            SqliteHistoryStore(db) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }.reset()
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reader = async(Dispatchers.IO) { readerStarted.countDown(); reopen(db).load() }
            val writer = async(Dispatchers.IO) { writerStarted.countDown(); reopen(db).replace(empty) }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(reader.isCompleted)
            assertFalse(writer.isCompleted)
            release.countDown()
            reset.await()
            assertEquals(empty, reader.await().history)
            writer.await()
            assertEquals(empty, reopen(db).load().history)
        } finally { release.countDown() }
    } }

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
            db.resetDirectory.deleteRecursively()
            // 합성 테스트 파일에만 적용한다. 제품의 초기화는 빈 봉투 커밋이다.
            if (db.databaseFile.isDirectory) db.databaseFile.delete()
            else SQLiteDatabase.deleteDatabase(db.databaseFile)
        }
    }
}
