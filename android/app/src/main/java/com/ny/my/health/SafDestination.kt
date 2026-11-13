package com.ny.my.health

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ny.my.health.core.*
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 확인한 한 묶음만 소비한다. 본문과 대상 증명은 실행 메모리에만 존재한다. */
internal class SafDestination(
    private val resolver: ContentResolver,
    private val proof: SafTreeProof,
    private val generation: DestinationGeneration,
    private val knownTargets: suspend (DestinationGeneration, LocalDate) -> Map<Dataset, Set<String>>,
    private val clock: Clock = Clock.systemUTC(),
) : StorageDestination {
    private data class Node(val id: String, val name: String, val mime: String, val flags: Int)
    private data class Target(val directory: Node?, val file: Node?)
    private data class Reservation(val files: List<PlannedFile>, val targets: Map<FileKey, Target>)
    private val mutex = Mutex()
    private var reservation: Reservation? = null
    private val tree get() = proof.treeUri
    private val root get() = uri(DocumentsContract.getTreeDocumentId(tree))

    override suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles = mutex.withLock {
        reservation = null
        validate(files)
        if (files.isEmpty()) return@withLock ReconciledFiles(emptyList(), emptyList(), emptySet())
        // 저장소 예외와 취소는 provider 예외로 바꾸지 않는다.
        val known = knownTargets(generation, files.first().key.date)
        val found = provider { snapshot(files.first().key.date, known) }
        val confirmed = mutableListOf<FileConfirmation>()
        val pending = mutableListOf<PlannedFile>()
        val reserved = mutableMapOf<FileKey, Target>()
        val blocked = mutableSetOf<FileKey>()
        for (file in files) {
            val target = found.getValue(file.key.dataset)
            val handles = known[file.key.dataset].orEmpty() + listOfNotNull(file.previousTargetHandle)
            if (handles.any { it != target.file?.let { node -> uri(node.id).toString() } }) {
                blocked += file.key
            } else if (target.file == null && file.createMayHaveSucceeded) {
                throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
            } else if (target.file != null && provider { matches(uri(target.file.id), bytes(file)) }) {
                if (provider { snapshot(file.key.date, known).getValue(file.key.dataset) } != target) blocked()
                confirmed += FileConfirmation(file.key, uri(target.file.id).toString(), clock.instant())
            } else {
                pending += file
                reserved[file.key] = target
            }
        }
        reservation = Reservation(pending.toList(), reserved.toMap())
        ReconciledFiles(confirmed, pending, blocked)
    }

    override suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit) = mutex.withLock {
        val reserved = reservation
        reservation = null
        if (reserved == null || reserved.files != files) blocked()
        validate(files)
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val known = knownTargets(generation, file.key.date)
            val handle = provider {
                var target = snapshot(file.key.date, known).getValue(file.key.dataset)
                if (target != reserved.targets[file.key]) blocked()
                val directory = target.directory ?: create(root, Document.MIME_TYPE_DIR, path(file.key.dataset))
                currentCoroutineContext().ensureActive()
                val node = target.file ?: create(uri(directory.id), "text/csv", "${file.key.date}.csv")
                target = Target(directory, node)
                // 생성 반환값도 전체 경로를 다시 열어 확인한다.
                if (snapshot(file.key.date, known).getValue(file.key.dataset) != target) blocked()
                currentCoroutineContext().ensureActive()
                val handle = uri(node.id)
                val descriptor = resolver.openFileDescriptor(handle, proof.writeMode) ?: blocked()
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {
                    it.write(bytes(file)); it.flush(); descriptor.checkError()
                }
                currentCoroutineContext().ensureActive()
                if (snapshot(file.key.date, known).getValue(file.key.dataset) != target || !matches(handle, bytes(file))) blocked()
                if (snapshot(file.key.date, known).getValue(file.key.dataset) != target) blocked()
                handle.toString()
            }
            currentCoroutineContext().ensureActive()
            // 원격 close/readback 후 로컬 확인을 직렬 호출한다. 콜백 실패는 그대로 전파한다.
            onConfirmed(FileConfirmation(file.key, handle, clock.instant()))
        }
    }

    private suspend fun snapshot(date: LocalDate, known: Map<Dataset, Set<String>>): Map<Dataset, Target> {
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT < 29 || proof.writeMode != "wt" || resolver.persistedUriPermissions.none {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }) blocked()
        val rootNode = query(root).singleOrNull() ?: blocked()
        if (rootNode.id != DocumentsContract.getTreeDocumentId(tree)) blocked()
        directory(rootNode)
        val children = children(rootNode)
        val targets = Dataset.entries.associateWith { dataset ->
            val dir = children.singleOrNull { it.name == path(dataset) }
            if (dir != null) directory(dir)
            val file = dir?.let { children(it).singleOrNull { child -> child.name == "$date.csv" } }
            if (file != null) uri(file.id)
            if (file != null && (file.mime != "text/csv" || file.flags and Document.FLAG_SUPPORTS_WRITE == 0)) blocked()
            val handles = known[dataset].orEmpty()
            if (handles.any { it != file?.let { n -> uri(n.id).toString() } }) blocked()
            Target(dir, file)
        }
        val identities = listOf(rootNode.id) + targets.values.flatMap { listOfNotNull(it.directory?.id, it.file?.id) }
        if (identities.size != identities.distinct().size) blocked()
        return targets
    }

    private fun directory(node: Node) {
        if (node.mime != Document.MIME_TYPE_DIR || node.flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) blocked()
    }
    private suspend fun children(parent: Node): List<Node> {
        val result = query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id))
        for (node in result) {
            currentCoroutineContext().ensureActive()
            if (node.id == parent.id || Build.VERSION.SDK_INT < 29 ||
                !DocumentsContract.isChildDocument(resolver, uri(parent.id), uri(node.id))) blocked()
            if (query(uri(node.id)).singleOrNull() != node) blocked()
        }
        return result
    }
    private fun query(target: Uri): List<Node> = resolver.query(target, arrayOf(Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS), null, null, null).use { cursor ->
        if (cursor == null || !complete(cursor)) blocked()
        val nodes = mutableListOf<Node>()
        while (cursor.moveToNext()) {
            if (nodes.size >= 4096 || (0..3).any { cursor.isNull(it) }) blocked()
            val node = Node(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3))
            if (node.id.isEmpty() || node.name.isEmpty() || node.mime.isEmpty() ||
                node.flags and (Document.FLAG_PARTIAL or Document.FLAG_VIRTUAL_DOCUMENT) != 0 ||
                nodes.any { it.id == node.id || it.name == node.name }) blocked()
            nodes += node
        }
        if (!complete(cursor)) blocked()
        nodes
    }
    private fun complete(cursor: Cursor) = !cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) &&
        !cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)
    private fun create(parent: Uri, mime: String, name: String): Node {
        val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: blocked()
        if (!validHandle(created.toString())) blocked()
        val node = query(created).singleOrNull() ?: blocked()
        if (created != uri(node.id) || node.name != name || node.mime != mime || Build.VERSION.SDK_INT < 29 ||
            !DocumentsContract.isChildDocument(resolver, parent, created)) blocked()
        return node
    }
    private suspend fun matches(target: Uri, expected: ByteArray): Boolean = resolver.openInputStream(target)?.use { input ->
        val actual = ByteArray(expected.size + 1)
        var count = 0
        while (count < actual.size) {
            currentCoroutineContext().ensureActive()
            val read = input.read(actual, count, actual.size - count)
            if (read < 0) break
            if (read == 0) blocked()
            count += read
        }
        count == expected.size && actual.copyOf(count).contentEquals(expected)
    } ?: blocked()
    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).also {
        if (!validHandle(it.toString())) blocked()
    }
    private fun validHandle(value: String) = value.toByteArray(Charsets.UTF_8).size in 1..4096 &&
        value.none { it.isWhitespace() || it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(value)
    private fun bytes(file: PlannedFile) = file.file.content.toByteArray(Charsets.UTF_8)
    private fun validate(files: List<PlannedFile>) {
        if (files.map { it.key }.distinct().size != files.size ||
            files.map { Triple(it.key.date, it.key.scopeKey, it.key.revision) }.distinct().size > 1 ||
            files.any { it.key.generation != generation || it.file.relativePath != "${path(it.key.dataset)}/${it.key.date}.csv" ||
                it.byteSha256 != MessageDigest.getInstance("SHA-256").digest(bytes(it)).joinToString("") { b -> "%02x".format(b) } }) blocked()
    }
    private fun path(dataset: Dataset) = when (dataset) {
        Dataset.DAILY -> "daily"; Dataset.WORKOUTS -> "workouts"; Dataset.HEART_RATE -> "heart-rate"; Dataset.SLEEP -> "sleep"
    }
    private suspend fun <T> provider(action: suspend () -> T): T = try { withContext(Dispatchers.IO) { action() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: SecurityException) { throw DestinationException(BlockReason.DESTINATION_BLOCKED) }
        catch (safe: DestinationException) { throw safe }
        catch (_: Exception) { throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED) }
    private fun blocked(): Nothing = throw DestinationException(BlockReason.DESTINATION_BLOCKED)
}
