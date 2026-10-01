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
    private val keyRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val keyDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.subtleColor }
    private var lastX = 0f
    private var lastY = 0f
    private var onKeyboardZone = false

    private fun keyboardZone() = height * 1.8f

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bg)
        if (onKeyboard != null) {
            // Icono de teclado: rectángulo con tres filas de teclas.
            val kx = keyboardZone() / 2f
            val cy = height / 2f
            val kw = 10f * d
            val kh = 6.5f * d
            canvas.drawRoundRect(kx - kw, cy - kh, kx + kw, cy + kh, 2f * d, 2f * d, keyRing)
            for (row in -1..1) {
                val ry = cy + row * 3f * d
                for (col in -2..2) canvas.drawPoint(kx + col * 3.6f * d, ry, keyDot)
            }
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
                onKeyboardZone = onKeyboard != null && e.x <= keyboardZone()
                lastX = e.rawX
                lastY = e.rawY
            }

            MotionEvent.ACTION_MOVE -> if (!onKeyboardZone) {
                cb.onResize(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }

            MotionEvent.ACTION_UP -> {
                if (onKeyboardZone) {
                    if (e.x <= keyboardZone()) onKeyboard?.invoke()
                } else {
                    cb.onResizeEnd()
                }
                onKeyboardZone = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!onKeyboardZone) cb.onResizeEnd()
                onKeyboardZone = false
            }
        }
        return true
    }
}
