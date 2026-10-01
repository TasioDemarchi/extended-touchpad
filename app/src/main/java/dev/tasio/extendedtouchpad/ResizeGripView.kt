package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Franja inferior de un panel. En la esquina derecha tiene un asa que cambia el tamaño del panel; el resto de la
 * franja no hace nada, salvo un icono opcional a la izquierda (teclado en el panel del touchpad, touchpad en el
 * panel del teclado) que ejecuta una acción y se resalta con el color de acento mientras está "activo".
 */
class ResizeGripView(
    context: Context,
    appearance: Appearance,
    private val cb: Callbacks,
    private val leftIcon: LeftIcon? = null,
    private val onLeftIcon: () -> Unit = {},
    private val leftActive: () -> Boolean = { false },
) : View(context) {
    enum class LeftIcon { KEYBOARD, TOUCHPAD }

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
    private val touchpadGlyph = TouchpadGlyph(appearance, d)
    private var lastX = 0f
    private var lastY = 0f

    private enum class Zone { NONE, ICON, RESIZE }

    private var zone = Zone.NONE

    private fun iconZone() = height * 1.8f

    /** Ancho de la esquina derecha que redimensiona: solo ahí está el asa. */
    private fun gripZone() = 44f * d

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bg)
        when (leftIcon) {
            LeftIcon.KEYBOARD -> keyboardGlyph.draw(canvas, iconZone() / 2f, height / 2f, leftActive())
            LeftIcon.TOUCHPAD -> touchpadGlyph.draw(canvas, iconZone() / 2f, height / 2f, leftActive())
            null -> Unit
        }
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
                    leftIcon != null && e.x <= iconZone() -> Zone.ICON
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
                    Zone.ICON -> if (e.x <= iconZone()) onLeftIcon()
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
