package com.example.activitymonitor.monitoring

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.activitymonitor.db.ActivityEventEntity
import com.example.activitymonitor.db.ApplicationSessionEntity
import com.example.activitymonitor.db.MonitorDatabase
import com.example.activitymonitor.repository.CryptoManager
import com.example.activitymonitor.repository.MonitorRepository
import com.example.activitymonitor.ui.EventTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

class MonitorAccessibilityService : AccessibilityService() {
    private lateinit var repository: MonitorRepository
    private lateinit var crypto: CryptoManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentPackage: String? = null
    private var currentSessionId: Long? = null
    private val signatures = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 256
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = MonitorRepository(applicationContext)
        crypto = CryptoManager(applicationContext)
        scope.launch { runCatching { repository.closeOpenSessions(System.currentTimeMillis()) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::repository.isInitialized || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("track_$pkg", true)) return
        val now = System.currentTimeMillis()
        val source = runCatching { event.source }.getOrNull()
        val password = runCatching { source?.isPassword == true }.getOrDefault(false)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val captureText = prefs.getBoolean("capture_text", true)
        val captureSource = prefs.getBoolean("capture_source", true)
        val encryptTechnical = prefs.getBoolean("encrypt_technical", false)
        val text = if (password || !captureText) null else event.text.joinToString(" ").trim().ifBlank { null }?.take(500)
        val contentDescription = if (password || !captureText) null else source?.contentDescription?.toString()?.take(500)
        val viewId = runCatching { source?.viewIdResourceName }.getOrNull()?.take(250)
        val className = event.className?.toString() ?: source?.className?.toString()
        val activity = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) className else null
        val signature = "$pkg|${event.eventType}|$className|$text|$contentDescription|$viewId"
        synchronized(signatures) {
            val last = signatures[signature]
            if (last != null && now - last < 250) { source?.recycle(); return }
            signatures[signature] = now
        }
        scope.launch {
            try {
                ensureSession(pkg, now)
                val sourceInfo = if (captureSource && source != null && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    val summary = sourceSummary(source)
                    if (encryptTechnical && summary != null) crypto.encrypt(summary) else summary
                } else null
                repository.insertEvent(ActivityEventEntity(
                    timestamp = now,
                    packageName = pkg,
                    appName = appName(pkg),
                    eventType = event.eventType,
                    eventDescription = EventTranslator.description(event.eventType),
                    text = text,
                    contentDescription = contentDescription,
                    viewId = viewId,
                    className = className?.take(250),
                    activityName = activity?.take(250),
                    sessionId = currentSessionId,
                    sourceInfo = sourceInfo
                ))
            } catch (_: Exception) {
                // Partial accessibility events and source failures are intentionally ignored.
            } finally { source?.recycle() }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::repository.isInitialized) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { repository.closeOpenSessions(System.currentTimeMillis()) } }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun ensureSession(pkg: String, now: Long) {
        if (currentPackage == pkg && currentSessionId != null) return
        if (currentSessionId != null) repository.closeOpenSessions(now)
        currentSessionId = MonitorDatabase.get(applicationContext).sessionDao().insert(ApplicationSessionEntity(packageName = pkg, appName = appName(pkg), startTime = now))
        currentPackage = pkg
    }

    private fun appName(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString().ifBlank { pkg }
    }.getOrElse { pkg }

    private fun sourceSummary(node: AccessibilityNodeInfo): String? = runCatching {
        val rect = Rect(); node.getBoundsInScreen(rect)
        "סוג=${node.className ?: "לא זמין"}; גבולות=${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }.getOrNull()
}
