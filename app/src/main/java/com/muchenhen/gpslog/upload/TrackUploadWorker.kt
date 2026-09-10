package com.muchenhen.gpslog.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.muchenhen.gpslog.GPSLogApplication
import com.muchenhen.gpslog.data.GPSLogDao
import com.muchenhen.gpslog.data.SettingsRepository
import com.muchenhen.gpslog.data.UploadStatus
import com.muchenhen.gpslog.export.TrackExporter
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class TrackUploadWorker(
    applicationContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(applicationContext, parameters) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val application = applicationContext as GPSLogApplication
        val dao = application.database.dao()
        val session = dao.getSession(sessionId) ?: return Result.failure()
        if (!TrackUploadPolicy.canUpload(session.status, session.endedAtUtc)) {
            dao.markUploadFailed(sessionId, UploadStatus.FAILED, "session_not_uploadable")
            return Result.failure(errorData("session_not_uploadable"))
        }
        val settings = application.settings.current()
        if (!manual && !settings.autoUploadEnabled) {
            dao.markUploadFailed(sessionId, UploadStatus.NOT_CONFIGURED, "auto_upload_disabled")
            return Result.success()
        }
        val serverUrl = runCatching {
            TrackUploadPolicy.normalizeServerUrl(settings.processingServerUrl)
        }.getOrElse {
            dao.markUploadFailed(sessionId, UploadStatus.NOT_CONFIGURED, "server_url_invalid")
            return Result.failure(errorData("server_url_invalid"))
        }
        val token = application.credentials.readToken()
        if (token == null) {
            dao.markUploadFailed(sessionId, UploadStatus.NOT_CONFIGURED, "token_missing")
            return Result.failure(errorData("token_missing"))
        }
        if (dao.markUploadStarted(sessionId, System.currentTimeMillis()) != 1) {
            return Result.failure(errorData("session_not_uploadable"))
        }

        return try {
            val points = dao.getPoints(sessionId)
            val response = TrackApiClient().upload(
                serverUrl = serverUrl,
                sessionId = sessionId,
                token = token,
                trackJson = TrackExporter.toJson(session, points),
            )
            when (response.disposition) {
                UploadDisposition.SUCCESS -> {
                    dao.markUploadCompleted(
                        sessionId,
                        response.revisionSha256,
                        System.currentTimeMillis(),
                    )
                    Result.success(
                        workDataOf(
                            KEY_REVISION_SHA256 to response.revisionSha256,
                            KEY_HTTP_STATUS to response.statusCode,
                        ),
                    )
                }
                UploadDisposition.RETRYABLE -> retryOrFail(
                    dao,
                    sessionId,
                    response.errorCode ?: "server_retryable",
                )
                UploadDisposition.PERMANENT_FAILURE -> {
                    val code = response.errorCode ?: "server_rejected"
                    dao.markUploadFailed(sessionId, UploadStatus.FAILED, code)
                    Result.failure(errorData(code, response.statusCode))
                }
            }
        }
        catch (cancelled: CancellationException) {
            throw cancelled
        }
        catch (_: IOException) {
            retryOrFail(dao, sessionId, "network_io")
        }
        catch (_: Exception) {
            dao.markUploadFailed(sessionId, UploadStatus.FAILED, "client_error")
            Result.failure(errorData("client_error"))
        }
    }

    private suspend fun retryOrFail(dao: GPSLogDao, sessionId: String, code: String): Result {
        val exhausted = runAttemptCount + 1 >= MAX_ATTEMPTS
        dao.markUploadFailed(
            sessionId,
            if (exhausted) UploadStatus.FAILED else UploadStatus.PENDING,
            code,
        )
        return if (exhausted) Result.failure(errorData(code)) else Result.retry()
    }

    private fun errorData(code: String, httpStatus: Int? = null): Data = workDataOf(
        KEY_ERROR_CODE to code,
        KEY_HTTP_STATUS to (httpStatus ?: 0),
    )

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_MANUAL = "manual"
        const val KEY_REVISION_SHA256 = "revision_sha256"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_HTTP_STATUS = "http_status"
        const val TAG_ALL_UPLOADS = "gpslog-track-uploads"
        const val MAX_ATTEMPTS = 8
    }
}

class TrackUploadScheduler(
    context: Context,
    private val dao: GPSLogDao,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun enqueueIfEnabled(sessionId: String): Boolean = enqueue(
        sessionId = sessionId,
        manual = false,
        replace = false,
    )

    suspend fun retry(sessionId: String): Boolean = enqueue(
        sessionId = sessionId,
        manual = true,
        replace = true,
    )

    suspend fun enqueueAllEligible(): Int {
        val current = settings.current()
        if (!current.autoUploadEnabled || !configurationReady(current.processingServerUrl)) return 0
        return dao.getUploadCandidates().count { enqueueIfEnabled(it.id) }
    }

    suspend fun enqueue(
        sessionId: String,
        manual: Boolean,
        replace: Boolean,
    ): Boolean {
        val current = settings.current()
        if (!manual && !current.autoUploadEnabled) return false
        if (!configurationReady(current.processingServerUrl)) {
            dao.markUploadFailed(sessionId, UploadStatus.NOT_CONFIGURED, "upload_not_configured")
            return false
        }
        if (dao.markUploadPending(sessionId) != 1) return false
        val request = OneTimeWorkRequestBuilder<TrackUploadWorker>()
            .setInputData(
                workDataOf(
                    TrackUploadWorker.KEY_SESSION_ID to sessionId,
                    TrackUploadWorker.KEY_MANUAL to manual,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TrackUploadWorker.TAG_ALL_UPLOADS)
            .addTag(TrackUploadPolicy.workName(sessionId))
            .build()
        workManager.enqueueUniqueWork(
            TrackUploadPolicy.workName(sessionId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    fun cancel(sessionId: String) {
        workManager.cancelUniqueWork(TrackUploadPolicy.workName(sessionId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TrackUploadWorker.TAG_ALL_UPLOADS)
    }

    private fun configurationReady(serverUrl: String): Boolean =
        credentials.hasToken() && runCatching {
            TrackUploadPolicy.normalizeServerUrl(serverUrl)
        }.isSuccess
}
