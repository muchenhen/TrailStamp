package com.muchenhen.gpslog.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.muchenhen.gpslog.GPSLogApplication
import com.muchenhen.gpslog.MainActivity
import com.muchenhen.gpslog.R
import com.muchenhen.gpslog.data.CandidatePoint
import com.muchenhen.gpslog.data.PointValidator
import com.muchenhen.gpslog.data.RecordingProfiles
import com.muchenhen.gpslog.data.SessionStatus
import com.muchenhen.gpslog.data.TrackPointEntity
import com.muchenhen.gpslog.data.TrackSessionEntity
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocationRecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operationMutex = Mutex()
    private val writeMutex = Mutex()
    private val locationQueue = Channel<List<Location>>(Channel.UNLIMITED)
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var application: GPSLogApplication
    private lateinit var adminResolver: AdminResolver
    private var activeSession: TrackSessionEntity? = null
    private var locationUpdatesStarted = false
    private var writerJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (result.locations.isNotEmpty()) locationQueue.trySend(result.locations)
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = applicationContext as GPSLogApplication
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        adminResolver = AdminResolver(this, application.database.dao(), application.settings)
        createNotificationChannel()
        writerJob = serviceScope.launch {
            for (firstBatch in locationQueue) {
                val combined = firstBatch.toMutableList()
                while (true) {
                    val next = locationQueue.tryReceive().getOrNull() ?: break
                    combined += next
                }
                try {
                    writeLocations(combined)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    activeSession?.let { session ->
                        try {
                            application.database.dao().incrementGapCount(session.id)
                        } catch (_: Exception) {
                            // The notification below remains the last-resort visible failure signal.
                        }
                    }
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        buildNotification("轨迹保存失败，等待下一批重试", activeSession),
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            showPreparingNotification()
        } catch (_: SecurityException) {
            serviceScope.launch { interruptActiveAndStop("required permission unavailable") }
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_RESUME
        launchServiceCommand(action) {
            when (action) {
                ACTION_STOP -> stopRecording()
                ACTION_PRECISE -> capturePrecisePoint(manual = true)
                ACTION_RESCUE -> {
                    if (activeSession == null) startOrResume(allowCreate = false) else runScheduledRescue()
                }
                ACTION_START, ACTION_RESUME -> startOrResume(action == ACTION_START)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startOrResume(allowCreate: Boolean) = operationMutex.withLock {
        if (!hasPersistentCorePermissions() || (allowCreate && !isLocationEnabled())) {
            interruptActiveAndStop("required permission, notification, or location unavailable")
            return
        }

        val dao = application.database.dao()
        var session = dao.getActiveSession()
        if (session != null && RecoveryPolicy.wasInterruptedByReboot(
                sessionStartedAtUtc = session.startedAtUtc,
                nowUtc = System.currentTimeMillis(),
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            )
        ) {
            val bootWallClockUtc = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            dao.finishSession(
                session.id,
                SessionStatus.INTERRUPTED,
                bootWallClockUtc,
                "device reboot interrupted recording",
            )
            application.settings.clearActiveSession()
            application.trackUploads.enqueueIfEnabled(session.id)
            stopSelfSafely()
            return
        }
        if (session == null && allowCreate) {
            val settings = application.settings.current()
            val profile = RecordingProfiles.config(settings.recordingProfile, settings.targetIntervalSeconds)
            val now = System.currentTimeMillis()
            session = TrackSessionEntity(
                id = UUID.randomUUID().toString(),
                startedAtUtc = now,
                endedAtUtc = null,
                timeZone = ZoneId.systemDefault().id,
                status = SessionStatus.ACTIVE,
                profile = profile.id,
                targetIntervalSeconds = profile.targetIntervalSeconds,
                minIntervalSeconds = profile.minIntervalSeconds,
                maxBatchDelaySeconds = profile.maxBatchDelaySeconds,
                adminLookupEnabled = settings.adminLookupEnabled,
            )
            dao.insertSession(session)
            application.settings.setActiveSession(session.id, now)
        }
        if (session == null) {
            application.settings.clearActiveSession()
            stopSelfSafely()
            return
        }

        activeSession = session
        val operationWakeLock = acquireOperationWakeLock("start-or-resume")
        try {
            scheduleRescueAlarm()
            updateNotification()
            requestLocationUpdates(session)
            capturePrecisePoint(manual = false)
            scheduleRescueAlarm()
        } finally {
            releaseWakeLock(operationWakeLock)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(session: TrackSessionEntity) {
        if (locationUpdatesStarted || !hasForegroundLocationPermission()) return
        val interval = TimeUnit.SECONDS.toMillis(session.targetIntervalSeconds.toLong())
        val highAccuracy = RecordingProfiles.usesHighAccuracy(session.profile)
        val priority = if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(TimeUnit.SECONDS.toMillis(session.minIntervalSeconds.toLong()))
            .setMaxUpdateDelayMillis(TimeUnit.SECONDS.toMillis(session.maxBatchDelaySeconds.toLong()))
            .setWaitForAccurateLocation(highAccuracy)
            .build()
        locationUpdatesStarted = true
        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            .addOnFailureListener {
                locationUpdatesStarted = false
                launchServiceCommand("continuous-location-request") {
                    application.database.dao().incrementGapCount(session.id)
                    updateNotification("持续定位启动失败，稍后补救")
                }
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun capturePrecisePoint(manual: Boolean): Boolean {
        val session = activeSession ?: return false
        if (!hasForegroundLocationPermission()) return false
        if (!isLocationEnabled()) {
            if (manual) application.database.dao().incrementGapCount(session.id)
            updateNotification(if (manual) "精准打点未取得位置" else "系统定位已关闭")
            return false
        }
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:precise-location",
        ).apply { acquire(35_000) }
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(30_000)
                .setMaxUpdateAgeMillis(if (manual) 0 else 5_000)
                .build()
            val location = awaitCurrentLocation(request)
            if (location == null) {
                if (manual) application.database.dao().incrementGapCount(session.id)
                updateNotification("精准打点未取得位置")
                false
            } else {
                val inserted = writeLocations(listOf(location)) > 0
                val freshPointAlreadyStored = !inserted && RescuePolicy.isFresh(
                    application.database.dao().getLatestPoint(session.id)?.timestampUtc,
                    System.currentTimeMillis(),
                )
                if (freshPointAlreadyStored) {
                    activeSession = application.database.dao().getSession(session.id)
                    updateNotification()
                } else if (!inserted) {
                    if (manual) application.database.dao().incrementGapCount(session.id)
                    updateNotification(if (manual) "精准打点未取得新位置" else "定位点未更新，稍后重试")
                }
                inserted || freshPointAlreadyStored
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun runScheduledRescue() = operationMutex.withLock {
        val session = activeSession ?: application.database.dao().getActiveSession()
        if (session == null) {
            cancelRescueAlarm()
            stopSelfSafely()
            return
        }
        activeSession = session
        val operationWakeLock = acquireOperationWakeLock("scheduled-rescue")
        try {
            scheduleRescueAlarm()
            val latest = application.database.dao().getLatestPoint(session.id)
            val now = System.currentTimeMillis()
            if (RescuePolicy.shouldRescue(latest?.timestampUtc, now, nextAllowedUtc = 0)) {
                if (!capturePrecisePoint(manual = false)) {
                    application.database.dao().incrementGapCount(session.id)
                    updateNotification("定位缺口，稍后重试")
                }
            } else {
                updateNotification()
            }
        } finally {
            try {
                if (activeSession != null) scheduleRescueAlarm()
            } finally {
                releaseWakeLock(operationWakeLock)
            }
        }
    }

    private fun scheduleRescueAlarm() {
        if (activeSession == null) return
        val alarmManager = getSystemService(AlarmManager::class.java)
        val trigger = SystemClock.elapsedRealtime() + RescuePolicy.STALE_AFTER_MILLIS
        val operation = rescuePendingIntent()
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
                return
            } catch (_: SecurityException) {
                // Fall through to a permission-free, inexact Doze alarm.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
    }

    private fun cancelRescueAlarm() {
        getSystemService(AlarmManager::class.java).cancel(rescuePendingIntent())
    }

    private fun rescuePendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        3,
        Intent(this, LocationRecordingService::class.java).setAction(ACTION_RESCUE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private suspend fun writeLocations(locations: List<Location>): Int = writeMutex.withLock {
        val session = activeSession ?: return@withLock 0
        if (locations.isEmpty()) return@withLock 0
        val dao = application.database.dao()
        var previous = dao.getLatestPoint(session.id)
        val entities = locations
            .sortedWith(compareBy<Location> { it.elapsedRealtimeNanos }.thenBy { it.time })
            .mapNotNull { location ->
                val entity = location.toEntity(session)
                if (PointValidator.isObviousDuplicate(previous, entity)) null else entity.also { previous = it }
            }
        if (entities.isEmpty()) return@withLock 0
        val ids = dao.insertPointsAndRefresh(session.id, entities)
        entities.zip(ids).forEach { (point, id) ->
            if (id > 0 && point.usable) {
                adminResolver.resolveAndAttach(id, point.copy(id = id), session.adminLookupEnabled)
            }
        }
        activeSession = dao.getSession(session.id)
        updateNotification()
        scheduleRescueAlarm()
        ids.count { it > 0 }
    }

    private suspend fun stopRecording(): Unit = operationMutex.withLock {
        val session = activeSession ?: application.database.dao().getActiveSession()
        if (session == null) {
            stopSelfSafely()
            return@withLock
        }
        Log.i(TAG, "Stopping session ${session.id}")
        activeSession = session
        val operationWakeLock = acquireOperationWakeLock("stop")
        try {
            if (locationUpdatesStarted) {
                try {
                    capturePrecisePoint(manual = false)
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "The final provider request was cancelled; completing the session without it")
                } catch (error: Exception) {
                    Log.w(TAG, "The final provider request failed; completing the session without it", error)
                }
                removeLocationUpdatesSafely()
                locationUpdatesStarted = false
            } else {
                Log.i(TAG, "Skipping the final provider request because this process was rebuilt only to stop")
            }
            application.database.dao().finishSession(
                session.id,
                SessionStatus.COMPLETED,
                System.currentTimeMillis(),
                null,
            )
            application.database.dao().refreshPointCounts(session.id)
            application.settings.clearActiveSession()
            application.trackUploads.enqueueIfEnabled(session.id)
            activeSession = null
            cancelRescueAlarm()
            stopSelfSafely()
            Log.i(TAG, "Stopped session ${session.id}")
        } finally {
            releaseWakeLock(operationWakeLock)
        }
    }

    private fun acquireOperationWakeLock(operation: String): PowerManager.WakeLock =
        getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:$operation",
        ).apply { acquire(45_000) }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        if (wakeLock.isHeld) wakeLock.release()
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(request: CurrentLocationRequest): Location? = supervisorScope {
        val providerRequest = async {
            val tokenSource = CancellationTokenSource()
            val task = fusedClient.getCurrentLocation(request, tokenSource.token)
            try {
                withTimeoutOrNull(30_500) { task.await() }
            } finally {
                if (!task.isComplete) tokenSource.cancel()
            }
        }
        try {
            providerRequest.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun removeLocationUpdatesSafely(): Unit = supervisorScope {
        val providerRemoval = async {
            withTimeoutOrNull(5_000) { fusedClient.removeLocationUpdates(locationCallback).await() }
        }
        try {
            providerRemoval.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (_: Exception) {
            // Finishing the durable session is more important than a failed provider cleanup call.
        }
    }

    private fun launchServiceCommand(action: String, block: suspend () -> Unit) {
        serviceScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Service command task was cancelled without cancelling its coroutine: $action", cancelled)
                handleCommandFailure(action)
            } catch (error: Exception) {
                Log.e(TAG, "Service command failed: $action", error)
                handleCommandFailure(action)
            }
        }
    }

    private suspend fun handleCommandFailure(action: String) {
        val session = activeSession ?: runCatching { application.database.dao().getActiveSession() }.getOrNull()
        if (session == null) {
            stopSelfSafely()
            return
        }
        activeSession = session
        try {
            application.database.dao().incrementGapCount(session.id)
        } catch (_: Exception) {
            // The foreground notification remains the final durable user-visible signal.
        }
        try {
            updateNotification(if (action == ACTION_STOP) "停止未完成，仍在记录；请重试" else "定位任务失败，等待下一次补救")
        } catch (_: Exception) {
            // Keep the active session and foreground service alive even if notification refresh fails.
        }
        try {
            scheduleRescueAlarm()
        } catch (_: Exception) {
            // Balanced/high-accuracy updates remain active even if the fallback alarm cannot be scheduled.
        }
    }

    private fun Location.toEntity(session: TrackSessionEntity): TrackPointEntity {
        val mock = if (android.os.Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider
        val accuracy = if (hasAccuracy()) accuracy else null
        return TrackPointEntity(
            sessionId = session.id,
            timestampUtc = time,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude()) altitude else null,
            accuracyMeters = accuracy,
            verticalAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
            speedMps = if (hasSpeed()) speed else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            provider = provider,
            isMock = mock,
            usable = PointValidator.isUsable(
                CandidatePoint(time, latitude, longitude, accuracy, mock),
                session.startedAtUtc,
            ),
        )
    }

    private fun showPreparingNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("正在准备定位…", null),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private suspend fun updateNotification(overrideText: String? = null) {
        val session = activeSession
        val latest = session?.let { application.database.dao().getLatestPoint(it.id) }
        val content = overrideText ?: when {
            session == null -> "正在准备定位…"
            latest == null -> "等待第一个定位点"
            else -> {
                val ageMinutes = ((System.currentTimeMillis() - latest.timestampUtc).coerceAtLeast(0) / 60_000)
                val accuracy = latest.accuracyMeters?.let { "±${it.toInt()}米" } ?: "精度未知"
                val place = listOfNotNull(latest.city, latest.district, latest.name).distinct().joinToString(" ")
                "${session.rawPointCount} 点 · ${ageMinutes}分钟前 · $accuracy${if (place.isBlank()) "" else " · $place"}"
            }
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(content, session),
        )
    }

    private fun buildNotification(text: String, session: TrackSessionEntity?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val preciseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationRecordingService::class.java).setAction(ACTION_PRECISE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LocationRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gpslog)
            .setContentTitle("GPSLog 正在记录")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "精准打点", preciseIntent)
            .addAction(0, "停止记录", stopIntent)
        if (session != null) {
            val elapsedSinceStart = (System.currentTimeMillis() - session.startedAtUtc).coerceAtLeast(0)
            builder.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsedSinceStart)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "持续定位记录", NotificationManager.IMPORTANCE_LOW).apply {
            description = "记录期间始终显示 GPSLog 的状态和控制按钮"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasForegroundLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasPersistentCorePermissions(): Boolean {
        val background = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationPermission = Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val recordingChannel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        val notifications = notificationPermission &&
            notificationManager.areNotificationsEnabled() &&
            (recordingChannel == null || recordingChannel.importance != NotificationManager.IMPORTANCE_NONE)
        return hasForegroundLocationPermission() && background && notifications
    }

    private fun isLocationEnabled(): Boolean = getSystemService(LocationManager::class.java).isLocationEnabled

    private suspend fun interruptActiveAndStop(reason: String) {
        application.database.dao().getActiveSession()?.let {
            application.database.dao().finishSession(
                it.id,
                SessionStatus.INTERRUPTED,
                System.currentTimeMillis(),
                reason,
            )
            application.trackUploads.enqueueIfEnabled(it.id)
        }
        application.settings.clearActiveSession()
        activeSession = null
        cancelRescueAlarm()
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (locationUpdatesStarted) fusedClient.removeLocationUpdates(locationCallback)
        locationQueue.close()
        runBlocking(Dispatchers.IO) { withTimeoutOrNull(2_000) { writerJob?.join() } }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GPSLogService"
        const val ACTION_START = "com.muchenhen.gpslog.action.START"
        const val ACTION_RESUME = "com.muchenhen.gpslog.action.RESUME"
        const val ACTION_PRECISE = "com.muchenhen.gpslog.action.PRECISE"
        const val ACTION_STOP = "com.muchenhen.gpslog.action.STOP"
        const val ACTION_RESCUE = "com.muchenhen.gpslog.action.RESCUE"
        const val NOTIFICATION_CHANNEL_ID = "gpslog_recording"
        private const val CHANNEL_ID = NOTIFICATION_CHANNEL_ID
        private const val NOTIFICATION_ID = 1107
        fun startIntent(context: Context) = Intent(context, LocationRecordingService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context) = Intent(context, LocationRecordingService::class.java).setAction(ACTION_STOP)
    }
}
