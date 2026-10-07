package com.example.activitymonitor.repository

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

class UsageStatsRepository(private val context: Context) {
    private val manager by lazy { context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager }

    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun queryAggregated(start: Long, end: Long): Map<String, Long> {
        if (!hasUsageAccess()) return emptyMap()
        return manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .filter { it.totalTimeInForeground > 0L }
            .associate { it.packageName to it.totalTimeInForeground }
    }

    fun dailyTotals(start: Long, end: Long): List<Pair<Long, Long>> {
        if (!hasUsageAccess()) return emptyList()
        val oneDay = 24L * 60L * 60L * 1000L
        val result = mutableListOf<Pair<Long, Long>>()
        var day = start
        while (day < end) {
            val next = minOf(day + oneDay, end)
            result += day to queryAggregated(day, next).values.sum()
            day = next
        }
        return result
    }
}
