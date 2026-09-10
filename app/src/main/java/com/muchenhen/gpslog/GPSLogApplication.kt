package com.muchenhen.gpslog

import android.app.Application
import com.muchenhen.gpslog.data.GPSLogDatabase
import com.muchenhen.gpslog.data.SettingsRepository
import com.muchenhen.gpslog.upload.CredentialStore
import com.muchenhen.gpslog.upload.TrackUploadScheduler

class GPSLogApplication : Application() {
    val database: GPSLogDatabase by lazy { GPSLogDatabase.create(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val credentials: CredentialStore by lazy { CredentialStore(this) }
    val trackUploads: TrackUploadScheduler by lazy {
        TrackUploadScheduler(this, database.dao(), settings, credentials)
    }
}
