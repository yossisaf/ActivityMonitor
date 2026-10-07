package com.example.activitymonitor

import com.example.activitymonitor.ui.Formatters
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {
    @Test fun duration_is_human_readable() {
        assertTrue(Formatters.duration(3_720_000).contains("שעות"))
    }
}
