package com.pinepi.thinksmart.launcher

/** The appliance has no reliable Android 8 system-wide night-mode switch. */
enum class ThemeMode {
    AUTO, LIGHT, DARK;
    fun isDark(hour: Int): Boolean {
        require(hour in 0..23)
        return when (this) {
            AUTO -> hour < 7 || hour >= 19
            LIGHT -> false
            DARK -> true
        }
    }
    companion object {
        fun fromSaved(value: String?) = entries.find { it.name == value } ?: AUTO
    }
}
