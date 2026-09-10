package com.muchenhen.gpslog.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GPSLogDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GPSLogDatabase::class.java,
    )

    @Before
    fun deletePreviousTestDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration1To2PreservesSessionsAndAddsUploadState() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO track_sessions (
                    id, startedAtUtc, endedAtUtc, timeZone, status, profile,
                    targetIntervalSeconds, minIntervalSeconds, maxBatchDelaySeconds,
                    adminLookupEnabled, rawPointCount, usablePointCount, gapCount,
                    interruptionReason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    SESSION_ID, 1_000L, 2_000L, "Asia/Shanghai", SessionStatus.COMPLETED,
                    "endurance", 240, 120, 300, 0, 1, 1, 0, null,
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            GPSLogDatabase.MIGRATION_1_2,
        ).use { migrated ->
            migrated.query(
                """
                SELECT id, uploadStatus, uploadRevisionSha256, uploadAttemptCount,
                       uploadLastErrorCode, uploadLastAttemptAtUtc, uploadedAtUtc
                FROM track_sessions WHERE id = ?
                """.trimIndent(),
                arrayOf(SESSION_ID),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(SESSION_ID, cursor.getString(0))
                assertEquals(UploadStatus.NOT_CONFIGURED, cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(true, cursor.isNull(4))
                assertEquals(true, cursor.isNull(5))
                assertEquals(true, cursor.isNull(6))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gpslog-migration-test"
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}
