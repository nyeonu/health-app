package com.ny.my.health

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 계정과 실제 건강 데이터 없이 플랫폼 SQLite의 실제 파일 재열기를 검증한다. */
@RunWith(AndroidJUnit4::class)
class HistoryDatabaseInstrumentedTest {
    @Test
    fun unsupportedHistorySchemaRejectsReadsAndWritesAndPreservesTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = HistoryDatabase(context, "history-unsupported-${UUID.randomUUID()}.db")
        try {
            history.transaction { database ->
                val probe = ContentValues().apply {
                    put("id", 1)
                    put("payload_format_version", 1)
                    put("payload", "{\"probe\":\"synthetic\"}")
                }
                database.insertOrThrow("history", null, probe)
                database.version = 99
            }
            val originalBytes = history.databaseFile.readBytes()

            assertThrows(UnsupportedHistorySchemaException::class.java) {
                history.openForRead()?.close()
            }
            assertThrows(UnsupportedHistorySchemaException::class.java) {
                history.openForWrite().close()
            }

            assertTrue(history.databaseFile.exists())
            assertArrayEquals(originalBytes, history.databaseFile.readBytes())
        } finally {
            SQLiteDatabase.deleteDatabase(history.databaseFile)
        }
    }

    @Test
    fun malformedPhysicalHistoryDatabaseReportsCorruptionAndPreservesTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = HistoryDatabase(context, "history-corrupt-${UUID.randomUUID()}.db")
        // SQLite 헤더가 없는 합성 파일이며 실제 기록이나 사용자 정보는 포함하지 않는다.
        val originalBytes = ByteArray(4096) { 0x5a.toByte() }
        try {
            history.databaseFile.writeBytes(originalBytes)

            val readError = assertThrows(SQLiteDatabaseCorruptException::class.java) {
                history.openForRead()?.close()
            }
            val writeError = assertThrows(SQLiteDatabaseCorruptException::class.java) {
                history.openForWrite().close()
            }

            assertEquals("기록 DB가 손상되었습니다", readError.message)
            assertEquals("기록 DB가 손상되었습니다", writeError.message)
            assertNull(readError.cause)
            assertNull(writeError.cause)
            assertTrue(history.databaseFile.exists())
            assertArrayEquals(originalBytes, history.databaseFile.readBytes())
        } finally {
            SQLiteDatabase.deleteDatabase(history.databaseFile)
        }
    }

    @Test
    fun privateHistoryDatabaseReopensACommittedSyntheticRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = HistoryDatabase(context, "history-probe-${UUID.randomUUID()}.db")
        val syntheticPayload = "{\"probe\":\"synthetic\"}"
        try {
            assertEquals(context.noBackupFilesDir.canonicalFile, history.databaseFile.parentFile?.canonicalFile)
            assertFalse(history.databaseFile.exists())
            assertNull(history.openForRead())
            assertFalse(history.databaseFile.exists())

            history.transaction { database ->
                assertEquals(1, database.version)
                database.rawQuery("PRAGMA journal_mode", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("delete", it.getString(0).lowercase())
                }
                database.rawQuery("PRAGMA synchronous", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(2, it.getInt(0))
                }
                val probe = ContentValues().apply {
                    put("id", 1)
                    put("payload_format_version", 1)
                    put("payload", syntheticPayload)
                }
                assertEquals(1L, database.insertOrThrow("history", null, probe))
            }

            // 새 인스턴스와 새 연결이 닫힌 연결의 커밋 결과를 읽는다.
            val reopened = HistoryDatabase(context, history.databaseFile.name)
            checkNotNull(reopened.openForRead()).use { database ->
                assertEquals(1, database.version)
                database.rawQuery(
                    "SELECT id, payload_format_version, payload FROM history",
                    null,
                ).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals(1, row.getInt(0))
                    assertEquals(1, row.getInt(1))
                    assertEquals(syntheticPayload, row.getString(2))
                    assertFalse(row.moveToNext())
                }
            }
        } finally {
            // 테스트 전용 파일만 정리하며 실제 기록 초기화는 파일을 삭제하지 않는다.
            SQLiteDatabase.deleteDatabase(history.databaseFile)
        }
    }
}
