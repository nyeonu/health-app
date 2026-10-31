package dev.nyeonu.healthapp

import dev.nyeonu.healthapp.core.DriveResponse
import dev.nyeonu.healthapp.core.DriveTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Keeps coroutine stack-trace recovery from exposing an exception cause. */
private class DriveTransportException : IOException("Drive transport failed") {
    override val cause: Throwable? get() = null
}

/** The token remains in memory for the user-authorized operation only. */
class GoogleDriveTransport internal constructor(
    private val accessToken: String,
    private val client: OkHttpClient,
) : DriveTransport {
    constructor(accessToken: String) : this(accessToken, productionClient())

    init {
        require(accessToken.isNotEmpty() && accessToken.all { it.code in 33..126 }) { "Invalid access token" }
    }

    override suspend fun execute(method: String, url: String, contentType: String?, body: ByteArray?): DriveResponse {
        val uri = runCatching { URI(url) }.getOrNull()
        // Validate the original endpoint before constructing any credential-bearing request.
        require(uri != null && uri.scheme == "https" && uri.host == "www.googleapis.com" &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawFragment == null &&
            Regex("/(upload/)?drive/v3/files(/[A-Za-z0-9_-]{1,200})?").matches(uri.rawPath)) { "Unsupported Drive endpoint" }
        require(method in setOf("GET", "POST", "PATCH")) { "Unsupported Drive method" }
        require(contentType == null || contentType.all { it.code in 32..126 }) { "Invalid content type" }
        require(body == null || contentType != null) { "Missing content type" }
        val request = try {
            Request.Builder().url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .apply { if (contentType != null && body != null) header("Content-Type", contentType) }
                .method(method, when {
                    body != null -> body.toRequestBody()
                    method == "POST" || method == "PATCH" -> ByteArray(0).toRequestBody()
                    else -> null
                }).build()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Drive request")
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(DriveTransportException())
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code !in 200..299) {
                                // Never consume or expose potentially sensitive HTTP error bodies.
                                DriveResponse(it.code, "")
                            } else {
                                val bytes = it.body.byteStream().use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        if (output.size() + count > 1_048_576) throw IOException()
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                                DriveResponse(it.code, bytes.toString(Charsets.UTF_8))
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (_: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(DriveTransportException())
                    }
                }
            })
        }
    }

    internal companion object {
        fun productionClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
