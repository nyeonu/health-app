package com.ny.my.health

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ny.my.health.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 엔진과 매번 재연결하는 SQLite 저장소로 원격·로컬 확인 경계를 검증한다. */
@RunWith(AndroidJUnit4::class)
class SafJournalInstrumentedTest {
    @Test fun safEncodedOpaqueDocumentUrisRetainTargetResolverIdentityAfterSQLiteReopen() = runBlocking {
        val f = JournalFixture()
        try {
            for (id in listOf(SyntheticDocumentsProvider.OPAQUE_SPACE_ID, SyntheticDocumentsProvider.OPAQUE_UNICODE_ID,
                SyntheticDocumentsProvider.OPAQUE_RESERVED_ID)) {
                f.prepare()
                f.saf.command(SyntheticDocumentsProvider.Control.OPAQUE_IDS)
                val attempt = f.start()
                val revision = RevisionKey("a".repeat(64), f.saf.time)
                val day = DayState(f.saf.date, attempt.scopeKey, f.scopes, revision, DayPhase.RUNNING, f.scopes,
                    f.saf.time, mapOf(Dataset.DAILY to DatasetState(Dataset.DAILY, revision, FilePhase.RUNNING,
                        true, null, f.saf.time, null, null)), null)
                f.commit(SyncMutation(attempt.id, null, day, null, null, null))
                val uri = DocumentsContract.buildDocumentUriUsingTree(f.saf.proof.treeUri, id)
                f.commit(SyncMutation(attempt.id, null, null, FileConfirmation(FileKey(f.saf.generation, f.saf.date,
                    attempt.scopeKey, Dataset.DAILY, revision.digest), uri.toString(), f.saf.time), null, null))
                f.finish(attempt)
                val handles = f.store().loadKnownTargets(f.saf.generation, f.saf.date).getValue(Dataset.DAILY)
                assertTrue("encoded URI 무손실 재열기", uri.toString() in handles)
                val reopened = f.store().loadDay(f.saf.generation, f.saf.date, attempt.scopeKey)!!.datasets.getValue(Dataset.DAILY).targetHandle!!
                val target = Uri.parse(reopened)
                val identity = f.saf.resolver.query(target, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use {
                    assertTrue("실제 대상 조회", it.moveToFirst()); val same = it.getString(0) == id
                    assertFalse("대상 하나", it.moveToNext()); same
                }
                assertTrue("opaque ID 동일", identity)
                assertTrue("실제 target resolver bytes 동일", f.saf.resolver.openInputStream(target)!!.use {
                    it.readBytes().contentEquals(SyntheticDocumentsProvider.seededBytes()) })
                // SQLite의 handle 존재만으로 고정 dataset/date 경로를 우회하지 못한다.
                val before = f.saf.counts()
                f.saf.expectFailure { f.saf.destination(f.store()::loadKnownTargets).reconcile(listOf(f.saf.planned("synthetic\n"))) }
                f.saf.assertNoMutation(before)
            }
        } finally { f.close() }
    }

    @Test fun afterPartialFailureCancellationOrUnknownCreateReconciliationRetriesOnlyPendingFiles() = runBlocking {
        val f = JournalFixture()
        try {
            // 첫 파일의 영속 확인 뒤 두 번째 readback 실패는 건강한 파일을 다시 쓰지 않는다.
            f.prepare()
            f.scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY, DataType.EXERCISE to ReadScope.EXPLICIT_DAY)
            f.workouts = true
            f.assertPartialFailureSetup()
            f.failAfterDaily = true
            var partialAttempt = f.start()
            assertEquals(BatchStatus.BLOCKED, f.run(partialAttempt).status)
            assertEquals(FilePhase.CONFIRMED, f.row(partialAttempt).datasets.getValue(Dataset.DAILY).phase)
            assertEquals(FilePhase.INDETERMINATE, f.row(partialAttempt).datasets.getValue(Dataset.WORKOUTS).phase)
            f.failAfterDaily = false
            f.saf.command(SyntheticDocumentsProvider.Control.WRONG_RETURN_IDENTITY)
            val beforePartialRetry = f.saf.counts()
            partialAttempt = f.start()
            assertEquals(BatchStatus.COMPLETED, f.run(partialAttempt).status)
            f.saf.assertNoMutation(beforePartialRetry)

            // directory 생성 후 응답불명: 다른 scope의 null handle도 absence create를 막는다.
            f.prepare()
            f.saf.command(SyntheticDocumentsProvider.Control.CREATE_AFTER_EFFECT_FAILURE)
            var attempt = f.start()
            assertEquals(BatchStatus.BLOCKED, f.run(attempt).status)
            assertNull(f.row(attempt).datasets.getValue(Dataset.DAILY).targetHandle)
            val beforeUnknown = f.saf.counts()
            f.saf.command(SyntheticDocumentsProvider.Control.RELEASE_BLOCK)
            // RELEASE_BLOCK은 실패 outcome을 지우지 않는다. 조회는 정상이며 재생성은 금지된다.
            f.scopes = mapOf(DataType.STEPS to ReadScope.SUMMARY)
            attempt = f.start()
            assertEquals(BatchStatus.BLOCKED, f.run(attempt).status)
            f.saf.assertNoMutation(beforeUnknown)
            assertEquals(BlockReason.DESTINATION_RECONCILIATION_REQUIRED, f.row(attempt).blocker)

            // 원격 성공 뒤 SQLite 확인 커밋 실패는 재열기 후 같은 bytes를 채택한다.
            f.prepare()
            attempt = f.start()
            f.failConfirmation = true
            assertEquals(BatchStatus.BLOCKED, f.run(attempt).status)
            assertTrue("영속 확인 전 성공 금지", f.row(attempt).datasets.getValue(Dataset.DAILY).phase != FilePhase.CONFIRMED)
            f.failConfirmation = false
            val beforeAdoption = f.saf.counts()
            f.finish(attempt)
            attempt = f.start()
            assertEquals(BatchStatus.COMPLETED, f.run(attempt).status)
            f.saf.assertNoMutation(beforeAdoption)
            val handle = f.row(attempt).datasets.getValue(Dataset.DAILY).targetHandle!!
            assertTrue("실제 URI 확인", handle.startsWith("content:"))

            // 소스 revision 변경과 EMPTY 왕복도 동일 원격 대상을 보존한다.
            f.steps = 2
            attempt = f.start()
            assertEquals(BatchStatus.COMPLETED, f.run(attempt).status)
            assertTrue("revision 변경 동일 URI", handle == f.row(attempt).datasets.getValue(Dataset.DAILY).targetHandle)
            f.steps = null
            attempt = f.start()
            val beforeEmpty = f.saf.counts()
            assertEquals(BatchStatus.BLOCKED, f.run(attempt).status)
            assertEquals(DayPhase.REMOTE_STALE, f.row(attempt).phase)
            f.saf.assertNoMutation(beforeEmpty)
            f.steps = 3
            attempt = f.start()
            assertEquals(BatchStatus.COMPLETED, f.run(attempt).status)
            assertTrue("EMPTY 왕복 동일 URI", handle == f.row(attempt).datasets.getValue(Dataset.DAILY).targetHandle)

            // 두 번의 콜백 취소 뒤에도 새 원격 파일은 만들지 않는다.
            for (iteration in 1..2) {
                f.steps = 3L + iteration
                attempt = f.start()
                val original = CancellationException("합성 journal 취소")
                f.cancelConfirmation = original
                try { f.run(attempt); fail("취소 전파 필요") }
                catch (cancel: CancellationException) { assertTrue("원래 취소 유지", cancel === original) }
                assertTrue("취소된 확인 미완료", f.row(attempt).datasets.getValue(Dataset.DAILY).phase != FilePhase.CONFIRMED)
                f.cancelConfirmation = null
                f.finish(attempt)
                val beforeRetry = f.saf.counts()
                attempt = f.start()
                assertEquals(BatchStatus.COMPLETED, f.run(attempt).status)
                f.saf.assertNoMutation(beforeRetry)
            }
        } finally { f.close() }
    }
}

private class JournalFixture {
    val saf = SafDestinationFixture()
    private val file = File(saf.context.noBackupFilesDir, "saf-journal-${UUID.randomUUID()}.db")
    var scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY)
    var steps: Long? = 1
    var workouts = false
    var failAfterDaily = false
    var failConfirmation = false
    var cancelConfirmation: CancellationException? = null
    private var rejectCommit = false
    fun store() = SqliteSyncStateStore(SyncStateDatabase(file), { saf.date.plusDays(1) }, {
        if (rejectCommit) throw IllegalStateException("합成 확인 커밋 실패")
    })
    private fun config() = SyncConfiguration("synthetic-saf", saf.date, false, setOf(DataType.STEPS, DataType.EXERCISE))
    suspend fun prepare() {
        SQLiteDatabase.deleteDatabase(file)
        saf.prepare()
        scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY); steps = 1
        failConfirmation = false; cancelConfirmation = null; rejectCommit = false; workouts = false; failAfterDaily = false
        val configured = store().configure(null, config(), PrivateDestinationBinding("LOCAL", treeUri = saf.proof.treeUri.toString()))
        assertEquals("실제 설정 커밋", CommitStatus.COMMITTED, configured.status)
        saf.generation = configured.generation!!
    }
    suspend fun start(): AttemptState {
        val access = ReadAccess(true, scopes.keys, scopes)
        val attempt = AttemptState(UUID.randomUUID().toString(), SyncPlanner.scopeKey(config(), access)!!, scopes,
            SyncTrigger.MANUAL, saf.time, saf.time, null, listOf(saf.date), "a".repeat(64), 0, emptySet())
        commit(SyncMutation(attempt.id, attempt, null, null, null, null))
        return attempt
    }
    suspend fun commit(mutation: SyncMutation) {
        assertEquals("실제 journal 커밋", CommitStatus.COMMITTED, store().transact(saf.generation, mutation).status)
    }
    suspend fun finish(attempt: AttemptState) {
        val current = store().loadCurrentAttempt(saf.generation)!!
        if (current.finishedAt == null) commit(SyncMutation(attempt.id, null, null, null, null, saf.time.plusSeconds(1)))
    }
    suspend fun row(attempt: AttemptState) = store().loadDay(saf.generation, saf.date, attempt.scopeKey)!!
    suspend fun run(attempt: AttemptState): BatchOutcome {
        val actual = saf.destination(store()::loadKnownTargets)
        val destination = object : StorageDestination {
            override suspend fun reconcile(files: List<PlannedFile>) = actual.reconcile(files)
            override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) {
                assertEquals("생성·쓰기 전 durable RUNNING", DayPhase.RUNNING, row(attempt).phase)
                assertTrue("pending 모두 RUNNING", files.all { row(attempt).datasets.getValue(it.key.dataset).phase == FilePhase.RUNNING })
                actual.writePending(files) {
                    cancelConfirmation?.let { cancel -> throw cancel }
                    rejectCommit = failConfirmation
                    try { onConfirmed(it) } finally { rejectCommit = false }
                    if (failAfterDaily && it.key.dataset == Dataset.DAILY)
                        saf.command(SyntheticDocumentsProvider.Control.READBACK_FAILURE)
                }
            }
        }
        val reader = object : ScopedHealthReader {
            override suspend fun access(trigger: SyncTrigger) = ReadAccess(true, scopes.keys, scopes)
            override suspend fun readScoped(date: java.time.LocalDate, requestedScopes: Map<DataType, ReadScope>) = snapshot(date)
        }
        return DestinationSync(store(), reader, destination, saf.clock).run(SyncTrigger.MANUAL, saf.generation, attempt.id)
    }
    private fun snapshot(date: java.time.LocalDate) = ScopedSnapshot(
        HealthSnapshot(date, steps, emptyList(), emptyList(), emptyList(),
            if (workouts) listOf(ExerciseSession("synthetic-exercise", "synthetic-source",
                DayWindow.start(saf.date).plusSeconds(3600), DayWindow.start(saf.date).plusSeconds(7200), 1))
            else emptyList(), scopes.keys), scopes, emptySet())
    fun assertPartialFailureSetup() {
        val source = snapshot(saf.date)
        val workout = source.snapshot.exerciseSessions.single()
        assertTrue("부분 실패 준비: 운동 시작은 대상 날짜 안", workout.start >= DayWindow.start(saf.date))
        assertTrue("부분 실패 준비: 운동 종료는 대상 날짜 안", workout.end <= DayWindow.end(saf.date))
        assertTrue("부분 실패 준비: 운동 구간은 양수", workout.start < workout.end)
        assertEquals("부분 실패 준비: 실제 정규화 결과는 daily와 workouts 두 파일",
            setOf(Dataset.DAILY, Dataset.WORKOUTS), SyncRevision.identify(source, null, saf.time).files.keys)
    }
    fun close() { saf.close(); SQLiteDatabase.deleteDatabase(file) }
}
