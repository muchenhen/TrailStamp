package com.muchenhen.gpslog.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gpsLogDataStore by preferencesDataStore(name = "gpslog")

data class AppSettings(
    val recordingProfile: String = RecordingProfiles.ENDURANCE,
    val targetIntervalSeconds: Int = RecordingProfiles.defaultEnduranceIntervalSeconds(),
    val adminLookupEnabled: Boolean = false,
    val activeSessionId: String? = null,
    val activeSessionStartedAtUtc: Long? = null,
    val lastGeocodeRequestUtc: Long = 0,
    val geocodeBackoffUntilUtc: Long = 0,
    val processingServerUrl: String = "",
    val autoUploadEnabled: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val recordingProfile = stringPreferencesKey("recording_profile")
        val targetIntervalSeconds = intPreferencesKey("target_interval_seconds")
        val adminLookupEnabled = booleanPreferencesKey("admin_lookup_enabled")
        val activeSessionId = stringPreferencesKey("active_session_id")
        val activeSessionStartedAtUtc = longPreferencesKey("active_session_started_at_utc")
        val lastGeocodeRequestUtc = longPreferencesKey("last_geocode_request_utc")
        val geocodeBackoffUntilUtc = longPreferencesKey("geocode_backoff_until_utc")
        val processingServerUrl = stringPreferencesKey("processing_server_url")
        val autoUploadEnabled = booleanPreferencesKey("auto_upload_enabled")
    }

    val settings: Flow<AppSettings> = context.gpsLogDataStore.data.map(::toSettings)

    suspend fun current(): AppSettings = settings.first()

    suspend fun setRecordingProfile(profile: String) {
        context.gpsLogDataStore.edit { it[Keys.recordingProfile] = RecordingProfiles.normalize(profile) }
    }

    suspend fun setTargetIntervalSeconds(seconds: Int) {
        context.gpsLogDataStore.edit { it[Keys.targetIntervalSeconds] = seconds.coerceIn(60, 300) }
    }

    suspend fun setAdminLookupEnabled(enabled: Boolean) {
        context.gpsLogDataStore.edit { it[Keys.adminLookupEnabled] = enabled }
    }

    suspend fun setProcessingServerUrl(url: String) {
        context.gpsLogDataStore.edit { preferences ->
            val normalized = url.trim().trimEnd('/')
            if (normalized.isBlank()) preferences.remove(Keys.processingServerUrl)
            else preferences[Keys.processingServerUrl] = normalized
        }
    }

    suspend fun setAutoUploadEnabled(enabled: Boolean) {
        context.gpsLogDataStore.edit { it[Keys.autoUploadEnabled] = enabled }
    }

    suspend fun setActiveSession(id: String, startedAtUtc: Long) {
        context.gpsLogDataStore.edit {
            it[Keys.activeSessionId] = id
            it[Keys.activeSessionStartedAtUtc] = startedAtUtc
        }
    }

    suspend fun clearActiveSession() {
        context.gpsLogDataStore.edit {
            it.remove(Keys.activeSessionId)
            it.remove(Keys.activeSessionStartedAtUtc)
        }
    }

    suspend fun markGeocodeRequest(nowUtc: Long) {
        context.gpsLogDataStore.edit { it[Keys.lastGeocodeRequestUtc] = nowUtc }
    }

    suspend fun markGeocodeFailure(backoffUntilUtc: Long) {
        context.gpsLogDataStore.edit { it[Keys.geocodeBackoffUntilUtc] = backoffUntilUtc }
    }

    suspend fun clearGeocodeBackoff() {
        context.gpsLogDataStore.edit { it[Keys.geocodeBackoffUntilUtc] = 0 }
    }

    private fun toSettings(preferences: Preferences) = AppSettings(
        recordingProfile = RecordingProfiles.normalize(preferences[Keys.recordingProfile]),
        targetIntervalSeconds = (
            preferences[Keys.targetIntervalSeconds] ?: RecordingProfiles.defaultEnduranceIntervalSeconds()
            ).coerceIn(60, 300),
        adminLookupEnabled = preferences[Keys.adminLookupEnabled] ?: false,
        activeSessionId = preferences[Keys.activeSessionId],
        activeSessionStartedAtUtc = preferences[Keys.activeSessionStartedAtUtc],
        lastGeocodeRequestUtc = preferences[Keys.lastGeocodeRequestUtc] ?: 0,
        geocodeBackoffUntilUtc = preferences[Keys.geocodeBackoffUntilUtc] ?: 0,
        processingServerUrl = preferences[Keys.processingServerUrl].orEmpty(),
        autoUploadEnabled = preferences[Keys.autoUploadEnabled] ?: false,
    )
}
