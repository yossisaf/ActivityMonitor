package com.example.activitymonitor

import android.view.accessibility.AccessibilityEvent
import com.example.activitymonitor.ui.EventTranslator
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTranslatorTest {
    @Test fun click_is_translated_to_hebrew() = assertEquals("לחיצה על רכיב", EventTranslator.description(AccessibilityEvent.TYPE_VIEW_CLICKED))
    @Test fun text_change_is_translated_to_hebrew() = assertEquals("שינוי טקסט", EventTranslator.description(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
    @Test fun technical_name_is_stable() = assertEquals("TYPE_VIEW_SCROLLED", EventTranslator.technicalName(AccessibilityEvent.TYPE_VIEW_SCROLLED))
}
