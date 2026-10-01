package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Pantalla oscura a pantalla completa mientras el selector de audio del sistema se abre, se toca y se cierra por debajo
 * (así el diálogo no se ve). Primero queda oscura y vacía y, al terminar, muestra el icono y el nombre
 * del dispositivo nuevo durante un rato para que se pueda leer. No recibe toques y se retira sola si algo se cuelga.
 */
class AudioCover(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private var view: FrameLayout? = null
    private var icon: ImageView? = null
    private var title: TextView? = null
    private var caption: TextView? = null
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val failsafe = Runnable { hide() }

    private fun dp(v: Int) = (v * density).toInt()

    /** Muestra la pantalla oscura y, cuando ya está dibujada, invoca [onReady]. */
    fun show(onReady: () -> Unit) {
        if (view != null) {
            onReady()
            return
        }
        try {
            val image = ImageView(service).apply {
                setColorFilter(Palette.VIOLET)
                visibility = View.GONE
            }
            // Mientras trabaja el selector por debajo la pantalla queda oscura y vacía; el nombre llega con el resultado.
            val name = TextView(service).apply {
                textSize = 22f
                setTextColor(Palette.TEXT2)
                gravity = Gravity.CENTER
            }
            val small = TextView(service).apply {
                textSize = 14f
                setTextColor(Palette.TEXT3)
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            val column = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(image, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(18) })
                addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(small, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            val cover = FrameLayout(service).apply {
                setBackgroundColor(Palette.BG)
                setPadding(dp(24), 0, dp(24), 0)
                addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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
            icon = image
            title = name
            caption = small
            handler.postDelayed(failsafe, FAILSAFE_MS)
            // Un instante para que la capa esté dibujada antes de abrir el selector por debajo.
            handler.postDelayed(onReady, DRAW_MS)
        } catch (t: Throwable) {
            ProbeLog.add("Audio: no se pudo mostrar la pantalla de cambio (${t.javaClass.simpleName}); se verá el selector")
            onReady()
        }
    }

    /**
     * Muestra el dispositivo nuevo (icono y nombre) y, pasado [HOLD_MS], retira la pantalla e invoca [onDone].
     * Si la pantalla no se pudo mostrar, avisa con un mensaje corto.
     */
    fun showResult(name: String, iconRes: Int, onDone: () -> Unit) {
        val cover = view
        if (cover == null) {
            Toast.makeText(service, "Audio: $name", Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        icon?.apply {
            setImageResource(iconRes)
            // El color de acento elegido en los ajustes.
            setColorFilter(Appearance(service.getSharedPreferences("touchpad", android.content.Context.MODE_PRIVATE)).accent)
            visibility = View.VISIBLE
        }
        title?.apply {
            text = name
            textSize = 30f
            setTextColor(Palette.TEXT)
        }
        caption?.apply {
            text = "Salida de audio"
            visibility = View.VISIBLE
        }
        handler.removeCallbacks(failsafe)
        handler.postDelayed(failsafe, HOLD_MS + FAILSAFE_MS)
        handler.postDelayed({
            hide()
            onDone()
        }, HOLD_MS)
    }

    fun hide() {
        handler.removeCallbacks(failsafe)
        val v = view ?: return
        view = null
        icon = null
        title = null
        caption = null
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val FAILSAFE_MS = 6000L
        const val DRAW_MS = 60L

        /** Cuánto se queda visible el nombre del dispositivo nuevo, para poder leerlo. */
        const val HOLD_MS = 800L
    }
}
