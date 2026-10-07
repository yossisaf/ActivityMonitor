package com.example.activitymonitor.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: ApplicationSessionEntity): Long
    @Query("UPDATE application_sessions SET endTime = :endTime, duration = MAX(0, :endTime - startTime) WHERE id = :id") suspend fun close(id: Long, endTime: Long): Int
    @Query("UPDATE application_sessions SET endTime = :endTime, duration = MAX(0, :endTime - startTime) WHERE endTime IS NULL") suspend fun closeAllOpen(endTime: Long): Int
    @Query("SELECT * FROM application_sessions ORDER BY startTime DESC LIMIT :limit") suspend fun recent(limit: Int): List<ApplicationSessionEntity>
    @Query("SELECT COUNT(*) FROM application_sessions WHERE packageName = :packageName AND startTime >= :start AND startTime < :end") suspend fun countForPackage(packageName: String, start: Long, end: Long): Int
    @Query("SELECT COALESCE(SUM(CASE WHEN endTime IS NULL THEN MAX(0, :now - startTime) ELSE duration END),0) FROM application_sessions WHERE startTime >= :start AND startTime < :end") suspend fun totalDuration(start: Long, end: Long, now: Long): Long
    @Query("SELECT COUNT(*) FROM application_sessions WHERE startTime >= :start AND startTime < :end") suspend fun count(start: Long, end: Long): Int
    @Query("DELETE FROM application_sessions WHERE startTime < :cutoff") suspend fun deleteOlderThan(cutoff: Long): Int
    @Query("DELETE FROM application_sessions") suspend fun deleteAll(): Int
}

@Dao
interface EventDao {
    @Insert suspend fun insert(event: ActivityEventEntity): Long
    @Insert suspend fun insertAll(events: List<ActivityEventEntity>): List<Long>
    @Query("SELECT * FROM activity_events ORDER BY timestamp DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<ActivityEventEntity>>
    @Query("SELECT * FROM activity_events WHERE packageName = :packageName AND timestamp >= :start AND timestamp < :end ORDER BY timestamp DESC LIMIT :limit") suspend fun byPackage(packageName: String, start: Long, end: Long, limit: Int): List<ActivityEventEntity>
    @Query("SELECT COUNT(*) FROM activity_events WHERE packageName = :packageName AND timestamp >= :start AND timestamp < :end") suspend fun countForPackage(packageName: String, start: Long, end: Long): Int
    @Query("SELECT * FROM activity_events WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT 1") suspend fun latestForPackage(packageName: String): ActivityEventEntity?
    @Query("""
        SELECT * FROM activity_events
        WHERE timestamp >= :start AND timestamp < :end
          AND (:packageName IS NULL OR packageName = :packageName)
          AND (:eventType IS NULL OR eventType = :eventType)
          AND (
            :query = '' OR
            appName LIKE '%' || :query || '%' OR
            packageName LIKE '%' || :query || '%' OR
            IFNULL(text, '') LIKE '%' || :query || '%' OR
            IFNULL(contentDescription, '') LIKE '%' || :query || '%' OR
            IFNULL(viewId, '') LIKE '%' || :query || '%' OR
            IFNULL(activityName, '') LIKE '%' || :query || '%' OR
            eventDescription LIKE '%' || :query || '%'
          )
        ORDER BY timestamp DESC LIMIT :limit
    """) suspend fun search(query: String, start: Long, end: Long, packageName: String?, eventType: Int?, limit: Int): List<ActivityEventEntity>
    @Query("SELECT * FROM activity_events WHERE id = :id LIMIT 1") suspend fun findById(id: Long): ActivityEventEntity?
    @Query("SELECT eventType, COUNT(*) AS count FROM activity_events WHERE timestamp >= :start AND timestamp < :end GROUP BY eventType ORDER BY count DESC") suspend fun eventCounts(start: Long, end: Long): List<EventCount>
    @Query("SELECT packageName, appName, COUNT(*) AS count FROM activity_events WHERE timestamp >= :start AND timestamp < :end GROUP BY packageName, appName ORDER BY count DESC LIMIT :limit") suspend fun topAppsByEvents(start: Long, end: Long, limit: Int): List<AppCount>
    @Query("SELECT COUNT(*) FROM activity_events WHERE timestamp >= :start AND timestamp < :end") suspend fun count(start: Long, end: Long): Int
    @Query("DELETE FROM activity_events WHERE timestamp < :cutoff") suspend fun deleteOlderThan(cutoff: Long): Int
    @Query("DELETE FROM activity_events") suspend fun deleteAll(): Int
}

data class EventCount(val eventType: Int, val count: Int)
data class AppCount(val packageName: String, val appName: String, val count: Int)

@Dao
interface BatteryDao {
    @Insert suspend fun insert(sample: BatterySampleEntity): Long
    @Query("SELECT * FROM battery_samples ORDER BY timestamp DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<BatterySampleEntity>>
    @Query("DELETE FROM battery_samples WHERE timestamp < :cutoff") suspend fun deleteOlderThan(cutoff: Long): Int
    @Query("DELETE FROM battery_samples") suspend fun deleteAll(): Int
}

@Dao
interface AppTrackingDao {
    @Query("SELECT * FROM app_tracking_settings") suspend fun all(): List<AppTrackingSettingEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(setting: AppTrackingSettingEntity)
}
