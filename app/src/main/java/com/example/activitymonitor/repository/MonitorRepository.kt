package com.example.activitymonitor.repository

import android.content.Context
import com.example.activitymonitor.db.ActivityEventEntity
import com.example.activitymonitor.db.ApplicationSessionEntity
import com.example.activitymonitor.db.BatterySampleEntity
import com.example.activitymonitor.db.MonitorDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MonitorRepository(context: Context) {
    private val db = MonitorDatabase.get(context)
    private val sessions = db.sessionDao()
    private val events = db.eventDao()
    private val battery = db.batteryDao()
    private val tracking = db.appTrackingDao()

    fun observeRecentEvents(limit: Int = 150): Flow<List<ActivityEventEntity>> = events.observeRecent(limit)
    fun observeBattery(limit: Int = 60): Flow<List<BatterySampleEntity>> = battery.observeRecent(limit)
    suspend fun recentSessions(limit: Int = 30): List<ApplicationSessionEntity> = withContext(Dispatchers.IO) { sessions.recent(limit) }

    suspend fun event(id: Long) = withContext(Dispatchers.IO) { events.findById(id) }
    suspend fun packageEvents(packageName: String, start: Long, end: Long, limit: Int = 1500) = withContext(Dispatchers.IO) { events.byPackage(packageName, start, end, limit) }
    suspend fun latestEvent(packageName: String) = withContext(Dispatchers.IO) { events.latestForPackage(packageName) }
    suspend fun packageSessionCounts(start: Long, end: Long) = withContext(Dispatchers.IO) { sessions.countsByPackage(start, end) }
    suspend fun eventsBetween(start: Long, end: Long, limit: Int = 5000) = withContext(Dispatchers.IO) { events.between(start, end, limit) }
    suspend fun search(query: String, start: Long, end: Long, packageName: String? = null, eventType: Int? = null, limit: Int = 500) = withContext(Dispatchers.IO) { events.search(query.trim(), start, end, packageName, eventType, limit) }
    suspend fun insertEvent(event: ActivityEventEntity) = withContext(Dispatchers.IO) { events.insert(event) }
    suspend fun insertBattery(sample: BatterySampleEntity) = withContext(Dispatchers.IO) { battery.insert(sample) }
    suspend fun totalDuration(start: Long, end: Long) = withContext(Dispatchers.IO) { sessions.totalDuration(start, end, System.currentTimeMillis()) }
    suspend fun sessionCount(start: Long, end: Long) = withContext(Dispatchers.IO) { sessions.count(start, end) }
    suspend fun eventCount(start: Long, end: Long) = withContext(Dispatchers.IO) { events.count(start, end) }
    suspend fun topAppsByEvents(start: Long, end: Long, limit: Int = 8) = withContext(Dispatchers.IO) { events.topAppsByEvents(start, end, limit) }
    suspend fun eventCounts(start: Long, end: Long) = withContext(Dispatchers.IO) { events.eventCounts(start, end) }
    suspend fun trackingSettings() = withContext(Dispatchers.IO) { tracking.all() }
    suspend fun upsertTracking(packageName: String, enabled: Boolean) = withContext(Dispatchers.IO) { tracking.upsert(com.example.activitymonitor.db.AppTrackingSettingEntity(packageName = packageName, enabled = enabled)) }
    suspend fun cleanup(retentionDays: Int, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val cutoff = now - retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        events.deleteOlderThan(cutoff); sessions.deleteOlderThan(cutoff); battery.deleteOlderThan(cutoff)
    }
    suspend fun deleteAllHistory() = withContext(Dispatchers.IO) { events.deleteAll(); sessions.deleteAll(); battery.deleteAll() }
    suspend fun closeOpenSessions(now: Long) = withContext(Dispatchers.IO) { sessions.closeAllOpen(now) }
}
