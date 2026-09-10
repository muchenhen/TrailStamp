package com.muchenhen.gpslog.service

object RescuePolicy {
    const val STALE_AFTER_MILLIS = 5 * 60 * 1_000L
    const val FRESH_POINT_MILLIS = 30_000L

    fun shouldRescue(lastPointUtc: Long?, nowUtc: Long, nextAllowedUtc: Long): Boolean =
        nowUtc >= nextAllowedUtc && (lastPointUtc == null || nowUtc - lastPointUtc > STALE_AFTER_MILLIS)

    fun isFresh(lastPointUtc: Long?, nowUtc: Long): Boolean =
        lastPointUtc != null && nowUtc - lastPointUtc in -5_000L..FRESH_POINT_MILLIS
}

object GeocodePolicy {
    const val MIN_INTERVAL_MILLIS = 15 * 60 * 1_000L
    const val FAILURE_BACKOFF_MILLIS = 30 * 60 * 1_000L

    fun shouldRequest(enabled: Boolean, nowUtc: Long, lastRequestUtc: Long, backoffUntilUtc: Long): Boolean =
        enabled && nowUtc >= backoffUntilUtc && nowUtc - lastRequestUtc >= MIN_INTERVAL_MILLIS
}

object RecoveryPolicy {
    fun wasInterruptedByReboot(sessionStartedAtUtc: Long, nowUtc: Long, elapsedRealtimeMillis: Long): Boolean {
        val bootWallClockUtc = nowUtc - elapsedRealtimeMillis
        return sessionStartedAtUtc < bootWallClockUtc - 5_000
    }
}
