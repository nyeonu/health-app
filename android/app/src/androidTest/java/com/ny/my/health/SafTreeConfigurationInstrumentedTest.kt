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
import com.ny.my.health.core.BlockReason
import com.ny.my.health.core.DestinationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 OS grant와 resolver로 전경 전용 capability를 검증한다. */
@RunWith(AndroidJUnit4::class)
class SafTreeConfigurationInstrumentedTest {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val configuration get() = SafTreeConfiguration.forTesting(resolver, SyntheticDocumentsProvider.AUTHORITY)

    @Test
    fun safConfigurationEnablesOnlyConsentedPersistedGrantedAndSuccessfullyProbedLocalCapabilities() = runBlocking {
        var primary: Throwable? = null
        try {
            reset()
            val result = grant(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
            val tree = requireNotNull(result.data)
            val flags = result.flags
            expectBlocked { configuration.select(null, flags, true) }
            expectBlocked { configuration.select(tree, flags, false) }
            expectBlocked { configuration.select(Uri.parse("file:///synthetic"), flags, true) }
            expectBlocked { configuration.select(DocumentsContract.buildDocumentUri(
                SyntheticDocumentsProvider.AUTHORITY, SyntheticDocumentsProvider.TREE_ID), flags, true) }
            expectBlocked { SafTreeConfiguration(resolver).select(tree, flags, true) }
            val cloud = DocumentsContract.buildTreeDocumentUri("synthetic.cloud.documents", "synthetic")
            expectBlocked { SafTreeConfiguration(resolver).select(cloud, flags, true) }
            expectBlocked { configuration.select(tree, flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv(), true) }
            assertEquals("동의·입력·flags 거부 전 query 없음", 0, SyntheticDocumentsProvider.queryCount(counts()))
            assertEquals("검증 전 probe 없음", 0, SyntheticDocumentsProvider.createCount(counts()))
            assertFalse("거부 결과의 영속 grant 없음", resolver.persistedUriPermissions.any { it.uri == tree })
            reset()
            val readOnly = grant(SyntheticDocumentsProvider.Control.GRANT_READ_ONLY)
            expectBlocked { configuration.select(readOnly.data, readOnly.flags, true) }
            assertEquals("읽기 전용 probe 없음", 0, SyntheticDocumentsProvider.createCount(counts()))
            reset()
            val granted = grant(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
            val proof = configuration.select(granted.data, granted.flags, true)
            assertTrue("같은 tree 증명", proof.treeUri == granted.data)
            assertEquals("확인된 overwrite mode", "wt", proof.writeMode)
            assertFalse("background 비활성", proof.backgroundCapable)
            assertFalse("증명 URI 비노출", proof.toString().contains("content:"))
            assertTrue("실제 persisted 읽기·쓰기", resolver.persistedUriPermissions.any {
                it.uri == granted.data && it.isReadPermission && it.isWritePermission
            })
            val observed = counts()
            assertEquals("고유 probe 하나", 1, SyntheticDocumentsProvider.createCount(observed))
            assertEquals("긴 write와 짧은 wt descriptor", 2, SyntheticDocumentsProvider.writeCount(observed))
            assertEquals("exact readback descriptor", 1, SyntheticDocumentsProvider.readCount(observed))
            assertEquals("소유 probe 하나 정리", 1, SyntheticDocumentsProvider.deleteCount(observed))
            assertSeededUnchanged(requireNotNull(granted.data))
            val restored = configuration.restore(requireNotNull(granted.data).toString(), true)
            assertTrue("restore 같은 tree", restored.treeUri == granted.data)
            assertFalse("restore background 비활성", restored.backgroundCapable)
            assertEquals("restore 새 probe", 2, SyntheticDocumentsProvider.createCount(counts()))
            assertEquals("restore probe 정리", 2, SyntheticDocumentsProvider.deleteCount(counts()))
            command(SyntheticDocumentsProvider.Control.REVOKE)
            expectBlocked { configuration.restore(requireNotNull(granted.data).toString(), true) }
            assertEquals("철회 뒤 probe 없음", 2, SyntheticDocumentsProvider.createCount(counts()))
            for (failure in listOf(SyntheticDocumentsProvider.Control.LOADING,
                SyntheticDocumentsProvider.Control.INCOMPLETE, SyntheticDocumentsProvider.Control.NULL_QUERY,
                SyntheticDocumentsProvider.Control.NULL_DESCRIPTOR, SyntheticDocumentsProvider.Control.NO_TRUNCATE,
                SyntheticDocumentsProvider.Control.SHORT_WRITE, SyntheticDocumentsProvider.Control.CLEANUP_FAILURE)) {
                reset()
                val picker = grant(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
                command(failure)
                expectBlocked { configuration.select(picker.data, picker.flags, true) }
                val snapshot = counts()
                val beforeProbe = failure in setOf(SyntheticDocumentsProvider.Control.LOADING,
                    SyntheticDocumentsProvider.Control.INCOMPLETE, SyntheticDocumentsProvider.Control.NULL_QUERY)
                assertEquals("불완전 조회는 생성 전 차단", if (beforeProbe) 0 else 1,
                    SyntheticDocumentsProvider.createCount(snapshot))
                assertEquals("소유 probe만 정리 시도", if (beforeProbe) 0 else 1,
                    SyntheticDocumentsProvider.deleteCount(snapshot))
            }
            reset()
            val missing = grant(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
            // tree 문자열은 persisted grant의 증거가 아니다.
            expectBlocked { configuration.restore(requireNotNull(missing.data).toString(), true) }
            expectBlocked { configuration.restore(requireNotNull(missing.data).toString(), false) }
            assertEquals("restore 실패 probe 없음", 0, SyntheticDocumentsProvider.createCount(counts()))
        } catch (failure: Throwable) {
            val safe = if (failure is AssertionError) failure else AssertionError("합성 SAF 설정 검증 실패")
            primary = safe
            throw safe
        } finally {
            var cleanup: AssertionError? = null
            for (control in listOf(SyntheticDocumentsProvider.Control.REVOKE, SyntheticDocumentsProvider.Control.RESET)) {
                try { command(control) } catch (_: Throwable) {
                    val safe = AssertionError("합성 설정 테스트 정리 실패")
                    if (primary != null) primary.addSuppressed(safe)
                    else if (cleanup != null) cleanup.addSuppressed(safe) else cleanup = safe
                }
            }
            if (primary == null) cleanup?.let { throw it }
        }
    }

    @Test
    fun safConfigurationRetainsOriginalMidProbeCancellationAndBlocksUnknownCreateWithoutGuessingCleanup() = runBlocking {
        var primary: Throwable? = null
        try {
            for (cleanupFails in listOf(false, true)) {
                reset()
                val picker = grant(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
                if (cleanupFails) command(SyntheticDocumentsProvider.Control.CLEANUP_FAILURE)
                val entered = CountDownLatch(1)
                val validIssuer = AtomicBoolean(false)
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        val instrumentation = InstrumentationRegistry.getInstrumentation()
                        validIssuer.set(resultCode == Activity.RESULT_OK &&
                            resultData?.getInt(SyntheticDocumentsProvider.EXTRA_UID, -1) ==
                                instrumentation.context.applicationInfo.uid &&
                            instrumentation.context.applicationInfo.uid != Process.myUid())
                        entered.countDown()
                    }
                }
                command(SyntheticDocumentsProvider.Control.BLOCK_BEFORE_EFFECT, receiver)
                val escaped = AtomicBoolean(false)
                val observedCancellation = AtomicReference<CancellationException?>()
                val original = CancellationException("합성 probe 취소")
                val probe = async(Dispatchers.Default) {
                    try {
                        configuration.select(picker.data, picker.flags, true)
                        escaped.set(true)
                    } catch (cancel: CancellationException) {
                        observedCancellation.set(cancel)
                        throw cancel
                    }
                }
                try {
                    assertTrue("실제 Binder probe 진입", entered.await(10, TimeUnit.SECONDS))
                    assertTrue("진입 이벤트 test APK 발급자", validIssuer.get())
                    probe.cancel(original)
                } finally {
                    // 차단 진입 확인 실패 시에도 실제 Binder 호출과 자식 coroutine을 남기지 않는다.
                    probe.cancel(original)
                    command(SyntheticDocumentsProvider.Control.RELEASE_BLOCK)
                    withTimeout(10_000) { probe.join() }
                }
                assertFalse("취소된 probe 증명 반환 없음", escaped.get())
                assertTrue("원래 CancellationException 유지", observedCancellation.get() === original)
                val snapshot = counts()
                assertEquals("진입 뒤 생성된 소유 probe", 1, SyntheticDocumentsProvider.createCount(snapshot))
                assertEquals("취소 뒤 소유 probe 정리 시도", 1, SyntheticDocumentsProvider.deleteCount(snapshot))
                assertEquals("취소 뒤 건강·probe 쓰기 없음", 0, SyntheticDocumentsProvider.writeCount(snapshot))
                assertEquals("정리 실패만 안전 suppressed", if (cleanupFails) 1 else 0, original.suppressed.size)
                if (cleanupFails) {
                    val suppressed = original.suppressed.single()
                    assertTrue("정리 실패 allowlist", suppressed is DestinationException &&
                        suppressed.reason == BlockReason.DESTINATION_BLOCKED)
                    assertTrue("정리 실패 원시 cause 없음", suppressed.cause == null)
                    assertTrue("정리 실패 고정 메시지", suppressed.message == "DESTINATION_BLOCKED")
                }
                assertSeededUnchanged(requireNotNull(picker.data))
            }
            reset()
            val picker = grant(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
            command(SyntheticDocumentsProvider.Control.CREATE_AFTER_EFFECT_FAILURE)
            expectBlocked { configuration.select(picker.data, picker.flags, true) }
            val snapshot = counts()
            assertEquals("반환 전 생성 부수효과", 1, SyntheticDocumentsProvider.createCount(snapshot))
            assertEquals("알 수 없는 URI 추측 삭제 금지", 0, SyntheticDocumentsProvider.deleteCount(snapshot))
            assertEquals("알 수 없는 대상 쓰기 금지", 0, SyntheticDocumentsProvider.writeCount(snapshot))
            assertSeededUnchanged(requireNotNull(picker.data))
        } catch (failure: Throwable) {
            val safe = if (failure is AssertionError) failure else AssertionError("합성 취소·생성 안전성 검증 실패")
            primary = safe
            throw safe
        } finally {
            var cleanup: AssertionError? = null
            for (control in listOf(SyntheticDocumentsProvider.Control.RELEASE_BLOCK,
                SyntheticDocumentsProvider.Control.REVOKE, SyntheticDocumentsProvider.Control.RESET)) {
                try { command(control) } catch (_: Throwable) {
                    val safe = AssertionError("합성 취소 테스트 정리 실패")
                    if (primary != null) primary.addSuppressed(safe)
                    else if (cleanup != null) cleanup.addSuppressed(safe) else cleanup = safe
                }
            }
            if (primary == null) cleanup?.let { throw it }
        }
    }

    private suspend fun expectBlocked(action: suspend () -> Unit) {
        var blocked = false
        try { action() } catch (failure: DestinationException) {
            blocked = true
            assertEquals("고정 차단 이유", BlockReason.DESTINATION_BLOCKED, failure.reason)
            assertEquals("원시 cause 없음", null, failure.cause)
            assertEquals("원시 suppressed 없음", 0, failure.suppressed.size)
            assertEquals("안전 메시지", "DESTINATION_BLOCKED", failure.message)
        }
        assertTrue("실패 시 증명 반환 금지", blocked)
    }
    private fun assertSeededUnchanged(tree: Uri) {
        val seeded = DocumentsContract.buildDocumentUriUsingTree(tree, SyntheticDocumentsProvider.SEEDED_ID)
        val same = resolver.openInputStream(seeded)?.use {
            it.readBytes().contentEquals(SyntheticDocumentsProvider.seededBytes()) } == true
        assertTrue("기존 파일 보존", same)
    }
    private fun reset() {
        command(SyntheticDocumentsProvider.Control.REVOKE)
        command(SyntheticDocumentsProvider.Control.RESET)
    }
    @Suppress("DEPRECATION")
    private fun grant(control: SyntheticDocumentsProvider.Control): Intent =
        command(control).getParcelable<Intent>(SyntheticDocumentsProvider.EXTRA_RESULT_DATA)
            ?: throw AssertionError("합성 원본 결과 없음")
    private fun counts(): Bundle = command(SyntheticDocumentsProvider.Control.OBSERVE)
        .getBundle(SyntheticDocumentsProvider.EXTRA_COUNTS) ?: throw AssertionError("합성 횟수 없음")
    private fun command(control: SyntheticDocumentsProvider.Control, blockReceiver: ResultReceiver? = null): Bundle {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val complete = CountDownLatch(1)
        var result = Activity.RESULT_CANCELED
        var issuerUid = -1
        var response = Bundle()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                result = resultCode
                response = resultData ?: Bundle()
                issuerUid = resultData?.getInt(SyntheticDocumentsProvider.EXTRA_UID, -1) ?: -1
                complete.countDown()
            }
        }
        try {
            instrumentation.targetContext.startActivity(Intent().apply {
                component = ComponentName(instrumentation.context.packageName,
                    SyntheticDocumentsProvider.GrantActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(SyntheticDocumentsProvider.EXTRA_CONTROL, control.name)
                putExtra(SyntheticDocumentsProvider.EXTRA_RECEIVER, receiver)
                if (blockReceiver != null) putExtra(SyntheticDocumentsProvider.EXTRA_BLOCK_RECEIVER, blockReceiver)
            })
        } catch (_: Exception) { throw AssertionError("합성 제어 Activity 시작 실패") }
        assertTrue("합성 제어 응답", complete.await(10, TimeUnit.SECONDS))
        assertEquals("합성 제어 결과", Activity.RESULT_OK, result)
        assertTrue("test APK UID 발급자", issuerUid == instrumentation.context.applicationInfo.uid)
        assertTrue("target UID와 발급자 분리", issuerUid != Process.myUid())
        return response
    }
}
