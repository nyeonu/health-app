package com.ny.my.health.core

import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface DriveTransport {
    suspend fun execute(method: String, url: String, contentType: String? = null, body: ByteArray? = null): DriveResponse
}

data class DriveResponse(val status: Int, val body: String)

/** Uses drive.file and only this app's marked, privately owned My Drive tree. */
class PrivateDriveUploader(private val transport: DriveTransport) : DriveUploader {
    private val mutex = Mutex()
    private val base = "https://www.googleapis.com/drive/v3/files"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"
    private val folderMime = "application/vnd.google-apps.folder"
    private val fields = "id,name,mimeType,parents,ownedByMe,shared,trashed,appProperties,driveId"

    override suspend fun upsert(file: CsvFile): String = mutex.withLock {
        require(Regex("(daily|workouts|heart-rate|sleep)/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.csv").matches(file.relativePath)) {
            "Invalid export path"
        }
        val (dataset, name) = file.relativePath.split('/')
        require(runCatching { LocalDate.parse(name.removeSuffix(".csv")) }.isSuccess) { "Invalid export date" }
        val bytes = file.content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 5_000_000) { "Export exceeds simple upload limit" }
        val root = appFolder()
        val parent = folder(dataset, root, DriveOperation.DATASET_FOLDER_LOOKUP, DriveOperation.DATASET_FOLDER_CREATE)
        val existing = operation(DriveOperation.FILE_LOOKUP) { find(DriveOperation.FILE_LOOKUP, name, parent, "text/csv") }
        if (existing != null) operation(DriveOperation.FILE_UPDATE) {
            val remoteId = id(existing)
            val confirmed = request(DriveOperation.FILE_UPDATE, "PATCH", "$upload/$remoteId?uploadType=media&fields=${encode(fields)}", "text/csv; charset=UTF-8", bytes)
            validate(confirmed, name, parent, "text/csv")
            check(id(confirmed) == remoteId) { "Unconfirmed Drive update" }
            remoteId
        } else operation(DriveOperation.FILE_CREATE) {
            // One multipart create, never a retried POST or a separate empty CSV create.
            val boundary = "healthapp-${UUID.randomUUID()}"
            val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata(name, parent, "text/csv")}\r\n--$boundary\r\nContent-Type: text/csv; charset=UTF-8\r\n\r\n"
            val body = prefix.toByteArray(Charsets.UTF_8) + bytes + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val confirmed = request(DriveOperation.FILE_CREATE, "POST", "$upload?uploadType=multipart&ignoreDefaultVisibility=true&fields=${encode(fields)}", "multipart/related; boundary=$boundary", body)
            validate(confirmed, name, parent, "text/csv")
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
        val response = transport.execute(method, url, type, bytes)
        if (response.status !in 200..299) throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.HTTP, response.status.takeIf { it in 100..599 }))
        // Never attach an API body, CSV, or parser exception to an outward failure.
        return runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            ?: throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.RESPONSE_INVALID))
    }

    private suspend fun <T> operation(operation: DriveOperation, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (safe: DriveUploadException) {
        throw safe
    } catch (_: IOException) {
        throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.NETWORK))
    } catch (_: Exception) {
        // No provider metadata, file content, IDs or exception causes cross this boundary.
        throw DriveUploadException(DriveFailureDetails(operation, DriveFailureKind.TARGET_REJECTED))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
