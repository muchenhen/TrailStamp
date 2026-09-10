package com.muchenhen.gpslog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackSessionEntity::class, TrackPointEntity::class, AdminCacheEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class GPSLogDatabase : RoomDatabase() {
    abstract fun dao(): GPSLogDao

    companion object {
        fun create(context: Context): GPSLogDatabase = Room.databaseBuilder(
            context.applicationContext,
            GPSLogDatabase::class.java,
            "gpslog.db",
        ).addMigrations(MIGRATION_1_2).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE track_sessions ADD COLUMN uploadStatus TEXT NOT NULL DEFAULT 'not_configured'")
                database.execSQL("ALTER TABLE track_sessions ADD COLUMN uploadRevisionSha256 TEXT")
                database.execSQL("ALTER TABLE track_sessions ADD COLUMN uploadAttemptCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE track_sessions ADD COLUMN uploadLastErrorCode TEXT")
                database.execSQL("ALTER TABLE track_sessions ADD COLUMN uploadLastAttemptAtUtc INTEGER")
                database.execSQL("ALTER TABLE track_sessions ADD COLUMN uploadedAtUtc INTEGER")
            }
        }
    }
}
