package com.example.activitymonitor.monitoring

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.activitymonitor.db.ActivityEventEntity
import com.example.activitymonitor.db.ApplicationSessionEntity
import com.example.activitymonitor.db.MonitorDatabase
import com.example.activitymonitor.repository.MonitorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Records app/window transitions only.
 * It deliberately does not inspect view trees or capture taps, text, scrolling or focus events.
 */
class MonitorAccessibilityService : AccessibilityService() {
    private val serialDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serialDispatcher)
    private lateinit var repository: MonitorRepository

    private var currentPackage: String? = null
    private var currentSessionId: Long? = null
    private var lastActivityClass: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = MonitorRepository(applicationContext)
        scope.launch {
            val database = MonitorDatabase.get(applicationContext)
            runCatching { repository.closeOpenSessions(System.currentTimeMillis()) }
            // Purge legacy click/text/scroll records and strip any old text payloads
            // from transition records: the new monitoring model is app/activity only.
            runCatching { database.eventDao().deleteOtherTypes(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) }
            runCatching { database.eventDao().clearDetailsForType(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) }
            currentPackage = null
            currentSessionId = null
            lastActivityClass = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foregroundPackage = event.packageName?.toString() ?: return
        val activityClass = event.className?.toString()
        val now = System.currentTimeMillis()

        val monitoringEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_MONITORING_ENABLED, true)

        if (!monitoringEnabled || foregroundPackage == packageName) {
            ScreenCaptureService.setActivePackage(null)
            scope.launch { closeCurrentSession(now) }
            return
        }

        // Keep screen recording transitions separate from the database logging preference.
        ScreenCaptureService.setActivePackage(foregroundPackage)

        if (!::repository.isInitialized) return
        val shouldTrackApp = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean("track_$foregroundPackage", true)

        scope.launch {
            if (!shouldTrackApp) {
                closeCurrentSession(now)
                return@launch
            }

            val isNewApp = currentPackage != foregroundPackage
            val isNewActivity = activityClass != null && activityClass != lastActivityClass
            if (!isNewApp && !isNewActivity) return@launch

            if (isNewApp) {
                closeCurrentSession(now)
                val appName = appName(foregroundPackage)
                currentSessionId = MonitorDatabase.get(applicationContext).sessionDao().insert(
                    ApplicationSessionEntity(
                        packageName = foregroundPackage,
                        appName = appName,
                        startTime = now
                    )
                )
                currentPackage = foregroundPackage
                lastActivityClass = null
                repository.insertEvent(
                    ActivityEventEntity(
                        timestamp = now,
                        packageName = foregroundPackage,
                        appName = appName,
                        eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                        eventDescription = "נפתחה אפליקציה",
                        className = activityClass?.take(250),
                        activityName = activityClass?.take(250),
                        sessionId = currentSessionId
                    )
                )
            } else if (isNewActivity) {
                repository.insertEvent(
                    ActivityEventEntity(
                        timestamp = now,
                        packageName = foregroundPackage,
                        appName = appName(foregroundPackage),
                        eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                        eventDescription = "מעבר למסך באפליקציה",
                        className = activityClass?.take(250),
                        activityName = activityClass?.take(250),
                        sessionId = currentSessionId
                    )
                )
            }

            lastActivityClass = activityClass
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        val openSessionId = currentSessionId
        if (openSessionId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    MonitorDatabase.get(applicationContext).sessionDao()
                        .close(openSessionId, System.currentTimeMillis())
                }
            }
        }
        scope.cancel()
        serialDispatcher.close()
        super.onDestroy()
    }

    private suspend fun closeCurrentSession(now: Long) {
        val id = currentSessionId
        val pkg = currentPackage
        if (id != null && pkg != null && ::repository.isInitialized) {
            val name = appName(pkg)
            runCatching {
                repository.insertEvent(
                    ActivityEventEntity(
                        timestamp = now,
                        packageName = pkg,
                        appName = name,
                        eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                        eventDescription = "נסגרה אפליקציה",
                        sessionId = id
                    )
                )
            }
            runCatching { MonitorDatabase.get(applicationContext).sessionDao().close(id, now) }
        }
        currentSessionId = null
        currentPackage = null
        lastActivityClass = null
    }

    private fun appName(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString().ifBlank { pkg }
    }.getOrElse { pkg }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    }
}
