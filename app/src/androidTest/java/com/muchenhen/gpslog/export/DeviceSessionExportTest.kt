package com.muchenhen.gpslog.export

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muchenhen.gpslog.GPSLogApplication
import java.io.File
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exports a real on-device session without requiring an unlocked SAF picker.
 *
 * Normal connected tests skip this fixture. Run it explicitly with
 * `-e gpslogDeviceExport true` after stopping a recorded session, then pull
 * `cache/device-export` with `run-as` for host-side validation.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSessionExportTest {
    @Test fun exportLatestCompletedSessionWhenExplicitlyRequested() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("device export was not requested", arguments.getString("gpslogDeviceExport") == "true")

        val application = ApplicationProvider.getApplicationContext<GPSLogApplication>()
        val dao = application.database.dao()
        require(dao.getActiveSession() == null) { "Stop recording before exporting the device session" }
        val session = dao.observeSessions().first().firstOrNull { it.endedAtUtc != null && it.rawPointCount > 0 }
            ?: error("No completed non-empty session is available for export")
        val points = dao.getPoints(session.id)
        require(points.isNotEmpty()) { "The completed session contains no points" }

        val outputDirectory = File(application.cacheDir, "device-export").apply { mkdirs() }
        ExportFormat.entries.forEach { format ->
            File(outputDirectory, "session.${format.extension}").writeText(
                TrackExporter.export(session, points, format),
                StandardCharsets.UTF_8,
            )
        }

        val json = Json.parseToJsonElement(File(outputDirectory, "session.json").readText()).jsonObject
        assertEquals("gpslog.track/v1", json.getValue("schema").toString().trim('"'))
        assertEquals(points.size, json.getValue("points").jsonArray.size)

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val gpxPoints = factory.newDocumentBuilder().parse(File(outputDirectory, "session.gpx"))
            .getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(points.count { it.usable }, gpxPoints.length)

        val csvLines = File(outputDirectory, "session.csv").readLines()
        assertTrue(csvLines.first().startsWith("timestampUtc,elapsedRealtimeNanos,"))
        assertEquals(points.size + 1, csvLines.size)
    }
}
