package com.muchenhen.gpslog.service

import com.muchenhen.gpslog.data.RecordingProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    @Test fun recordingProfilesKeepEnduranceConfigurableAndPrecisionWalkDense() {
        val endurance = RecordingProfiles.config(RecordingProfiles.ENDURANCE, 240)
        assertEquals(RecordingProfiles.ENDURANCE, endurance.id)
        assertEquals(240, endurance.targetIntervalSeconds)
        assertEquals(120, endurance.minIntervalSeconds)
        assertEquals(300, endurance.maxBatchDelaySeconds)
        assertFalse(endurance.highAccuracy)

        val precision = RecordingProfiles.config(RecordingProfiles.PRECISION_WALK, 300)
        assertEquals(RecordingProfiles.PRECISION_WALK, precision.id)
        assertEquals(10, precision.targetIntervalSeconds)
        assertEquals(5, precision.minIntervalSeconds)
        assertEquals(15, precision.maxBatchDelaySeconds)
        assertTrue(precision.highAccuracy)

        assertEquals(60, RecordingProfiles.config(RecordingProfiles.ENDURANCE, 1).targetIntervalSeconds)
        assertEquals(300, RecordingProfiles.config(RecordingProfiles.ENDURANCE, 3_600).targetIntervalSeconds)
        assertEquals(RecordingProfiles.ENDURANCE, RecordingProfiles.normalize("future_profile"))
    }

    @Test fun rescueStartsOnlyAfterFiveMinutesAndHonorsBackoff() {
        val now = 1_000_000L
        assertFalse(RescuePolicy.shouldRescue(now - RescuePolicy.STALE_AFTER_MILLIS, now, 0))
        assertTrue(RescuePolicy.shouldRescue(now - RescuePolicy.STALE_AFTER_MILLIS - 1, now, 0))
        assertFalse(RescuePolicy.shouldRescue(null, now, now + 1))
        assertTrue(RescuePolicy.isFresh(now - RescuePolicy.FRESH_POINT_MILLIS, now))
        assertTrue(RescuePolicy.isFresh(now + 5_000, now))
        assertFalse(RescuePolicy.isFresh(now - RescuePolicy.FRESH_POINT_MILLIS - 1, now))
        assertFalse(RescuePolicy.isFresh(null, now))
    }

    @Test fun disabledGeocoderMakesZeroNetworkRequests() {
        val now = GeocodePolicy.MIN_INTERVAL_MILLIS * 2
        assertFalse(GeocodePolicy.shouldRequest(false, now, 0, 0))
        assertTrue(GeocodePolicy.shouldRequest(true, now, 0, 0))
        assertFalse(GeocodePolicy.shouldRequest(true, now, now - 1, 0))
        assertFalse(GeocodePolicy.shouldRequest(true, now, 0, now + 1))
    }

    @Test fun rebootDetectionUsesElapsedRealtimeBootBoundary() {
        val now = 1_000_000L
        val elapsed = 100_000L
        assertTrue(RecoveryPolicy.wasInterruptedByReboot(800_000L, now, elapsed))
        assertFalse(RecoveryPolicy.wasInterruptedByReboot(950_000L, now, elapsed))
    }
}
