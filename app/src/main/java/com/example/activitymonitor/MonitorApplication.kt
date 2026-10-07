package com.example.activitymonitor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.example.activitymonitor.db.BatterySampleEntity
import com.example.activitymonitor.repository.MonitorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MonitorApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: MonitorRepository
        private set

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val pending = goAsync()
            appScope.launch {
                try {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    if (level >= 0) {
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                        val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                        val now = System.currentTimeMillis()
                        val lastAt = prefs.getLong("last_battery_sample", 0L)
                        val lastLevel = prefs.getInt("last_battery_level", -1)
                        val lastStatus = prefs.getInt("last_battery_status", Int.MIN_VALUE)
                        val significantChange = kotlin.math.abs(percent - lastLevel) >= 2 || status != lastStatus
                        if (now - lastAt >= 15L * 60L * 1000L || significantChange) {
                            repository.insertBattery(BatterySampleEntity(
                                timestamp = now,
                                batteryLevel = percent,
                                charging = charging,
                                temperature = if (tempRaw == Int.MIN_VALUE) null else tempRaw / 10f,
                                status = status
                            ))
                            prefs.edit()
                                .putLong("last_battery_sample", now)
                                .putInt("last_battery_level", percent)
                                .putInt("last_battery_status", status)
                                .apply()
                        }
                    }
                } finally { pending.finish() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = MonitorRepository(this)
        val retention = getSharedPreferences("settings", MODE_PRIVATE).getInt("retention_days", 30)
        appScope.launch { runCatching { repository.cleanup(retention) } }
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onTerminate() {
        runCatching { unregisterReceiver(batteryReceiver) }
        appScope.cancel()
        super.onTerminate()
    }
}
