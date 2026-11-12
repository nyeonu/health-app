package com.ny.my.health.core

import java.io.IOException
import java.net.URLEncoder
import java.time.Clock
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface DriveTransport {
    suspend fun execute(method: String, url: String, contentType: String? = null, body: ByteArray? = null): DriveResponse
}

data class DriveResponse(val status: Int, val body: String)

/** Uses drive.file and only this app's marked, privately owned My Drive tree. */
class PrivateDriveUploader(
    private val transport: DriveTransport,
    private val profiling: SyncProfiling? = null,
    private val clock: Clock = Clock.systemUTC(),
) : DriveUploader {
    private val mutex = Mutex()
    private val base = "https://www.googleapis.com/drive/v3/files"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"
    private val folderMime = "application/vnd.google-apps.folder"
    private val fields = "id,name,mimeType,parents,ownedByMe,shared,trashed,appProperties,driveId,md5Checksum,sha256Checksum"

    override suspend fun upsert(file: CsvFile): String = mutex.withLock {
        preparedBatch = null
        currentCoroutineContext().ensureActive()
        val (dataset, name, bytes) = prepare(file)
        val root = appFolder()
        val parent = folder(dataset, root, DriveOperation.DATASET_FOLDER_LOOKUP, DriveOperation.DATASET_FOLDER_CREATE)
        uploadFile(name, parent, bytes)
    }

    override suspend fun upsertBatch(files: List<CsvFile>, onConfirmed: suspend (String) -> Unit) =
        upsertTracked(files) { _, id -> onConfirmed(id) }

    override suspend fun upsertTracked(files: List<CsvFile>, onConfirmed: suspend (CsvFile, String) -> Unit) = mutex.withLock {
        preparedBatch = null
        currentCoroutineContext().ensureActive()
        val prepared = files.map(::prepare)
        require(files.map { it.relativePath }.toSet().size == files.size) { "Duplicate export path" }
        if (prepared.isEmpty()) return@withLock
        val root = appFolder()
        val parents = mutableMapOf<String, String>()
        for ((dataset) in prepared) {
            if (dataset !in parents) {
                parents[dataset] = folder(dataset, root, DriveOperation.DATASET_FOLDER_LOOKUP, DriveOperation.DATASET_FOLDER_CREATE)
            }
        }
        val slots = Semaphore(2)
        val confirmations = Mutex()
        val failure = AtomicReference<Throwable?>(null)
        coroutineScope {
            prepared.mapIndexed { index, (dataset, name, bytes) ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    slots.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (failure.get() != null) return@withPermit
                        try {
                            val confirmed = uploadFile(name, parents.getValue(dataset), bytes)
                            confirmations.withLock {
                                currentCoroutineContext().ensureActive()
                                if (failure.get() == null) onConfirmed(files[index], confirmed)
                            }
                        } catch (error: Throwable) {
                            // 대기 파일이 permit을 얻기 전에 실패를 게시한다.
                            failure.compareAndSet(null, error)
                            throw error
                        }
                    }
                }
            }.awaitAll()
        }
        Unit
    }

    // 검증한 현재 배치만 보관하며 새 조회 또는 쓰기 진입에서 소모한다.
    private data class PreparedBatch(val files: List<PlannedFile>, val root: String?,
        val parents: Map<String, String?>, val targets: Map<FileKey, String?>)
    private var preparedBatch: PreparedBatch? = null

    suspend fun reconcileTracked(files: List<PlannedFile>, knownTargets: Map<Dataset, Set<String>>): ReconciledFiles = mutex.withLock {
        preparedBatch = null
        destinationBoundary {
            validatePlanned(files)
            if (files.isEmpty()) return@destinationBoundary ReconciledFiles(emptyList(), emptyList(), emptySet())
            val date = files.first().key.date
            val known = knownTargets.mapValues { (_, ids) -> ids.toSet() }.toMutableMap()
            for (file in files) file.previousTargetHandle?.let {
                known[file.key.dataset] = known[file.key.dataset].orEmpty() + it
            }
            val root = operation(DriveOperation.APP_FOLDER_LOOKUP) {
                find(DriveOperation.APP_FOLDER_LOOKUP, "HealthApp", "root", folderMime)
            }?.let(::id)
            val parents = mutableMapOf<String, String?>()
            val byDataset = mutableMapOf<Dataset, JsonObject?>()
            for (dataset in (files.map { it.key.dataset } + known.keys).distinct()) {
                val name = datasetPath(dataset)
                val parent = if (root == null) null else operation(DriveOperation.DATASET_FOLDER_LOOKUP) {
                    find(DriveOperation.DATASET_FOLDER_LOOKUP, name, root, folderMime)
                }?.let(::id)
                parents[name] = parent
                val target = if (parent == null) null else operation(DriveOperation.FILE_LOOKUP) {
                    find(DriveOperation.FILE_LOOKUP, "$date.csv", parent, "text/csv")
                }
                byDataset[dataset] = target
                val ids = known[dataset].orEmpty()
                if (ids.isNotEmpty() && (target == null || ids != setOf(id(target)))) {
                    throw DestinationException(BlockReason.DESTINATION_BLOCKED)
                }
            }
            val confirmed = mutableListOf<FileConfirmation>()
            val pending = mutableListOf<PlannedFile>()
            val targets = mutableMapOf<FileKey, String?>()
            for (file in files) {
                val target = byDataset[file.key.dataset]
                if (target == null && file.createMayHaveSucceeded) {
                    throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
                }
                if (target != null && checksumMatches(target, file.file.content.toByteArray(Charsets.UTF_8), requirePresent = true)) {
                    confirmed += FileConfirmation(file.key, id(target), clock.instant())
                } else {
                    pending += file
                    targets[file.key] = target?.let(::id)
                }
            }
            preparedBatch = PreparedBatch(pending.toList(), root, parents.toMap(), targets.toMap())
            ReconciledFiles(confirmed, pending, emptySet())
        }
    }

    suspend fun writeTrackedPending(files: List<PlannedFile>, onConfirmed: suspend (CsvFile, String) -> Unit) = mutex.withLock {
        val batch = preparedBatch
        preparedBatch = null
        destinationBoundary {
            if (batch == null || batch.files != files) throw DestinationException(BlockReason.DESTINATION_RECONCILIATION_REQUIRED)
            validatePlanned(files)
            if (files.isEmpty()) return@destinationBoundary
            val root = batch.root ?: appFolder()
            val parents = mutableMapOf<String, String>()
            for (file in files) {
                val name = datasetPath(file.key.dataset)
                if (name !in parents) parents[name] = batch.parents[name] ?: folder(name, root,
                    DriveOperation.DATASET_FOLDER_LOOKUP, DriveOperation.DATASET_FOLDER_CREATE)
            }
            val slots = Semaphore(2)
            val confirmations = Mutex()
            val failure = AtomicReference<Throwable?>(null)
            coroutineScope {
                files.map { file -> async(start = CoroutineStart.UNDISPATCHED) {
                    slots.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (failure.get() != null) return@withPermit
                        try {
                            val (_, name, bytes) = prepare(file.file)
                            val parent = parents.getValue(datasetPath(file.key.dataset))
                            val target = batch.targets[file.key]
                            val result = mutateFile(name, parent, bytes, target, verifyChecksum = true)
                            confirmations.withLock {
                                currentCoroutineContext().ensureActive()
                                if (failure.get() == null) onConfirmed(file.file, result)
                            }
                        } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                            throw error
                        }
                    }
                } }.awaitAll()
            }
            Unit
        }
    }

    private fun validatePlanned(files: List<PlannedFile>) {
        require(files.map { it.key }.distinct().size == files.size)
        require(files.map { it.file.relativePath }.distinct().size == files.size)
        require(files.map { Triple(it.key.generation, it.key.date, it.key.scopeKey) }.distinct().size <= 1)
        for (file in files) {
            prepare(file.file)
            require(file.file.relativePath == "${datasetPath(file.key.dataset)}/${file.key.date}.csv")
            require(file.byteSha256 == digest("SHA-256", file.file.content.toByteArray(Charsets.UTF_8)))
        }
    }

    private fun datasetPath(dataset: Dataset): String = when (dataset) {
        Dataset.DAILY -> "daily"
        Dataset.WORKOUTS -> "workouts"
        Dataset.HEART_RATE -> "heart-rate"
        Dataset.SLEEP -> "sleep"
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun checksumMatches(value: JsonObject, bytes: ByteArray, requirePresent: Boolean): Boolean {
        if (requirePresent && value["sha256Checksum"] != JsonPrimitive(digest("SHA-256", bytes))) return false
        var present = false
        for ((field, algorithm) in listOf("md5Checksum" to "MD5", "sha256Checksum" to "SHA-256")) {
            val checksum = value[field] ?: continue
            present = true
            if (checksum != JsonPrimitive(digest(algorithm, bytes))) return false
        }
        return present || !requirePresent
    }

    private fun destinationReason(details: DriveFailureDetails): BlockReason {
        if (details.httpStatus in setOf(401, 403)) return BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED
        if (details.operation in setOf(DriveOperation.APP_FOLDER_CREATE, DriveOperation.DATASET_FOLDER_CREATE, DriveOperation.FILE_CREATE)) {
            return BlockReason.DESTINATION_RECONCILIATION_REQUIRED
        }
        if (details.kind == DriveFailureKind.NETWORK || details.httpStatus in setOf(429, 500, 502, 503, 504)) {
            return BlockReason.DESTINATION_TRANSIENT_FAILURE
        }
        if (details.operation == DriveOperation.FILE_UPDATE && details.kind in setOf(DriveFailureKind.RESPONSE_INVALID, DriveFailureKind.TARGET_REJECTED)) {
            return BlockReason.DESTINATION_RECONCILIATION_REQUIRED
        }
        return BlockReason.DESTINATION_BLOCKED
    }

    private suspend fun <T> destinationBoundary(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (safe: DestinationException) {
        throw safe
    } catch (safe: DriveUploadException) {
        throw DestinationException(destinationReason(safe.details))
    } catch (_: Exception) {
        throw DestinationException(BlockReason.DESTINATION_BLOCKED)
    }

    private fun prepare(file: CsvFile): Triple<String, String, ByteArray> {
        require(Regex("(daily|workouts|heart-rate|sleep)/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.csv").matches(file.relativePath)) {
            "Invalid export path"
        }
        val (dataset, name) = file.relativePath.split('/')
        require(runCatching { LocalDate.parse(name.removeSuffix(".csv")) }.isSuccess) { "Invalid export date" }
        val bytes = file.content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 5_000_000) { "Export exceeds simple upload limit" }
        return Triple(dataset, name, bytes)
    }

    private suspend fun uploadFile(name: String, parent: String, bytes: ByteArray): String {
        val existing = operation(DriveOperation.FILE_LOOKUP) { find(DriveOperation.FILE_LOOKUP, name, parent, "text/csv") }
        return mutateFile(name, parent, bytes, existing?.let(::id))
    }

    private suspend fun mutateFile(name: String, parent: String, bytes: ByteArray, target: String?, verifyChecksum: Boolean = false): String {
        return if (target != null) operation(DriveOperation.FILE_UPDATE) {
            val confirmed = request(DriveOperation.FILE_UPDATE, "PATCH", "$upload/$target?uploadType=media&fields=${encode(fields)}", "text/csv; charset=UTF-8", bytes)
            validate(confirmed, name, parent, "text/csv")
            check(id(confirmed) == target) { "Unconfirmed Drive update" }
            check(!verifyChecksum || checksumMatches(confirmed, bytes, requirePresent = false)) { "Unconfirmed Drive checksum" }
            target
        } else operation(DriveOperation.FILE_CREATE) {
            val boundary = "healthapp-${UUID.randomUUID()}"
            val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata(name, parent, "text/csv")}\r\n--$boundary\r\nContent-Type: text/csv; charset=UTF-8\r\n\r\n"
            val body = prefix.toByteArray(Charsets.UTF_8) + bytes + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val confirmed = request(DriveOperation.FILE_CREATE, "POST", "$upload?uploadType=multipart&ignoreDefaultVisibility=true&fields=${encode(fields)}", "multipart/related; boundary=$boundary", body)
            validate(confirmed, name, parent, "text/csv")
            check(!verifyChecksum || checksumMatches(confirmed, bytes, requirePresent = false)) { "Unconfirmed Drive checksum" }
            id(confirmed)
        }
    }

    private suspend fun appFolder(): String {
        // drive.file need not expose My Drive's own metadata. Use the documented
        // root alias in the membership query rather than GET files/root.
        val found = operation(DriveOperation.APP_FOLDER_LOOKUP) {
            find(DriveOperation.APP_FOLDER_LOOKUP, "HealthApp", "root", folderMime)
        }
        if (found != null) return id(found)
        val created = operation(DriveOperation.APP_FOLDER_CREATE) {
            // Omitting parents places a new item directly in My Drive (Drive v3 contract).
            val value = request(DriveOperation.APP_FOLDER_CREATE, "POST", "$base?ignoreDefaultVisibility=true&fields=${encode(fields)}",
                "application/json; charset=UTF-8", metadata("HealthApp", null, folderMime).toString().toByteArray(Charsets.UTF_8))
            validate(value, "HealthApp", "root", folderMime)
            value
        }
        return operation(DriveOperation.APP_FOLDER_LOOKUP) {
            // Confirm root membership and identity before any dataset or CSV write.
            val confirmed = find(DriveOperation.APP_FOLDER_LOOKUP, "HealthApp", "root", folderMime)
            check(confirmed != null && id(confirmed) == id(created) && confirmed["parents"] == created["parents"]) {
                "Unconfirmed Drive app folder"
            }
            id(confirmed)
        }
    }

    private suspend fun folder(name: String, parent: String, lookup: DriveOperation, create: DriveOperation): String {
        val found = operation(lookup) { find(lookup, name, parent, folderMime) }
        if (found != null) return id(found)
        return operation(create) {
            val created = request(create, "POST", "$base?ignoreDefaultVisibility=true&fields=${encode(fields)}", "application/json; charset=UTF-8", metadata(name, parent, folderMime).toString().toByteArray(Charsets.UTF_8))
            validate(created, name, parent, folderMime)
            id(created)
        }
    }

    private fun metadata(name: String, parent: String?, mime: String) = buildJsonObject {
        put("name", name)
        put("mimeType", mime)
        if (parent != null) putJsonArray("parents") { add(parent) }
        putJsonObject("appProperties") { put("healthApp", "p0-v1") }
    }

    private suspend fun find(operation: DriveOperation, name: String, parent: String, mime: String): JsonObject? {
        // name and parent are allowlisted before interpolation; URI encoding is separate.
        val q = "'$parent' in parents and name = '$name' and trashed = false and appProperties has { key='healthApp' and value='p0-v1' }"
        var page: String? = null
        var found: JsonObject? = null
        val seen = mutableSetOf<String>()
        do {
            val url = "$base?corpora=user&spaces=drive&q=${encode(q)}&fields=${encode("nextPageToken,incompleteSearch,files($fields)")}&pageSize=100" + (page?.let { "&pageToken=${encode(it)}" } ?: "")
            val result = request(operation, "GET", url)
            check(result["incompleteSearch"] == null || result["incompleteSearch"] == JsonPrimitive(false)) { "Incomplete Drive lookup" }
            val entries = result["files"] as? JsonArray ?: error("Malformed Drive lookup")
            for (entry in entries) {
                val item = entry as? JsonObject ?: error("Malformed Drive entry")
                validate(item, name, parent, mime)
                check(found == null) { "Ambiguous Drive target" }
                found = item
            }
            page = result["nextPageToken"]?.let {
                val token = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                    ?: error("Malformed Drive pagination")
                check(token.isNotEmpty() && token.length <= 4096 && seen.add(token) && seen.size <= 100) { "Invalid Drive pagination" }
                token
            }
        } while (page != null)
        return found
    }

    private fun validate(value: JsonObject, name: String, parent: String, mime: String) {
        id(value)
        val parents = value["parents"] as? JsonArray
        val canonicalParent = (parents?.singleOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Root membership comes from the server-side 'root' in parents predicate.
        // Responses contain canonical IDs, not the alias. Child parents remain exact.
        val validParent = if (parent == "root") {
            canonicalParent != null && canonicalParent != "root" && Regex("[A-Za-z0-9_-]{1,200}").matches(canonicalParent)
        } else value["parents"] == buildJsonArray { add(parent) }
        check(value["name"] == JsonPrimitive(name) && value["mimeType"] == JsonPrimitive(mime) &&
            validParent &&
            value["ownedByMe"] == JsonPrimitive(true) && value["shared"] == JsonPrimitive(false) &&
            value["trashed"] == JsonPrimitive(false) && value["driveId"] == null &&
            (value["appProperties"] as? JsonObject)?.get("healthApp") == JsonPrimitive("p0-v1")) {
            "Unsafe or malformed Drive target"
        }
    }

    private fun id(value: JsonObject): String {
        val id = (value["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        check(id != null && Regex("[A-Za-z0-9_-]{1,200}").matches(id)) { "Malformed Drive ID" }
        return id
    }

    private suspend fun request(operation: DriveOperation, method: String, url: String, type: String? = null, bytes: ByteArray? = null): JsonObject {
        val execute: suspend () -> JsonObject = {
            val response = transport.execute(method, url, type, bytes)
            if (response.status !in 200..299) throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.HTTP, response.status.takeIf { it in 100..599 }))
            // API 본문, CSV, 파서 예외는 외부 오류에 첨부하지 않는다.
            runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
                ?: throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.RESPONSE_INVALID))
        }
        return if (profiling != null) profiling.request(operation, execute) else execute()
    }

    private suspend fun <T> operation(operation: DriveOperation, block: suspend () -> T): T {
        val execute: suspend () -> T = {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (safe: DriveUploadException) {
                throw safe
            } catch (_: IOException) {
                throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.NETWORK))
            } catch (_: Exception) {
                // 공급자 메타데이터, 파일 내용, ID, 예외 원인은 이 경계를 넘지 않는다.
                throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.TARGET_REJECTED))
            }
        }
        return if (profiling != null) profiling.operation(operation, execute) else execute()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
