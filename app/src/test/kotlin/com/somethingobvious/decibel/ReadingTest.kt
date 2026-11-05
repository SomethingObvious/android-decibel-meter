package com.somethingobvious.decibel

import com.somethingobvious.decibel.ui.Palette
import com.somethingobvious.decibel.ui.zone
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTest {
    @Test
    fun `a level that rounds up to 85 is named and coloured for 85`() {
        val up = checkNotNull(Reading.level(dbfs = -15.04f, offset = 100.0))
        assertEquals(Reading.format(85.0), Reading.format(up))
        assertEquals(R.string.band_harmful_hours, Reading.band(up))
        assertEquals(Palette.day.harm, Palette.day.zone(up))
        val down = checkNotNull(Reading.level(dbfs = -15.06f, offset = 100.0))
        assertEquals(R.string.band_loud, Reading.band(down))
        assertEquals(Palette.day.caution, Palette.day.zone(down))
    }
}
