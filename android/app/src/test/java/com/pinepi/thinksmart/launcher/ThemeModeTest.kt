package com.pinepi.thinksmart.launcher

import org.junit.Assert.*
import org.junit.Test

class ThemeModeTest {
    @Test fun automaticSwitchesAtSevenAndNineteen() {
        for (hour in listOf(0, 6, 19, 23)) assertTrue("hour=$hour", ThemeMode.AUTO.isDark(hour))
        for (hour in listOf(7, 12, 18)) assertFalse("hour=$hour", ThemeMode.AUTO.isDark(hour))
    }
    @Test fun manualModesOverrideScheduleAcrossTheWholeDay() {
        for (hour in 0..23) {
            assertFalse(ThemeMode.LIGHT.isDark(hour))
            assertTrue(ThemeMode.DARK.isDark(hour))
        }
    }
    @Test fun MissingOrUnknownPreferenceUsesAutomatic() {
        assertEquals(ThemeMode.AUTO, ThemeMode.fromSaved(null))
        assertEquals(ThemeMode.AUTO, ThemeMode.fromSaved("obsolete"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromSaved("DARK"))
    }
}
