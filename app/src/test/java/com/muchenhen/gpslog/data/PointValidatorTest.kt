package com.muchenhen.gpslog.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointValidatorTest {
    private val start = 1_000L

    @Test fun acceptsValidAccuratePoint() {
        assertTrue(PointValidator.isUsable(CandidatePoint(start, 31.0, 121.0, 35f, false), start))
    }

    @Test fun rejectsMockOldInvalidAndVeryInaccuratePoints() {
        assertFalse(PointValidator.isUsable(CandidatePoint(start, 31.0, 121.0, 35f, true), start))
        assertFalse(PointValidator.isUsable(CandidatePoint(start - 1, 31.0, 121.0, 35f, false), start))
        assertFalse(PointValidator.isUsable(CandidatePoint(start, 91.0, 121.0, 35f, false), start))
        assertFalse(PointValidator.isUsable(CandidatePoint(start, 31.0, 121.0, 501f, false), start))
        assertFalse(PointValidator.isUsable(CandidatePoint(start, 31.0, 121.0, null, false), start))
    }

    @Test fun duplicateTimestampOrElapsedRealtimeIsRejected() {
        val previous = TrackPointEntity(
            sessionId = "session", timestampUtc = 2_000, elapsedRealtimeNanos = 10,
            latitude = 31.0, longitude = 121.0, altitudeMeters = null, accuracyMeters = 10f,
            verticalAccuracyMeters = null, speedMps = null, bearingDegrees = null,
            provider = "test", isMock = false, usable = true,
        )
        assertTrue(PointValidator.isObviousDuplicate(previous, previous.copy(latitude = 32.0)))
        assertTrue(
            PointValidator.isObviousDuplicate(
                previous,
                previous.copy(timestampUtc = 3_000, elapsedRealtimeNanos = 10),
            ),
        )
        assertFalse(
            PointValidator.isObviousDuplicate(
                previous,
                previous.copy(timestampUtc = 3_000, elapsedRealtimeNanos = 11),
            ),
        )
    }
}
