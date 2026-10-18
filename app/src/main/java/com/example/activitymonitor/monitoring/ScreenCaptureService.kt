package com.example.activitymonitor.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.activitymonitor.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var activePackage: String? = null
    private var recordingPackage: String? = null
    private var recordingFile: File? = null
    private var running = false
    private var continuousMode = false
    private var recordingError: String? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseProjection()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
            val resultData = extractIntent(intent, EXTRA_RESULT_DATA)
            if (resultCode == -1 && resultData != null) {
                startProjection(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        runCatching {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjection unavailable")
            projection?.registerCallback(projectionCallback, null)
            running = true
            continuousMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_CONTINUOUS_MODE, false)

            if (continuousMode) {
                startRecording(CONTINUOUS_PACKAGE, allowUnselected = true)
            } else {
                activePackage?.takeIf { selectedPackages().contains(it) }?.let { startRecording(it) }
            }
            refreshNotification()
        }.onFailure {
            releaseProjection()
            stopSelf()
        }
    }

    // Called only on foreground window transitions. A continuous session ignores
    // app transitions; normal mode creates one file per selected-app visit.
    fun setForegroundPackage(packageName: String?) {
        if (packageName == activePackage) return
        activePackage = packageName
        if (!running) return

        if (continuousMode) return
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("monitoring_enabled", true)) {
            stopRecording()
            return
        }

        if (packageName != null && selectedPackages().contains(packageName)) {
            startRecording(packageName)
        } else {
            stopRecording()
        }
        refreshNotification()
    }

    fun setContinuousMode(enabled: Boolean) {
        if (continuousMode == enabled) return
        continuousMode = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_CONTINUOUS_MODE, enabled).apply()
        if (!running) return

        stopRecording()
        if (enabled) {
            startRecording(CONTINUOUS_PACKAGE, allowUnselected = true)
        } else if (
            getSharedPreferences("settings", MODE_PRIVATE).getBoolean("monitoring_enabled", true)
        ) {
            activePackage?.takeIf { selectedPackages().contains(it) }?.let { startRecording(it) }
        }
        refreshNotification()
    }

    private fun startRecording(packageName: String, allowUnselected: Boolean = false) {
        if (!running || recorder != null) return
        if (!allowUnselected && !selectedPackages().contains(packageName)) return
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("monitoring_enabled", true)) return

        var candidateRecorder: MediaRecorder? = null
        var candidateDisplay: android.hardware.display.VirtualDisplay? = null
        var outputFile: File? = null
        try {
            recordingError = null
            val metrics = resources.displayMetrics
            val screenW = metrics.widthPixels.coerceAtLeast(2)
            val screenH = metrics.heightPixels.coerceAtLeast(2)
            // Keep a practical resolution while preserving the screen aspect ratio.
            val scale = minOf(1f, 720f / maxOf(screenW, screenH).toFloat())
            val width = (((screenW * scale).toInt().coerceAtLeast(2) / 2) * 2).coerceAtLeast(2)
            val height = (((screenH * scale).toInt().coerceAtLeast(2) / 2) * 2).coerceAtLeast(2)
            val density = (metrics.densityDpi * scale).toInt().coerceAtLeast(1)

            val root = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "ActivityMonitor"
            )
            if (!root.exists() && !root.mkdirs()) error("לא ניתן ליצור תיקיית סרטונים")

            val safePackage = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(96)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            var file = File(root, "${stamp}__${safePackage}.mp4")
            var suffix = 1
            while (file.exists()) {
                file = File(root, "${stamp}_${suffix++}__${safePackage}.mp4")
            }
            outputFile = file

            val mr = MediaRecorder()
            candidateRecorder = mr
            @Suppress("DEPRECATION")
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(width, height)
            mr.setVideoFrameRate(15)
            mr.setVideoEncodingBitRate(700_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()

            // Start the encoder before connecting the virtual display so the
            // first captured frames are written to an active encoder surface.
            mr.start()

            val display = projection?.createVirtualDisplay(
                "ActivityMonitorCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mr.surface,
                null,
                null
            ) ?: error("Android לא אפשרה ליצור תצוגת הקלטה")
            candidateDisplay = display

            virtualDisplay = display
            recorder = mr
            recordingPackage = packageName
            recordingFile = file
            candidateDisplay = null
            candidateRecorder = null
            refreshNotification()
        } catch (failure: Exception) {
            runCatching { candidateDisplay?.release() }
            runCatching { candidateRecorder?.reset() }
            runCatching { candidateRecorder?.release() }
            runCatching { outputFile?.delete() }
            recordingPackage = null
            recordingFile = null
            recordingError = failure.message?.take(90)
                ?.takeIf { it.isNotBlank() }
                ?: "בדוק הרשאות ותאימות של המכשיר"
            refreshNotification()
        }
    }



    private fun stopRecording() {
        val mr = recorder
        recorder = null
        val file = recordingFile
        recordingFile = null
        recordingPackage = null

        // Finalize the MP4 while the virtual display is still connected. Releasing
        // the display first can starve MediaRecorder and leave a corrupt/black file.
        var stopFailed = false
        if (mr != null) {
            try {
                mr.stop()
            } catch (_: Exception) {
                stopFailed = true
            } finally {
                runCatching { mr.reset() }
                runCatching { mr.release() }
            }
        }

        val display = virtualDisplay
        virtualDisplay = null
        runCatching { display?.release() }

        val usableFile = file != null && file.exists() && file.length() >= 8_192L
        if (file != null && (stopFailed || !usableFile)) {
            runCatching { file.delete() }
            recordingError = "הסרטון לא נשמר כראוי. נסה להקליט שוב והשאר את ההסרטה פעילה עד לסיום."
        } else {
            recordingError = null
        }
        if (running) refreshNotification()
    }

    private fun selectedPackages(): Set<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()

    private fun releaseProjection() {
        running = false
        stopRecording()
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        releaseProjection()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val text = when {
            !recordingError.isNullOrBlank() -> "שגיאת הסרטה: ${recordingError}"
            continuousMode -> "הסרטה כללית פעילה עד עצירה ידנית"
            recordingPackage != null -> "מקליט אפליקציה שסומנה"
            else -> "ממתין לפתיחת אפליקציה שסומנה"
        }

        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("תיעוד מסך פעיל")
                .setContentText(text)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("תיעוד מסך פעיל")
                .setContentText(text)
                .setOngoing(true)
                .build()
        }
    }

    private fun refreshNotification() {
        if (!running) return
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "תיעוד מסך", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun extractIntent(source: Intent?, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            source?.getParcelableExtra(key, Intent::class.java)
        } else {
            source?.getParcelableExtra(key)
        }

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 2401
        private const val PREFS = "screen_recording"
        private const val KEY_CONTINUOUS_MODE = "continuous_recording"
        const val KEY_PACKAGES = "packages"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CONTINUOUS_PACKAGE = "continuous.session"

        @Volatile private var instance: ScreenCaptureService? = null

        fun setActivePackage(packageName: String?) {
            instance?.setForegroundPackage(packageName)
        }

        fun setContinuousMode(enabled: Boolean) {
            instance?.setContinuousMode(enabled)
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        fun isRunning(): Boolean = instance?.running == true
        fun currentRecordingPackage(): String? = instance?.recordingPackage
        fun currentRecordingPath(): String? = instance?.recordingFile?.absolutePath
        fun isContinuousMode(): Boolean = instance?.continuousMode == true
    }
}
