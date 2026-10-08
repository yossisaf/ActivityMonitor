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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording()
            releaseProjection()
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

            val pkg = activePackage
            if (pkg != null && selectedPackages().contains(pkg)) startRecording(pkg)
        }.onFailure {
            releaseProjection()
            stopSelf()
        }
    }

    // Called for foreground window transitions only. Clicks, typing, scrolling
    // and other accessibility actions do not split a recording.
    fun setForegroundPackage(packageName: String?) {
        if (packageName == activePackage) return
        activePackage = packageName
        if (!running) return

        if (packageName != null && selectedPackages().contains(packageName)) {
            startRecording(packageName)
        } else {
            stopRecording()
        }
    }

    private fun startRecording(packageName: String) {
        if (!running || recorder != null || !selectedPackages().contains(packageName)) return

        runCatching {
            val metrics = resources.displayMetrics
            val screenW = metrics.widthPixels.coerceAtLeast(320)
            val screenH = metrics.heightPixels.coerceAtLeast(320)
            val scale = minOf(1f, 480f / maxOf(screenW, screenH).toFloat())
            val width = ((screenW * scale).toInt().coerceAtLeast(320) / 2) * 2
            val height = ((screenH * scale).toInt().coerceAtLeast(320) / 2) * 2
            val density = (metrics.densityDpi * scale).toInt().coerceIn(120, 320)

            val root = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "ActivityMonitor"
            ).apply { mkdirs() }

            val safePackage = packageName
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                .take(96)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // One MP4 is created for one continuous visit to this app.
            val file = File(root, stamp + "__" + safePackage + ".mp4")

            val mr = MediaRecorder()
            @Suppress("DEPRECATION")
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(width, height)
            mr.setVideoFrameRate(1)
            mr.setVideoEncodingBitRate(220_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()

            val display = projection?.createVirtualDisplay(
                "ActivityMonitorCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mr.surface,
                null,
                null
            ) ?: error("Virtual display unavailable")

            mr.start()
            virtualDisplay = display
            recorder = mr
            recordingPackage = packageName
            recordingFile = file
        }.onFailure {
            stopRecording()
        }
    }

    // This is the normal end of the app-visit video. It is not called for
    // individual accessibility actions inside the same package.
    private fun stopRecording() {
        val display = virtualDisplay
        virtualDisplay = null
        runCatching { display?.release() }

        val mr = recorder
        recorder = null
        val file = recordingFile
        recordingFile = null
        recordingPackage = null

        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.reset() }
            runCatching { mr.release() }
        }

        // Avoid leaving empty/corrupt files after an extremely short visit or
        // an Android interruption of MediaRecorder.
        if (file != null && (!file.exists() || file.length() < 8_192L)) {
            runCatching { file.delete() }
        }
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

    private fun buildNotification(): Notification =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("תיעוד מסך פעיל")
                .setContentText("סרטון רציף אחד לכל כניסה לאפליקציה שסומנה")
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("תיעוד מסך פעיל")
                .setContentText("סרטון רציף אחד לכל כניסה לאפליקציה שסומנה")
                .setOngoing(true)
                .build()
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
        const val KEY_PACKAGES = "packages"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile private var instance: ScreenCaptureService? = null

        fun setActivePackage(packageName: String?) {
            instance?.setForegroundPackage(packageName)
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
    }
}
