package dev.nyeonu.healthapp

import dev.nyeonu.healthapp.core.DriveResponse
import dev.nyeonu.healthapp.core.DriveTransport
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The token remains in memory for the user-authorized operation only. */
class GoogleDriveTransport(private val accessToken: String) : DriveTransport {
    init {
        require(accessToken.isNotEmpty() && accessToken.all { it.code in 33..126 }) { "Invalid access token" }
    }

    override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme == "https" && uri.host == "www.googleapis.com" &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawFragment == null &&
            Regex("/(upload/)?drive/v3/files(/[A-Za-z0-9_-]{1,200})?").matches(uri.rawPath)) { "Unsupported Drive endpoint" }
        require(method in setOf("GET", "POST", "PATCH")) { "Unsupported Drive method" }
        require(contentType == null || contentType.all { it.code in 32..126 }) { "Invalid content type" }
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                require(contentType != null) { "Missing content type" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Do not consume or expose potentially sensitive HTTP error bodies.
                DriveResponse(status, "")
            } else {
                val response = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > 1_048_576) throw IOException("Drive response too large")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                DriveResponse(status, response.toString(Charsets.UTF_8))
            }
        } catch (_: IOException) {
            throw IOException("Drive transport failed")
        } finally {
            connection.disconnect()
        }
    }
}
