package com.cropguard.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [YardProfile::class, YardSession::class, ScanRecord::class, ScheduledNotification::class],
    version  = 2,
    exportSchema = false
)
abstract class CropGuardDatabase : RoomDatabase() {

    abstract fun yardProfileDao(): YardProfileDao
    abstract fun yardSessionDao(): YardSessionDao
    abstract fun scanRecordDao(): ScanRecordDao
    abstract fun scheduledNotificationDao(): ScheduledNotificationDao

    companion object {
        @Volatile private var INSTANCE: CropGuardDatabase? = null

        // v1 → v2: optional GPS coordinates on yard_profiles + scheduled_notifications table.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE yard_profiles ADD COLUMN latitudeDeg REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE yard_profiles ADD COLUMN longitudeDeg REAL DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scheduled_notifications (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        yardProfileId   INTEGER NOT NULL,
                        cellKey         TEXT NOT NULL,
                        title           TEXT NOT NULL,
                        body            TEXT NOT NULL,
                        scheduledForMs  INTEGER NOT NULL,
                        urgency         TEXT NOT NULL,
                        workRequestId   TEXT NOT NULL,
                        FOREIGN KEY(yardProfileId) REFERENCES yard_profiles(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_notifications_yardProfileId ON scheduled_notifications(yardProfileId)")
            }
        }

        fun getInstance(context: Context): CropGuardDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CropGuardDatabase::class.java,
                    "cropguard.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
