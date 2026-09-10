package com.muchenhen.gpslog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SessionStatus {
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
    const val INTERRUPTED = "interrupted"
}

object UploadStatus {
    const val NOT_CONFIGURED = "not_configured"
    const val PENDING = "pending"
    const val UPLOADING = "uploading"
    const val UPLOADED = "uploaded"
    const val FAILED = "failed"
}

@Entity(tableName = "track_sessions")
data class TrackSessionEntity(
    @PrimaryKey val id: String,
    val startedAtUtc: Long,
    val endedAtUtc: Long?,
    val timeZone: String,
    val status: String,
    val profile: String,
    val targetIntervalSeconds: Int,
    val minIntervalSeconds: Int,
    val maxBatchDelaySeconds: Int,
    val adminLookupEnabled: Boolean,
    val rawPointCount: Int = 0,
    val usablePointCount: Int = 0,
    val gapCount: Int = 0,
    val interruptionReason: String? = null,
    @ColumnInfo(defaultValue = "'not_configured'")
    val uploadStatus: String = UploadStatus.NOT_CONFIGURED,
    val uploadRevisionSha256: String? = null,
    @ColumnInfo(defaultValue = "0")
    val uploadAttemptCount: Int = 0,
    val uploadLastErrorCode: String? = null,
    val uploadLastAttemptAtUtc: Long? = null,
    val uploadedAtUtc: Long? = null,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "timestampUtc"], unique = true),
    ],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampUtc: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float?,
    val verticalAccuracyMeters: Float?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val provider: String?,
    val isMock: Boolean,
    val usable: Boolean,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val name: String? = null,
    val adminSource: String? = null,
    val adminResolvedAtUtc: Long? = null,
)

@Entity(tableName = "admin_cache")
data class AdminCacheEntity(
    @PrimaryKey val gridKey: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val country: String?,
    val province: String?,
    val city: String?,
    val district: String?,
    val name: String?,
    val source: String,
    val resolvedAtUtc: Long,
)
