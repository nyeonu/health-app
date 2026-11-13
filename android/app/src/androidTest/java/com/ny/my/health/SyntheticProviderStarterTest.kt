package com.ny.my.health

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** mock이 아닌 target UID resolver와 OS grant를 확인하는 기반 테스트. */
@RunWith(AndroidJUnit4::class)
class SyntheticProviderStarterTest {
    @Test
    fun syntheticProviderGrantsTargetUidTreeAccessAndResolverReadsTheSeededDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val test = instrumentation.context
        assertTrue("target UID 확인", Process.myUid() == target.applicationInfo.uid)
        assertTrue("별도 test APK UID 확인", Process.myUid() != test.applicationInfo.uid)
        @Suppress("DEPRECATION")
        val production = target.packageManager.getPackageInfo(target.packageName, PackageManager.GET_PROVIDERS)
        assertFalse("production provider 격리", production.providers.orEmpty().any {
            it.authority == SyntheticDocumentsProvider.AUTHORITY ||
                it.name == SyntheticDocumentsProvider::class.java.name
        })

        val resolver = target.contentResolver
        val tree = DocumentsContract.buildTreeDocumentUri(SyntheticDocumentsProvider.AUTHORITY,
            SyntheticDocumentsProvider.TREE_ID)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, SyntheticDocumentsProvider.SEEDED_ID)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, SyntheticDocumentsProvider.TREE_ID)
        // URI·ID·원시 예외가 JUnit 실패 출력에 포함되지 않도록 경계 예외를 고정 문구로 바꾼다.
        var primaryFailure: Throwable? = null
        try {
            command(SyntheticDocumentsProvider.Control.REVOKE)
            command(SyntheticDocumentsProvider.Control.RESET)
            command(SyntheticDocumentsProvider.Control.GRANT_READ_WRITE)
            resolver.takePersistableUriPermission(tree, SyntheticDocumentsProvider.ACCESS_FLAGS)
            assertTrue("target persisted 읽기·쓰기 grant", resolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            })
            assertTrue("중첩 ancestry 확인", DocumentsContract.isChildDocument(resolver, root, document))
            resolver.query(document, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_SIZE),
                null, null, null).use { cursor ->
                assertTrue("합성 문서 query", cursor != null && cursor.moveToFirst())
                assertTrue("합성 문서 identity", cursor!!.getString(0) == SyntheticDocumentsProvider.SEEDED_ID)
                assertEquals("합성 크기", SyntheticDocumentsProvider.seededBytes().size.toLong(), cursor.getLong(1))
                assertFalse("합성 단일 행", cursor.moveToNext())
            }
            val contentMatches = resolver.openInputStream(document)?.use {
                it.readBytes().contentEquals(SyntheticDocumentsProvider.seededBytes())
            } == true
            assertTrue("합성 바이트 읽기·닫기", contentMatches)

            command(SyntheticDocumentsProvider.Control.REVOKE)
            assertFalse("철회 후 persisted grant 없음", resolver.persistedUriPermissions.any { it.uri == tree })
            expectSecurityDenial { resolver.openInputStream(document)?.use { it.read() } }
            command(SyntheticDocumentsProvider.Control.GRANT_READ_ONLY)
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertTrue("읽기 전용 persisted grant", resolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && !it.isWritePermission
            })
            expectSecurityDenial { resolver.openFileDescriptor(document, "wt")?.use { } }
            expectSecurityDenial {
                resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            assertTrue("읽기 전용 읽기 유지", resolver.openInputStream(document)?.use {
                it.readBytes().contentEquals(SyntheticDocumentsProvider.seededBytes())
            } == true)
            command(SyntheticDocumentsProvider.Control.REVOKE)
            expectSecurityDenial { resolver.query(document, null, null, null, null)?.close() }
        } catch (failure: Throwable) {
            val reportedFailure = if (failure is AssertionError) failure else AssertionError("합성 SAF 경계 실행 실패")
            primaryFailure = reportedFailure
            throw reportedFailure
        } finally {
            // 한 정리 작업이 실패해도 나머지를 시도하고, 본래 실패를 정리 실패로 덮어쓰지 않는다.
            var cleanupFailure: AssertionError? = null
            for (control in listOf(SyntheticDocumentsProvider.Control.REVOKE, SyntheticDocumentsProvider.Control.RESET)) {
                try {
                    command(control)
                } catch (_: Throwable) {
                    val redacted = AssertionError(if (control == SyntheticDocumentsProvider.Control.REVOKE)
                        "합성 grant 정리 실패" else "합성 fixture 정리 실패")
                    val primary = primaryFailure
                    val previousCleanup = cleanupFailure
                    if (primary != null) primary.addSuppressed(redacted)
                    else if (previousCleanup != null) previousCleanup.addSuppressed(redacted)
                    else cleanupFailure = redacted
                }
            }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun expectSecurityDenial(operation: () -> Unit) {
        var denied = false
        try {
            operation()
        } catch (_: SecurityException) {
            denied = true
        }
        assertTrue("OS grant 경계 거부", denied)
    }

    private fun command(control: SyntheticDocumentsProvider.Control) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val complete = CountDownLatch(1)
        var result = Activity.RESULT_CANCELED
        var issuerUid = -1
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                SyntheticDocumentsProvider.safeLog(control, SyntheticDocumentsProvider.Stage.RECEIVE)
                result = resultCode
                issuerUid = resultData?.getInt(SyntheticDocumentsProvider.EXTRA_UID, -1) ?: -1
                complete.countDown()
            }
        }
        try {
            SyntheticDocumentsProvider.safeLog(control, SyntheticDocumentsProvider.Stage.DISPATCH)
            instrumentation.targetContext.startActivity(Intent().apply {
                component = ComponentName(instrumentation.context.packageName,
                    SyntheticDocumentsProvider.GrantActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(SyntheticDocumentsProvider.EXTRA_CONTROL, control.name)
                putExtra(SyntheticDocumentsProvider.EXTRA_RECEIVER, receiver)
            })
        } catch (_: Exception) {
            throw AssertionError("합성 제어 Activity 시작 실패")
        }
        assertTrue("합성 제어 응답", complete.await(10, TimeUnit.SECONDS))
        assertEquals("합성 제어 결과", Activity.RESULT_OK, result)
        assertTrue("test APK UID 발급자", issuerUid == instrumentation.context.applicationInfo.uid)
        assertTrue("target UID와 발급자 분리", issuerUid != Process.myUid())
    }
}
