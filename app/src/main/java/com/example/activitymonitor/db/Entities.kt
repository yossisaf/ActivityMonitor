package com.example.activitymonitor.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "application_sessions", indices = [Index(value = ["packageName", "startTime"]), Index(value = ["startTime"]), Index(value = ["endTime"])])
data class ApplicationSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val startTime: Long,
    val endTime: Long? = null,
    val duration: Long = 0L
)

@Entity(tableName = "activity_events", indices = [Index(value = ["timestamp"]), Index(value = ["packageName", "timestamp"]), Index(value = ["eventType", "timestamp"]), Index(value = ["sessionId"]), Index(value = ["viewId"])])
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val packageName: String,
    val appName: String,
    val eventType: Int,
    val eventDescription: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val activityName: String? = null,
    val sessionId: Long? = null,
    val sourceInfo: String? = null
)

@Entity(tableName = "battery_samples", indices = [Index(value = ["timestamp"])])
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val batteryLevel: Int,
    val charging: Boolean,
    val temperature: Float?,
    val status: Int
)

@Entity(tableName = "app_tracking_settings", indices = [Index(value = ["packageName"], unique = true)])
data class AppTrackingSettingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val enabled: Boolean = true
)
