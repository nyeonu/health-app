package com.ny.my.health

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ny.my.health.core.BlockReason
import com.ny.my.health.core.DestinationException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 실행 메모리 전용 증명. 구현과 생성은 성공한 설정 경계에만 둔다. */
internal sealed interface SafTreeProof {
    val treeUri: Uri
    val writeMode: String
    val backgroundCapable: Boolean
}

/** 실제 OS grant와 합성 파일 왕복을 확인한 전경 전용 설정 경계. */
internal class SafTreeConfiguration private constructor(
    private val resolver: ContentResolver,
    private val allowedAuthority: String,
) {
    constructor(resolver: ContentResolver) : this(resolver, LOCAL_AUTHORITY)

    private class VerifiedProof(override val treeUri: Uri) : SafTreeProof {
        override val writeMode = "wt"
        override val backgroundCapable = false
        override fun toString() = "SafTreeProof(redacted, foregroundOnly)"
    }

    suspend fun select(uri: Uri?, returnedFlags: Int, privacyConsent: Boolean): SafTreeProof = boundary {
        validateSelection(uri, privacyConsent)
        if (!hasRequiredResultFlags(returnedFlags)) blocked()
        val tree = requireNotNull(uri)
        resolver.takePersistableUriPermission(tree, returnedFlags and ACCESS_FLAGS)
        verify(tree)
    }

    suspend fun restore(treeUri: String, privacyConsent: Boolean): SafTreeProof = boundary {
        val tree = Uri.parse(treeUri)
        validateSelection(tree, privacyConsent)
        verify(tree)
    }

    private fun validateSelection(uri: Uri?, consent: Boolean) {
        if (Build.VERSION.SDK_INT < 29) blocked()
        if (!consent || !isAllowedTree(uri, allowedAuthority)) blocked()
    }

    private suspend fun verify(tree: Uri): SafTreeProof {
        currentCoroutineContext().ensureActive()
        if (resolver.persistedUriPermissions.none {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }) blocked()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        resolver.query(root, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS), null, null, null).use { cursor ->
            if (cursor == null || !isComplete(cursor) || !cursor.moveToFirst() ||
                cursor.isNull(0) || cursor.getString(0) != rootId ||
                cursor.getString(1) != Document.MIME_TYPE_DIR || cursor.isNull(2) ||
                cursor.getInt(2) and Document.FLAG_DIR_SUPPORTS_CREATE == 0 || cursor.moveToNext()) blocked()
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val existing = mutableSetOf<String>()
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null).use { cursor ->
            if (cursor == null || !isComplete(cursor)) blocked()
            while (cursor.moveToNext()) {
                if (cursor.isNull(0) || !existing.add(cursor.getString(0))) blocked()
            }
            if (!isComplete(cursor)) blocked()
        }
        probe(tree, root, existing)
        currentCoroutineContext().ensureActive()
        return VerifiedProof(tree)
    }

    private suspend fun probe(tree: Uri, root: Uri, existing: Set<String>) {
        var owned: Uri? = null
        var primary: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            val name = ".health-synthetic-probe-${UUID.randomUUID()}.tmp"
            val created = DocumentsContract.createDocument(resolver, root, "application/octet-stream", name)
                ?: blocked()
            // 반환 URI가 새로 만든 직접 자식임을 확인한 뒤에만 소유 정리 대상으로 인정한다.
            if (created.scheme != "content" || created.authority != tree.authority ||
                !DocumentsContract.isTreeUri(created) ||
                DocumentsContract.getTreeDocumentId(created) != DocumentsContract.getTreeDocumentId(tree) ||
                DocumentsContract.getDocumentId(created) in existing ||
                DocumentsContract.getDocumentId(created) == DocumentsContract.getTreeDocumentId(tree) ||
                Build.VERSION.SDK_INT < 29 || !DocumentsContract.isChildDocument(resolver, root, created)) blocked()
            resolver.query(created, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE), null, null, null).use { cursor ->
                if (cursor == null || !isComplete(cursor) || !cursor.moveToFirst() ||
                    cursor.getString(0) != DocumentsContract.getDocumentId(created) ||
                    cursor.getString(1) != name || cursor.getString(2) != "application/octet-stream" ||
                    cursor.moveToNext()) blocked()
            }
            owned = created
            currentCoroutineContext().ensureActive()
            write(created, ByteArray(128) { 65 }, "w")
            currentCoroutineContext().ensureActive()
            val expected = "synthetic-probe\n".toByteArray(Charsets.UTF_8)
            write(created, expected, "wt")
            currentCoroutineContext().ensureActive()
            val actual = resolver.openInputStream(created)?.use { input ->
                val buffer = ByteArray(expected.size + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) blocked()
                    count += read
                }
                buffer.copyOf(count)
            } ?: blocked()
            if (!probeMatches(expected, actual)) blocked()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            val cleanup = owned
            if (cleanup != null) withContext(NonCancellable) {
                try {
                    if (!DocumentsContract.deleteDocument(resolver, cleanup)) blocked()
                } catch (cancel: CancellationException) {
                    if (primary is CancellationException) primary.addSuppressed(safeFailure()) else throw cancel
                } catch (_: Exception) {
                    if (primary is CancellationException) primary.addSuppressed(safeFailure()) else blocked()
                }
            }
        }
    }

    private fun write(uri: Uri, bytes: ByteArray, mode: String) {
        val descriptor = resolver.openFileDescriptor(uri, mode) ?: blocked()
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            output.write(bytes)
            output.flush()
            descriptor.checkError()
        }
    }

    private suspend fun <T> boundary(operation: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { operation() }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        throw safeFailure()
    }

    companion object {
        private const val LOCAL_AUTHORITY = "com.android.externalstorage.documents"
        private const val ACCESS_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        /** 합성 provider를 명시하는 테스트에서만 호출한다. URI에서 authority를 추출하지 않는다. */
        internal fun forTesting(resolver: ContentResolver, testAuthority: String) =
            SafTreeConfiguration(resolver, testAuthority)

        internal fun hasRequiredResultFlags(flags: Int): Boolean {
            val required = ACCESS_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            return flags and required == required
        }
        internal fun isAllowedTree(uri: Uri?, authority: String): Boolean = uri != null &&
            uri.scheme == "content" && uri.authority == authority && DocumentsContract.isTreeUri(uri) &&
            uri.pathSegments.size == 2 && uri.pathSegments[1].isNotEmpty() && uri.query == null && uri.fragment == null
        internal fun probeMatches(expected: ByteArray, actual: ByteArray) = expected.contentEquals(actual)
        private fun isComplete(cursor: Cursor) = !cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) &&
            !cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)
        private fun safeFailure() = DestinationException(BlockReason.DESTINATION_BLOCKED)
        private fun blocked(): Nothing = throw safeFailure()
    }
}
