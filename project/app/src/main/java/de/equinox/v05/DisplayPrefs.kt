package de.equinox.v05

import android.content.Context
import android.graphics.Color

data class DisplayPrefs(
    val backgroundColor: Int,
    val audioEdgeColor: Int,
    val macroLevel1Color: Int,
    val macroLevel2Color: Int,
    val macroLevel3Color: Int,
    val macroLevel4Color: Int,
    val glowEnabled: Boolean,
    val glowStrength: Int
)

class DisplayPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("equinox_display_prefs", Context.MODE_PRIVATE)

    fun load(): DisplayPrefs = DisplayPrefs(
        backgroundColor = prefs.getInt("backgroundColor", Color.rgb(8, 10, 14)),
        audioEdgeColor = prefs.getInt("audioEdgeColor", Color.rgb(70, 220, 205)),
        macroLevel1Color = prefs.getInt("macro1", Color.rgb(245, 155, 45)),
        macroLevel2Color = prefs.getInt("macro2", Color.rgb(235, 75, 155)),
        macroLevel3Color = prefs.getInt("macro3", Color.rgb(105, 95, 245)),
        macroLevel4Color = prefs.getInt("macro4", Color.rgb(210, 85, 245)),
        glowEnabled = prefs.getBoolean("glowEnabled", true),
        glowStrength = prefs.getInt("glowStrength", 80)
    )

    fun save(value: DisplayPrefs) {
        prefs.edit()
            .putInt("backgroundColor", value.backgroundColor)
            .putInt("audioEdgeColor", value.audioEdgeColor)
            .putInt("macro1", value.macroLevel1Color)
            .putInt("macro2", value.macroLevel2Color)
            .putInt("macro3", value.macroLevel3Color)
            .putInt("macro4", value.macroLevel4Color)
            .putBoolean("glowEnabled", value.glowEnabled)
            .putInt("glowStrength", value.glowStrength)
            .apply()
    }

    fun parseHexOrNull(text: String): Int? {
        val raw = text.trim().removePrefix("#")
        if (raw.length != 6) return null
        return try {
            Color.rgb(
                raw.substring(0, 2).toInt(16),
                raw.substring(2, 4).toInt(16),
                raw.substring(4, 6).toInt(16)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun toHex(color: Int): String = String.format(
        "#%02X%02X%02X",
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
