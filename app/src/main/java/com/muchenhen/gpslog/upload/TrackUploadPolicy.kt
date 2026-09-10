package com.muchenhen.gpslog.upload

import com.muchenhen.gpslog.data.SessionStatus
import java.net.URI

enum class UploadDisposition {
    SUCCESS,
    RETRYABLE,
    PERMANENT_FAILURE,
}

object TrackUploadPolicy {
    const val MIN_TOKEN_LENGTH = 32

    fun normalizeToken(value: String): String {
        val normalized = value.trim()
        require(normalized.length >= MIN_TOKEN_LENGTH) { "上传 token 长度无效" }
        return normalized
    }

    fun normalizeServerUrl(value: String): String {
        val parsed = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("服务器地址无效") }
        require(parsed.scheme.equals("https", ignoreCase = true)) { "服务器地址必须使用 HTTPS" }
        require(!parsed.host.isNullOrBlank()) { "服务器地址缺少主机名" }
        require(parsed.userInfo == null) { "服务器地址不能包含用户名或密码" }
        require(parsed.query == null && parsed.fragment == null) { "服务器地址不能包含查询或片段" }
        require(parsed.port == -1 || parsed.port in 1..65535) { "服务器端口无效" }
        val normalizedPath = parsed.normalize().path.orEmpty().trimEnd('/')
        require(!normalizedPath.split('/').any { it == ".." }) { "服务器地址路径无效" }
        return URI(
            "https",
            null,
            parsed.host,
            parsed.port,
            normalizedPath.ifBlank { null },
            null,
            null,
        ).toASCIIString()
    }

    fun trackUrl(serverUrl: String, sessionId: String): String {
        require(SESSION_ID.matches(sessionId)) { "会话 ID 无效" }
        return "${normalizeServerUrl(serverUrl)}/api/processing/v1/tracks/$sessionId"
    }

    fun workName(sessionId: String): String {
        require(SESSION_ID.matches(sessionId)) { "会话 ID 无效" }
        return "gpslog-track-upload-$sessionId"
    }

    fun canUpload(status: String, endedAtUtc: Long?): Boolean =
        status in setOf(SessionStatus.COMPLETED, SessionStatus.INTERRUPTED) && endedAtUtc != null

    fun classifyHttpStatus(statusCode: Int): UploadDisposition = when {
        statusCode == 200 || statusCode == 201 -> UploadDisposition.SUCCESS
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500 ->
            UploadDisposition.RETRYABLE
        else -> UploadDisposition.PERMANENT_FAILURE
    }

    private val SESSION_ID = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )
}
