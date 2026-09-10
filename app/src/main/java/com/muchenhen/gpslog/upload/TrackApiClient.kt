package com.muchenhen.gpslog.upload

import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection

data class TrackUploadResponse(
    val statusCode: Int,
    val disposition: UploadDisposition,
    val revisionSha256: String,
    val errorCode: String?,
)

class TrackApiClient {
    fun upload(
        serverUrl: String,
        sessionId: String,
        token: String,
        trackJson: String,
    ): TrackUploadResponse {
        val normalizedToken = TrackUploadPolicy.normalizeToken(token)
        val raw = trackJson.toByteArray(StandardCharsets.UTF_8)
        val revisionSha256 = MessageDigest.getInstance("SHA-256")
            .digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(raw) }
            output.toByteArray()
        }
        val connection = URL(TrackUploadPolicy.trackUrl(serverUrl, sessionId))
            .openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $normalizedToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Content-Encoding", "gzip")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "GPSLog-Android/1")
            connection.setFixedLengthStreamingMode(compressed.size)
            connection.outputStream.use { output ->
                output.write(compressed)
                output.flush()
            }
            val statusCode = connection.responseCode
            val disposition = TrackUploadPolicy.classifyHttpStatus(statusCode)
            closeResponse(connection, disposition)
            return TrackUploadResponse(
                statusCode = statusCode,
                disposition = disposition,
                revisionSha256 = revisionSha256,
                errorCode = if (disposition == UploadDisposition.SUCCESS) null else "http_$statusCode",
            )
        }
        finally {
            connection.disconnect()
        }
    }

    private fun closeResponse(
        connection: HttpsURLConnection,
        disposition: UploadDisposition,
    ) {
        val stream = if (disposition == UploadDisposition.SUCCESS) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        stream?.use { input ->
            val buffer = ByteArray(4_096)
            var remaining = MAX_RESPONSE_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
