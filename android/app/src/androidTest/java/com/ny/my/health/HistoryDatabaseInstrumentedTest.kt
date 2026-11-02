package com.ny.my.health

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 계정과 실제 건강 데이터 없이 플랫폼 SQLite의 실제 파일 재열기를 검증한다. */
@RunWith(AndroidJUnit4::class)
class HistoryDatabaseInstrumentedTest {
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
