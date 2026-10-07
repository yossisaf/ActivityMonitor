package com.example.activitymonitor.ui

import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {
    private val locale = Locale("he", "IL")
    fun dateTime(timestamp: Long): String = DateFormat.format("dd/MM/yyyy • HH:mm:ss", Date(timestamp)).toString()
    fun time(timestamp: Long): String = DateFormat.format("HH:mm:ss", Date(timestamp)).toString()
    fun shortDate(timestamp: Long): String = DateFormat.format("dd/MM/yyyy", Date(timestamp)).toString()
    fun duration(ms: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return when { h > 0 -> String.format(locale, "%d שעות ו־%d דקות", h, m); m > 0 -> String.format(locale, "%d דקות ו־%d שניות", m, s); else -> String.format(locale, "%d שניות", s) }
    }
    fun dayStart(offsetDays: Int = 0): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
