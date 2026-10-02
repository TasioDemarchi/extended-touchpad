package dev.tasio.extendedtouchpad

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/** Contenedor raíz de un panel flotante: avisa cuando el usuario termina de tocarlo (para traerlo al frente). */
class TouchEndFrameLayout(context: Context) : FrameLayout(context) {
    var onTouchEnd: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Se avisa después de procesar el evento: quitar y volver a añadir la ventana durante el toque lo cortaría.
            post { onTouchEnd?.invoke() }
        }
        return handled
    }
}
