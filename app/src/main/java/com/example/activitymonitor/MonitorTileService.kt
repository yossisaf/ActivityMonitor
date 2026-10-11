package com.example.activitymonitor

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.activitymonitor.monitoring.ScreenCaptureService

class MonitorTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val currentlyEnabled = settings.getBoolean("monitoring_enabled", true)
        val nextEnabled = !currentlyEnabled
        settings.edit().putBoolean("monitoring_enabled", nextEnabled).apply()

        if (!nextEnabled) {
            getSharedPreferences("screen_recording", MODE_PRIVATE)
                .edit().putBoolean("continuous_recording", false).apply()
            ScreenCaptureService.stop(this)
            syncTile(false)
            return
        }

        syncTile(true)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_FROM_TILE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivityAndCollapse(intent)
    }

    private fun syncTile(enabled: Boolean = getSharedPreferences("settings", MODE_PRIVATE)
        .getBoolean("monitoring_enabled", true)) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.monitor_tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (enabled) "פעיל" else "כבוי"
        }
        tile.updateTile()
    }

    companion object {
        fun refreshTile(context: android.content.Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    requestListeningState(context, ComponentName(context, MonitorTileService::class.java))
                }
            }
        }
    }
}
