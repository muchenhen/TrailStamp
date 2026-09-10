package com.muchenhen.gpslog.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GPSLogDatabaseTest {
    private lateinit var database: GPSLogDatabase
    private lateinit var dao: GPSLogDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GPSLogDatabase::class.java,
        ).build()
        dao = database.dao()
    }

    @After fun tearDown() = database.close()

    @Test fun sessionLifecycleDedupCountsAndCascadeAreTransactional() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        dao.insertSession(
            TrackSessionEntity(
                id = sessionId,
                startedAtUtc = 1_000,
                endedAtUtc = null,
                timeZone = ZoneId.of("Asia/Shanghai").id,
                status = SessionStatus.ACTIVE,
                profile = "endurance",
                targetIntervalSeconds = 240,
                minIntervalSeconds = 120,
                maxBatchDelaySeconds = 300,
                adminLookupEnabled = false,
            ),
        )
        val valid = point(sessionId, 2_000, 31.0, 121.0, usable = true)
        val invalid = point(sessionId, 3_000, 91.0, 121.0, usable = false)
        val sameTimestamp = valid.copy(
            elapsedRealtimeNanos = valid.elapsedRealtimeNanos + 1,
            latitude = 32.0,
        )
        val ids = dao.insertPointsAndRefresh(sessionId, listOf(valid, invalid, valid, sameTimestamp))
        assertNotEquals(-1L, ids[0])
        assertEquals(-1L, ids[2])
        assertEquals(-1L, ids[3])
        val active = dao.getActiveSession()!!
        assertEquals(2, active.rawPointCount)
        assertEquals(1, active.usablePointCount)

        dao.incrementGapCount(sessionId)
        dao.finishSession(sessionId, SessionStatus.COMPLETED, 4_000, null)
        val completed = dao.getSession(sessionId)!!
        assertEquals(SessionStatus.COMPLETED, completed.status)
        assertEquals(1, completed.gapCount)
        assertEquals(1, dao.markUploadPending(sessionId))
        assertEquals(1, dao.markUploadStarted(sessionId, 4_100))
        dao.markUploadCompleted(sessionId, "a".repeat(64), 4_200)
        val uploaded = dao.getSession(sessionId)!!
        assertEquals(UploadStatus.UPLOADED, uploaded.uploadStatus)
        assertEquals("a".repeat(64), uploaded.uploadRevisionSha256)
        assertEquals(1, uploaded.uploadAttemptCount)
        assertEquals(1, dao.deleteCompletedSession(sessionId))
        assertNull(dao.getSession(sessionId))
        assertEquals(0, dao.getPoints(sessionId).size)
    }

    @Test fun mockAndLowAccuracyValidationNeverBecomeUsable() {
        val start = 1_000L
        assertEquals(false, PointValidator.isUsable(CandidatePoint(2_000, 31.0, 121.0, 20f, true), start))
        assertEquals(false, PointValidator.isUsable(CandidatePoint(2_000, 31.0, 121.0, 800f, false), start))
    }

    private fun point(
        sessionId: String,
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        usable: Boolean,
    ) = TrackPointEntity(
        sessionId = sessionId,
        timestampUtc = timestamp,
        elapsedRealtimeNanos = timestamp * 1_000_000,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        accuracyMeters = 20f,
        verticalAccuracyMeters = null,
        speedMps = null,
        bearingDegrees = null,
        provider = "test",
        isMock = false,
        usable = usable,
    )
}
