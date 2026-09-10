package com.muchenhen.gpslog

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muchenhen.gpslog.data.AppSettings
import com.muchenhen.gpslog.data.RecordingProfiles
import com.muchenhen.gpslog.data.SessionStatus
import com.muchenhen.gpslog.data.TrackPointEntity
import com.muchenhen.gpslog.data.TrackSessionEntity
import com.muchenhen.gpslog.export.ExportFormat
import com.muchenhen.gpslog.export.TrackExporter
import com.muchenhen.gpslog.service.AdminResolver
import com.muchenhen.gpslog.service.RecoveryPolicy
import com.muchenhen.gpslog.upload.TrackUploadPolicy
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionState(
    val preciseLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val notifications: Boolean = false,
    val locationEnabled: Boolean = false,
) {
    val readyToRecord: Boolean get() = preciseLocation && backgroundLocation && notifications && locationEnabled
}

data class GPSLogUiState(
    val sessions: List<TrackSessionEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val permissions: PermissionState = PermissionState(),
    val latestPoint: TrackPointEntity? = null,
    val message: String? = null,
    val uploadTokenConfigured: Boolean = false,
) {
    val activeSession: TrackSessionEntity? get() = sessions.firstOrNull { it.status == SessionStatus.ACTIVE }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GPSLogViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GPSLogApplication
    private val dao = app.database.dao()
    private val permissions = MutableStateFlow(PermissionState())
    private val message = MutableStateFlow<String?>(null)
    private val uploadTokenConfigured = MutableStateFlow(app.credentials.hasToken())
    private val activeSession = dao.observeActiveSession()
    private val latestPoint = activeSession.flatMapLatest { session ->
        if (session == null) flowOf(null) else dao.observeLatestPoint(session.id)
    }

    private val settingsAndToken = combine(app.settings.settings, uploadTokenConfigured) { settings, token ->
        settings to token
    }

    val uiState: StateFlow<GPSLogUiState> = combine(
        dao.observeSessions(), settingsAndToken, permissions, latestPoint, message,
    ) { sessions, configured, permissionState, point, currentMessage ->
        GPSLogUiState(
            sessions,
            configured.first,
            permissionState,
            point,
            currentMessage,
            configured.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GPSLogUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val session = dao.getActiveSession()
            if (session != null) {
                val now = System.currentTimeMillis()
                if (RecoveryPolicy.wasInterruptedByReboot(session.startedAtUtc, now, SystemClock.elapsedRealtime())) {
                    val bootWallClock = now - SystemClock.elapsedRealtime()
                    dao.finishSession(
                        session.id,
                        SessionStatus.INTERRUPTED,
                        bootWallClock,
                        "device reboot interrupted recording",
                    )
                    app.settings.clearActiveSession()
                    app.trackUploads.enqueueIfEnabled(session.id)
                }
            }
            app.trackUploads.enqueueAllEligible()
        }
    }

    fun updatePermissions(value: PermissionState) { permissions.value = value }
    fun clearMessage() { message.value = null }

    fun setTargetIntervalMinutes(minutes: Int) {
        viewModelScope.launch { app.settings.setTargetIntervalSeconds(minutes.coerceIn(1, 5) * 60) }
    }

    fun setRecordingProfile(profile: String) {
        viewModelScope.launch { app.settings.setRecordingProfile(RecordingProfiles.normalize(profile)) }
    }

    fun setAdminLookupEnabled(enabled: Boolean) {
        viewModelScope.launch { app.settings.setAdminLookupEnabled(enabled) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.trackUploads.cancel(sessionId)
            val deleted = dao.deleteCompletedSession(sessionId)
            message.value = if (deleted == 1) "会话已删除" else "活动会话不能删除"
        }
    }

    fun completeAdministrativeAreas(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!app.settings.current().adminLookupEnabled) {
                message.value = "请先在设置中开启联网解析行政区"
                return@launch
            }
            val resolver = AdminResolver(app, dao, app.settings)
            val unresolved = dao.getUnresolvedUsablePoints(sessionId)
            unresolved.forEach { point -> resolver.resolveAndAttach(point.id, point, enabled = true) }
            val remaining = dao.countUnresolvedUsablePoints(sessionId)
            app.trackUploads.enqueueIfEnabled(sessionId)
            message.value = if (remaining == 0) {
                "行政区补全完成"
            } else {
                "已使用缓存并尝试联网；仍有 $remaining 点待补全（联网请求最短间隔 15 分钟）"
            }
        }
    }

    fun saveUploadSettings(serverUrl: String, token: String, autoUploadEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val normalizedUrl = if (serverUrl.isBlank()) "" else {
                    TrackUploadPolicy.normalizeServerUrl(serverUrl)
                }
                if (autoUploadEnabled) require(normalizedUrl.isNotBlank()) { "开启自动上传前请填写 HTTPS 地址" }
                if (token.isNotBlank()) {
                    app.credentials.writeToken(token)
                    uploadTokenConfigured.value = true
                }
                if (autoUploadEnabled) require(app.credentials.hasToken()) { "开启自动上传前请保存 token" }
                app.settings.setProcessingServerUrl(normalizedUrl)
                app.settings.setAutoUploadEnabled(autoUploadEnabled)
                if (autoUploadEnabled) app.trackUploads.enqueueAllEligible()
                else app.trackUploads.cancelAll()
            }.onSuccess {
                message.value = "上传设置已保存"
            }.onFailure {
                message.value = "上传设置无效：${it.message}"
            }
        }
    }

    fun clearUploadToken() {
        viewModelScope.launch(Dispatchers.IO) {
            app.credentials.clearToken()
            uploadTokenConfigured.value = false
            app.settings.setAutoUploadEnabled(false)
            app.trackUploads.cancelAll()
            message.value = "上传 token 已清除，自动上传已关闭"
        }
    }

    fun retryUpload(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val queued = app.trackUploads.retry(sessionId)
            message.value = if (queued) "已排队重试" else "上传尚未配置或会话不可上传"
        }
    }

    suspend fun session(sessionId: String): TrackSessionEntity? = withContext(Dispatchers.IO) { dao.getSession(sessionId) }

    fun export(contentResolver: ContentResolver, sessionId: String, format: ExportFormat, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val session = requireNotNull(dao.getSession(sessionId)) { "会话不存在" }
                val points = dao.getPoints(sessionId)
                val content = TrackExporter.export(session, points, format)
                requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "无法打开导出目标" }.use {
                    it.write(content.toByteArray(StandardCharsets.UTF_8))
                    it.flush()
                }
            }.onSuccess { message.value = "${format.name} 导出完成" }
                .onFailure { message.value = "导出失败：${it.message}" }
        }
    }
}
