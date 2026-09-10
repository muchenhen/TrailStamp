package com.muchenhen.gpslog.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muchenhen.gpslog.GPSLogApplication
import com.muchenhen.gpslog.data.RecordingProfiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit device fixture used to select a production recording profile without an unlocked UI. */
@RunWith(AndroidJUnit4::class)
class DeviceProfileConfigurationTest {
    @Test fun selectRequestedRecordingProfile() = runBlocking {
        val requested = InstrumentationRegistry.getArguments().getString("gpslogDeviceProfile")
        assumeTrue(
            "device profile configuration was not requested",
            requested == RecordingProfiles.ENDURANCE || requested == RecordingProfiles.PRECISION_WALK,
        )

        val application = ApplicationProvider.getApplicationContext<GPSLogApplication>()
        application.settings.setRecordingProfile(requireNotNull(requested))
        assertEquals(requested, application.settings.current().recordingProfile)
    }

    @Test fun verifyLatestCompletedSessionProfileWhenRequested() = runBlocking {
        val expected = InstrumentationRegistry.getArguments().getString("gpslogExpectedSessionProfile")
        assumeTrue(
            "completed session profile verification was not requested",
            expected == RecordingProfiles.ENDURANCE || expected == RecordingProfiles.PRECISION_WALK,
        )

        val application = ApplicationProvider.getApplicationContext<GPSLogApplication>()
        val session = application.database.dao().observeSessions().first().firstOrNull()
        requireNotNull(session) { "No session exists on the device" }
        assertNotNull("Stop the session before verifying its profile", session.endedAtUtc)
        assertEquals(expected, session.profile)
        if (expected == RecordingProfiles.PRECISION_WALK) {
            assertEquals(10, session.targetIntervalSeconds)
            assertEquals(5, session.minIntervalSeconds)
            assertEquals(15, session.maxBatchDelaySeconds)
        }
    }
}
