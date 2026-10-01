package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Franja inferior con un asa en la esquina: arrastrarla cambia el tamaño del panel o del teclado. */
class ResizeGripView(
    context: Context,
    appearance: Appearance,
    private val cb: Callbacks,
    /** Si no es null, se dibuja un icono de teclado a la izquierda de la franja que ejecuta esta acción al tocarlo. */
    private val onKeyboard: (() -> Unit)? = null,
    /** Si el teclado está abierto, el icono se resalta con el color de acento. */
    private val keyboardActive: () -> Boolean = { false },
) : View(context) {
    interface Callbacks {
        /** Desplazamiento en px desde el último evento (positivo = más grande). */
        fun onResize(dx: Float, dy: Float)
        fun onResizeEnd()
    }

    private val d = resources.displayMetrics.density
    private val bg = Paint().apply { color = appearance.barColor }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.subtleColor
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val keyboardGlyph = KeyboardGlyph(appearance, d)
    private var lastX = 0f
    private var lastY = 0f
    private enum class Zone { NONE, KEYBOARD, RESIZE }

    private var zone = Zone.NONE

    private fun keyboardZone() = height * 1.8f

    /** Ancho de la esquina derecha que redimensiona: solo ahí está el asa. */
    private fun gripZone() = 44f * d

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bg)
        if (onKeyboard != null) keyboardGlyph.draw(canvas, keyboardZone() / 2f, height / 2f, keyboardActive())
        // Tres rayas diagonales en la esquina inferior derecha.
        val right = width - 8f * d
        val bottom = height - 5f * d
        for (i in 1..3) {
            val s = i * 4f * d
            canvas.drawLine(right, bottom - s, right - s, bottom, line)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zone = when {
                    onKeyboard != null && e.x <= keyboardZone() -> Zone.KEYBOARD
                    e.x >= width - gripZone() -> Zone.RESIZE
                    else -> Zone.NONE // el resto de la franja no hace nada
                }
                lastX = e.rawX
                lastY = e.rawY
            }

            MotionEvent.ACTION_MOVE -> if (zone == Zone.RESIZE) {
                cb.onResize(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }

            MotionEvent.ACTION_UP -> {
                when (zone) {
                    Zone.KEYBOARD -> if (e.x <= keyboardZone()) onKeyboard?.invoke()
                    Zone.RESIZE -> cb.onResizeEnd()
                    Zone.NONE -> Unit
                }
                zone = Zone.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (zone == Zone.RESIZE) cb.onResizeEnd()
                zone = Zone.NONE
            }
        }
        return true
    }
}
