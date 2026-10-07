package com.example.activitymonitor.repository

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache

class AppIconCache(private val context: Context) {
    private val cache = object : LruCache<String, Drawable>(80) {}
    fun get(packageName: String): Drawable? {
        cache.get(packageName)?.let { return it }
        return runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()?.also { cache.put(packageName, it) }
    }
}
