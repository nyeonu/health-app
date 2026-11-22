package com.ny.my.health

import android.database.sqlite.SQLiteDatabase
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 SQLite를 매번 다시 열며 합성 식별자만 사용한다. */
@RunWith(AndroidJUnit4::class)
class ManualDriveStoreInstrumentedTest {
    @Test
    fun allActiveScopeGenericUriAndPriorHandlesSurviveReopenWhileStaleCorruptAndOrphanReadsFailClosed() = runBlocking {
        val f = Fixture()
        try {
            val archived = f.configure()
            f.insert(archived,f.day(f.explicit,documentHandle("archived target"),documentHandle("archived prior")))
            val active = f.configure(archived)
            val current = documentHandle("current 공백 /?#%:&=+ target")
            val prior = documentHandle("prior 이전 /?#%:&=+ target")
            val otherCurrent = documentHandle("summary 요약 /?#%:&=+ target")
            val boundaryId = "boundary 경계 /?#%:&=+ "
            val boundaryPadding = 4096 - documentHandle(boundaryId).toByteArray(Charsets.UTF_8).size
            val boundary = documentHandle(boundaryId + "x".repeat(boundaryPadding))
            assertEquals(4096,boundary.toByteArray(Charsets.UTF_8).size)
            assertEquals(boundary,SyncStateCodec.decodeHandle(boundary))
            val oversized = documentHandle(boundaryId + "x".repeat(boundaryPadding + 1))
            assertEquals(4097,oversized.toByteArray(Charsets.UTF_8).size)
            f.reject { SyncStateCodec.decodeHandle(oversized) }
            val first = f.day(f.explicit,current,prior)
            val second = f.day(f.summary,otherCurrent,boundary)
            f.insert(active,first)
            f.insert(active,second)
            f.insert(active,first.copy(date = f.date.minusDays(1),datasets = first.datasets.mapValues { (_,file) ->
                file.copy(targetHandle = documentHandle("other date"),priorConfirmation = null)
            }))
            // 쓰기 연결은 모두 닫혔으며 새 store가 DB를 다시 열어 두 범위의 문자열을 읽는다.
            val expected = mapOf(Dataset.DAILY to setOf(current,prior,otherCurrent,boundary))
            val reopened = f.store().loadKnownTargets(active,f.day)
            assertEquals(expected,reopened)
            for (handle in expected.getValue(Dataset.DAILY)) assertArrayEquals(handle.toByteArray(Charsets.UTF_8),
                reopened.getValue(Dataset.DAILY).single { it == handle }.toByteArray(Charsets.UTF_8))
            assertEquals(reopened,f.store().loadKnownDriveTargets(active,f.day))
            assertEquals(emptyMap<Dataset,Set<String>>(),f.store().loadKnownTargets(active,f.date.minusDays(3)))
            assertEquals(f.store().loadKnownTargets(active,f.date.minusDays(3)),
                f.store().loadKnownDriveTargets(active,f.date.minusDays(3)))
            val conflicting = documentHandle("conflicting 충돌 /?#%:&=+ target")
            f.sql("UPDATE dataset SET target_handle=? WHERE generation=? AND date=? AND scope_key=?",
                arrayOf(conflicting,active.value,f.day.toString(),second.scopeKey.value))
            // 이 집합의 충돌을 uploader가 차단할 수 있도록 어느 범위의 ID도 버리지 않는다.
            assertEquals(setOf(current,prior,conflicting,boundary),
                f.store().loadKnownTargets(active,f.day).getValue(Dataset.DAILY))
            assertEquals(f.store().loadKnownTargets(active,f.day),f.store().loadKnownDriveTargets(active,f.day))
            f.reject { f.store().loadKnownTargets(archived,f.day) }
            f.reject { f.store().loadKnownDriveTargets(archived,f.day) }
            assertEquals(CommitStatus.STALE,f.store().reopenManualBlockers(archived).status)
        } finally { f.close() }

        val duplicate = Fixture()
        try {
            val generation = duplicate.configure()
            duplicate.insert(generation,duplicate.day(duplicate.explicit,"same-target","prior-target"))
            duplicate.insert(generation,duplicate.day(duplicate.summary,"same-target","prior-target"))
            val expected = mapOf(Dataset.DAILY to setOf("same-target","prior-target"))
            assertEquals(expected,duplicate.store().loadKnownTargets(generation,duplicate.day))
            assertEquals(expected,duplicate.store().loadKnownDriveTargets(generation,duplicate.day))
        } finally { duplicate.close() }

        for (kind in listOf("malformed-profile","orphan","unsupported","unavailable",
            "raw-whitespace-handle","corrupt-prior")) {
            val broken = Fixture()
            try {
                val generation = broken.configure()
                broken.insert(generation,broken.day(broken.explicit,documentHandle("safe target"),null))
                broken.insert(generation,broken.day(broken.summary,documentHandle("other target"),documentHandle("prior target")))
                when (kind) {
                    "malformed-profile" -> broken.sql("UPDATE day SET requested_scopes_json='{}' WHERE scope_key=?",
                        arrayOf(broken.key(broken.summary).value))
                    "orphan" -> {
                        val schemaBefore = broken.db.openForRead().use { broken.schemaNames(checkNotNull(it)) }
                        SQLiteDatabase.openDatabase(broken.db.databaseFile.path,null,
                            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                            db.execSQL("PRAGMA foreign_keys=OFF")
                            db.execSQL("DELETE FROM day WHERE scope_key=?",arrayOf(broken.key(broken.summary).value))
                            assertEquals(schemaBefore,broken.schemaNames(db))
                            // 날짜 행만 지워졌고 해당 파일 행이 실제 orphan으로 남았는지 먼저 입증한다.
                            db.rawQuery("SELECT count(*) FROM dataset AS file LEFT JOIN day AS parent " +
                                "ON parent.generation=file.generation AND parent.date=file.date AND parent.scope_key=file.scope_key " +
                                "WHERE parent.generation IS NULL AND file.generation=? AND file.scope_key=?",
                                arrayOf(generation.value,broken.key(broken.summary).value)).use { rows ->
                                assertTrue(rows.moveToFirst())
                                assertEquals(1L,rows.getLong(0))
                            }
                        }
                    }
                    "unsupported" -> broken.db.openForWrite().use { it.version = 3 }
                    "unavailable" -> assertTrue(broken.db.resetDirectory.mkdir())
                    "raw-whitespace-handle" -> {
                        val invalid = "raw opaque 공백 /?#%:&=+"
                        broken.reject { SyncStateCodec.decodeHandle(invalid) }
                        broken.sql("UPDATE dataset SET target_handle=? WHERE scope_key=?",
                            arrayOf(invalid,broken.key(broken.summary).value))
                    }
                    "corrupt-prior" -> broken.sql("UPDATE dataset SET prior_confirmation_json=? WHERE scope_key=?",
                        arrayOf("{}",broken.key(broken.summary).value))
                }
                val bytes = broken.db.databaseFile.readBytes()
                broken.reject { broken.store().loadKnownTargets(generation,broken.day) }
                broken.reject { broken.store().loadKnownDriveTargets(generation,broken.day) }
                assertEquals(CommitStatus.UNAVAILABLE,broken.store().reopenManualBlockers(generation).status)
                assertArrayEquals(bytes,broken.db.databaseFile.readBytes())
            } finally { broken.close() }
        }
    }

    private fun documentHandle(opaqueId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("com.ny.my.health.synthetic","tree 합성 공백 /?#%:&=+"),
            opaqueId).toString()

    @Test
    fun explicitManualRecoveryReopensEligibleBlockersWithoutDeletingFileUncertaintyOrRemoteStale() = runBlocking {
        val eligible = listOf(BlockReason.DESTINATION_BLOCKED,BlockReason.DESTINATION_RECONCILIATION_REQUIRED,
            BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED,BlockReason.RETRY_EXHAUSTED)
        for (reason in eligible) {
            val f = Fixture()
            try {
                val generation = f.configure()
                val blocked = f.day(f.explicit,null,"prior-target").copy(phase = DayPhase.BLOCKED,blocker = reason)
                f.insert(generation,blocked)
                val otherScope = f.day(f.summary,"other-current","other-prior").copy(phase = DayPhase.BLOCKED,blocker = reason)
                f.insert(generation,otherScope)
                val remote = blocked.copy(date = f.date.minusDays(1),phase = DayPhase.REMOTE_STALE,
                    blocker = BlockReason.DESTINATION_BLOCKED,datasets = blocked.datasets.mapValues { (_,file) ->
                        file.copy(phase = FilePhase.REMOTE_STALE,populated = false,targetHandle = "stale-target",priorConfirmation = null)
                    })
                f.insert(generation,remote)
                val finished = f.attempt(generation,finished = true)
                val before = f.selected(generation,f.explicit)
                val beforeOther = f.selected(generation,f.summary)
                assertTrue(SyncPlanner.plan(before,f.date,f.access(f.explicit)).dates.isEmpty())
                assertEquals(CommitStatus.UNAVAILABLE,f.store { error("합성 commit 실패") }.reopenManualBlockers(generation).status)
                assertEquals(before,f.selected(generation,f.explicit))
                assertEquals(beforeOther,f.selected(generation,f.summary))
                assertEquals(CommitStatus.COMMITTED,f.store().reopenManualBlockers(generation).status)
                for ((scopes,original) in listOf(f.explicit to blocked,f.summary to otherScope)) {
                    val state = f.selected(generation,scopes)
                    assertTrue(state.isValid())
                    assertEquals(original.copy(phase = DayPhase.PENDING,blocker = null),state.days[f.day])
                    assertTrue(f.day in SyncPlanner.plan(state,f.date,f.access(scopes)).dates)
                }
                val restored = f.selected(generation,f.explicit)
                assertEquals(remote.copy(blocker = null),restored.days[remote.date])
                assertFalse(remote.date in SyncPlanner.plan(restored,f.date,f.access(f.explicit)).dates)
                assertEquals(finished,f.store().loadCurrentAttempt(generation))
                assertEquals(setOf(Dataset.DAILY),f.store().loadUnresolvedCreates(generation,f.day))
                assertEquals(mapOf(Dataset.DAILY to setOf("prior-target","other-current","other-prior")),
                    f.store().loadKnownDriveTargets(generation,f.day))
            } finally { f.close() }
        }

        for (reason in listOf(BlockReason.SOURCE_ACCESS_REQUIRED,BlockReason.HISTORY_ACCESS_REQUIRED)) {
            val f = Fixture()
            try {
                val generation = f.configure()
                val blocked = f.day(f.explicit,null,null).copy(phase = DayPhase.BLOCKED,blocker = reason)
                f.insert(generation,blocked)
                assertEquals(CommitStatus.COMMITTED,f.store().reopenManualBlockers(generation).status)
                assertEquals(blocked,f.store().loadDay(generation,f.day,blocked.scopeKey))
            } finally { f.close() }
        }
        val running = Fixture()
        try {
            val generation = running.configure()
            val blocked = running.day(running.explicit,null,"prior-target").copy(
                phase = DayPhase.BLOCKED,blocker = BlockReason.DESTINATION_BLOCKED)
            running.insert(generation,blocked)
            val attempt = running.attempt(generation,finished = false)
            val before = running.selected(generation,running.explicit)
            assertEquals(CommitStatus.INVALID,running.store().reopenManualBlockers(generation).status)
            assertEquals(before,running.selected(generation,running.explicit))
            assertEquals(attempt,running.store().loadCurrentAttempt(generation))
        } finally { running.close() }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = SyncStateDatabase(File(context.noBackupFilesDir,"manual-drive-store-${UUID.randomUUID()}.db"))
        val date = LocalDate.of(2026,11,5)
        val day = date.minusDays(2)
        private val time = Instant.parse("2026-11-05T00:00:00Z")
        val explicit = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
        val summary = mapOf(DataType.STEPS to ReadScope.SUMMARY)
        private var activeGeneration = DestinationGeneration("00000000-0000-0000-0000-000000000001")
        private val config = SyncConfiguration("synthetic-destination",day,false,DataType.entries.toSet())
        fun store(hook: () -> Unit = {}) = SqliteSyncStateStore(SyncStateDatabase(db.databaseFile),{ date },hook)
        suspend fun configure(expected: DestinationGeneration? = null): DestinationGeneration {
            // 등록 계약은 어제이므로 등록 직후 시계를 하루 진행한 상태를 합성한다.
            val registering = SqliteSyncStateStore(SyncStateDatabase(db.databaseFile),{ day.plusDays(1) })
            val result = registering.configure(expected,config,PrivateDestinationBinding("SYNTHETIC"))
            assertEquals(CommitStatus.COMMITTED,result.status)
            activeGeneration = result.generation!!
            return activeGeneration
        }
        fun access(scopes: Map<DataType,ReadScope>) = ReadAccess(true,scopes.keys,scopes)
        fun key(scopes: Map<DataType,ReadScope>) = SyncPlanner.scopeKey(config,access(scopes))!!
        fun day(scopes: Map<DataType,ReadScope>,target: String?,prior: String?): DayState {
            val revision = RevisionKey("a".repeat(64),time)
            val confirmation = prior?.let { FileConfirmation(FileKey(activeGeneration,
                day,key(scopes),Dataset.DAILY,"b".repeat(64)),it,time) }
            val file = DatasetState(Dataset.DAILY,revision,FilePhase.INDETERMINATE,true,target,time,null,confirmation)
            return DayState(day,key(scopes),scopes,revision,DayPhase.INDETERMINATE,scopes,time,mapOf(Dataset.DAILY to file),null)
        }
        fun insert(generation: DestinationGeneration,source: DayState) {
            val value = source.copy(datasets = source.datasets.mapValues { (_,file) -> file.copy(
                priorConfirmation = file.priorConfirmation?.let { it.copy(key = it.key.copy(generation = generation,date = source.date)) }) })
            assertTrue(value.isValid(generation,config.enrolledDay,date))
            db.openForWrite().use { sql -> db.transaction(sql) {
                sql.execSQL("INSERT INTO day VALUES (?,?,?,?,?,?,?,?,?,?)",arrayOf(generation.value,value.date.toString(),value.scopeKey.value,
                    SyncStateCodec.encodeScopes(value.requestedScopes),value.revision?.digest,value.revision?.generatedAt?.toString(),
                    value.phase.name,SyncStateCodec.encodeScopes(value.scopes),value.checkedAt?.toString(),value.blocker?.name))
                for (file in value.datasets.values) sql.execSQL("INSERT INTO dataset VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(
                    generation.value,value.date.toString(),value.scopeKey.value,file.dataset.name,file.revision.digest,file.revision.generatedAt.toString(),
                    file.phase.name,if(file.populated) 1 else 0,file.targetHandle,file.startedAt?.toString(),file.confirmedAt?.toString(),
                    file.priorConfirmation?.let(SyncStateCodec::encodeConfirmation)))
            } }
        }
        suspend fun selected(generation: DestinationGeneration,scopes: Map<DataType,ReadScope>): SyncState {
            val result = store().loadForScope(generation,date,access(scopes))
            assertEquals(StateStatus.READY,result.status)
            return result.state!!
        }
        fun schemaNames(sql: SQLiteDatabase): Set<String> = sql.rawQuery("SELECT name FROM sqlite_master",null).use { rows ->
            val names = mutableSetOf<String>()
            while (rows.moveToNext()) names += rows.getString(0)
            names
        }
        fun sql(statement: String,args: Array<String>) = db.openForWrite().use { it.execSQL(statement,args) }
        suspend fun attempt(generation: DestinationGeneration,finished: Boolean): AttemptState {
            val attempt = AttemptState(UUID.randomUUID().toString(),key(explicit),explicit,SyncTrigger.MANUAL,time,time,null,
                listOf(day),"c".repeat(64),0,emptySet())
            assertEquals(CommitStatus.COMMITTED,store().transact(generation,SyncMutation(attempt.id,attempt,null,null,null,null)).status)
            if (!finished) return attempt
            assertEquals(CommitStatus.COMMITTED,store().transact(generation,SyncMutation(attempt.id,null,null,null,null,time.plusSeconds(1))).status)
            return attempt.copy(finishedAt = time.plusSeconds(1))
        }
        suspend fun reject(block: suspend () -> Unit) {
            try { block(); fail("검증되지 않은 저장소 조회는 거부해야 합니다") }
            catch (error: Exception) { assertFalse(error is kotlinx.coroutines.CancellationException) }
        }
        fun close() {
            SQLiteDatabase.deleteDatabase(db.databaseFile)
            db.resetDirectory.deleteRecursively()
        }
    }
}
