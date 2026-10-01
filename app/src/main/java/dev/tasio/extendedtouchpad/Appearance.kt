package dev.tasio.extendedtouchpad

import android.content.SharedPreferences
import android.graphics.Color

/** Apariencia configurable del panel, el teclado y el cursor. Se lee de las preferencias al construir las vistas. */
class Appearance(private val prefs: SharedPreferences) {
    val dark: Boolean get() = prefs.getBoolean(KEY_DARK, false)
    val accent: Int get() = prefs.getInt(KEY_ACCENT, ACCENTS.first())
    val cursorDp: Int get() = prefs.getInt(KEY_CURSOR_DP, DEFAULT_CURSOR_DP)
    val cursorShape: CursorShape get() = CursorShape.fromName(prefs.getString(KEY_CURSOR_SHAPE, null))
    val cursorColor: Int get() = prefs.getInt(KEY_CURSOR_COLOR, CURSOR_COLORS.first())

    /** Opacidad en porcentaje (20–100). */
    var padOpacity: Int
        get() = prefs.getInt(KEY_PAD_OPACITY, 100).coerceIn(MIN_OPACITY, 100)
        set(value) = prefs.edit().putInt(KEY_PAD_OPACITY, value.coerceIn(MIN_OPACITY, 100)).apply()

    var keyboardOpacity: Int
        get() = prefs.getInt(KEY_KB_OPACITY, 100).coerceIn(MIN_OPACITY, 100)
        set(value) = prefs.edit().putInt(KEY_KB_OPACITY, value.coerceIn(MIN_OPACITY, 100)).apply()

    val cardColor: Int get() = if (dark) Color.rgb(43, 43, 46) else Color.rgb(214, 214, 214)
    val barColor: Int get() = if (dark) Color.rgb(30, 30, 32) else Color.rgb(176, 176, 176)
    val padColor: Int get() = if (dark) Color.rgb(56, 56, 60) else Color.rgb(224, 224, 224)
    val keyColor: Int get() = if (dark) Color.rgb(74, 74, 79) else Color.rgb(250, 250, 250)
    val specialKeyColor: Int get() = if (dark) Color.rgb(52, 52, 56) else Color.rgb(189, 189, 189)
    val textColor: Int get() = if (dark) Color.argb(230, 255, 255, 255) else Color.argb(200, 0, 0, 0)
    val keyTextColor: Int get() = if (dark) Color.WHITE else Color.BLACK
    val subtleColor: Int get() = if (dark) Color.argb(150, 255, 255, 255) else Color.argb(150, 0, 0, 0)
    val hintColor: Int get() = if (dark) Color.argb(80, 255, 255, 255) else Color.argb(70, 0, 0, 0)

    /** Color de una tecla pulsada o activa: el acento mezclado con el color base. */
    fun pressed(base: Int): Int = blend(accent, base, 0.6f)

    /** Contorno del cursor: negro sobre rellenos claros, blanco sobre oscuros. */
    val cursorOutline: Int
        get() {
            val c = cursorColor
            val luminance = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            return if (luminance > 140f) Color.BLACK else Color.WHITE
        }

    fun resetAll() {
        prefs.edit()
            .remove(KEY_DARK).remove(KEY_ACCENT).remove(KEY_CURSOR_DP).remove(KEY_CURSOR_COLOR).remove(KEY_CURSOR_SHAPE)
            .remove(KEY_PAD_OPACITY).remove(KEY_KB_OPACITY)
            .apply()
    }

    fun setDark(value: Boolean) = prefs.edit().putBoolean(KEY_DARK, value).apply()
    fun setAccent(value: Int) = prefs.edit().putInt(KEY_ACCENT, value).apply()
    fun setCursorDp(value: Int) = prefs.edit().putInt(KEY_CURSOR_DP, value.coerceIn(MIN_CURSOR_DP, MAX_CURSOR_DP)).apply()
    fun setCursorShape(value: CursorShape) = prefs.edit().putString(KEY_CURSOR_SHAPE, value.name).apply()
    fun setCursorColor(value: Int) = prefs.edit().putInt(KEY_CURSOR_COLOR, value).apply()

    companion object {
        const val KEY_DARK = "ap_dark"
        const val KEY_ACCENT = "ap_accent"
        const val KEY_CURSOR_DP = "ap_cursor_dp"
        const val KEY_CURSOR_COLOR = "ap_cursor_color"
        const val KEY_CURSOR_SHAPE = "ap_cursor_shape"
        const val KEY_PAD_OPACITY = "ap_pad_opacity"
        const val KEY_KB_OPACITY = "ap_kb_opacity"

        const val MIN_OPACITY = 20
        const val MIN_CURSOR_DP = 16
        const val MAX_CURSOR_DP = 64
        const val DEFAULT_CURSOR_DP = 28

        /** Niveles del botón de transparencia rápida: cada toque baja al siguiente y, tras el último, vuelve a 100. */
        val OPACITY_LEVELS = listOf(100, 75, 50, 30)

        val ACCENTS = listOf(
            Color.rgb(30, 136, 229), // azul
            Color.rgb(67, 160, 71), // verde
            Color.rgb(251, 140, 0), // naranja
            Color.rgb(142, 36, 170), // violeta
            Color.rgb(229, 57, 53), // rojo
        )

        val CURSOR_COLORS = listOf(
            Color.WHITE,
            Color.BLACK,
            Color.rgb(255, 235, 59), // amarillo
            Color.rgb(239, 83, 80), // rojo
            Color.rgb(102, 187, 106), // verde
            Color.rgb(18, 35, 58), // azul marino
        )

        /** Siguiente nivel de opacidad más bajo que [current]; si no hay, vuelve a 100. */
        fun nextOpacity(current: Int): Int = OPACITY_LEVELS.firstOrNull { it < current - 2 } ?: 100

        private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
            (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
            (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt(),
        )
    }
}
