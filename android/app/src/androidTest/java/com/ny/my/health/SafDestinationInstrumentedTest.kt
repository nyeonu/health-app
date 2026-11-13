package com.ny.my.health

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ny.my.health.core.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 target UID의 grant와 resolver만 사용한다. */
@RunWith(AndroidJUnit4::class)
class SafDestinationInstrumentedTest {
    @Test fun safShorterOverwriteReadsBackExactBytesAndAmbiguousRevokedMovedDeletedInterruptedTargetsRequireRecovery() = runBlocking {
        val f = SafDestinationFixture()
        try {
            for (control in listOf(null, SyntheticDocumentsProvider.Control.DUPLICATE,
                SyntheticDocumentsProvider.Control.MOVE_OUTSIDE_TREE, SyntheticDocumentsProvider.Control.DELETE_SEEDED,
                SyntheticDocumentsProvider.Control.REVOKE, SyntheticDocumentsProvider.Control.LOADING,
                SyntheticDocumentsProvider.Control.INCOMPLETE, SyntheticDocumentsProvider.Control.PARTIAL, SyntheticDocumentsProvider.Control.NULL_QUERY,
                SyntheticDocumentsProvider.Control.DOCUMENT_PARTIAL, SyntheticDocumentsProvider.Control.SHORT_WRITE,
                SyntheticDocumentsProvider.Control.NO_TRUNCATE, SyntheticDocumentsProvider.Control.WRITE_INTERRUPTION,
                SyntheticDocumentsProvider.Control.CLOSE_INTERRUPTION, SyntheticDocumentsProvider.Control.READBACK_FAILURE)) {
                f.prepare()
                var handle = ""
                val first = f.planned("synthetic-long-content-for-truncation\n")
                val destination = f.destination()
                destination.writePending(destination.reconcile(listOf(first)).pending) { handle = it.targetHandle }
                f.known = mapOf(Dataset.DAILY to setOf(handle))
                val shorter = f.planned("short\n").copy(previousTargetHandle = handle)
                if (control in setOf(SyntheticDocumentsProvider.Control.SHORT_WRITE,
                        SyntheticDocumentsProvider.Control.NO_TRUNCATE, SyntheticDocumentsProvider.Control.WRITE_INTERRUPTION,
                        SyntheticDocumentsProvider.Control.CLOSE_INTERRUPTION, SyntheticDocumentsProvider.Control.READBACK_FAILURE)) {
                    val pending = destination.reconcile(listOf(shorter)).pending
                    f.command(requireNotNull(control))
                    var callbacks = 0
                    f.expectFailure { destination.writePending(pending) { callbacks++ } }
                    assertEquals("실패 후 확인 없음", 0, callbacks)
                } else if (control != null) {
                    f.command(control, DocumentsContract.getDocumentId(Uri.parse(handle)))
                    val before = f.counts()
                    f.expectFailure { destination.reconcile(listOf(shorter)) }
                    f.assertNoMutation(before)
                } else {
                    val before = f.counts()
                    val reconciled = destination.reconcile(listOf(shorter))
                    f.assertNoMutation(before)
                    assertEquals(listOf(shorter), reconciled.pending)
                    destination.writePending(reconciled.pending) { assertTrue("동일 대상", handle == it.targetHandle) }
                    assertTrue("짧은 exact bytes", f.resolver.openInputStream(Uri.parse(handle))!!.use {
                        it.readBytes().contentEquals("short\n".toByteArray()) })
                    val healthy = f.counts()
                    assertEquals(1, destination.reconcile(listOf(shorter)).confirmed.size)
                    f.assertNoMutation(healthy)
                }
                assertEquals("건강 파일 삭제 없음", 1, SyntheticDocumentsProvider.deleteCount(f.counts()))
            }
        } finally { f.close() }
    }

    @Test fun safReconciliationPerformsNoWritesAndReservationsRejectChangedOrReusedInputs() = runBlocking {
        val f = SafDestinationFixture()
        try {
            f.prepare()
            val destination = f.destination()
            val file = f.planned("synthetic\n")
            val before = f.counts()
            val pending = destination.reconcile(listOf(file)).pending
            f.assertNoMutation(before)
            assertEquals(listOf(file), pending)
            f.expectFailure { destination.writePending(listOf(f.planned("changed\n"))) {} }
            f.assertNoMutation(before)
            f.expectFailure { destination.writePending(pending) {} }
            val fresh = destination.reconcile(listOf(file)).pending
            val original = CancellationException("합성 콜백 취소")
            try { destination.writePending(fresh) { throw original }; fail("취소 필요") }
            catch (cancel: CancellationException) { assertTrue("취소 객체 보존", cancel === original) }
            f.expectFailure { destination.writePending(fresh) {} }
            val healthy = f.counts()
            assertEquals(1, destination.reconcile(listOf(file)).confirmed.size)
            f.assertNoMutation(healthy)
            val callbackFailure = IllegalStateException("합성 로컬 콜백 실패")
            val changed = f.planned("changed-again\n")
            val changedPending = destination.reconcile(listOf(changed)).pending
            try { destination.writePending(changedPending) { throw callbackFailure }; fail("콜백 실패 필요") }
            catch (failure: IllegalStateException) { assertTrue("콜백 예외 원본", failure === callbackFailure) }
            val afterCallback = f.counts()
            assertEquals(1, destination.reconcile(listOf(changed)).confirmed.size)
            f.assertNoMutation(afterCallback)
            val unknown = file.copy(key = file.key.copy(dataset = Dataset.SLEEP),
                file = CsvFile("sleep/${f.date}.csv", file.file.content), createMayHaveSucceeded = true)
            f.expectFailure { destination.reconcile(listOf(unknown)) }
            f.assertNoMutation(afterCallback)

            // persisted grant가 남아 있어도 실제 provider SecurityException은 정확히 차단한다.
            f.prepare()
            val securityDestination = f.destination()
            val securityPending = securityDestination.reconcile(listOf(file)).pending
            f.command(SyntheticDocumentsProvider.Control.SECURITY_FAILURE)
            val beforeSecurity = f.counts()
            var securityCallbacks = 0
            f.expectFailure(BlockReason.DESTINATION_BLOCKED) { securityDestination.writePending(securityPending) { securityCallbacks++ } }
            f.expectFailure(BlockReason.DESTINATION_BLOCKED) { securityDestination.reconcile(listOf(file)) }
            assertEquals("권한 예외 확인 없음", 0, securityCallbacks)
            f.assertNoMutation(beforeSecurity)

            // 서로 다른 dataset이 동일 canonical 문서를 노출하면 전체 묶음을 차단한다.
            f.prepare()
            val aliasDestination = f.destination()
            val workout = file.copy(key = file.key.copy(dataset = Dataset.WORKOUTS),
                file = CsvFile("workouts/${f.date}.csv", file.file.content))
            aliasDestination.writePending(aliasDestination.reconcile(listOf(file, workout)).pending) {}
            f.command(SyntheticDocumentsProvider.Control.SAME_TARGET_ACROSS_DATASETS)
            val beforeAlias = f.counts()
            f.expectFailure(BlockReason.DESTINATION_BLOCKED) { aliasDestination.reconcile(listOf(file, workout)) }
            var aliasCallbacks = 0
            f.expectFailure(BlockReason.DESTINATION_BLOCKED) { aliasDestination.writePending(listOf(file, workout)) { aliasCallbacks++ } }
            assertEquals("별칭 확인 없음", 0, aliasCallbacks)
            f.assertNoMutation(beforeAlias)

            // 실제 encoded URI 경계는 기존 대상과 create 반환값 모두에서 검사한다.
            for (size in listOf(4096, 4097)) {
                f.prepare()
                val sizedDestination = f.destination()
                sizedDestination.writePending(sizedDestination.reconcile(listOf(file)).pending) {}
                f.command(SyntheticDocumentsProvider.Control.OVERSIZED_TARGET_ID, uriBytes = size)
                val beforeSized = f.counts()
                if (size == 4096) {
                    val adopted = sizedDestination.reconcile(listOf(file))
                    assertEquals("4096 bytes 채택", 1, adopted.confirmed.size)
                    assertEquals("encoded URI 경계", 4096, adopted.confirmed.single().targetHandle.toByteArray(Charsets.UTF_8).size)
                    f.assertNoMutation(beforeSized)
                    val changedSized = f.planned("synthetic-sized-change\n")
                    val sizedPending = sizedDestination.reconcile(listOf(changedSized)).pending
                    var callbacks = 0
                    sizedDestination.writePending(sizedPending) {
                        assertEquals("4096 bytes 쓰기 확인", 4096, it.targetHandle.toByteArray(Charsets.UTF_8).size)
                        callbacks++
                    }
                    assertEquals("허용 경계 확인 하나", 1, callbacks)
                } else {
                    f.expectFailure(BlockReason.DESTINATION_BLOCKED) { sizedDestination.reconcile(listOf(file)) }
                    var callbacks = 0
                    f.expectFailure(BlockReason.DESTINATION_BLOCKED) { sizedDestination.writePending(listOf(file)) { callbacks++ } }
                    assertEquals("초과 경계 확인 없음", 0, callbacks)
                    f.assertNoMutation(beforeSized)
                }
            }
            f.prepare()
            val oversizedCreate = f.destination()
            val createPending = oversizedCreate.reconcile(listOf(file)).pending
            f.command(SyntheticDocumentsProvider.Control.OVERSIZED_TARGET_ID, uriBytes = 4097)
            val beforeCreate = f.counts()
            var createCallbacks = 0
            f.expectFailure(BlockReason.DESTINATION_BLOCKED) { oversizedCreate.writePending(createPending) { createCallbacks++ } }
            val afterCreate = f.counts()
            assertEquals("초과 ID create 후 건강 쓰기 없음", SyntheticDocumentsProvider.writeCount(beforeCreate), SyntheticDocumentsProvider.writeCount(afterCreate))
            assertEquals("초과 ID create 후 확인 없음", 0, createCallbacks)
            assertEquals("directory와 파일 생성만", SyntheticDocumentsProvider.createCount(beforeCreate) + 2, SyntheticDocumentsProvider.createCount(afterCreate))
            assertEquals("초과 ID 건강 파일 삭제 없음", SyntheticDocumentsProvider.deleteCount(beforeCreate), SyntheticDocumentsProvider.deleteCount(afterCreate))

            // directory 생성의 Binder 중단 후 원래 취소를 보존하고 확인하지 않는다.
            f.prepare()
            val interrupted = f.destination()
            val interruptedPending = interrupted.reconcile(listOf(file)).pending
            val entered = CountDownLatch(1)
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    if (code == Activity.RESULT_OK && data?.getInt(SyntheticDocumentsProvider.EXTRA_UID) ==
                        f.instrumentation.context.applicationInfo.uid) entered.countDown()
                }
            }
            f.command(SyntheticDocumentsProvider.Control.BLOCK_BEFORE_EFFECT, blockReceiver = receiver)
            val cancellation = CancellationException("합성 directory 취소")
            val observed = AtomicReference<CancellationException?>()
            var confirmed = 0
            val job = async(Dispatchers.Main) {
                try { interrupted.writePending(interruptedPending) { confirmed++ } }
                catch (cancel: CancellationException) { observed.set(cancel); throw cancel }
            }
            try {
                assertTrue("Main heartbeat와 실제 생성 진입 10초", entered.await(10, TimeUnit.SECONDS))
                job.cancel(cancellation)
            } finally {
                job.cancel(cancellation)
                f.command(SyntheticDocumentsProvider.Control.RELEASE_BLOCK)
                withTimeout(10_000) { job.join() }
            }
            assertTrue("원래 생성 취소", observed.get() === cancellation)
            assertEquals("중단 후 확인 없음", 0, confirmed)
        } finally { f.close() }
    }
}

internal class SafDestinationFixture {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val resolver get() = context.contentResolver
    val date: LocalDate = LocalDate.of(2026, 11, 4)
    val time: Instant = Instant.parse("2026-11-05T00:00:00Z")
    val clock: Clock = Clock.fixed(time, ZoneOffset.UTC)
    var generation = DestinationGeneration("00000000-0000-0000-0000-000000000001")
    lateinit var proof: SafTreeProof
    var known: Map<Dataset, Set<String>> = emptyMap()
    suspend fun prepare() {
        close()
        val response = command(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
        @Suppress("DEPRECATION")
        val picker = response.getParcelable<Intent>(SyntheticDocumentsProvider.EXTRA_RESULT_DATA)!!
        proof = SafTreeConfiguration.forTesting(resolver, SyntheticDocumentsProvider.AUTHORITY)
            .select(picker.data, picker.flags, true)
        known = emptyMap()
    }
    fun destination(lookup: suspend (DestinationGeneration, LocalDate) -> Map<Dataset, Set<String>> = { _, _ -> known }) =
        SafDestination(resolver, proof, generation, lookup, clock)
    fun planned(content: String): PlannedFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        return PlannedFile(FileKey(generation, date, ReadScopeKey("b".repeat(64)), Dataset.DAILY, "a".repeat(64)),
            CsvFile("daily/$date.csv", content), digest, null)
    }
    fun counts(): Bundle = command(SyntheticDocumentsProvider.Control.OBSERVE).getBundle(SyntheticDocumentsProvider.EXTRA_COUNTS)!!
    fun assertNoMutation(before: Bundle) {
        val after = counts()
        assertEquals("생성 없음", SyntheticDocumentsProvider.createCount(before), SyntheticDocumentsProvider.createCount(after))
        assertEquals("쓰기 없음", SyntheticDocumentsProvider.writeCount(before), SyntheticDocumentsProvider.writeCount(after))
        assertEquals("삭제 없음", SyntheticDocumentsProvider.deleteCount(before), SyntheticDocumentsProvider.deleteCount(after))
    }
    suspend fun expectFailure(expectedReason: BlockReason? = null, action: suspend () -> Unit) {
        var caught = false
        try { action() } catch (failure: DestinationException) {
            caught = true
            assertTrue("고정 이유", failure.reason in setOf(BlockReason.DESTINATION_BLOCKED, BlockReason.DESTINATION_RECONCILIATION_REQUIRED))
            if (expectedReason != null) assertEquals("정확한 차단 이유", expectedReason, failure.reason)
            assertNull("원시 cause 없음", failure.cause)
            assertEquals("원시 suppressed 없음", 0, failure.suppressed.size)
        }
        assertTrue("차단 필요", caught)
    }
    fun close() {
        command(SyntheticDocumentsProvider.Control.RELEASE_BLOCK)
        command(SyntheticDocumentsProvider.Control.REVOKE)
        command(SyntheticDocumentsProvider.Control.RESET)
    }
    fun command(control: SyntheticDocumentsProvider.Control, target: String? = null, blockReceiver: ResultReceiver? = null, uriBytes: Int? = null): Bundle {
        val complete = CountDownLatch(1)
        var result = Activity.RESULT_CANCELED
        var response = Bundle()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) { result = code; response = data ?: Bundle(); complete.countDown() }
        }
        context.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName, SyntheticDocumentsProvider.GrantActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(SyntheticDocumentsProvider.EXTRA_CONTROL, control.name)
            putExtra(SyntheticDocumentsProvider.EXTRA_RECEIVER, receiver)
            if (target != null) putExtra(SyntheticDocumentsProvider.EXTRA_TARGET_ID, target)
            if (blockReceiver != null) putExtra(SyntheticDocumentsProvider.EXTRA_BLOCK_RECEIVER, blockReceiver)
            if (uriBytes != null) putExtra(SyntheticDocumentsProvider.EXTRA_TARGET_URI_BYTES, uriBytes)
        })
        assertTrue("합성 제어 10초 응답", complete.await(10, TimeUnit.SECONDS))
        assertEquals("합성 제어 성공", Activity.RESULT_OK, result)
        assertEquals("test APK 발급자", instrumentation.context.applicationInfo.uid, response.getInt(SyntheticDocumentsProvider.EXTRA_UID))
        assertTrue("target UID 분리", response.getInt(SyntheticDocumentsProvider.EXTRA_UID) != Process.myUid())
        return response
    }
}
