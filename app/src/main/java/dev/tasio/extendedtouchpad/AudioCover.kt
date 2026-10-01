package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Pantalla oscura con una leyenda («Cambiando de dispositivo de audio…») a pantalla completa mientras el selector de
 * audio del sistema se abre, se toca y se cierra por debajo, para que el diálogo no se vea. No recibe toques y se retira
 * sola si algo se queda colgado.
 */
class AudioCover(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private var view: FrameLayout? = null
    private val wm = service.getSystemService(WindowManager::class.java)
    private val failsafe = Runnable { hide() }

    /** Muestra la pantalla oscura y, cuando ya está dibujada, invoca [onReady]. */
    fun show(onReady: () -> Unit) {
        if (view != null) {
            onReady()
            return
        }
        try {
            val density = service.resources.displayMetrics.density
            val label = TextView(service).apply {
                text = "Cambiando de dispositivo de audio…"
                textSize = 20f
                setTextColor(Palette.TEXT2)
                gravity = Gravity.CENTER
            }
            val cover = FrameLayout(service).apply {
                setBackgroundColor(Palette.BG)
                setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
                addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE,
            )
            wm.addView(cover, params)
            view = cover
            handler.postDelayed(failsafe, FAILSAFE_MS)
            // Un instante para que la capa esté dibujada antes de abrir el selector por debajo.
            handler.postDelayed(onReady, DRAW_MS)
        } catch (t: Throwable) {
            ProbeLog.add("Audio: no se pudo mostrar la pantalla de cambio (${t.javaClass.simpleName}); se verá el selector")
            onReady()
        }
    }

    fun hide() {
        handler.removeCallbacks(failsafe)
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val FAILSAFE_MS = 6000L
        const val DRAW_MS = 60L
    }
}
