package dev.tasio.extendedtouchpad

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

object Displays {
    fun manager(context: Context): DisplayManager =
        context.getSystemService(DisplayManager::class.java)

    /** Primer display distinto del principal, o null si no hay ninguno conectado. */
    fun external(context: Context): Display? =
        manager(context).displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    fun size(display: Display): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    fun describe(display: Display): String {
        val (w, h) = size(display)
        val flags = buildList {
            if (display.flags and Display.FLAG_PRESENTATION != 0) add("PRESENTATION")
            if (display.flags and Display.FLAG_PRIVATE != 0) add("PRIVATE")
            if (display.flags and Display.FLAG_SECURE != 0) add("SECURE")
            if (display.flags and Display.FLAG_ROUND != 0) add("ROUND")
        }.joinToString("|").ifEmpty { "-" }
        return "id=${display.displayId} \"${display.name}\"\n" +
            "  ${w}x$h px  ${"%.0f".format(display.refreshRate)} Hz  flags=$flags  state=${display.state}"
    }
}
