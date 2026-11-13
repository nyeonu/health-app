package com.ny.my.health

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ny.my.health.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 엔진과 매번 재연결하는 SQLite 저장소로 원격·로컬 확인 경계를 검증한다. */
@RunWith(AndroidJUnit4::class)
class SafJournalInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun safExplicitRecoveryPreservesConfirmedFilesUnknownCreatesAndOldGenerationIsolationAfterReopen() {
        val f = JournalFixture(localTree = true)
        var activity: JournalActivityFixture? = null
        try {
            runBlocking {
                f.prepare()
                val initial = f.start()
                assertEquals(BatchStatus.COMPLETED, f.run(initial).status)
                val oldGeneration = f.saf.generation
                val handle = requireNotNull(f.row(initial).datasets.getValue(Dataset.DAILY).targetHandle)
                val initialBytes = f.bytes(handle)
                f.steps = 2
                f.failConfirmation = true
                val failed = f.start()
                assertEquals(BatchStatus.BLOCKED, f.run(failed).status)
                assertTrue("확인 커밋 실패 후 미확인", f.row(failed).datasets.getValue(Dataset.DAILY).phase != FilePhase.CONFIRMED)
                f.failConfirmation = false
                f.finish(failed)
                val pendingBytes = f.bytes(handle)
                assertFalse("새 revision이 원격 파일을 변경", initialBytes.contentEquals(pendingBytes))
                val beforeHealthy = f.saf.counts()
                activity = JournalActivityFixture(f).also { it.launch(); it.consent(); it.retry() }
                assertEquals(BatchStatus.COMPLETED, activity!!.model.saf.state.value.outcome)
                val recovered = f.row(failed).datasets.getValue(Dataset.DAILY)
                assertEquals(FilePhase.CONFIRMED, recovered.phase)
                assertTrue("기존 확인 파일 identity 유지", recovered.targetHandle == handle)
                assertTrue("재시도에서 같은 bytes 채택", f.bytes(handle).contentEquals(pendingBytes))
                activity!!.close(); activity = null
                val afterHealthy = f.saf.counts()
                assertEquals("정상 재시도는 probe 외 파일 생성 없음", SyntheticDocumentsProvider.createCount(beforeHealthy) + 1,
                    SyntheticDocumentsProvider.createCount(afterHealthy))
                assertEquals("정상 재시도는 probe 정리", SyntheticDocumentsProvider.deleteCount(beforeHealthy) + 1,
                    SyntheticDocumentsProvider.deleteCount(afterHealthy))

                f.localCategories = setOf(DataType.STEPS, DataType.EXERCISE)
                f.scopes = mapOf(DataType.STEPS to ReadScope.EXPLICIT_DAY, DataType.EXERCISE to ReadScope.EXPLICIT_DAY)
                f.workouts = true
                val configured = f.store().configure(oldGeneration, f.config(),
                    PrivateDestinationBinding("LOCAL", treeUri = f.saf.proof.treeUri.toString()))
                assertEquals(CommitStatus.COMMITTED, configured.status)
                f.saf.generation = configured.generation!!
                assertNotEquals(oldGeneration, f.saf.generation)
                assertTrue("새 세대는 이전 확인을 상속하지 않음", f.store().loadKnownTargets(f.saf.generation, f.saf.date).isEmpty())
                f.saf.command(SyntheticDocumentsProvider.Control.CREATE_AFTER_EFFECT_FAILURE)
                val attempt = f.start()
                assertEquals(BatchStatus.BLOCKED, f.run(attempt).status)
                val blocked = f.row(attempt)
                assertEquals(FilePhase.CONFIRMED, blocked.datasets.getValue(Dataset.DAILY).phase)
                assertTrue("현재 일별 파일 identity 유지", blocked.datasets.getValue(Dataset.DAILY).targetHandle == handle)
                assertEquals(FilePhase.INDETERMINATE, blocked.datasets.getValue(Dataset.WORKOUTS).phase)
                assertNull(blocked.datasets.getValue(Dataset.WORKOUTS).targetHandle)
                f.finish(attempt)
                val blockedBytes = f.bytes(handle)
                val beforeBlocked = f.saf.counts()
                activity = JournalActivityFixture(f).also { it.launch(); it.consent() }
                val accesses = f.accessCount
                activity!!.retry()
                assertTrue("실제 재시도 접근 검사 실행", f.accessCount > accesses)
                assertEquals(ManualSafPhase.BLOCKED, activity!!.model.saf.state.value.phase)
                val reopened = f.row(attempt)
                assertEquals(FilePhase.CONFIRMED, reopened.datasets.getValue(Dataset.DAILY).phase)
                assertTrue("차단 뒤 현재 파일 identity 유지", reopened.datasets.getValue(Dataset.DAILY).targetHandle == handle)
                assertEquals(FilePhase.INDETERMINATE, reopened.datasets.getValue(Dataset.WORKOUTS).phase)
                assertNull(reopened.datasets.getValue(Dataset.WORKOUTS).targetHandle)
                assertEquals(f.saf.generation, f.store().load().state?.generation)
                assertTrue("이전 세대 확인 identity 보존", f.archivedHandle(oldGeneration, initial.scopeKey) == handle)
                assertEquals(CommitStatus.STALE, f.store().reopenManualBlockers(oldGeneration).status)
                assertTrue("차단 뒤 실제 파일 bytes 보존", f.bytes(handle).contentEquals(blockedBytes))
                activity!!.close(); activity = null
                val afterBlocked = f.saf.counts()
                assertEquals("차단 재시도에서 새 probe 생성 시도", SyntheticDocumentsProvider.createCount(beforeBlocked) + 1,
                    SyntheticDocumentsProvider.createCount(afterBlocked))
                assertEquals("차단 재시도는 기존 파일 쓰기 없음", SyntheticDocumentsProvider.writeCount(beforeBlocked),
                    SyntheticDocumentsProvider.writeCount(afterBlocked))
                assertEquals("차단 재시도는 파일 삭제 없음", SyntheticDocumentsProvider.deleteCount(beforeBlocked),
                    SyntheticDocumentsProvider.deleteCount(afterBlocked))
            }
        } finally { activity?.close(); f.close() }
    }

    private inner class JournalActivityFixture(private val f: JournalFixture) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val application = instrumentation.targetContext.applicationContext as Application
        lateinit var model: SyncViewModel
        private lateinit var scenario: ActivityScenario<MainActivity>
        private val execution = SyncExecutionSession(f.store(), f.reader, null, f.saf.clock, { UUID.randomUUID().toString() })
        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, state: Bundle?) {
                if (a !is MainActivity) return
                a.viewModelFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return SyncViewModel(application, SyncViewModel.ManualDestinationDependencies(f.store(), f.reader,
                            execution, SafTreeConfiguration.forTesting(f.saf.resolver, SyntheticDocumentsProvider.AUTHORITY),
                            f.saf.clock), null).also { model = it } as T
                    }
                }
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        fun launch() {
            application.registerActivityLifecycleCallbacks(callbacks)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { ::model.isInitialized && model.saf.state.value.foreground &&
                model.saf.state.value.configured && model.committedDestination.value.kind == ManualDestination.LOCAL }
        }
        fun consent() {
            compose.onNodeWithContentDescription("로컬 폴더 저장 동의").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.saf.state.value.privacyConsent }
        }
        fun retry() {
            var jobs: List<Job> = emptyList()
            compose.onNodeWithText("로컬 동기화 재시도").performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { click ->
                    val parent = model.viewModelScope.coroutineContext[Job]!!
                    val before = parent.children.toSet()
                    assertTrue("실제 재시도 버튼 실행", click())
                    jobs = parent.children.filter { it !in before }.toList()
                }
            runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        }
        fun close() {
            if (::scenario.isInitialized) scenario.close()
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

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

private class JournalFixture(private val localTree: Boolean = false) {
    val saf = SafDestinationFixture()
    private val file = File(saf.context.noBackupFilesDir, "saf-journal-${UUID.randomUUID()}.db")
    var localCategories = setOf(DataType.STEPS)
    @Volatile var accessCount = 0
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
    fun config() = SyncConfiguration(if (localTree) "local-tree" else "synthetic-saf", saf.date, false,
        if (localTree) localCategories else setOf(DataType.STEPS, DataType.EXERCISE))
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
    val reader = object : ScopedHealthReader {
        override suspend fun access(trigger: SyncTrigger): ReadAccess {
            accessCount++
            return ReadAccess(true, scopes.keys, scopes)
        }
        override suspend fun readScoped(date: java.time.LocalDate, requestedScopes: Map<DataType, ReadScope>) = snapshot(date)
    }
    fun bytes(handle: String): ByteArray = saf.resolver.openInputStream(Uri.parse(handle))!!.use { it.readBytes() }
    fun archivedHandle(generation: DestinationGeneration, scope: ReadScopeKey): String? =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT target_handle FROM dataset WHERE generation=? AND date=? AND scope_key=? AND dataset=?",
                arrayOf(generation.value, saf.date.toString(), scope.value, Dataset.DAILY.name)).use { cursor ->
                assertTrue("이전 세대 확인 행 보존", cursor.moveToFirst())
                val result = cursor.getString(0)
                assertFalse("이전 세대 확인 행 유일", cursor.moveToNext())
                result
            }
        }
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
