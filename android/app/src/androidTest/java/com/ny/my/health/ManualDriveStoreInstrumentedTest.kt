package com.ny.my.health

import android.database.sqlite.SQLiteDatabase
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
    fun allActiveScopeKnownAndPriorHandlesSurviveReopenAndConflictingTargetsBlock() = runBlocking {
        val f = Fixture()
        try {
            val archived = f.configure()
            f.insert(archived,f.day(f.explicit,"archived-target","archived-prior"))
            val active = f.configure(archived)
            val first = f.day(f.explicit,"same-target","prior-target")
            val second = f.day(f.summary,"same-target","prior-target")
            f.insert(active,first)
            f.insert(active,second)
            f.insert(active,first.copy(date = f.date.minusDays(1),datasets = first.datasets.mapValues { (_,file) ->
                file.copy(targetHandle = "other-date",priorConfirmation = null)
            }))
            assertEquals(mapOf(Dataset.DAILY to setOf("same-target","prior-target")),
                f.store().loadKnownDriveTargets(active,f.day))
            assertEquals(emptyMap<Dataset,Set<String>>(),f.store().loadKnownDriveTargets(active,f.date.minusDays(3)))
            f.sql("UPDATE dataset SET target_handle=? WHERE generation=? AND date=? AND scope_key=?",
                arrayOf("conflicting-target",active.value,f.day.toString(),second.scopeKey.value))
            // 이 집합의 충돌을 uploader가 차단할 수 있도록 어느 범위의 ID도 버리지 않는다.
            assertEquals(setOf("same-target","prior-target","conflicting-target"),
                f.store().loadKnownDriveTargets(active,f.day).getValue(Dataset.DAILY))
            f.reject { f.store().loadKnownDriveTargets(archived,f.day) }
            assertEquals(CommitStatus.STALE,f.store().reopenManualBlockers(archived).status)
        } finally { f.close() }

        for (kind in listOf("malformed-profile","orphan","unsupported","unavailable")) {
            val broken = Fixture()
            try {
                val generation = broken.configure()
                broken.insert(generation,broken.day(broken.explicit,"safe-target",null))
                broken.insert(generation,broken.day(broken.summary,"other-target",null))
                when (kind) {
                    "malformed-profile" -> broken.sql("UPDATE day SET requested_scopes_json='{}' WHERE scope_key=?",
                        arrayOf(broken.key(broken.summary).value))
                    "orphan" -> SQLiteDatabase.openDatabase(broken.db.databaseFile.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                        db.execSQL("PRAGMA foreign_keys=OFF")
                        db.execSQL("DELETE FROM day WHERE scope_key=?",arrayOf(broken.key(broken.summary).value))
                    }
                    "unsupported" -> broken.db.openForWrite().use { it.version = 2 }
                    "unavailable" -> assertTrue(broken.db.resetDirectory.mkdir())
                }
                val bytes = broken.db.databaseFile.readBytes()
                broken.reject { broken.store().loadKnownDriveTargets(generation,broken.day) }
                assertEquals(CommitStatus.UNAVAILABLE,broken.store().reopenManualBlockers(generation).status)
                assertArrayEquals(bytes,broken.db.databaseFile.readBytes())
            } finally { broken.close() }
        }
    }

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
