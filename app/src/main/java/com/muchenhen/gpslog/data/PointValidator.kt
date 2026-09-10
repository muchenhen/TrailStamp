package com.muchenhen.gpslog.data

data class CandidatePoint(
    val timestampUtc: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val isMock: Boolean,
)

object PointValidator {
    fun isUsable(point: CandidatePoint, sessionStartedAtUtc: Long): Boolean =
        point.timestampUtc >= sessionStartedAtUtc &&
            point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
            !point.isMock &&
            point.accuracyMeters != null && point.accuracyMeters.isFinite() &&
            point.accuracyMeters >= 0f && point.accuracyMeters <= 500f

    fun isObviousDuplicate(previous: TrackPointEntity?, current: TrackPointEntity): Boolean =
        previous != null &&
            (previous.timestampUtc == current.timestampUtc ||
                previous.elapsedRealtimeNanos == current.elapsedRealtimeNanos)
}
