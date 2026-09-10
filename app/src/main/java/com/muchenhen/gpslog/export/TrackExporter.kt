package com.muchenhen.gpslog.export

import com.muchenhen.gpslog.data.TrackPointEntity
import com.muchenhen.gpslog.data.TrackSessionEntity
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    GPX("gpx", "application/gpx+xml"),
    CSV("csv", "text/csv"),
}

object TrackExporter {
    fun export(session: TrackSessionEntity, points: List<TrackPointEntity>, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> toJson(session, points)
        ExportFormat.GPX -> toGpx(session, points)
        ExportFormat.CSV -> toCsv(points)
    }

    fun suggestedFileName(session: TrackSessionEntity, format: ExportFormat): String =
        "gpslog-${Instant.ofEpochMilli(session.startedAtUtc).toString().replace(':', '-')}.${format.extension}"

    fun toJson(session: TrackSessionEntity, points: List<TrackPointEntity>): String {
        val root = buildJsonObject {
            put("schema", "gpslog.track/v1")
            put("session", buildJsonObject {
                put("id", session.id)
                put("startedAt", Instant.ofEpochMilli(session.startedAtUtc).toString())
                // put() returns the previous entry; select the nullable value before inserting.
                put("endedAt", session.endedAtUtc?.let {
                    JsonPrimitive(Instant.ofEpochMilli(it).toString())
                } ?: JsonNull)
                put("timeZone", session.timeZone)
                put("profile", session.profile)
                put("targetIntervalSeconds", session.targetIntervalSeconds)
                put("status", session.status)
                put("gapCount", session.gapCount)
            })
            put("points", buildJsonArray { points.forEach { add(it.toJson()) } })
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    fun toGpx(session: TrackSessionEntity, points: List<TrackPointEntity>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GPSLog\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpslog=\"https://github.com/muchenhen/GPSLog/schema/gpx/1\">\n")
        append("  <metadata><time>").append(Instant.ofEpochMilli(session.startedAtUtc)).append("</time></metadata>\n")
        append("  <trk><name>").append(xml(session.id)).append("</name><trkseg>\n")
        points.filter { it.usable }.forEach { point ->
            append("    <trkpt lat=\"").append(point.latitude).append("\" lon=\"").append(point.longitude).append("\">\n")
            point.altitudeMeters?.takeIf { it.isFinite() }
                ?.let { append("      <ele>").append(it).append("</ele>\n") }
            append("      <time>").append(Instant.ofEpochMilli(point.timestampUtc)).append("</time>\n")
            append("      <extensions>")
            point.accuracyMeters?.let { append("<gpslog:accuracyMeters>").append(it).append("</gpslog:accuracyMeters>") }
            append("<gpslog:elapsedRealtimeNanos>").append(point.elapsedRealtimeNanos).append("</gpslog:elapsedRealtimeNanos>")
            append("</extensions>\n")
            append("    </trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    fun toCsv(points: List<TrackPointEntity>): String = buildString {
        append("timestampUtc,elapsedRealtimeNanos,latitude,longitude,altitudeMeters,accuracyMeters,verticalAccuracyMeters,speedMps,bearingDegrees,provider,isMock,usable,country,province,city,district,name,adminSource,adminResolvedAtUtc\r\n")
        points.forEach { point ->
            val values = listOf(
                Instant.ofEpochMilli(point.timestampUtc).toString(), point.elapsedRealtimeNanos,
                point.latitude, point.longitude, point.altitudeMeters, point.accuracyMeters,
                point.verticalAccuracyMeters, point.speedMps, point.bearingDegrees, point.provider,
                point.isMock, point.usable, point.country, point.province, point.city, point.district, point.name,
                point.adminSource, point.adminResolvedAtUtc?.let { Instant.ofEpochMilli(it).toString() },
            )
            append(values.joinToString(",") { csv(it?.toString().orEmpty()) }).append("\r\n")
        }
    }

    private fun TrackPointEntity.toJson(): JsonObject = buildJsonObject {
        put("timestampUtc", Instant.ofEpochMilli(timestampUtc).toString())
        put("elapsedRealtimeNanos", elapsedRealtimeNanos)
        put("latitude", latitude)
        put("longitude", longitude)
        nullableNumber("altitudeMeters", altitudeMeters)
        nullableNumber("accuracyMeters", accuracyMeters?.toDouble())
        nullableNumber("verticalAccuracyMeters", verticalAccuracyMeters?.toDouble())
        nullableNumber("speedMps", speedMps?.toDouble())
        nullableNumber("bearingDegrees", bearingDegrees?.toDouble())
        put("provider", provider?.let { JsonPrimitive(it) } ?: JsonNull)
        put("isMock", isMock)
        put("usable", usable)
        if (listOf(country, province, city, district, name).any { it != null }) {
            put("admin", buildJsonObject {
                put("country", country.orEmpty())
                put("province", province.orEmpty())
                put("city", city.orEmpty())
                put("district", district.orEmpty())
                put("name", name.orEmpty())
                adminSource?.let { put("source", it) }
                adminResolvedAtUtc?.let { put("resolvedAt", Instant.ofEpochMilli(it).toString()) }
            })
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.nullableNumber(name: String, value: Double?) {
        if (value == null || !value.isFinite()) put(name, JsonNull) else put(name, JsonPrimitive(value))
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun csv(value: String): String = if (value.any { it == ',' || it == '\"' || it == '\r' || it == '\n' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
}
