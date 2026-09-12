package com.wactab.app

import android.content.Context
import androidx.compose.ui.graphics.Color

enum class ThemeMode { DARK, LIGHT }

/** Fixed preset swatches, plus a hex field for anything else. */
object Palettes {
    val accents = listOf(
        Color(0xFF80CBC4), // teal (old default)
        Color(0xFF64B5F6), // blue
        Color(0xFF7986CB), // indigo
        Color(0xFFBA68C8), // purple
        Color(0xFFF06292), // pink
        Color(0xFFE57373), // red
        Color(0xFFFFB74D), // orange
        Color(0xFF81C784), // green
    )

    val canvasBackgrounds = listOf(
        "000000", // pure black (default — best for OLED)
        "1C1C1E", // dark grey
        "0D1B2A", // navy
        "2B2B2B", // charcoal
        "122016", // dark green
        "1E1425", // dark purple
        "241016", // maroon
        "1A1F2E", // slate blue
    )
}

/** Accepts "RRGGBB" or "#RRGGBB". Returns null for anything else instead of throwing. */
fun parseHexColor(input: String): Color? {
    val hex = input.removePrefix("#")
    if (hex.length != 6 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
    return try {
        Color(0xFF000000L.toInt() or hex.toLong(16).toInt())
    } catch (e: NumberFormatException) {
        null
    }
}

fun Color.toHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return "%02X%02X%02X".format(r, g, b)
}

data class WactabSettings(
    val penOnlyMode: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val oledMode: Boolean = true,
    val useDynamicColor: Boolean = false,
    val accentIndex: Int = 0,
    val canvasColorHex: String = Palettes.canvasBackgrounds[0],
    val showUndoRedo: Boolean = false,
) {
    val accentColor: Color get() = Palettes.accents[accentIndex.coerceIn(0, Palettes.accents.lastIndex)]
    val canvasColor: Color get() = parseHexColor(canvasColorHex) ?: Color(0xFF000000)
}

private const val PREFS_NAME = "wactab_settings"

object SettingsStore {
    fun load(context: Context): WactabSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = WactabSettings()
        return WactabSettings(
            penOnlyMode = prefs.getBoolean("pen_only_mode", default.penOnlyMode),
            themeMode = if (prefs.getString("theme_mode", null) == "LIGHT") ThemeMode.LIGHT else ThemeMode.DARK,
            oledMode = prefs.getBoolean("oled_mode", default.oledMode),
            useDynamicColor = prefs.getBoolean("use_dynamic_color", default.useDynamicColor),
            accentIndex = prefs.getInt("accent_index", default.accentIndex),
            canvasColorHex = prefs.getString("canvas_color_hex", default.canvasColorHex) ?: default.canvasColorHex,
            showUndoRedo = prefs.getBoolean("show_undo_redo", default.showUndoRedo),
        )
    }

    fun save(context: Context, settings: WactabSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("pen_only_mode", settings.penOnlyMode)
            .putString("theme_mode", settings.themeMode.name)
            .putBoolean("oled_mode", settings.oledMode)
            .putBoolean("use_dynamic_color", settings.useDynamicColor)
            .putInt("accent_index", settings.accentIndex)
            .putString("canvas_color_hex", settings.canvasColorHex)
            .putBoolean("show_undo_redo", settings.showUndoRedo)
            .apply()
    }
}
