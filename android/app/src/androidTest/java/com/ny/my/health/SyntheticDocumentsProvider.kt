package com.ny.my.health

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 테스트 APK UID의 전용 합성 저장소. 실제 계정·건강 데이터·외부 provider를 사용하지 않는다. */
class SyntheticDocumentsProvider : DocumentsProvider() {
    enum class Control {
        RESET, GRANT_READ_WRITE, GRANT_READ_ONLY, REVOKE,
        DUPLICATE, MOVE_OUTSIDE_TREE, DELETE_SEEDED,
        PARTIAL, LOADING, NULL_QUERY, NO_TRUNCATE, WRONG_RETURN_IDENTITY,
        CREATE_AFTER_EFFECT_FAILURE, WRITE_INTERRUPTION, CLOSE_INTERRUPTION,
        BLOCK_BEFORE_EFFECT, RELEASE_BLOCK,
    }

    private data class Node(
        val id: String,
        var parent: String?,
        val mime: String,
        val name: String,
        val file: File?,
    )

    override fun onCreate(): Boolean {
        synchronized(lock) {
            storage = File(checkNotNull(context).noBackupFilesDir, "synthetic-saf-fixture")
            reset()
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = synchronized(lock) {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    Root.COLUMN_ROOT_ID -> "synthetic-root"
                    Root.COLUMN_DOCUMENT_ID -> TREE_ID
                    Root.COLUMN_TITLE -> "합성 테스트"
                    Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
                    else -> null
                }
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        synchronized(lock) { cursor(listOf(node(documentId)), projection) }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?,
    ): Cursor? = synchronized(lock) {
        node(parentDocumentId)
        if (outcome == Control.NULL_QUERY) return@synchronized null
        val children = nodes.values.filter { it.parent == parentDocumentId }
        cursor(if (outcome == Control.PARTIAL) children.take(1) else children, projection).apply {
            if (outcome == Control.LOADING) {
                extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        synchronized(lock) {
            if (!nodes.containsKey(parentDocumentId)) return@synchronized false
            var current = nodes[documentId]?.parent
            val seen = mutableSetOf<String>()
            while (current != null && seen.add(current)) {
                if (current == parentDocumentId) return@synchronized true
                current = nodes[current]?.parent
            }
            false
        }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        checkpoint()
        return synchronized(lock) {
            if (node(parentDocumentId).mime != Document.MIME_TYPE_DIR) syntheticFailure()
            val created = addNode(parentDocumentId, mimeType, displayName)
            if (outcome == Control.CREATE_AFTER_EFFECT_FAILURE) syntheticFailure()
            if (outcome == Control.WRONG_RETURN_IDENTITY) OUTSIDE_ID else created.id
        }
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        checkpoint()
        return synchronized(lock) {
            val file = node(documentId).file ?: syntheticFailure()
            val writable = mode.contains('w')
            if (writable && outcome == Control.WRITE_INTERRUPTION) syntheticFailure()
            if (writable && outcome == Control.CLOSE_INTERRUPTION) {
                // reliable descriptor의 오류는 수신 측 checkError로 관찰한다. close()의 예외를 가정하지 않는다.
                val pipe = ParcelFileDescriptor.createReliablePipe()
                pipe[0].closeWithError("합성 종료 실패")
                return@synchronized pipe[1]
            }
            var flags = ParcelFileDescriptor.parseMode(mode)
            if (writable && outcome == Control.NO_TRUNCATE) {
                flags = flags and ParcelFileDescriptor.MODE_TRUNCATE.inv()
            }
            ParcelFileDescriptor.open(file, flags)
        }
    }

    override fun deleteDocument(documentId: String) = synchronized(lock) {
        if (documentId == TREE_ID || documentId == OUTSIDE_ID) syntheticFailure()
        val removed = node(documentId)
        removed.file?.delete()
        nodes.remove(documentId)
        Unit
    }

    override fun moveDocument(
        sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String,
    ): String = synchronized(lock) {
        val source = node(sourceDocumentId)
        if (source.parent != sourceParentDocumentId || source.file == null ||
            node(targetParentDocumentId).mime != Document.MIME_TYPE_DIR) syntheticFailure()
        source.parent = targetParentDocumentId
        source.id
    }

    private fun cursor(values: List<Node>, projection: Array<out String>?): MatrixCursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        return MatrixCursor(columns).apply {
            values.forEach { entry ->
                addRow(columns.map {
                    when (it) {
                        Document.COLUMN_DOCUMENT_ID -> entry.id
                        Document.COLUMN_DISPLAY_NAME -> entry.name
                        Document.COLUMN_MIME_TYPE -> entry.mime
                        Document.COLUMN_SIZE -> entry.file?.length() ?: 0L
                        Document.COLUMN_FLAGS -> if (entry.mime == Document.MIME_TYPE_DIR)
                            Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or
                            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_MOVE
                        else -> null
                    }
                })
            }
        }
    }

    /** 외부 입력은 고정 enum뿐이며 grant 수신자와 tree도 고정한다. */
    class GrantActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            @Suppress("DEPRECATION")
            val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
            var success = false
            try {
                val command = Control.entries.singleOrNull { it.name == intent.getStringExtra(EXTRA_CONTROL) }
                    ?: throw IllegalArgumentException("합성 제어 거부")
                val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID)
                when (command) {
                    Control.GRANT_READ_WRITE, Control.GRANT_READ_ONLY -> {
                        val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            if (command == Control.GRANT_READ_WRITE) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
                        grantUriPermission(TARGET_PACKAGE, tree, access or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    }
                    Control.REVOKE -> revokeUriPermission(TARGET_PACKAGE, tree, ACCESS_FLAGS)
                    else -> control(command)
                }
                success = true
            } catch (_: Exception) {
                // 원시 provider 예외와 URI는 callback·공개 로그로 전달하지 않는다.
            } finally {
                receiver?.send(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                    Bundle().apply { putInt(EXTRA_UID, Process.myUid()) })
                finish()
            }
        }
    }

    companion object {
        const val AUTHORITY = "com.ny.my.health.test.synthetic.documents"
        const val TARGET_PACKAGE = "com.ny.my.health"
        const val TREE_ID = "opaque-tree-a"
        const val SEEDED_ID = "opaque-document-b"
        private const val OUTSIDE_ID = "opaque-directory-c"
        private const val NESTED_ID = "opaque-directory-d"
        const val EXTRA_CONTROL = "synthetic_control"
        const val EXTRA_RECEIVER = "synthetic_receiver"
        const val EXTRA_UID = "synthetic_uid"
        const val ACCESS_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        fun seededBytes(): ByteArray = "synthetic fixture only\n".toByteArray(Charsets.UTF_8)

        private val lock = Any()
        private val nodes = linkedMapOf<String, Node>()
        private lateinit var storage: File
        private var outcome: Control? = null
        @Volatile private var barrier: CountDownLatch? = null

        private fun node(id: String): Node = nodes[id] ?: syntheticFailure()
        private fun syntheticFailure(): Nothing = throw FileNotFoundException("합성 작업 실패")

        private fun addNode(parent: String, mime: String, name: String): Node {
            // 표시 이름·document ID를 파일 경로로 해석하지 않는다.
            val id = UUID.randomUUID().toString()
            val file = if (mime == Document.MIME_TYPE_DIR) null else File(storage, UUID.randomUUID().toString())
            file?.writeBytes(byteArrayOf())
            return Node(id, parent, mime, name, file).also { nodes[id] = it }
        }

        private fun reset() {
            barrier?.countDown()
            barrier = null
            outcome = null
            storage.deleteRecursively()
            check(storage.mkdirs()) { "합성 저장소 준비 실패" }
            nodes.clear()
            nodes[TREE_ID] = Node(TREE_ID, null, Document.MIME_TYPE_DIR, "합성 폴더", null)
            nodes[OUTSIDE_ID] = Node(OUTSIDE_ID, null, Document.MIME_TYPE_DIR, "합성 외부", null)
            nodes[NESTED_ID] = Node(NESTED_ID, TREE_ID, Document.MIME_TYPE_DIR, "합성 하위", null)
            val file = File(storage, "seed")
            file.writeBytes(seededBytes())
            nodes[SEEDED_ID] = Node(SEEDED_ID, NESTED_ID, "text/plain", "seed.txt", file)
        }

        private fun control(command: Control) = synchronized(lock) {
            when (command) {
                Control.RESET -> reset()
                Control.DUPLICATE -> addNode(NESTED_ID, "text/plain", "seed.txt").file!!.writeBytes(seededBytes())
                Control.MOVE_OUTSIDE_TREE -> node(SEEDED_ID).parent = OUTSIDE_ID
                Control.DELETE_SEEDED -> { nodes.remove(SEEDED_ID)?.file?.delete(); Unit }
                Control.BLOCK_BEFORE_EFFECT -> barrier = CountDownLatch(1)
                Control.RELEASE_BLOCK -> { barrier?.countDown(); barrier = null }
                else -> outcome = command
            }
        }

        private fun checkpoint() {
            if (barrier?.await(10, TimeUnit.SECONDS) == false) syntheticFailure()
        }
    }
}
