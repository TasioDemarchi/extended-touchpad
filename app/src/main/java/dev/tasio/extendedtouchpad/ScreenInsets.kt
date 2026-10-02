package dev.tasio.extendedtouchpad

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max

/** Zona superior de la pantalla que ocupan la barra de estado y el recorte de la cámara: los paneles no deben meterse ahí. */
object ScreenInsets {
    fun top(context: Context, wm: WindowManager): Int {
        // Altura de la barra de estado según el sistema; es fiable aunque la ventana no sea de una actividad.
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) res.getDimensionPixelSize(id) else 0
        val fromInsets = try {
            wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        } catch (_: Throwable) {
            0
        }
        return max(statusBar, fromInsets)
    }
}
