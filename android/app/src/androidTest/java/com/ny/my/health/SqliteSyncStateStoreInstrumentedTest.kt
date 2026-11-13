package com.ny.my.health

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 합성 메타데이터만 사용하며 각 관찰은 새 연결을 연다. */
@RunWith(AndroidJUnit4::class)
class SqliteSyncStateStoreInstrumentedTest {
    @Test
    fun bulkSnapshotsRejectCorruptUnselectedProfilesAndPreserveScopedCoverageAndPriorTimestamps() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val enrolled = f.date.minusDays(1)
            val full = DataType.entries.associateWith { ReadScope.EXPLICIT_DAY }
            val partial = mapOf(DataType.STEPS to ReadScope.SUMMARY)
            val first = f.start(generation,full,listOf(enrolled))
            val day = f.day(enrolled,full)
            f.commit(generation,first.id,day = day)
            val prior = f.confirmation(generation,day).copy(confirmedAt = Instant.parse("2026-11-05T01:00:00Z"))
            f.commit(generation,first.id,confirmation = prior)
            f.finish(generation,first)
            val second = f.start(generation,partial,listOf(enrolled))
            val partialDay = f.day(enrolled,partial)
            f.commit(generation,second.id,day = partialDay)
            val current = f.confirmation(generation,partialDay)
            f.commit(generation,second.id,confirmation = current)
            f.finish(generation,second)
            val selected = f.selected(generation,partial)
            assertEquals(enrolled,selected.coverage.scopeCheckedThrough)
            assertEquals(enrolled,selected.coverage.scopeUploadedThrough)
            assertEquals(0L,selected.coverage.scopePendingCount)
            assertEquals(enrolled,selected.coverage.checkedThrough)
            assertNull(selected.coverage.uploadedThrough)
            assertEquals(prior.confirmedAt,selected.coverage.lastUploadAt)
            assertEquals(prior,f.store().loadDay(generation,enrolled,day.scopeKey)!!.datasets.getValue(Dataset.DAILY).priorConfirmation)
            assertEquals(second.copy(finishedAt = Instant.parse("2026-11-05T00:00:02Z")),f.store().loadCurrentAttempt(generation))
            assertNull(f.store().loadCurrentAttempt(DestinationGeneration("00000000-0000-0000-0000-000000000099")))
            f.db.openForWrite().use { db ->
                db.execSQL("UPDATE day SET requested_scopes_json=? WHERE generation=? AND scope_key=?",arrayOf("{}",generation.value,day.scopeKey.value))
            }
            // header는 다른 profile의 건강함을 주장하지 않지만 broad read와 write는 이를 숨기지 않는다.
            assertEquals(second.id,f.store().loadCurrentAttempt(generation)!!.id)
            assertEquals(StateStatus.CORRUPT,f.store().loadForScope(generation,f.date,f.access(partial)).status)
            assertEquals(StateStatus.CORRUPT,f.store().load().status)
            assertEquals(CommitStatus.UNAVAILABLE,f.store().transact(generation,
                f.mutation(second.id,finish = Instant.parse("2026-11-05T00:00:03Z"))).status)
            assertEquals(CommitStatus.UNAVAILABLE,f.store().invalidate(generation).status)
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun reopeningAroundAtomicCommitsNeverConfirmsAnUnacknowledgedFileOrDate() = runBlocking {
        val f = Fixture()
        try {
            assertEquals(f.context.noBackupFilesDir.canonicalFile, f.db.databaseFile.parentFile!!.canonicalFile)
            assertEquals(StateStatus.ABSENT, f.store().load().status)
            assertFalse(f.db.databaseFile.exists())
            var generation = f.configure()
            assertFalse(f.ready().configuration!!.automatic)
            assertEquals(PrivateDestinationBinding("SYNTHETIC"), f.store().readBinding(generation))
            assertFalse(f.ready().toString().contains("accountName"))
            assertNull(f.ready().coverage.scopePendingCount)
            f.integrity()

            val enrolled = f.ready().configuration!!.enrolledDay
            f.date = f.date.plusDays(15)
            val manual = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY,DataType.HEART_RATE to ReadScope.EXPLICIT_DAY)
            val automatic = mapOf(DataType.STEPS to ReadScope.SUMMARY)
            // 완료된 앞 날짜가 LIMIT을 점유하지 않으며 두 배치 이상 진행한다.
            for (profile in listOf(manual, automatic)) {
                val visited = mutableSetOf<LocalDate>()
                repeat(4) {
                    val selected = f.selected(generation,profile)
                    val batch = SyncPlanner.plan(selected,f.date,f.access(profile))
                    val attempt = f.start(generation,profile,batch.dates)
                    for (date in batch.dates) {
                        val pending = f.day(date,profile,withFile = false)
                        f.commit(generation,attempt.id,day = pending)
                        f.commit(generation,attempt.id,day = pending.copy(phase = DayPhase.EMPTY))
                        visited += date
                    }
                    f.finish(generation,attempt)
                    f.integrity()
                }
                assertEquals(16,visited.size)
                val done = f.selected(generation,profile)
                assertEquals(0L,done.coverage.scopePendingCount)
                assertEquals(f.date.minusDays(1),done.coverage.scopeCheckedThrough)
                assertNull(done.coverage.scopeUploadedThrough)
                assertNull(done.coverage.checkedThrough)
            }
            assertEquals(32L,f.count("day"))
            val yesterday = f.date.minusDays(1)
            val attempt = f.start(generation,manual,listOf(yesterday))
            val dailyRunning = f.day(yesterday,manual)
            val running = dailyRunning.copy(datasets = dailyRunning.datasets + (Dataset.HEART_RATE to
                dailyRunning.datasets.getValue(Dataset.DAILY).copy(dataset = Dataset.HEART_RATE)))
            f.commit(generation,attempt.id,day = running)
            assertFalse(f.store().loadDay(generation,yesterday,running.scopeKey)!!.isUploaded())
            assertEquals(StateStatus.UNAVAILABLE,f.store().loadForScope(generation,f.date,f.access(automatic)).status)
            assertEquals(attempt,f.ready().attempt)
            val confirmation = f.confirmation(generation,running)
            val old = f.ready()
            val failed = f.store { throw IllegalStateException("합성 커밋 실패") }
                .transact(generation,f.mutation(attempt.id,confirmation = confirmation))
            assertEquals(CommitStatus.UNAVAILABLE,failed.status)
            assertEquals(old,f.ready())
            assertEquals(FilePhase.RUNNING,f.store().loadDay(generation,yesterday,running.scopeKey)!!.datasets.getValue(Dataset.DAILY).phase)
            try {
                f.store { throw CancellationException("합성 취소") }.transact(generation,f.mutation(attempt.id,confirmation = confirmation))
                fail("취소는 전파되어야 합니다")
            } catch (_: CancellationException) { }
            assertEquals(old,f.ready())
            f.commit(generation,attempt.id,confirmation = confirmation)
            f.commit(generation,attempt.id,confirmation = confirmation)
            assertFalse(f.store().loadDay(generation,yesterday,running.scopeKey)!!.isUploaded())
            val heartConfirmation = confirmation.copy(key = confirmation.key.copy(dataset = Dataset.HEART_RATE),targetHandle = "synthetic-heart-target")
            f.commit(generation,attempt.id,confirmation = heartConfirmation)
            assertTrue(f.store().loadDay(generation,yesterday,running.scopeKey)!!.isUploaded())
            assertEquals(CommitStatus.STALE,f.store().transact(generation,f.mutation(UUID.randomUUID().toString(),confirmation = confirmation)).status)
            assertEquals(CommitStatus.STALE,f.store().transact(generation,f.mutation(attempt.id,confirmation = confirmation.copy(key = confirmation.key.copy(revision = "b".repeat(64))))).status)
            f.finish(generation,attempt)
            assertEquals(StateStatus.READY,f.store().loadForScope(generation,f.date,f.access(automatic)).status)
            f.integrity()

            // 다른 profile의 같은 dataset은 현재 확인을 무효화하고 prior를 보존한다.
            val autoAttempt = f.start(generation,automatic,listOf(yesterday))
            val autoDay = f.day(yesterday,automatic)
            f.commit(generation,autoAttempt.id,day = autoDay)
            val invalidated = f.store().loadDay(generation,yesterday,running.scopeKey)!!
            assertEquals(FilePhase.INDETERMINATE,invalidated.datasets.getValue(Dataset.DAILY).phase)
            assertEquals(confirmation,invalidated.datasets.getValue(Dataset.DAILY).priorConfirmation)
            assertNull(invalidated.datasets.getValue(Dataset.DAILY).confirmedAt)
            assertEquals(FilePhase.CONFIRMED,invalidated.datasets.getValue(Dataset.HEART_RATE).phase)
            assertEquals(heartConfirmation.confirmedAt,invalidated.datasets.getValue(Dataset.HEART_RATE).confirmedAt)
            assertEquals(running.checkedAt,invalidated.checkedAt)
            f.commit(generation,autoAttempt.id,confirmation = f.confirmation(generation,autoDay))
            f.finish(generation,autoAttempt)
            f.integrity()

            // 정상 파일의 확인은 실패한 형제 파일의 blocker를 지우지 않는다.
            val mixedDate = yesterday.minusDays(1)
            val mixedAttempt = f.start(generation,manual,listOf(mixedDate))
            val healthy = f.day(mixedDate,manual)
            val blockedFile = healthy.datasets.getValue(Dataset.DAILY).copy(dataset = Dataset.HEART_RATE,phase = FilePhase.BLOCKED)
            val mixed = healthy.copy(datasets = healthy.datasets + (Dataset.HEART_RATE to blockedFile),blocker = BlockReason.DESTINATION_BLOCKED)
            f.commit(generation,mixedAttempt.id,day = mixed)
            f.commit(generation,mixedAttempt.id,day = mixed.copy(phase = DayPhase.BLOCKED))
            f.commit(generation,mixedAttempt.id,confirmation = f.confirmation(generation,mixed))
            val mixedReopened = f.store().loadDay(generation,mixedDate,mixed.scopeKey)!!
            assertEquals(DayPhase.BLOCKED,mixedReopened.phase)
            assertEquals(BlockReason.DESTINATION_BLOCKED,mixedReopened.blocker)
            assertEquals(blockedFile,mixedReopened.datasets.getValue(Dataset.HEART_RATE))
            assertEquals(FilePhase.CONFIRMED,mixedReopened.datasets.getValue(Dataset.DAILY).phase)
            assertFalse(mixedReopened.isUploaded())
            assertTrue(f.selected(generation,manual).coverage.scopePendingCount!! > 0)
            f.commit(generation,mixedAttempt.id,day = mixedReopened.copy(blocker = BlockReason.DESTINATION_RECONCILIATION_REQUIRED))
            f.finish(generation,mixedAttempt)
            val blockedAutoAttempt = f.start(generation,automatic,listOf(mixedDate))
            f.commit(generation,blockedAutoAttempt.id,day = f.day(mixedDate,automatic))
            val blockedOtherScope = f.store().loadDay(generation,mixedDate,mixed.scopeKey)!!
            assertEquals(DayPhase.BLOCKED,blockedOtherScope.phase)
            assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED,blockedOtherScope.blocker)
            assertEquals(blockedFile,blockedOtherScope.datasets.getValue(Dataset.HEART_RATE))
            assertEquals(FilePhase.INDETERMINATE,blockedOtherScope.datasets.getValue(Dataset.DAILY).phase)
            assertEquals(f.confirmation(generation,mixed),blockedOtherScope.datasets.getValue(Dataset.DAILY).priorConfirmation)
            assertFalse(blockedOtherScope.isUploaded())
            f.finish(generation,blockedAutoAttempt)
            f.integrity()

            // 다른 profile의 쓰기도 원격에 남은 형제 파일의 stale 상태를 지우지 않는다.
            val staleDate = yesterday.minusDays(2)
            val staleAttempt = f.start(generation,manual,listOf(staleDate))
            val seed = f.day(staleDate,manual)
            val populated = seed.copy(datasets = seed.datasets + (Dataset.HEART_RATE to seed.datasets.getValue(Dataset.DAILY).copy(dataset = Dataset.HEART_RATE)))
            f.commit(generation,staleAttempt.id,day = populated)
            val dailyPrior = f.confirmation(generation,populated)
            val heartPrior = dailyPrior.copy(key = dailyPrior.key.copy(dataset = Dataset.HEART_RATE),targetHandle = "synthetic-stale-heart")
            f.commit(generation,staleAttempt.id,confirmation = dailyPrior)
            f.commit(generation,staleAttempt.id,confirmation = heartPrior)
            val confirmedSeed = f.store().loadDay(generation,staleDate,seed.scopeKey)!!
            val newRevision = seed.revision!!.copy(digest = "b".repeat(64))
            val reread = confirmedSeed.copy(revision = newRevision,phase = DayPhase.RUNNING,
                datasets = confirmedSeed.datasets.mapValues { (dataset,file) -> file.copy(revision = newRevision,
                    phase = if(dataset == Dataset.HEART_RATE) FilePhase.REMOTE_STALE else FilePhase.RUNNING,
                    populated = dataset != Dataset.HEART_RATE,confirmedAt = null,
                    priorConfirmation = if(dataset == Dataset.HEART_RATE) heartPrior else dailyPrior) })
            f.commit(generation,staleAttempt.id,day = reread)
            f.commit(generation,staleAttempt.id,day = reread.copy(phase = DayPhase.REMOTE_STALE))
            val healthyReconfirmation = f.confirmation(generation,reread)
            f.commit(generation,staleAttempt.id,confirmation = healthyReconfirmation)
            val remoteStaleBefore = f.store().loadDay(generation,staleDate,seed.scopeKey)!!
            assertEquals(DayPhase.REMOTE_STALE,remoteStaleBefore.phase)
            f.finish(generation,staleAttempt)
            val staleAutoAttempt = f.start(generation,automatic,listOf(staleDate))
            f.commit(generation,staleAutoAttempt.id,day = f.day(staleDate,automatic))
            val remoteStaleAfter = f.store().loadDay(generation,staleDate,seed.scopeKey)!!
            assertEquals(DayPhase.REMOTE_STALE,remoteStaleAfter.phase)
            assertEquals(remoteStaleBefore.datasets.getValue(Dataset.HEART_RATE),remoteStaleAfter.datasets.getValue(Dataset.HEART_RATE))
            assertEquals(FilePhase.INDETERMINATE,remoteStaleAfter.datasets.getValue(Dataset.DAILY).phase)
            assertEquals(healthyReconfirmation,remoteStaleAfter.datasets.getValue(Dataset.DAILY).priorConfirmation)
            assertFalse(remoteStaleAfter.isUploaded())
            f.finish(generation,staleAutoAttempt)
            f.integrity()

            val beforeReplace = f.ready()
            val beforeCount = f.count("day")
            assertEquals(CommitStatus.UNAVAILABLE,f.store { error("합성 설정 실패") }.configure(generation,f.config(),PrivateDestinationBinding("SYNTHETIC")).status)
            assertEquals(beforeReplace,f.ready())
            assertEquals(beforeCount,f.count("day"))
            assertEquals(PrivateDestinationBinding("SYNTHETIC"),f.store().readBinding(generation))
            val archivedGeneration = generation
            generation = f.configure(generation)
            assertEquals(beforeCount,f.count("day"))
            assertTrue(f.ready().days.isEmpty())
            assertNull(f.ready().coverage.lastUploadAt)
            assertEquals(CommitStatus.STALE,f.store().transact(archivedGeneration,f.mutation(autoAttempt.id,confirmation = f.confirmation(archivedGeneration,autoDay))).status)
            assertEquals(2L,f.count("generation"))
            f.integrity()
            f.db.openForRead()!!.use { db ->
                val columns = mutableSetOf<String>()
                db.rawQuery("PRAGMA table_info(generation)",null).use { rows -> while(rows.moveToNext()) columns += rows.getString(1) }
                assertEquals(setOf("id","destination_handle","enrolled_day","categories_json"),columns)
            }
            val newAttempt = f.start(generation,manual,listOf(yesterday))
            f.commit(generation,newAttempt.id,day = f.day(yesterday,manual))
            val secondGeneration = generation
            val rows = f.count("day")
            generation = f.store().invalidate(generation).let { assertEquals(CommitStatus.COMMITTED,it.status); it.generation!! }
            assertEquals(3L,f.count("generation"))
            assertEquals(rows,f.count("day"))
            assertNull(f.ready().attempt)
            assertNull(f.ready().coverage.lastUploadAt)
            assertEquals(CommitStatus.STALE,f.store().transact(secondGeneration,f.mutation(newAttempt.id,day = f.day(yesterday,manual))).status)
            f.integrity()

            // 같은 expected 세대로 시작하는 두 설정 중 하나만 CAS 를 통과한다.
            val pool = Executors.newFixedThreadPool(2)
            try {
                val expected = generation
                val futures = (1..2).map { pool.submit<SyncCommit> { runBlocking { f.store().configure(expected,f.config(),PrivateDestinationBinding("SYNTHETIC")) } } }
                val statuses = futures.map { it.get().status }
                assertEquals(1,statuses.count { it == CommitStatus.COMMITTED })
                assertEquals(1,statuses.count { it == CommitStatus.STALE })
            } finally { pool.shutdownNow() }
            generation = f.ready().generation
            f.integrity()
            val foundation = HistoryDatabase(f.context,"foundation-probe-${UUID.randomUUID()}.db")
            foundation.transaction { db ->
                db.insertOrThrow("history",null,ContentValues().apply {
                    put("id",1); put("payload_format_version",1); put("payload","{\"synthetic\":true}")
                })
            }
            val history = foundation.databaseFile
            val historyBytes = history.readBytes()
            try {
                val beforeReset = f.ready()
                assertEquals(CommitStatus.UNAVAILABLE,f.store { error("합성 reset 실패") }.reset(generation).status)
                assertEquals(beforeReset,f.ready())
                assertEquals(CommitStatus.INVALID,f.store().reset(null).status)
                val reset = f.store().reset(generation)
                assertEquals(CommitStatus.COMMITTED,reset.status)
                val tombstone = f.ready()
                assertNull(tombstone.configuration)
                assertNull(f.store().readBinding(tombstone.generation))
                assertEquals(1L,f.count("generation"))
                for (table in listOf("day","dataset","attempt")) assertEquals(0L,f.count(table))
                assertArrayEquals(historyBytes,history.readBytes())
                foundation.openForRead()!!.close()
                assertEquals(CommitStatus.STALE,f.store().configure(null,f.config(),PrivateDestinationBinding("SYNTHETIC")).status)
                generation = f.configure(tombstone.generation)
                assertFalse(f.ready().configuration!!.automatic)
                f.integrity()
            } finally { history.delete() }
            assertEquals(enrolled,LocalDate.of(2026,10,19))
        } finally { f.close() }
    }

    @Test
    fun priorOnlyUploadTimestampRemainsHistoricalWithoutCurrentUploadedCoverage() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
            val date = f.date.minusDays(1)
            val attempt = f.start(generation,scopes,listOf(date))
            val running = f.day(date,scopes)
            f.commit(generation,attempt.id,day = running)
            val confirmation = f.confirmation(generation,running)
            f.commit(generation,attempt.id,confirmation = confirmation)
            val confirmed = f.store().loadDay(generation,date,running.scopeKey)!!
            val revision = running.revision!!.copy(digest = "b".repeat(64),generatedAt = confirmation.confirmedAt.plusSeconds(1))
            val next = confirmed.copy(revision = revision,phase = DayPhase.RUNNING,checkedAt = revision.generatedAt,
                datasets = confirmed.datasets.mapValues { (_,file) -> file.copy(revision = revision,phase = FilePhase.RUNNING,
                    startedAt = revision.generatedAt,confirmedAt = null,priorConfirmation = confirmation) })
            f.commit(generation,attempt.id,day = next)
            assertEquals(1L,f.count("dataset"))
            f.db.openForRead()!!.use { db ->
                db.rawQuery("SELECT count(*) FROM dataset WHERE confirmed_at IS NOT NULL",null).use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals(0L,rows.getLong(0))
                }
            }
            val reopened = f.selected(generation,scopes)
            assertEquals(confirmation.confirmedAt,reopened.coverage.lastUploadAt)
            assertEquals(confirmation.confirmedAt,f.ready().coverage.lastUploadAt)
            assertEquals(1L,reopened.coverage.scopePendingCount)
            assertNull(reopened.coverage.scopeUploadedThrough)
            assertNull(reopened.coverage.uploadedThrough)
            assertFalse(f.store().loadDay(generation,date,running.scopeKey)!!.isUploaded())
            f.integrity()
            val reset = f.store().reset(generation)
            assertEquals(CommitStatus.COMMITTED,reset.status)
            assertNull(f.ready().coverage.lastUploadAt)
            f.configure(reset.generation)
            assertNull(f.ready().coverage.lastUploadAt)
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun schemaCorruptionAndUnknownPathsRemainDistinctAndPreserveOriginals() = runBlocking {
        for (change in listOf("version","column","trigger","corrupt","directory","marker","row")) {
            val f = Fixture()
            try {
                val generation = f.configure()
                when(change) {
                    "version" -> SQLiteDatabase.openDatabase(f.db.databaseFile.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.version = 99 }
                    "column" -> SQLiteDatabase.openDatabase(f.db.databaseFile.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.execSQL("ALTER TABLE day ADD COLUMN extra TEXT") }
                    "trigger" -> SQLiteDatabase.openDatabase(f.db.databaseFile.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.execSQL("CREATE TRIGGER unexpected AFTER UPDATE ON day BEGIN SELECT 1; END") }
                    "corrupt" -> f.db.databaseFile.writeBytes(ByteArray(4096) { 0x5a })
                    "directory" -> { assertTrue(f.db.databaseFile.delete()); assertTrue(f.db.databaseFile.mkdir()) }
                    "marker" -> { assertTrue(f.db.resetDirectory.mkdir()); File(f.db.resetDirectory,"unknown").writeText("합성") }
                    "row" -> SQLiteDatabase.openDatabase(f.db.databaseFile.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.execSQL("UPDATE generation SET categories_json='[ \"STEPS\" ]'") }
                }
                val original = f.db.databaseFile.takeIf { it.isFile }?.readBytes()
                val expected = when(change) {
                    "version","column","trigger" -> StateStatus.UNSUPPORTED
                    "corrupt","row" -> StateStatus.CORRUPT
                    else -> StateStatus.UNAVAILABLE
                }
                assertEquals(change,expected,f.store().load().status)
                if (original != null) assertArrayEquals(original,f.db.databaseFile.readBytes())
                assertEquals(CommitStatus.UNAVAILABLE,f.store().invalidate(generation).status)
                if (change in setOf("version","corrupt")) {
                    assertEquals(CommitStatus.UNAVAILABLE,f.store { error("합성 교체 실패") }.reset(null).status)
                    assertTrue(f.db.resetDirectory.exists())
                    assertEquals(StateStatus.UNAVAILABLE,f.store().load().status)
                    assertArrayEquals(original,f.db.databaseFile.readBytes())
                    assertEquals(CommitStatus.COMMITTED,f.store().reset(null).status)
                    assertNull(f.ready().configuration)
                    assertFalse(f.db.resetDirectory.exists())
                    f.integrity()
                }
                if (change == "marker") {
                    assertEquals(CommitStatus.UNAVAILABLE,f.store().reset(null).status)
                    assertTrue(File(f.db.resetDirectory,"unknown").exists())
                }
            } finally { f.close() }
        }
    }

    @Test
    fun staleConfigurationOnAbsentDatabaseCreatesNeitherDatabaseNorMarker() = runBlocking {
        val f = Fixture()
        try {
            val result = f.store().configure(DestinationGeneration(UUID.randomUUID().toString()),f.config(),PrivateDestinationBinding("SYNTHETIC"))
            assertEquals(CommitStatus.STALE,result.status)
            assertNull(result.generation)
            assertFalse(f.db.databaseFile.exists())
            assertFalse(f.db.resetDirectory.exists())
            assertEquals(StateStatus.ABSENT,f.store().load().status)
        } finally { f.close() }
    }

    @Test
    fun malformedBindingAndNoncanonicalSqlScalarTypesReportCorruptWithoutChangingBytes() = runBlocking {
        for (kind in listOf("binding-json","epoch-text","epoch-real","handle-blob","retry-real","target-blob")) {
            val f = Fixture()
            try {
                val generation = f.configure()
                val scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
                val attempt = f.start(generation,scopes,listOf(f.date.minusDays(1)))
                f.commit(generation,attempt.id,day = f.day(f.date.minusDays(1),scopes))
                f.db.openForWrite().use { db ->
                    when(kind) {
                        "binding-json" -> db.execSQL("UPDATE config SET binding_payload=?",arrayOf("{"))
                        "epoch-text" -> db.execSQL("UPDATE config SET reset_epoch=?",arrayOf("invalid"))
                        "epoch-real" -> db.execSQL("UPDATE config SET reset_epoch=?",arrayOf(1.5))
                        "handle-blob" -> db.execSQL("UPDATE generation SET destination_handle=? WHERE id=?",arrayOf("synthetic-destination".toByteArray(),generation.value))
                        "retry-real" -> db.execSQL("UPDATE attempt SET retries_used=?",arrayOf(1.5))
                        "target-blob" -> db.execSQL("UPDATE dataset SET target_handle=?",arrayOf("synthetic-target".toByteArray()))
                    }
                }
                val original = f.db.databaseFile.readBytes()
                assertEquals(kind,StateStatus.CORRUPT,f.store().load().status)
                assertArrayEquals(original,f.db.databaseFile.readBytes())
            } finally { f.close() }
        }
    }

    @Test
    fun maximumResetEpochRejectsOverflowAndPreservesIntegerState() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            f.db.openForWrite().use { it.execSQL("UPDATE config SET reset_epoch=?",arrayOf(Long.MAX_VALUE)) }
            val state = f.ready()
            assertEquals(CommitStatus.INVALID,f.store().reset(generation).status)
            assertEquals(state,f.ready())
            f.db.openForRead()!!.use { db -> db.rawQuery("SELECT reset_epoch,typeof(reset_epoch) FROM config",null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals(Long.MAX_VALUE,row.getLong(0)); assertEquals("integer",row.getString(1))
            } }
            assertFalse(f.db.resetDirectory.exists())
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun configuredResetStageIsRejectedBeforeMovingOriginalDatabase() = runBlocking {
        val f = Fixture()
        val configuredStage = Fixture()
        try {
            f.configure()
            configuredStage.configure()
            val original = prepareReset(f)
            val stage = File(f.db.resetDirectory,"stage")
            configuredStage.db.databaseFile.copyTo(stage,overwrite = true)
            val stageBytes = stage.readBytes()
            assertEquals(CommitStatus.UNAVAILABLE,f.store().reset(null).status)
            assertArrayEquals(original,f.db.databaseFile.readBytes())
            assertArrayEquals(stageBytes,stage.readBytes())
            assertFalse(File(f.db.resetDirectory,"old").exists())
            assertTrue(f.db.resetDirectory.exists())
        } finally { f.close(); configuredStage.close() }
    }

    @Test
    fun ownedCompletedResetRetryAtomicallyRetiresMarkerAndPreservesQuarantinedOriginal() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val original = prepareReset(f)
            val owner = File(f.db.resetDirectory,"owner").readText()
            val old = File(f.db.resetDirectory,"old")
            val quarantine = File(f.db.databaseFile.parentFile,"${f.db.databaseFile.name}.old-$owner")
            // 새 protocol의 설치ㆍ재검증ㆍ원본 격리 후 퇴역 직전 중단 상태다.
            assertTrue(f.db.databaseFile.renameTo(old))
            assertTrue(File(f.db.resetDirectory,"stage").renameTo(f.db.databaseFile))
            assertTrue(old.renameTo(quarantine))
            File(f.db.resetDirectory,"installed").writeText(owner)
            val result = f.store().reset(null)
            assertEquals(CommitStatus.COMMITTED,result.status)
            val completed = File(f.db.databaseFile.parentFile,"${f.db.databaseFile.name}.reset.completed-$owner")
            assertFalse(f.db.resetDirectory.exists())
            assertTrue(completed.isDirectory)
            assertEquals(setOf("owner","installed"),completed.listFiles()!!.map { it.name }.toSet())
            assertEquals(owner,File(completed,"owner").readText())
            assertEquals(owner,File(completed,"installed").readText())
            assertArrayEquals(original,quarantine.readBytes())
            assertNull(f.ready().configuration)
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun unknownResetRetirementTargetAndOversizedOwnershipRemainUntouched() = runBlocking {
        for (kind in listOf("retirement-target","owner-size")) {
            val f = Fixture()
            try {
                f.configure()
                val original = prepareReset(f)
                val ownerFile = File(f.db.resetDirectory,"owner")
                val owner = ownerFile.readText()
                val unknown = if(kind == "retirement-target") File(f.db.databaseFile.parentFile,
                    "${f.db.databaseFile.name}.reset.completed-$owner") else ownerFile
                val unknownBytes = ByteArray(if(kind == "owner-size") 4096 else 16) { 0x5a }
                unknown.writeBytes(unknownBytes)
                assertEquals(CommitStatus.UNAVAILABLE,f.store().reset(null).status)
                assertArrayEquals(original,f.db.databaseFile.readBytes())
                assertArrayEquals(unknownBytes,unknown.readBytes())
                assertTrue(f.db.resetDirectory.exists())
                assertTrue(File(f.db.resetDirectory,"stage").exists())
            } finally { f.close() }
        }
    }

    @Test
    fun brokenQuarantineSidecarSymlinkBlocksResetAndPreservesBothArtifacts() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            val original = prepareReset(f)
            val owner = File(f.db.resetDirectory,"owner").readText()
            val old = File(f.db.resetDirectory,"old")
            val quarantine = File(f.db.databaseFile.parentFile,"${f.db.databaseFile.name}.old-$owner")
            assertTrue(f.db.databaseFile.renameTo(old))
            assertTrue(File(f.db.resetDirectory,"stage").renameTo(f.db.databaseFile))
            assertTrue(old.renameTo(quarantine))
            File(f.db.resetDirectory,"installed").writeText(owner)
            val retiredSidecar = File(f.db.resetDirectory,"old-wal")
            val sidecarBytes = ByteArray(16) { 0x5a }
            retiredSidecar.writeBytes(sidecarBytes)
            val destination = File(quarantine.path + "-wal")
            val missingTarget = File(f.db.databaseFile.parentFile,"${f.db.databaseFile.name}.missing-synthetic")
            java.nio.file.Files.createSymbolicLink(destination.toPath(),missingTarget.toPath())
            assertTrue(java.nio.file.Files.isSymbolicLink(destination.toPath()))
            assertFalse(destination.exists())
            assertEquals(CommitStatus.UNAVAILABLE,f.store().reset(null).status)
            assertTrue(java.nio.file.Files.isSymbolicLink(destination.toPath()))
            assertEquals(missingTarget.toPath(),java.nio.file.Files.readSymbolicLink(destination.toPath()))
            assertArrayEquals(sidecarBytes,retiredSidecar.readBytes())
            assertArrayEquals(original,quarantine.readBytes())
            assertTrue(f.db.resetDirectory.exists())
            assertEquals(StateStatus.UNAVAILABLE,f.store().load().status)
        } finally { f.close() }
    }

    @Test
    fun generationCapacityRejectsConfigurationAndRollsBackActivePointerAndHistory() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            val scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
            val date = f.date.minusDays(1)
            val attempt = f.start(generation,scopes,listOf(date))
            val day = f.day(date,scopes)
            f.commit(generation,attempt.id,day = day)
            f.db.openForWrite().use { db -> f.db.transaction(db) {
                repeat(9999) { db.insertOrThrow("generation",null,ContentValues().apply {
                    put("id",UUID.randomUUID().toString()); putNull("destination_handle"); putNull("enrolled_day"); put("categories_json","[]")
                }) }
            } }
            assertEquals(10000L,f.count("generation"))
            val before = f.ready()
            assertEquals(CommitStatus.UNAVAILABLE,f.store().configure(generation,f.config(),PrivateDestinationBinding("SYNTHETIC")).status)
            assertEquals(10000L,f.count("generation"))
            assertEquals(before,f.ready())
            assertEquals(day,f.store().loadDay(generation,date,day.scopeKey))
            assertEquals(PrivateDestinationBinding("SYNTHETIC"),f.store().readBinding(generation))
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun resetFromSyntheticAutomaticOnReopensWithAutomaticOffAndNoConfiguration() = runBlocking {
        val f = Fixture()
        try {
            val generation = f.configure()
            f.db.openForWrite().use { it.execSQL("UPDATE config SET automatic=1") }
            assertTrue(f.ready().configuration!!.automatic)
            val result = f.store().reset(generation)
            assertEquals(CommitStatus.COMMITTED,result.status)
            assertNull(f.ready().configuration)
            f.db.openForRead()!!.use { db -> db.rawQuery("SELECT automatic FROM config",null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals(0,row.getInt(0))
            } }
            f.configure(result.generation)
            assertFalse(f.ready().configuration!!.automatic)
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun initialConfigurationBeforeCommitFailurePreservesAbsentAndAllowsFreshRetry() = runBlocking {
        val f = Fixture()
        try {
            assertEquals(CommitStatus.UNAVAILABLE,f.store { error("합성 최초 커밋 실패") }
                .configure(null,f.config(),PrivateDestinationBinding("SYNTHETIC")).status)
            assertEquals(StateStatus.ABSENT,f.store().load().status)
            f.configure()
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun initialConfigurationBeforeCommitCancellationPreservesAbsentAndAllowsFreshRetry() = runBlocking {
        val f = Fixture()
        try {
            try {
                f.store { throw CancellationException("합성 최초 커밋 취소") }
                    .configure(null,f.config(),PrivateDestinationBinding("SYNTHETIC"))
                fail("최초 커밋 취소는 전파되어야 합니다")
            } catch (_: CancellationException) { }
            assertEquals(StateStatus.ABSENT,f.store().load().status)
            f.configure()
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun interruptionAfterInitialSchemaPreservesAbsentAndAllowsFreshRetry() = runBlocking {
        val f = Fixture()
        try {
            var observed = false
            val result = f.store(observer = { phase ->
                if (phase == SyncStatePhase.INITIAL_SCHEMA_READY) { observed = true; error("합성 초기 schema 중단") }
            }).configure(null,f.config(),PrivateDestinationBinding("SYNTHETIC"))
            assertTrue(observed)
            assertEquals(CommitStatus.UNAVAILABLE,result.status)
            assertEquals(StateStatus.ABSENT,f.store().load().status)
            f.configure()
            f.integrity()
        } finally { f.close() }
    }

    @Test
    fun malformedBindingRejectsConfigurationWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("binding","configure")
    }

    @Test
    fun malformedBindingRejectsInvalidationWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("binding","invalidate")
    }

    @Test
    fun malformedBindingRejectsTransactionWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("binding","transact")
    }

    @Test
    fun malformedBindingRejectsNormalResetWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("binding","reset")
    }

    @Test
    fun malformedDayRejectsConfigurationWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("day","configure")
    }

    @Test
    fun malformedDayRejectsInvalidationWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("day","invalidate")
    }

    @Test
    fun malformedDayRejectsTransactionWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("day","transact")
    }

    @Test
    fun malformedDayRejectsNormalResetWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("day","reset")
    }

    @Test
    fun malformedAttemptRejectsConfigurationWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("attempt","configure")
    }

    @Test
    fun malformedAttemptRejectsInvalidationWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("attempt","invalidate")
    }

    @Test
    fun malformedAttemptRejectsTransactionWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("attempt","transact")
    }

    @Test
    fun malformedAttemptRejectsNormalResetWithoutChangingOriginalBytes() = runBlocking {
        corruptOrdinaryWritePreservesOriginal("attempt","reset")
    }

    private suspend fun corruptOrdinaryWritePreservesOriginal(kind: String,operation: String) {
        val f = Fixture()
        try {
            val generation = f.configure()
            val scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
            val attempt = f.start(generation,scopes,listOf(f.date.minusDays(1)))
            f.commit(generation,attempt.id,day = f.day(f.date.minusDays(1),scopes))
            f.db.openForWrite().use { db ->
                when(kind) {
                    "binding" -> db.execSQL("UPDATE config SET binding_payload=?",arrayOf("{"))
                    "day" -> db.execSQL("UPDATE day SET scopes_json=?",arrayOf("{"))
                    "attempt" -> db.execSQL("UPDATE attempt SET pending_triggers_json=?",arrayOf("{"))
                }
            }
            val original = f.db.databaseFile.readBytes()
            assertEquals(StateStatus.CORRUPT,f.store().load().status)
            val result = when(operation) {
                "configure" -> f.store().configure(generation,f.config(),PrivateDestinationBinding("SYNTHETIC"))
                "invalidate" -> f.store().invalidate(generation)
                "transact" -> f.store().transact(generation,SyncMutation(attempt.id,null,null,null,setOf(SyncTrigger.CATCH_UP),null))
                "reset" -> f.store().reset(generation)
                else -> error("지원하지 않는 합성 mutation")
            }
            assertEquals(CommitStatus.UNAVAILABLE,result.status)
            assertArrayEquals(original,f.db.databaseFile.readBytes())
            assertEquals(StateStatus.CORRUPT,f.store().load().status)
        } finally { f.close() }
    }

    @Test
    fun interruptionBeforeResetOwnerAllowsExplicitResetRetry() = runBlocking {
        interruptedResetPreparationAllowsRetry(SyncStatePhase.RESET_OWNER_BEFORE)
    }

    @Test
    fun interruptionDuringResetOwnerWriteAllowsExplicitResetRetry() = runBlocking {
        interruptedResetPreparationAllowsRetry(SyncStatePhase.RESET_OWNER_WRITING)
    }

    @Test
    fun interruptionAfterResetOwnerWriteAllowsExplicitResetRetry() = runBlocking {
        interruptedResetPreparationAllowsRetry(SyncStatePhase.RESET_OWNER_WRITTEN)
    }

    @Test
    fun interruptionAfterResetStageSchemaAllowsExplicitResetRetry() = runBlocking {
        interruptedResetPreparationAllowsRetry(SyncStatePhase.RESET_STAGE_SCHEMA_READY)
    }

    private suspend fun interruptedResetPreparationAllowsRetry(stop: SyncStatePhase) {
        val f = Fixture()
        try {
            f.configure()
            f.db.openForWrite().use { it.version = 99 }
            val original = f.db.databaseFile.readBytes()
            var observed = false
            val interrupted = f.store(observer = { phase ->
                if (phase == stop) { observed = true; error("합성 reset 준비 중단") }
            }).reset(null)
            assertTrue(observed)
            assertEquals(CommitStatus.UNAVAILABLE,interrupted.status)
            assertArrayEquals(original,f.db.databaseFile.readBytes())
            assertEquals(CommitStatus.COMMITTED,f.store().reset(null).status)
            assertNull(f.ready().configuration)
            f.integrity()
        } finally { f.close() }
    }

    private suspend fun prepareReset(f: Fixture): ByteArray {
        f.db.openForWrite().use { it.version = 99 }
        val original = f.db.databaseFile.readBytes()
        assertEquals(CommitStatus.UNAVAILABLE,f.store { error("합성 설치 전 중단") }.reset(null).status)
        assertTrue(f.db.resetDirectory.exists())
        assertTrue(File(f.db.resetDirectory,"stage").exists())
        assertArrayEquals(original,f.db.databaseFile.readBytes())
        return original
    }

    @Test
    fun validatedHotJournalsRestoreCommittedStateThroughEveryStoreEntryWithoutFabricatingConfirmations() = runBlocking {
        for (entry in listOf("load","alias-load","scoped","day","header","binding","invalidate")) {
            val h = HotFixture()
            try {
                h.seed()
                h.dirty()
                when (entry) {
                    "load" -> h.assertRestored(h.f.store().load())
                    "alias-load" -> h.assertRestored(SqliteSyncStateStore(SyncStateDatabase(
                        File(h.f.db.databaseFile.parentFile,"./${h.f.db.databaseFile.name}")),{ h.f.date }).load())
                    "scoped" -> {
                        val read = h.f.store().loadForScope(h.generation,h.f.date,h.f.access(h.scopes))
                        h.assertRestored(read)
                        assertEquals(h.f.date.minusDays(1),read.state!!.coverage.scopeUploadedThrough)
                    }
                    "day" -> assertEquals(h.confirmed,h.f.store().loadDay(h.generation,h.f.date.minusDays(1),h.key)!!
                        .datasets.getValue(Dataset.DAILY).confirmedAt)
                    "header" -> assertNull(h.f.store().loadCurrentAttempt(h.generation))
                    "binding" -> assertEquals(PrivateDestinationBinding("SYNTHETIC"),h.f.store().readBinding(h.generation))
                    "invalidate" -> assertEquals(CommitStatus.COMMITTED,h.f.store().invalidate(h.generation).status)
                }
                h.assertRaw()
                assertEquals(1,h.artifacts(".hot-journal-").size)
                val saved = h.raw().let { File(it,"raw/state.db").readBytes() }
                if (entry != "invalidate") {
                    h.assertRestored(h.f.store().load())
                    assertEquals(CommitStatus.COMMITTED,h.f.store().invalidate(h.generation).status)
                }
                assertArrayEquals(saved,File(h.raw(),"raw/state.db").readBytes())
                assertTrue(h.artifacts(".hot-work-").isEmpty())
            } finally { h.close() }
        }
        val normal = Fixture()
        try {
            normal.configure()
            val bytes = normal.db.databaseFile.readBytes()
            normal.ready()
            assertArrayEquals(bytes,normal.db.databaseFile.readBytes())
            assertTrue(normal.db.databaseFile.parentFile!!.listFiles()!!.none {
                it.name.startsWith(normal.db.databaseFile.name + ".hot-") })
        } finally { normal.close() }
    }

    @Test
    fun unsupportedOrMalformedRecoveredPairsPreserveOriginalBytesWithoutAWritableOriginalOpen() = runBlocking {
        for (kind in listOf("version","schema","binding","unselected","attempt")) {
            val h = HotFixture()
            try {
                h.seed()
                h.dirty { db -> when (kind) {
                    "version" -> db.version = 99
                    "schema" -> db.execSQL("CREATE TABLE unexpected (value TEXT)")
                    "binding" -> db.execSQL("UPDATE config SET binding_payload='{}'")
                    "unselected" -> db.execSQL("UPDATE day SET requested_scopes_json='{}' WHERE date=?",
                        arrayOf(h.enrolled.toString()))
                    "attempt" -> db.execSQL("INSERT INTO attempt VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(
                        UUID.randomUUID().toString(),h.generation.value,"b".repeat(64),SyncStateCodec.encodeScopes(h.scopes),
                        SyncTrigger.MANUAL.name,h.started.toString(),h.started.toString(),null,
                        "[\"${h.enrolled}\"]","a".repeat(64),0,"[]"))
                } }
                var rolled = 0
                val store = h.f.store({ if (it == SyncStatePhase.HOT_ORIGINAL_ROLLED_BACK) rolled++ })
                val result = store.load()
                assertEquals(if (kind in setOf("version","schema")) StateStatus.UNSUPPORTED else StateStatus.CORRUPT,result.status)
                assertEquals(0,rolled)
                h.assertOriginal()
                h.assertRaw()
                // narrow header도 최초 recovery에서는 비선택 profile 손상을 숨기지 않는다.
                assertTrue(runCatching { runBlocking { h.f.store().loadCurrentAttempt(h.generation) } }.isFailure)
                h.assertOriginal()
                assertEquals(1,h.artifacts(".hot-journal-").size)
            } finally { h.close() }
        }
    }

    @Test
    fun unsafeRollbackSidecarsAndChangedSourcePairsNeverFollowExternalPathsOrDeleteUnknownFiles() = runBlocking {
        for (kind in listOf("main-directory","orphan-journal","orphan-wal","orphan-shm","footer","wal","shm","journal-link","journal-hardlink","db-link","db-hardlink",
            "short-journal","short-db","raw-unknown","raw-manifest","raw-owner","raw-order","raw-size","raw-hash","raw-link","work-unknown","changed")) {
            val h = HotFixture()
            val sentinel = File(h.f.db.databaseFile.parentFile,"synthetic-sentinel-${UUID.randomUUID()}")
            try {
                if (kind == "main-directory") {
                    val main = h.f.db.databaseFile
                    assertTrue(main.mkdir())
                    val unknown = File(main,"unknown")
                    val bytes = "합성 알 수 없는 디렉터리 내용".toByteArray(Charsets.UTF_8)
                    unknown.writeBytes(bytes)
                    var phaseObserved = false
                    val store = h.f.store({ phaseObserved = true })
                    assertEquals(StateStatus.UNAVAILABLE,store.load().status)
                    assertEquals(CommitStatus.UNAVAILABLE,store.configure(null,h.f.config(),
                        PrivateDestinationBinding("SYNTHETIC")).status)
                    assertTrue(main.isDirectory)
                    assertEquals(listOf("unknown"),main.list()!!.toList())
                    assertArrayEquals(bytes,unknown.readBytes())
                    assertFalse(phaseObserved)
                    assertTrue(h.artifacts(".init-").isEmpty())
                    assertTrue(h.artifacts(".hot-").isEmpty())
                    continue
                }
                if (kind.startsWith("orphan-")) {
                    val main = h.f.db.databaseFile
                    val sidecar = File(main.path + "-" + kind.removePrefix("orphan-"))
                    val bytes = "합성 미소유 보조 파일".toByteArray(Charsets.UTF_8)
                    sidecar.writeBytes(bytes)
                    assertEquals(StateStatus.UNAVAILABLE,h.f.store().load().status)
                    // 유효한 어제 등록 입력으로 실제 파일 admission까지 도달한다.
                    val result = h.f.store().configure(null,h.f.config(),PrivateDestinationBinding("SYNTHETIC"))
                    assertEquals(CommitStatus.UNAVAILABLE,result.status)
                    assertNull(result.generation)
                    assertFalse(main.exists())
                    assertTrue(h.artifacts(".init-").isEmpty())
                    assertFalse(h.f.db.resetDirectory.exists())
                    assertTrue(h.artifacts(".hot-").isEmpty())
                    assertArrayEquals(bytes,sidecar.readBytes())
                    continue
                }
                h.seed(); h.dirty()
                val db = h.f.db.databaseFile
                val journal = File(db.path + "-journal")
                sentinel.writeText("합성 외부 sentinel")
                val sentinelBytes = sentinel.readBytes()
                val unknown = mutableListOf<File>()
                var expectedDb = h.dirtyDb
                var expectedJournal = h.dirtyJournal
                when (kind) {
                    "footer" -> {
                        val path = sentinel.path.toByteArray(Charsets.UTF_8)
                        val tail = java.nio.ByteBuffer.allocate(8).putInt(path.size)
                            .putInt(path.sumOf { it.toInt() and 255 }).array()
                        journal.appendBytes(path + tail + h.magic); expectedJournal = journal.readBytes()
                    }
                    "wal","shm" -> File(db.path + "-" + kind).also { it.writeBytes(byteArrayOf(1)); unknown += it }
                    "journal-link","db-link" -> {
                        val target = if (kind == "journal-link") journal else db
                        assertTrue(target.delete()); java.nio.file.Files.createSymbolicLink(target.toPath(),sentinel.toPath())
                    }
                    "journal-hardlink","db-hardlink" -> {
                        val target = if (kind == "journal-hardlink") journal else db
                        val alias = File(db.parentFile,db.name + ".hardlink")
                        val before = android.system.Os.lstat(target.path)
                        assertEquals(1L,before.st_nlink)
                        try {
                            java.nio.file.Files.createLink(alias.toPath(),target.toPath())
                        } catch (_: java.nio.file.AccessDeniedException) {
                            // 플랫폼 생성 거부와 stat 경계 검증을 실제 hardlink admission 증거로 합치지 않는다.
                            assertFalse(java.nio.file.Files.exists(alias.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                            val current = android.system.Os.lstat(target.path)
                            assertEquals(before.st_dev,current.st_dev)
                            assertEquals(before.st_ino,current.st_ino)
                            assertEquals(1L,current.st_nlink)
                            h.assertOriginal()
                            assertArrayEquals(sentinelBytes,sentinel.readBytes())
                            assertTrue(h.artifacts(".hot-").isEmpty())
                            SyncStateHotJournalRecovery.regular(current)
                            val linked = android.system.StructStat(current.st_dev,current.st_ino,current.st_mode,2L,
                                current.st_uid,current.st_gid,current.st_rdev,current.st_size,current.st_atime,
                                current.st_mtime,current.st_ctime,current.st_blksize,current.st_blocks)
                            try {
                                SyncStateHotJournalRecovery.regular(linked)
                                fail("여러 링크의 stat은 거부해야 합니다")
                            } catch (_: IllegalArgumentException) { }
                            continue
                        }
                        val linked = android.system.Os.lstat(target.path)
                        val aliasStat = android.system.Os.lstat(alias.path)
                        assertEquals(before.st_dev,linked.st_dev)
                        assertEquals(before.st_ino,linked.st_ino)
                        assertEquals(linked.st_dev,aliasStat.st_dev)
                        assertEquals(linked.st_ino,aliasStat.st_ino)
                        assertEquals(2L,linked.st_nlink)
                        assertEquals(2L,aliasStat.st_nlink)
                    }
                    "short-journal" -> { journal.writeBytes(byteArrayOf(1)); expectedJournal = journal.readBytes() }
                    "short-db" -> { db.writeBytes(h.dirtyDb.copyOf(40)); expectedDb = db.readBytes() }
                }
                var rolled = 0
                val observer: (SyncStatePhase) -> Unit = { phase ->
                    if (phase == SyncStatePhase.HOT_ORIGINAL_ROLLED_BACK) rolled++
                    if (phase == SyncStatePhase.HOT_RAW_SAVED) when (kind) {
                        "raw-unknown" -> File(h.raw(),"unknown").also { it.writeText("합성 unknown"); unknown += it }
                        "raw-manifest" -> File(h.raw(),"manifest").also { it.appendText("x".repeat(513)); unknown += it }
                        "raw-owner","raw-order","raw-size","raw-hash" -> File(h.raw(),"manifest").also { manifest ->
                            val lines = manifest.readLines().toMutableList()
                            when (kind) {
                                "raw-owner" -> lines[1] = "owner=invalid"
                                "raw-order" -> { val value = lines[2]; lines[2] = lines[3]; lines[3] = value }
                                "raw-size" -> lines[4] = "db_size=0" + lines[4].substringAfter('=')
                                "raw-hash" -> lines[2] = "db_sha256=" + "0".repeat(64)
                            }
                            manifest.writeText(lines.joinToString("\n") + "\n"); unknown += manifest
                        }
                        "raw-link" -> {
                            val target = File(h.raw(),"raw/state.db-journal")
                            assertTrue(target.delete()); java.nio.file.Files.createSymbolicLink(target.toPath(),sentinel.toPath())
                            unknown += target
                        }
                    }
                    if (phase == SyncStatePhase.HOT_CANARY_VALIDATED) when (kind) {
                        "work-unknown" -> File(h.artifacts(".hot-work-").single(),"unknown").also {
                            it.writeText("합성 unknown"); unknown += it }
                        "changed" -> {
                            val changed = db.readBytes(); changed[60] = (changed[60].toInt() xor 1).toByte()
                            db.writeBytes(changed); expectedDb = changed
                        }
                    }
                }
                val result = h.f.store(observer).load()
                if (kind == "db-link") assertEquals(StateStatus.UNAVAILABLE,result.status)
                else assertNotEquals(StateStatus.READY,result.status)
                assertEquals(0,rolled)
                assertArrayEquals(sentinelBytes,sentinel.readBytes())
                if (kind !in setOf("db-link")) assertArrayEquals(expectedDb,db.readBytes())
                if (kind !in setOf("journal-link")) assertArrayEquals(expectedJournal,journal.readBytes())
                unknown.forEach { assertTrue(java.nio.file.Files.exists(it.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) }
                if (kind == "work-unknown") {
                    val work = h.artifacts(".hot-work-").single()
                    assertTrue(File(work,"owner").exists()); assertTrue(File(work,"state.db").exists())
                }
            } finally {
                // 합성 fixture가 직접 만든 링크만 해제하며 대상 sentinel은 별도로 확인한다.
                for (entry in listOf(h.f.db.databaseFile,File(h.f.db.databaseFile.path + "-journal")))
                    if (java.nio.file.Files.isSymbolicLink(entry.toPath())) java.nio.file.Files.delete(entry.toPath())
                File(h.f.db.databaseFile.parentFile,h.f.db.databaseFile.name + ".hardlink").delete()
                h.close(); sentinel.delete()
            }
        }
    }

    @Test
    fun interruptedRollbackPreparationRetainsRawEvidenceAndSafelyRetriesWithoutInventingSuccess() = runBlocking {
        val phases = listOf(SyncStatePhase.HOT_RAW_SAVED,SyncStatePhase.HOT_CANARY_VALIDATED,
            SyncStatePhase.HOT_BEFORE_ORIGINAL,SyncStatePhase.HOT_ORIGINAL_ROLLED_BACK)
        for (phase in phases) for (cancel in listOf(false,true)) {
            val h = HotFixture()
            try {
                h.seed(); h.dirty()
                var stops = 0
                val interrupted = h.f.store({ observed -> if (observed == phase && stops++ == 0) {
                    if (cancel) throw CancellationException("합성 취소") else error("합성 중단")
                } })
                if (cancel) {
                    try { interrupted.load(); fail("취소가 전파되어야 합니다") } catch (_: CancellationException) { }
                } else assertNotEquals(StateStatus.READY,interrupted.load().status)
                h.assertRaw()
                if (phase != SyncStatePhase.HOT_ORIGINAL_ROLLED_BACK) h.assertOriginal()
                // 같은 Store와 새 Store 모두 실제 readonly admission을 다시 수행한다.
                h.assertRestored(interrupted.load())
                h.assertRestored(h.f.store().load())
                assertEquals(1,h.artifacts(".hot-journal-").size)
                assertTrue(h.artifacts(".hot-work-").isEmpty())
            } finally { h.close() }
        }
        val h = HotFixture()
        try {
            h.seed(); h.dirty()
            synchronized(h.f.db.pathLock) {
                try {
                    h.f.db.openAdmittedRead({ error("부분 복사는 native 검증에 도달하면 안 됩니다") }, {
                        val partial = h.artifacts(".hot-journal.prepare-").flatMap { directory ->
                            File(directory,"raw").listFiles()?.toList().orEmpty() }
                        if (partial.any { it.length() > 0 }) throw CancellationException("합성 부분 복사 취소")
                    })
                    fail("부분 복사 취소가 필요합니다")
                } catch (_: CancellationException) { }
            }
            h.assertOriginal()
            assertTrue(h.artifacts(".hot-journal.prepare-").isNotEmpty())
            assertTrue(h.artifacts(".hot-journal-").isEmpty())
            h.assertRestored(h.f.store().load())
            h.assertRaw()
        } finally { h.close() }
        val interrupted = HotFixture()
        try {
            interrupted.seed(); interrupted.dirty()
            val cancelled = CancellationException("합성 unknown 작업 취소")
            var unknown: File? = null
            val store = interrupted.f.store({ phase ->
                if (phase == SyncStatePhase.HOT_CANARY_VALIDATED) {
                    unknown = File(interrupted.artifacts(".hot-work-").single(),"unknown").also {
                        it.writeText("합성 unknown 보존")
                    }
                    throw cancelled
                }
            })
            try {
                store.load()
                fail("최초 취소가 전파되어야 합니다")
            } catch (error: CancellationException) {
                assertSame(cancelled,error)
                assertEquals(1,error.suppressed.size)
                assertEquals("동기화 복구 작업 정리 실패",error.suppressed.single().message)
                assertNull(error.suppressed.single().cause)
            }
            interrupted.assertOriginal()
            interrupted.assertRaw()
            assertEquals("합성 unknown 보존",checkNotNull(unknown).readText())
            val work = interrupted.artifacts(".hot-work-").single()
            assertTrue(File(work,"owner").exists())
            assertTrue(File(work,"state.db").exists())
        } finally { interrupted.close() }
    }

    /** 실제 kill의 대체 증거가 아닌, 검증된 dirty pair fault 분할용 제한된 합성 fixture. */
    private class HotFixture {
        val f = Fixture()
        val started: Instant = Instant.parse("2026-11-05T00:00:00Z")
        val confirmed: Instant = started.plusSeconds(1)
        val enrolled: LocalDate = f.date.minusDays(4096)
        val scopes = DataType.entries.associateWith { ReadScope.EXPLICIT_DAY }
        private var seededGeneration: DestinationGeneration? = null
        val generation: DestinationGeneration get() = requireNotNull(seededGeneration)
        private var seededKey: ReadScopeKey? = null
        val key: ReadScopeKey get() = requireNotNull(seededKey)
        lateinit var dirtyDb: ByteArray
        lateinit var dirtyJournal: ByteArray
        val magic = byteArrayOf(0xd9.toByte(),0xd5.toByte(),5,0xf9.toByte(),0x20,0xa1.toByte(),0x63,0xd7.toByte())
        private val handle = "s".repeat(4096)
        suspend fun seed() {
            val date = f.date
            f.date = enrolled.plusDays(1)
            seededGeneration = f.configure()
            f.date = date
            val config = f.ready().configuration!!
            seededKey = SyncPlanner.scopeKey(config,f.access(scopes))!!
            val encoded = SyncStateCodec.encodeScopes(scopes)
            f.db.openForWrite().use { db -> f.db.transaction(db) {
                repeat(4096) { offset ->
                    val day = enrolled.plusDays(offset.toLong())
                    val revision = RevisionKey("a".repeat(64),started)
                    val leaf = DatasetState(Dataset.DAILY,revision,FilePhase.CONFIRMED,true,handle,started,confirmed,null)
                    assertTrue(DayState(day,key,scopes,revision,DayPhase.CONFIRMED,scopes,started,mapOf(Dataset.DAILY to leaf),null)
                        .isValid(generation,enrolled,date))
                    db.execSQL("INSERT INTO day VALUES (?,?,?,?,?,?,?,?,?,?)",arrayOf(generation.value,day.toString(),key.value,
                        encoded,revision.digest,started.toString(),DayPhase.CONFIRMED.name,encoded,started.toString(),null))
                    db.execSQL("INSERT INTO dataset VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(generation.value,day.toString(),
                        key.value,Dataset.DAILY.name,revision.digest,started.toString(),FilePhase.CONFIRMED.name,1,
                        handle,started.toString(),confirmed.toString(),null))
                }
            } }
            assertRestored(f.store().load())
        }
        fun dirty(malformed: (SQLiteDatabase) -> Unit = {}) {
            val file = f.db.databaseFile
            val journal = File(file.path + "-journal")
            class Copied : RuntimeException()
            try {
                f.db.openForWrite().use { db ->
                    malformed(db)
                    val before = file.readBytes()
                    f.db.transaction(db,{
                        dirtyDb = file.readBytes(); dirtyJournal = journal.readBytes()
                        android.util.Log.i("HotWriterFixture",
                            "main_before_bytes=${before.size} main_during_bytes=${dirtyDb.size} " +
                                "journal_during_bytes=${dirtyJournal.size} main_changed=${!before.contentEquals(dirtyDb)} " +
                                "journal_magic_valid=${magic.contentEquals(dirtyJournal.copyOfRange(0,8))}")
                        assertFalse(before.contentEquals(dirtyDb))
                        assertArrayEquals(magic,dirtyJournal.copyOfRange(0,8))
                        assertTrue(dirtyJournal.size > 512)
                        throw Copied()
                    }) {
                        // transaction에 고정된 실제 writer의 cache/spill만 준비하고 관측한다.
                        db.execSQL("PRAGMA cache_size=1")
                        db.execSQL("PRAGMA cache_spill=1")
                        fun numericPragma(sql: String): Long = db.rawQuery(sql,null).use {
                            check(it.moveToFirst())
                            it.getLong(0)
                        }
                        val pageSize = numericPragma("PRAGMA page_size")
                        val pageCount = numericPragma("PRAGMA page_count")
                        val cacheSize = numericPragma("PRAGMA cache_size")
                        val cacheSpill = numericPragma("PRAGMA cache_spill")
                        val journalMode = db.rawQuery("PRAGMA journal_mode",null).use {
                            check(it.moveToFirst())
                            when (it.getString(0)) {
                                "delete" -> "DELETE"
                                "truncate" -> "TRUNCATE"
                                "persist" -> "PERSIST"
                                "memory" -> "MEMORY"
                                "wal" -> "WAL"
                                "off" -> "OFF"
                                else -> "UNKNOWN"
                            }
                        }
                        val synchronous = numericPragma("PRAGMA synchronous")
                        val rowCount = numericPragma("SELECT count(*) FROM dataset")
                        android.util.Log.i("HotWriterFixture",
                            "writer_in_transaction=${db.inTransaction()} page_size=$pageSize page_count=$pageCount " +
                                "cache_size=$cacheSize cache_spill=$cacheSpill journal_mode=$journalMode synchronous=$synchronous")
                        db.execSQL("UPDATE dataset SET target_handle=?",arrayOf("d".repeat(4096)))
                        val updatedRowCount = numericPragma("SELECT changes()")
                        android.util.Log.i("HotWriterFixture","row_count=$rowCount updated_row_count=$updatedRowCount")
                    }
                }
                fail("원래 writer는 rollback해야 합니다")
            } catch (_: Copied) { }
            file.writeBytes(dirtyDb); journal.writeBytes(dirtyJournal)
            SyncStateHotJournalRecovery.requireRollbackPair(file)
            try {
                f.db.openForRead()?.close()
                fail("합성 dirty pair는 실제 typed readonly 오류가 필요합니다")
            } catch (_: android.database.sqlite.SQLiteReadOnlyDatabaseException) { }
            assertOriginal()
        }
        fun assertRestored(read: SyncStateRead) {
            assertEquals(StateStatus.READY,read.status)
            assertEquals(generation,read.state!!.generation)
            assertEquals(confirmed,read.state!!.coverage.lastUploadAt)
            assertEquals(f.date.minusDays(1),read.state!!.coverage.uploadedThrough)
            assertNull(read.state!!.attempt)
        }
        fun artifacts(suffix: String) = f.db.databaseFile.parentFile!!.listFiles()!!.filter {
            it.name.startsWith(f.db.databaseFile.name + suffix) }
        fun raw() = artifacts(".hot-journal-").single()
        fun assertOriginal() {
            assertArrayEquals(dirtyDb,f.db.databaseFile.readBytes())
            assertArrayEquals(dirtyJournal,File(f.db.databaseFile.path + "-journal").readBytes())
        }
        fun assertRaw() {
            SyncStateHotJournalRecovery.ownedRawFiles(f.db.databaseFile,raw())
            assertArrayEquals(dirtyDb,File(raw(),"raw/state.db").readBytes())
            assertArrayEquals(dirtyJournal,File(raw(),"raw/state.db-journal").readBytes())
        }
        fun close() {
            // 이 fixture가 만든 정확한 basename 아래의 합성 파일만 테스트 종료 때 정리한다.
            for (directory in artifacts(".hot-")) {
                directory.walkBottomUp().forEach { entry ->
                    if (java.nio.file.Files.isSymbolicLink(entry.toPath())) java.nio.file.Files.delete(entry.toPath())
                    else entry.delete()
                }
            }
            f.close()
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = SyncStateDatabase(File(context.noBackupFilesDir,"sync-state-probe-${UUID.randomUUID()}.db"))
        var date: LocalDate = LocalDate.of(2026,10,20)
        private val time = Instant.parse("2026-11-05T00:00:00Z")
        fun store(observer: (SyncStatePhase) -> Unit = {}, hook: () -> Unit = {}) =
            SqliteSyncStateStore(SyncStateDatabase(db.databaseFile,observer),{ date },hook)
        fun config() = SyncConfiguration("synthetic-destination",date.minusDays(1),true,DataType.entries.toSet())
        suspend fun configure(expected: DestinationGeneration? = null): DestinationGeneration = store().configure(expected,config(),PrivateDestinationBinding("SYNTHETIC")).let {
            assertEquals(CommitStatus.COMMITTED,it.status); it.generation!!
        }
        suspend fun ready(): SyncState = store().load().let { assertEquals(StateStatus.READY,it.status); it.state!! }
        fun access(scopes: Map<DataType,ReadScope>) = ReadAccess(true,scopes.keys,scopes)
        suspend fun selected(generation: DestinationGeneration, scopes: Map<DataType,ReadScope>): SyncState =
            store().loadForScope(generation,date,access(scopes)).let { assertEquals(StateStatus.READY,it.status); it.state!! }
        suspend fun start(generation: DestinationGeneration, scopes: Map<DataType,ReadScope>, dates: List<LocalDate>): AttemptState {
            val key = SyncPlanner.scopeKey(config(),access(scopes))!!
            val attempt = AttemptState(UUID.randomUUID().toString(),key,scopes,SyncTrigger.MANUAL,time,time,null,dates,"a".repeat(64),0,emptySet())
            assertEquals(CommitStatus.COMMITTED,store().transact(generation,mutation(attempt.id,start = attempt)).status)
            assertEquals(attempt,ready().attempt)
            return attempt
        }
        fun day(date: LocalDate, scopes: Map<DataType,ReadScope>, withFile: Boolean = true): DayState {
            val key = SyncPlanner.scopeKey(config(),access(scopes))!!
            val revision = RevisionKey((if(withFile) "a" else "c").repeat(64),time)
            val files = if(withFile) mapOf(Dataset.DAILY to DatasetState(Dataset.DAILY,revision,FilePhase.RUNNING,true,null,time,null,null)) else emptyMap()
            return DayState(date,key,scopes,revision,if(withFile) DayPhase.RUNNING else DayPhase.PENDING,scopes,time,files,null)
        }
        fun confirmation(generation: DestinationGeneration, day: DayState) = FileConfirmation(FileKey(generation,day.date,day.scopeKey,Dataset.DAILY,day.revision!!.digest),"synthetic-target",time.plusSeconds(1))
        fun mutation(id: String, start: AttemptState? = null, day: DayState? = null, confirmation: FileConfirmation? = null, finish: Instant? = null) =
            SyncMutation(id,start,day,confirmation,null,finish)
        suspend fun commit(generation: DestinationGeneration,id: String,day: DayState? = null,confirmation: FileConfirmation? = null) {
            assertEquals(CommitStatus.COMMITTED,store().transact(generation,mutation(id,day = day,confirmation = confirmation)).status)
        }
        suspend fun finish(generation: DestinationGeneration,attempt: AttemptState) {
            assertEquals(CommitStatus.COMMITTED,store().transact(generation,mutation(attempt.id,finish = time.plusSeconds(2))).status)
        }
        fun count(table: String): Long = db.openForRead()!!.use { database ->
            database.rawQuery("SELECT count(*) FROM $table",null).use { assertTrue(it.moveToFirst()); it.getLong(0) }
        }
        fun integrity() {
            db.openForWrite().use { database ->
                database.rawQuery("PRAGMA foreign_key_check",null).use { assertFalse(it.moveToFirst()) }
                database.rawQuery("PRAGMA foreign_keys",null).use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
                database.rawQuery("PRAGMA journal_mode",null).use { assertTrue(it.moveToFirst()); assertEquals("delete",it.getString(0)) }
                database.rawQuery("PRAGMA synchronous",null).use { assertTrue(it.moveToFirst()); assertEquals(2,it.getInt(0)) }
            }
        }
        fun close() {
            if(db.databaseFile.isDirectory) db.databaseFile.deleteRecursively() else SQLiteDatabase.deleteDatabase(db.databaseFile)
            db.resetDirectory.deleteRecursively()
            db.databaseFile.parentFile!!.listFiles()!!.filter {
                it.name.startsWith("${db.databaseFile.name}.old-") || it.name.startsWith("${db.databaseFile.name}.reset.completed-") ||
                    it.name.startsWith("${db.databaseFile.name}.init-") || it.name.startsWith("${db.databaseFile.name}.reset.prepare-")
            }.forEach { if(it.isDirectory) it.deleteRecursively() else it.delete() }
        }
    }
}
