package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * Capa en la que se dibujan el panel del touchpad y el teclado.
 *
 * - Con el permiso «Mostrar sobre otras apps» y el ajuste activado se usa TYPE_APPLICATION_OVERLAY, que Android
 *   coloca POR DEBAJO de las notificaciones y del centro de control (y se oculta con la pantalla de bloqueo).
 * - Si no, TYPE_ACCESSIBILITY_OVERLAY, que se dibuja por encima de todo, incluido el centro de control.
 *
 * El cursor del display externo no pasa por aquí: el centro de control es de la tablet y no lo tapa.
 */
object OverlayLayer {
    const val KEY_PREFERRED = "panels_below_shade"
    private const val PREFS = "touchpad"

    /** El usuario quiere los paneles por debajo del centro de control (por defecto, sí; requiere el permiso). */
    fun isPreferred(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREFERRED, true)

    fun setPreferred(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PREFERRED, value).apply()
    }

    fun hasPermission(context: Context) = Settings.canDrawOverlays(context)

    fun panelType(context: Context): Int =
        if (isPreferred(context) && hasPermission(context)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

    /**
     * WindowManager adecuado para añadir ventanas de ese tipo. Para la capa «sobre otras apps» hace falta un contexto de
     * ventana, y estos solo se crean a partir de uno asociado a una pantalla (el servicio por sí solo no lo está).
     * Si algo falla se devuelve el de accesibilidad, para no tumbar el servicio.
     */
    fun windowManager(service: AccessibilityService, type: Int): WindowManager {
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            try {
                val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                return service.createDisplayContext(display).createWindowContext(type, null).getSystemService(WindowManager::class.java)
            } catch (e: Throwable) {
                ProbeLog.add("No se pudo crear el contexto «sobre otras apps» (${e.javaClass.simpleName}): se usa la capa de accesibilidad")
            }
        }
        return service.getSystemService(WindowManager::class.java)
    }

    /**
     * Añade [view]. Si con la capa «sobre otras apps» falla (p. ej. permiso retirado), vuelve a intentarlo con la
     * de accesibilidad. Devuelve el WindowManager con el que quedó añadida, que es el que hay que usar después.
     */
    fun addWithFallback(service: AccessibilityService, view: View, params: WindowManager.LayoutParams, current: WindowManager): WindowManager {
        try {
            current.addView(view, params)
            return current
        } catch (e: Throwable) {
            if (params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) throw e
            ProbeLog.add("Capa «sobre otras apps» no disponible (${e.javaClass.simpleName}): se usa la de accesibilidad")
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            params.token = null
            val fallback = windowManager(service, params.type)
            fallback.addView(view, params)
            return fallback
        }
    }
}
