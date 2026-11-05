package com.somethingobvious.decibel

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertWatchTest {
    @Test
    fun `an alert fires once per crossing and rearms 3 dB under`() {
        val watch = AlertWatch()
        val levels = listOf(80.0, 86.0, 90.0, 84.0, 83.0, 86.0, 81.0, 85.0)
        val fired = levels.map { watch.crosses(level = it, alert = 85.0) }
        assertEquals(listOf(false, true, false, false, false, false, false, true), fired)
    }

    @Test
    fun `no alert level never fires`() {
        val watch = AlertWatch()
        assertEquals(false, watch.crosses(level = 140.0, alert = null))
    }
}
