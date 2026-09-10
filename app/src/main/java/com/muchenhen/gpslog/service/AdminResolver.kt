package com.muchenhen.gpslog.service

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.muchenhen.gpslog.data.AdminCacheEntity
import com.muchenhen.gpslog.data.GPSLogDao
import com.muchenhen.gpslog.data.SettingsRepository
import com.muchenhen.gpslog.data.TrackPointEntity
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AdminResolver(
    context: Context,
    private val dao: GPSLogDao,
    private val settings: SettingsRepository,
) {
    private val geocoder = Geocoder(context.applicationContext, Locale.SIMPLIFIED_CHINESE)

    suspend fun resolveAndAttach(pointId: Long, point: TrackPointEntity, enabled: Boolean) {
        if (!enabled || pointId <= 0) return
        val key = gridKey(point.latitude, point.longitude)
        val cached = dao.getAdminCache(key)
        if (cached != null && distanceMeters(point.latitude, point.longitude, cached.centerLatitude, cached.centerLongitude) <= 7_500) {
            attach(pointId, cached)
            return
        }

        val now = System.currentTimeMillis()
        val current = settings.current()
        if (!GeocodePolicy.shouldRequest(
                enabled = enabled,
                nowUtc = now,
                lastRequestUtc = current.lastGeocodeRequestUtc,
                backoffUntilUtc = current.geocodeBackoffUntilUtc,
            )
        ) return
        settings.markGeocodeRequest(now)

        val address = try {
            withTimeoutOrNull(20_000) { lookup(point.latitude, point.longitude) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (address == null) {
            settings.markGeocodeFailure(now + GeocodePolicy.FAILURE_BACKOFF_MILLIS)
            return
        }

        val entry = address.toCache(key, point.latitude, point.longitude, now)
        dao.putAdminCache(entry)
        settings.clearGeocodeBackoff()
        attach(pointId, entry)
    }

    private suspend fun attach(pointId: Long, entry: AdminCacheEntity) = dao.attachAdmin(
        pointId = pointId,
        country = entry.country,
        province = entry.province,
        city = entry.city,
        district = entry.district,
        name = entry.name,
        source = entry.source,
        resolvedAtUtc = entry.resolvedAtUtc,
    )

    @Suppress("DEPRECATION")
    private suspend fun lookup(latitude: Double, longitude: Double): Address? {
        if (!Geocoder.isPresent()) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                    if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                }
            }
        } else {
            withContext(Dispatchers.IO) { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }
        }
    }

    companion object {
        fun gridKey(latitude: Double, longitude: Double): String {
            val latCell = kotlin.math.floor(latitude / 0.045).toInt()
            val longitudeWidth = 0.045 / kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(0.2)
            val lonCell = kotlin.math.floor(longitude / longitudeWidth).toInt()
            return "$latCell:$lonCell"
        }

        private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
            return 6_371_000 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        }
    }
}

private fun Address.toCache(key: String, latitude: Double, longitude: Double, now: Long) = AdminCacheEntity(
    gridKey = key,
    centerLatitude = latitude,
    centerLongitude = longitude,
    country = countryName?.takeIf { it.isNotBlank() },
    province = adminArea?.takeIf { it.isNotBlank() },
    city = locality?.takeIf { it.isNotBlank() } ?: adminArea?.takeIf { it.isNotBlank() },
    district = subAdminArea?.takeIf { it.isNotBlank() } ?: subLocality?.takeIf { it.isNotBlank() },
    name = featureName?.takeIf { it.isNotBlank() && it != locality && it != subLocality },
    source = "android-geocoder",
    resolvedAtUtc = now,
)
