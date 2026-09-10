package com.muchenhen.gpslog.upload

import com.muchenhen.gpslog.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackUploadPolicyTest {
    @Test
    fun `token policy matches the server minimum and trims paste whitespace`() {
        val token = "a".repeat(TrackUploadPolicy.MIN_TOKEN_LENGTH)
        assertEquals(token, TrackUploadPolicy.normalizeToken("  $token\n"))
        assertThrows(IllegalArgumentException::class.java) {
            TrackUploadPolicy.normalizeToken("a".repeat(TrackUploadPolicy.MIN_TOKEN_LENGTH - 1))
        }
    }

    @Test
    fun `server endpoint is HTTPS-only and strips trailing slash`() {
        assertEquals(
            "https://processing.example.com/base",
            TrackUploadPolicy.normalizeServerUrl(" https://processing.example.com/base/ "),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TrackUploadPolicy.normalizeServerUrl("http://processing.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackUploadPolicy.normalizeServerUrl("https://token@example.com")
        }
    }

    @Test
    fun `one session has one stable work name and URL`() {
        val sessionId = "123e4567-e89b-42d3-a456-426614174000"
        assertEquals(
            "gpslog-track-upload-$sessionId",
            TrackUploadPolicy.workName(sessionId),
        )
        assertEquals(
            "https://processing.example.com/api/processing/v1/tracks/$sessionId",
            TrackUploadPolicy.trackUrl("https://processing.example.com", sessionId),
        )
    }

    @Test
    fun `only terminal complete sessions are uploadable`() {
        assertTrue(TrackUploadPolicy.canUpload(SessionStatus.COMPLETED, 1L))
        assertTrue(TrackUploadPolicy.canUpload(SessionStatus.INTERRUPTED, 1L))
        assertFalse(TrackUploadPolicy.canUpload(SessionStatus.ACTIVE, null))
        assertFalse(TrackUploadPolicy.canUpload(SessionStatus.COMPLETED, null))
    }

    @Test
    fun `retry classification is explicit`() {
        assertEquals(UploadDisposition.SUCCESS, TrackUploadPolicy.classifyHttpStatus(201))
        assertEquals(UploadDisposition.RETRYABLE, TrackUploadPolicy.classifyHttpStatus(429))
        assertEquals(UploadDisposition.RETRYABLE, TrackUploadPolicy.classifyHttpStatus(503))
        assertEquals(UploadDisposition.PERMANENT_FAILURE, TrackUploadPolicy.classifyHttpStatus(401))
        assertEquals(UploadDisposition.PERMANENT_FAILURE, TrackUploadPolicy.classifyHttpStatus(422))
    }
}
