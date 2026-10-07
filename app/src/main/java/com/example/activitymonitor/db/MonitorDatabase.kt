package com.example.activitymonitor.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ApplicationSessionEntity::class, ActivityEventEntity::class, BatterySampleEntity::class, AppTrackingSettingEntity::class], version = 1, exportSchema = false)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun batteryDao(): BatteryDao
    abstract fun appTrackingDao(): AppTrackingDao

    companion object {
        @Volatile private var INSTANCE: MonitorDatabase? = null
        fun get(context: Context): MonitorDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, MonitorDatabase::class.java, "activity_monitor.db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
