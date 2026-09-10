package com.muchenhen.gpslog.export

import com.muchenhen.gpslog.data.SessionStatus
import com.muchenhen.gpslog.data.TrackPointEntity
import com.muchenhen.gpslog.data.TrackSessionEntity
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackExporterTest {
    private val session = TrackSessionEntity(
        id = "测试-session", startedAtUtc = 1_700_000_000_000, endedAtUtc = 1_700_000_300_000,
        timeZone = "Asia/Shanghai", status = SessionStatus.COMPLETED, profile = "endurance",
        targetIntervalSeconds = 240, minIntervalSeconds = 120, maxBatchDelaySeconds = 300,
        adminLookupEnabled = false, rawPointCount = 1, usablePointCount = 1,
    )
    private val point = TrackPointEntity(
        sessionId = session.id, timestampUtc = 1_700_000_120_000, elapsedRealtimeNanos = 123,
        latitude = 31.0, longitude = 121.0, altitudeMeters = 8.0, accuracyMeters = 35f,
        verticalAccuracyMeters = 4f, speedMps = 1.2f, bearingDegrees = 90f,
        provider = "fused,\"special\"", isMock = false, usable = true,
        country = "中国", province = "上海市", city = "上海市", district = "徐汇区", name = "测试,地点",
    )

    @Test fun jsonRoundTripsAndKeepsUtf8() {
        val text = TrackExporter.toJson(session, listOf(point))
        val parsed = Json.parseToJsonElement(text)
        assertEquals("gpslog.track/v1", parsed.jsonObject["schema"]?.toString()?.trim('"'))
        assertTrue(text.contains("徐汇区"))
    }

    @Test fun exporterMatchesTheVersionedSharedTrackFixture() {
        val fixtureSession = TrackSessionEntity(
            id = "00000000-0000-4000-8000-000000000001",
            startedAtUtc = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli(),
            endedAtUtc = Instant.parse("2000-01-01T00:10:00Z").toEpochMilli(),
            timeZone = "UTC",
            status = SessionStatus.COMPLETED,
            profile = "endurance",
            targetIntervalSeconds = 240,
            minIntervalSeconds = 120,
            maxBatchDelaySeconds = 300,
            adminLookupEnabled = true,
            rawPointCount = 2,
            usablePointCount = 2,
        )
        fun fixturePoint(timestamp: String, elapsedRealtimeNanos: Long) = TrackPointEntity(
            sessionId = fixtureSession.id,
            timestampUtc = Instant.parse(timestamp).toEpochMilli(),
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            latitude = 0.0,
            longitude = 0.0,
            altitudeMeters = 8.0,
            accuracyMeters = 12f,
            verticalAccuracyMeters = 4f,
            speedMps = 0f,
            bearingDegrees = 0f,
            provider = "fixture",
            isMock = false,
            usable = true,
            country = "示例国家",
            province = "示例省份",
            city = "示例城市",
            district = "示例地区",
            name = "示例地点",
        )
        val actual = Json.parseToJsonElement(
            TrackExporter.toJson(
                fixtureSession,
                listOf(
                    fixturePoint("2000-01-01T00:02:00Z", 1_000_000_000),
                    fixturePoint("2000-01-01T00:06:00Z", 241_000_000_000),
                ),
            ),
        )
        val expectedText = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("sample-track.json"),
        ).bufferedReader().use { it.readText() }
        assertEquals(Json.parseToJsonElement(expectedText), actual)
    }

    @Test fun gpxAndCsvEscapeSpecialCharacters() {
        val gpx = TrackExporter.toGpx(session, listOf(point))
        assertTrue(gpx.contains("<trkpt lat=\"31.0\" lon=\"121.0\">"))
        val csv = TrackExporter.toCsv(listOf(point))
        assertTrue(csv.contains("\"fused,\"\"special\"\"\""))
        assertTrue(csv.contains("\"测试,地点\""))
    }

    @Test fun missingEndTimeAndProviderRemainJsonNull() {
        val parsed = Json.parseToJsonElement(
            TrackExporter.toJson(
                session.copy(endedAtUtc = null),
                listOf(point.copy(provider = null)),
            ),
        ).jsonObject
        assertEquals(JsonNull, parsed["session"]!!.jsonObject["endedAt"])
        assertEquals(JsonNull, parsed["points"]!!.jsonArray[0].jsonObject["provider"])
    }

    @Test fun nonFiniteOptionalMetricsNeverCreateInvalidJsonOrGpx() {
        val invalidAltitude = point.copy(altitudeMeters = Double.NaN)
        val json = TrackExporter.toJson(session, listOf(invalidAltitude))
        assertTrue(json.contains("\"altitudeMeters\": null"))
        val gpx = TrackExporter.toGpx(session, listOf(invalidAltitude))
        assertTrue(!gpx.contains("<ele>"))
        assertTrue(!gpx.contains("NaN"))
    }
}
