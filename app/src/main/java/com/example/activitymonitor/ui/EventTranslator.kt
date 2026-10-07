package com.example.activitymonitor.ui

import android.view.accessibility.AccessibilityEvent

object EventTranslator {
    fun description(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "לחיצה על רכיב"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "בחירת רכיב"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "שינוי טקסט"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "מעבר למסך"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "שינוי תוכן המסך"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "גלילה"
        else -> "פעולה בממשק"
    }
    fun technicalName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
        else -> "ACCESSIBILITY_EVENT_$type"
    }
}
