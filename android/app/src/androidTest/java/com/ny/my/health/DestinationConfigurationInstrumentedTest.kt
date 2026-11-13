package com.ny.my.health

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 합성 식별자와 실제 SQLite를 사용하며 계정·원격 서비스에는 접속하지 않는다. */
@RunWith(AndroidJUnit4::class)
class DestinationConfigurationInstrumentedTest {
    @Test fun localConfigurationFailureRestoreAndResetPreserveDriveBindingAndHistory() = runBlocking {
        val f = ManualDriveNativeFixture()
        val provider = SafDestinationFixture()
        try {
            val drive = f.session()
            val driveTicket = f.configure(drive)
            val history = SyncHistory(1, null, HistorySuccess(f.today.minusDays(2), f.clock.instant(), setOf(DataType.STEPS), 1))
            val historyStore = SqliteHistoryStore(HistoryDatabase(f.historyFile))
            historyStore.replace(history)
            provider.prepare()
            val configuration = SyncConfiguration("local-tree", f.today.minusDays(1), false, setOf(DataType.STEPS))
            val binding = PrivateDestinationBinding("LOCAL", treeUri = provider.proof.treeUri.toString())
            val driveBinding = f.reopen().readBinding(driveTicket.generation)
            assertTrue("기존 Drive binding 존재", driveBinding != null)
            val failuresBefore = f.failedCommitCallbacks
            f.failCommit = true
            val failed = f.store.configure(driveTicket.generation, configuration, binding)
            assertEquals(CommitStatus.UNAVAILABLE, failed.status)
            assertTrue("커밋 실패 주입 도달", f.failedCommitCallbacks == failuresBefore + 1)
            val preserved = f.reopen().load().state!!
            assertTrue("Drive 세대 보존", preserved.generation == driveTicket.generation)
            assertTrue("Drive binding 보존", f.reopen().readBinding(driveTicket.generation) == driveBinding)
            assertTrue("기존 이력 보존", historyStore.load().history == history)
            f.failCommit = false
            val committed = f.store.configure(driveTicket.generation, configuration, binding)
            assertEquals(CommitStatus.COMMITTED, committed.status)
            val localGeneration = committed.generation!!
            assertTrue("새 로컬 세대", localGeneration != driveTicket.generation)
            val saved = f.reopen().load().state!!
            assertTrue("로컬 구성 복원", saved.generation == localGeneration && saved.configuration == configuration)
            assertTrue("로컬 binding 복원", f.reopen().readBinding(localGeneration) == binding)
            val execution = SyncExecutionSession(f.store, f.reader, null, f.clock, { UUID.randomUUID().toString() })
            val session = ManualSafSession(f.store, f.reader, execution, provider.resolver,
                SafTreeConfiguration.forTesting(provider.resolver, SyntheticDocumentsProvider.AUTHORITY), f.clock)
            session.refresh()
            assertTrue("controller 로컬 구성 복원", session.state.value.configured && session.state.value.generation == localGeneration &&
                session.state.value.categories == configuration.categories)
            assertFalse("이전 세대 초기화 거부", session.reset(driveTicket.generation))
            assertTrue("현재 세대 초기화", session.reset(localGeneration))
            val reset = f.reopen().load().state!!
            assertTrue("초기화 뒤 새 세대와 빈 구성", reset.generation != localGeneration && reset.configuration == null)
            assertTrue("초기화 뒤 binding 없음", f.reopen().readBinding(reset.generation) == null)
            assertTrue("초기화 뒤 이력 보존", historyStore.load().history == history)
            assertFalse("이전 Drive 인증 거부", drive.acceptAuthorization(driveTicket, false, "synthetic-memory-only", f.scopes))
        } finally { provider.close(); f.close() }
    }

    @Test fun cancelledSelectionAndFailedConfigurationPreserveDurableBindingAndResetOnlyV1Coverage() = runBlocking {
        val f = ManualDriveNativeFixture()
        try {
            val session = f.session()
            session.refresh()
            assertEquals(StateStatus.ABSENT, f.store.load().status)
            session.setForeground(true)
            session.setPrivateFolderConsent(true)
            val cancelled = checkNotNull(session.beginAccountSelection())
            assertNull(session.selectAccount(false, cancelled, false, null, null, f.categories))
            assertEquals(StateStatus.ABSENT, f.reopen().load().status)
            session.setPrivateFolderConsent(true)
            val ticket = f.configure(session)
            val saved = f.reopen().load().state!!
            assertEquals(f.categories, saved.configuration!!.categories)
            assertEquals(f.account, ticket.account)
            assertEquals(f.account.name, f.reopen().readBinding(saved.generation)!!.accountName)
            assertFalse(saved.configuration!!.automatic)
            assertEquals(f.today.minusDays(1), saved.configuration!!.enrolledDay)

            val history = SyncHistory(1, null, HistorySuccess(f.today.minusDays(2), f.clock.instant(), setOf(DataType.STEPS), 1))
            val historyStore = SqliteHistoryStore(HistoryDatabase(f.historyFile))
            historyStore.replace(history)
            val historyBytes = f.historyFile.readBytes()
            f.failCommit = true
            val picker = checkNotNull(session.beginAccountSelection())
            assertNull(session.selectAccount(true, picker, true, "changed@example.invalid", "com.google", setOf(DataType.STEPS)))
            assertEquals(saved, f.reopen().load().state)
            assertEquals(f.account.name, f.reopen().readBinding(saved.generation)!!.accountName)
            f.failCommit = false
            assertTrue(session.resetConfiguration())
            val reset = f.reopen().load().state!!
            assertNull(reset.configuration)
            assertTrue(reset.days.isEmpty())
            assertNotEquals(saved.generation, reset.generation)
            assertArrayEquals(historyBytes, f.historyFile.readBytes())
            assertEquals(history, historyStore.load().history)
            assertFalse(session.acceptAuthorization(ticket, false, "synthetic-memory-only", f.scopes))
            assertEquals(0, f.rawReads)
            assertEquals(0, f.proofs)
            // foundation 삭제 역시 V1 구성 변경을 대신하지 않는다.
            session.setPrivateFolderConsent(true)
            val renewed = f.configure(session)
            historyStore.reset()
            assertEquals(renewed.generation, f.reopen().load().state!!.generation)
            assertEquals(f.categories, f.reopen().load().state!!.configuration!!.categories)
        } finally { f.close() }
    }
}

/** 외부 경계만 합성하고 저장·admission·controller·engine은 실제 구현을 사용한다. */
internal class ManualDriveNativeFixture {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val identifier = UUID.randomUUID().toString()
    val file = File(context.noBackupFilesDir, "manual-flow-$identifier.db")
    val historyFile = File(context.noBackupFilesDir, "manual-history-$identifier.db")
    val today: LocalDate = LocalDate.of(2026, 11, 6)
    val clock: Clock = Clock.fixed(Instant.parse("2026-11-05T16:00:00Z"), ZoneOffset.UTC)
    val account = BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")
    val categories = DataType.entries.toSet()
    val scopes = listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE)
    var failCommit = false
    var failedCommitCallbacks = 0
    var grants = setOf(DataType.STEPS)
    var rawReads = 0
    var proofs = 0
    var writes = 0
    var proofBody = """{"user":{"emailAddress":"selected@example.invalid","me":true}}"""
    var duringProof: suspend () -> Unit = {}
    var duringRead: suspend () -> Unit = {}
    val store = SqliteSyncStateStore(SyncStateDatabase(file), { today }) {
        if (failCommit) {
            failedCommitCallbacks++
            error("합성 저장 실패")
        }
    }
    val reader = object : ScopedHealthReader {
        override suspend fun access(trigger: SyncTrigger) = ReadAccess(true, grants,
            grants.associateWith { ReadScope.EXPLICIT_DAY })
        override suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot {
            rawReads++
            duringRead()
            val types = requestedScopes.keys.intersect(grants)
            return ScopedSnapshot(HealthSnapshot(date, null, emptyList(), emptyList(), emptyList(), emptyList(), types),
                types.associateWith { ReadScope.EXPLICIT_DAY }, requestedScopes.keys - types)
        }
    }
    private val execution = SyncExecutionSession(store, reader, null, clock, { UUID.randomUUID().toString() })
    fun reopen() = SqliteSyncStateStore(SyncStateDatabase(file), { today })
    fun session() = ManualDriveSession(object : ManualDrivePorts {
        override val store: SyncStateStore = this@ManualDriveNativeFixture.store
        override val reader: ScopedHealthReader = this@ManualDriveNativeFixture.reader
        override suspend fun binding(generation: DestinationGeneration): BoundDriveAuthorization.AccountSelection? {
            val binding = reopen().readBinding(generation) ?: return null
            return BoundDriveAuthorization.pickerCandidate(true, 1, 1, true, binding.accountName, binding.accountType)
        }
        override suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration,
            account: BoundDriveAuthorization.AccountSelection) = this@ManualDriveNativeFixture.store.configure(
                expected, configuration, PrivateDestinationBinding("DRIVE", account.name, account.type))
        override suspend fun reopen(generation: DestinationGeneration) = this@ManualDriveNativeFixture.store.reopenManualBlockers(generation)
        override suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync) =
            execution.runPrepared(SyncTrigger.MANUAL, generation, prepare)
        override fun transport(token: String) = object : DriveTransport {
            override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
                if (method != "GET") writes++
                assertEquals("GET", method)
                assertEquals(BoundDriveAuthorization.ABOUT_URL, url)
                assertNull(body)
                proofs++
                duringProof()
                return DriveResponse(200, proofBody)
            }
        }
        override fun destination(transport: DriveTransport, generation: DestinationGeneration): StorageDestination =
            DriveApiDestination(PrivateDriveUploader(transport, clock = clock), generation, this@ManualDriveNativeFixture.store, clock)
    }, clock)
    suspend fun configure(session: ManualDriveSession): BoundDriveAuthorization.Ticket {
        session.setForeground(true)
        session.setPrivateFolderConsent(true)
        val picker = checkNotNull(session.beginAccountSelection())
        return checkNotNull(session.selectAccount(true, picker, true, account.name, account.type, categories))
    }
    fun close() {
        SQLiteDatabase.deleteDatabase(file)
        SyncStateDatabase(file).resetDirectory.deleteRecursively()
        SQLiteDatabase.deleteDatabase(historyFile)
        HistoryDatabase(historyFile).resetDirectory.deleteRecursively()
    }
}
