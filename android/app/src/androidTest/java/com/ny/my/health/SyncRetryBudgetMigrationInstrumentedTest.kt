package com.ny.my.health

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRetryBudgetMigrationInstrumentedTest {
    private val today = LocalDate.parse("2026-11-14")
    private val generation = "00000000-0000-4000-8000-000000000001"
    private val archived = "00000000-0000-4000-8000-000000000002"
    private val attempt = "00000000-0000-4000-8000-000000000003"
    private val scopes = mapOf(DataType.STEPS to ReadScope.SUMMARY)
    private val scope = SyncPlanner.scopeKey(SyncConfiguration("synthetic", LocalDate.parse("2026-11-01"), false, scopes.keys), ReadAccess(true, scopes.keys, scopes))!!.value
    private val time = "2026-11-05T00:00:00Z"
    private val revision = "a".repeat(64)

    private fun fixture(): File {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        val file = File(root, "migration-test-${UUID.randomUUID()}.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { require(it.moveToFirst() && it.getString(0).equals("delete", true)) }
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
            db.beginTransaction()
            try {
                SyncStateDatabase.schemaV1.values.forEach(db::execSQL)
                db.version = 1
                for (id in listOf(generation, archived)) db.execSQL("INSERT INTO generation VALUES(?,?,?,?)", arrayOf(id, "synthetic", "2026-11-01", SyncStateCodec.encodeCategories(scopes.keys)))
                db.execSQL("INSERT INTO config VALUES(1,1,?,0,?,?,0)", arrayOf(generation, "SYNTHETIC", "{\"kind\":\"SYNTHETIC\",\"accountName\":null,\"accountType\":null,\"treeUri\":null}"))
                val requested = SyncStateCodec.encodeScopes(scopes)
                val dates = SyncStateCodec.encodeDates(listOf(LocalDate.parse("2026-11-05")))
                db.execSQL("INSERT INTO attempt VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(attempt, generation, scope, requested, "MANUAL", time, time, null, dates, revision, 0, "[]"))
                for (id in listOf(generation, archived)) {
                    db.execSQL("INSERT INTO day VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(id, "2026-11-05", scope, requested, revision, time, "INDETERMINATE", requested, time, null))
                    val prior = SyncStateCodec.encodeConfirmation(FileConfirmation(FileKey(DestinationGeneration(id), LocalDate.parse("2026-11-05"), ReadScopeKey(scope), Dataset.DAILY, revision), "synthetic-target", java.time.Instant.parse(time)))
                    db.execSQL("INSERT INTO dataset VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(id, "2026-11-05", scope, "DAILY", revision, time, "INDETERMINATE", 1, "synthetic-target", time, null, prior))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return file
    }
    private fun cleanup(file: File) {
        file.parentFile!!.listFiles()!!.filter { it.name.startsWith(file.name) }.forEach { it.deleteRecursively() }
    }
    private fun store(file: File, observer: (SyncStatePhase) -> Unit = {}) = SqliteSyncStateStore(SyncStateDatabase(file, observer), { today })
    private fun query(db: SQLiteDatabase, sql: String): List<String> = db.rawQuery(sql, null).use { c ->
        val rows = mutableListOf<String>()
        while (c.moveToNext()) rows += (0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "NULL" else c.getString(it) }
        rows.sorted()
    }

    @Test
    fun validatedV1MigrationRetainsBindingsJournalsUncertaintyPriorConfirmationsAndGapsThroughInterruptedV2Publish() = runBlocking {
        for (phase in listOf(SyncStatePhase.MIGRATION_OWNER_READY, SyncStatePhase.MIGRATION_COPY_COMPLETE,
            SyncStatePhase.MIGRATION_TRANSFORMED, SyncStatePhase.MIGRATION_STAGE_VALIDATED,
            SyncStatePhase.MIGRATION_MARKER_PUBLISHED, SyncStatePhase.MIGRATION_SOURCE_RETIRED,
            SyncStatePhase.MIGRATION_PUBLISHED, SyncStatePhase.MIGRATION_VERIFIED)) {
            val file = fixture()
            try {
                val original = file.readBytes()
                var reached = false
                assertEquals(StateStatus.UNAVAILABLE, store(file) { if (it == phase) { reached = true; error("합성 중단") } }.load().status)
                assertTrue(reached)
                assertEquals(StateStatus.READY, store(file).load().status)
                assertEquals(PrivateDestinationBinding("SYNTHETIC"), store(file).readBinding(DestinationGeneration(generation)))
                val current = store(file).loadCurrentAttempt(DestinationGeneration(generation))!!
                assertEquals(attempt, current.id)
                assertNull(current.finishedAt)
                val day = store(file).loadDay(DestinationGeneration(generation), LocalDate.parse("2026-11-05"), ReadScopeKey(scope))!!
                assertEquals(FilePhase.INDETERMINATE, day.datasets.getValue(Dataset.DAILY).phase)
                assertEquals("synthetic-target", day.datasets.getValue(Dataset.DAILY).priorConfirmation!!.targetHandle)
                val selected = store(file).loadForScope(DestinationGeneration(generation), today, ReadAccess(true, scopes.keys, scopes)).state!!
                assertEquals(LocalDate.parse("2026-11-01"), selected.oldestPendingDays.first())
                assertEquals(13L, selected.coverage.scopePendingCount)
                val completed = file.parentFile!!.listFiles()!!.single { it.name.startsWith("${file.name}.migration.completed-") }
                assertArrayEquals(original, File(completed, "old").readBytes())
                SyncStateDatabase(file).openForRead().use { db ->
                    assertEquals(2, db!!.version)
                    assertEquals(listOf("NULL"), query(db, "SELECT automatic_lineage_id FROM attempt"))
                    assertEquals(listOf("2"), query(db, "SELECT count(*) FROM generation"))
                    assertEquals(listOf("2"), query(db, "SELECT count(*) FROM dataset"))
                    assertTrue(query(db, "PRAGMA foreign_key_check").isEmpty())
                    assertEquals(listOf("ok"), query(db, "PRAGMA quick_check"))
                }
            } finally { cleanup(file) }
        }
    }

    @Test
    fun migratedAndFreshV2SchemasHaveIdenticalSqlAndForeignKeysAndUseActiveIndex() = runBlocking {
        val migrated = fixture()
        val fresh = File(migrated.parentFile, "${migrated.name}-fresh")
        try {
            assertEquals(StateStatus.READY, store(migrated).load().status)
            SyncStateDatabase(fresh).openForWrite().close()
            SyncStateDatabase(migrated).openForRead().use { left -> SyncStateDatabase(fresh).openForRead().use { right ->
                val schema = "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'"
                assertEquals(query(left!!, schema), query(right!!, schema))
                for (table in listOf("attempt", "retry_budget")) assertEquals(query(left, "PRAGMA foreign_key_list($table)"), query(right, "PRAGMA foreign_key_list($table)"))
                assertTrue(query(left, "EXPLAIN QUERY PLAN SELECT * FROM retry_budget WHERE generation='$generation' AND status='ACTIVE'").any { it.contains("retry_budget_active") })
            } }
        } finally { cleanup(migrated); cleanup(fresh) }
    }

    @Test
    fun malformedFutureAndAutomaticV1SourcesRemainUnchanged() = runBlocking {
        for (kind in listOf("malformed", "future", "automatic")) {
            val file = fixture()
            try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    when (kind) {
                        "malformed" -> db.execSQL("UPDATE config SET binding_payload='{' ")
                        "future" -> db.version = 3
                        else -> db.execSQL("UPDATE config SET automatic=1")
                    }
                }
                val before = file.readBytes()
                assertNotEquals(StateStatus.READY, store(file).load().status)
                assertArrayEquals(before, file.readBytes())
                assertFalse(File(file.parentFile, "${file.name}.migration").exists())
            } finally { cleanup(file) }
        }
    }

    @Test
    fun partialStageAndSameContentInodeReplacementsRemainBlockedAndPreserved() = runBlocking {
        for (kind in listOf("partial", "source", "stage")) {
            val file = fixture()
            try {
                val stop = if (kind == "partial") SyncStatePhase.MIGRATION_OWNER_READY else SyncStatePhase.MIGRATION_STAGE_VALIDATED
                assertEquals(StateStatus.UNAVAILABLE, store(file) { if (it == stop) error("합성 중단") }.load().status)
                val marker = File(file.parentFile, "${file.name}.migration")
                val target = if (kind == "source") file else File(marker, "stage")
                if (kind == "partial") target.writeBytes(byteArrayOf(1, 2, 3))
                else {
                    val replacement = File(target.parentFile, "replacement")
                    replacement.writeBytes(target.readBytes())
                    assertTrue(target.delete())
                    assertTrue(replacement.renameTo(target))
                }
                val bytes = target.readBytes()
                assertNotEquals(StateStatus.READY, store(file).load().status)
                assertArrayEquals(bytes, target.readBytes())
                assertTrue(marker.exists())
            } finally { cleanup(file) }
        }
    }

    private fun installTerminalBudget(file: File, updated: String, missingParent: Boolean = false) {
        val lineage = "00000000-0000-4000-8000-000000000004"
        val dates = listOf(LocalDate.parse("2026-11-05"))
        val budget = RetryBudget.validated(lineage, DestinationGeneration(generation), ReadScopeKey(scope), scopes, dates,
            dates.associateWith { null }, 0, null, null, 0, null, RetryBudgetStatus.COMPLETED,
            java.time.Instant.parse(time), java.time.Instant.parse(updated))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.beginTransaction()
            try {
                if (!missingParent) db.execSQL("INSERT INTO retry_budget VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(lineage, generation, scope,
                    SyncStateCodec.encodeScopes(scopes), SyncStateCodec.encodeDates(dates), "{\"2026-11-05\":null}", 0, null, null, 0, null, "COMPLETED", time, updated))
                db.execSQL("UPDATE attempt SET trigger='SCHEDULED',finished_at=?,retry_budget_key=?,automatic_lineage_id=? WHERE id=?",
                    arrayOf("2026-11-05T00:00:02Z", budget.retryBudgetKey, lineage, attempt))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    @Test
    fun retainedTerminalBudgetResetsWithForeignKeysEnabledAndValidLaterRetirement() = runBlocking {
        val file = fixture()
        try {
            assertEquals(StateStatus.READY, store(file).load().status)
            installTerminalBudget(file, "2026-11-05T00:00:03Z")
            assertEquals(StateStatus.READY, store(file).load().status)
            assertEquals(CommitStatus.COMMITTED, store(file).reset(DestinationGeneration(generation)).status)
            SyncStateDatabase(file).openForRead().use { db ->
                assertEquals(listOf("1"), query(db!!, "PRAGMA foreign_keys"))
                assertTrue(query(db, "PRAGMA foreign_key_check").isEmpty())
                assertEquals(listOf("0"), query(db, "SELECT count(*) FROM retry_budget"))
                assertEquals(listOf("0"), query(db, "SELECT count(*) FROM attempt"))
            }
        } finally { cleanup(file) }
    }

    @Test
    fun finishedAttemptTimingAndMissingBudgetParentFailClosedOnReopen() = runBlocking {
        for (missing in listOf(false, true)) {
            val file = fixture()
            try {
                assertEquals(StateStatus.READY, store(file).load().status)
                installTerminalBudget(file, "2026-11-05T00:00:01Z", missing)
                val before = file.readBytes()
                assertNotEquals(StateStatus.READY, store(file).load().status)
                assertArrayEquals(before, file.readBytes())
            } finally { cleanup(file) }
        }
    }

    @Test
    fun changedSourceOrStageBytesAndLegacyPreparationRemainBlockedAndPreserved() = runBlocking {
        for (kind in listOf("source", "stage", "legacy")) {
            val file = fixture()
            try {
                if (kind == "legacy") {
                    val spill = File(file.parentFile, "${file.name}.migration.prepare-unknown")
                    assertTrue(spill.mkdir())
                    File(spill, "unknown").writeText("synthetic")
                    val before = file.readBytes()
                    assertNotEquals(StateStatus.READY, store(file).load().status)
                    assertArrayEquals(before, file.readBytes())
                    assertEquals("synthetic", File(spill, "unknown").readText())
                } else {
                    assertEquals(StateStatus.UNAVAILABLE, store(file) { if (it == SyncStatePhase.MIGRATION_STAGE_VALIDATED) error("합성 중단") }.load().status)
                    val target = if (kind == "source") file else File(file.parentFile, "${file.name}.migration/stage")
                    target.appendBytes(byteArrayOf(1))
                    val before = target.readBytes()
                    assertNotEquals(StateStatus.READY, store(file).load().status)
                    assertArrayEquals(before, target.readBytes())
                }
            } finally { cleanup(file) }
        }
    }

    @Test
    fun realV1HotJournalCanaryPreservesRawPairAndMigratesCommittedMetadata() = runBlocking {
        val file = fixture()
        val journal = File(file.path + "-journal")
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.beginTransaction()
                try {
                    db.execSQL("UPDATE generation SET enrolled_day='2020-01-01' WHERE id=?", arrayOf(generation))
                    val requested = SyncStateCodec.encodeScopes(scopes)
                    repeat(1024) { index ->
                        val date = LocalDate.parse("2020-01-01").plusDays(index.toLong()).toString()
                        db.execSQL("INSERT INTO day VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(generation,date,scope,requested,revision,time,"INDETERMINATE",requested,time,null))
                        db.execSQL("INSERT INTO dataset VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(generation,date,scope,"DAILY",revision,time,"INDETERMINATE",1,"s".repeat(4096),time,null,null))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            lateinit var dirty: ByteArray
            lateinit var dirtyJournal: ByteArray
            class Snapshot : RuntimeException()
            try {
                val wrapped = SyncStateDatabase(file)
                wrapped.openForWrite().use { db ->
                    val before = file.readBytes()
                    wrapped.transaction(db, {
                        dirty = file.readBytes()
                        dirtyJournal = journal.readBytes()
                        assertFalse(before.contentEquals(dirty))
                        assertArrayEquals(byteArrayOf(0xd9.toByte(),0xd5.toByte(),5,0xf9.toByte(),0x20,0xa1.toByte(),0x63,0xd7.toByte()), dirtyJournal.copyOfRange(0,8))
                        throw Snapshot()
                    }) {
                        db.execSQL("PRAGMA cache_size=1")
                        db.execSQL("PRAGMA cache_spill=1")
                        db.execSQL("UPDATE dataset SET target_handle=? WHERE generation=?", arrayOf("d".repeat(4096), generation))
                    }
                }
                fail("합성 writer는 rollback해야 합니다")
            } catch (_: Snapshot) { }
            file.writeBytes(dirty)
            journal.writeBytes(dirtyJournal)
            SyncStateHotJournalRecovery.requireRollbackPair(file)
            assertEquals(StateStatus.READY, store(file).load().status)
            val raw = file.parentFile!!.listFiles()!!.single { it.name.startsWith("${file.name}.hot-journal-") }
            assertArrayEquals(dirty, File(raw, "raw/state.db").readBytes())
            assertArrayEquals(dirtyJournal, File(raw, "raw/state.db-journal").readBytes())
            assertEquals("synthetic-target", store(file).loadDay(DestinationGeneration(generation), LocalDate.parse("2026-11-05"), ReadScopeKey(scope))!!.datasets.getValue(Dataset.DAILY).targetHandle)
        } finally { cleanup(file) }
    }
}
