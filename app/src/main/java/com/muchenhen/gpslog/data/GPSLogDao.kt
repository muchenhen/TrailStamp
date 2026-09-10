package com.muchenhen.gpslog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GPSLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: TrackSessionEntity)

    @Query("SELECT * FROM track_sessions ORDER BY startedAtUtc DESC")
    fun observeSessions(): Flow<List<TrackSessionEntity>>

    @Query("SELECT * FROM track_sessions WHERE status = 'active' ORDER BY startedAtUtc DESC LIMIT 1")
    fun observeActiveSession(): Flow<TrackSessionEntity?>

    @Query("SELECT * FROM track_sessions WHERE status = 'active' ORDER BY startedAtUtc DESC LIMIT 1")
    suspend fun getActiveSession(): TrackSessionEntity?

    @Query("SELECT * FROM track_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): TrackSessionEntity?

    @Query("SELECT * FROM track_sessions WHERE id = :sessionId")
    fun observeSession(sessionId: String): Flow<TrackSessionEntity?>

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY elapsedRealtimeNanos, timestampUtc, id")
    suspend fun getPoints(sessionId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId AND usable = 1 AND adminResolvedAtUtc IS NULL ORDER BY elapsedRealtimeNanos, timestampUtc, id")
    suspend fun getUnresolvedUsablePoints(sessionId: String): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_points WHERE sessionId = :sessionId AND usable = 1 AND adminResolvedAtUtc IS NULL")
    suspend fun countUnresolvedUsablePoints(sessionId: String): Int

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY elapsedRealtimeNanos DESC, id DESC LIMIT 1")
    suspend fun getLatestPoint(sessionId: String): TrackPointEntity?

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY elapsedRealtimeNanos DESC, id DESC LIMIT 1")
    fun observeLatestPoint(sessionId: String): Flow<TrackPointEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoints(points: List<TrackPointEntity>): List<Long>

    @Query("UPDATE track_sessions SET rawPointCount = (SELECT COUNT(*) FROM track_points WHERE sessionId = :sessionId), usablePointCount = (SELECT COUNT(*) FROM track_points WHERE sessionId = :sessionId AND usable = 1) WHERE id = :sessionId")
    suspend fun refreshPointCounts(sessionId: String)

    @Query("UPDATE track_sessions SET gapCount = gapCount + 1 WHERE id = :sessionId")
    suspend fun incrementGapCount(sessionId: String)

    @Query("UPDATE track_sessions SET status = :status, endedAtUtc = :endedAtUtc, interruptionReason = :reason WHERE id = :sessionId")
    suspend fun finishSession(sessionId: String, status: String, endedAtUtc: Long, reason: String?)

    @Query("UPDATE track_sessions SET status = 'interrupted', endedAtUtc = :endedAtUtc, interruptionReason = :reason WHERE status = 'active'")
    suspend fun interruptAllActive(endedAtUtc: Long, reason: String)

    @Query("DELETE FROM track_sessions WHERE id = :sessionId AND status != 'active'")
    suspend fun deleteCompletedSession(sessionId: String): Int

    @Query("SELECT * FROM track_sessions WHERE status IN ('completed', 'interrupted') AND endedAtUtc IS NOT NULL AND uploadStatus != 'uploaded' ORDER BY startedAtUtc")
    suspend fun getUploadCandidates(): List<TrackSessionEntity>

    @Query("UPDATE track_sessions SET uploadStatus = 'pending', uploadRevisionSha256 = NULL, uploadLastErrorCode = NULL, uploadedAtUtc = NULL WHERE id = :sessionId AND status IN ('completed', 'interrupted') AND endedAtUtc IS NOT NULL")
    suspend fun markUploadPending(sessionId: String): Int

    @Query("UPDATE track_sessions SET uploadStatus = 'uploading', uploadAttemptCount = uploadAttemptCount + 1, uploadLastErrorCode = NULL, uploadLastAttemptAtUtc = :attemptedAtUtc WHERE id = :sessionId AND status IN ('completed', 'interrupted') AND endedAtUtc IS NOT NULL")
    suspend fun markUploadStarted(sessionId: String, attemptedAtUtc: Long): Int

    @Query("UPDATE track_sessions SET uploadStatus = 'uploaded', uploadRevisionSha256 = :revisionSha256, uploadLastErrorCode = NULL, uploadedAtUtc = :uploadedAtUtc WHERE id = :sessionId")
    suspend fun markUploadCompleted(sessionId: String, revisionSha256: String, uploadedAtUtc: Long)

    @Query("UPDATE track_sessions SET uploadStatus = :status, uploadLastErrorCode = :errorCode WHERE id = :sessionId")
    suspend fun markUploadFailed(sessionId: String, status: String, errorCode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAdminCache(entry: AdminCacheEntity)

    @Query("SELECT * FROM admin_cache WHERE gridKey = :gridKey")
    suspend fun getAdminCache(gridKey: String): AdminCacheEntity?

    @Query("UPDATE track_points SET country = :country, province = :province, city = :city, district = :district, name = :name, adminSource = :source, adminResolvedAtUtc = :resolvedAtUtc WHERE id = :pointId")
    suspend fun attachAdmin(
        pointId: Long,
        country: String?,
        province: String?,
        city: String?,
        district: String?,
        name: String?,
        source: String,
        resolvedAtUtc: Long,
    )

    @Transaction
    suspend fun insertPointsAndRefresh(sessionId: String, points: List<TrackPointEntity>): List<Long> {
        val ids = insertPoints(points)
        refreshPointCounts(sessionId)
        return ids
    }
}
