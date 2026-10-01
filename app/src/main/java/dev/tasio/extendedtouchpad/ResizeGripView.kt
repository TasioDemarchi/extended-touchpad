package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Franja inferior con un asa en la esquina: arrastrarla cambia el tamaño del panel o del teclado. */
class ResizeGripView(context: Context, appearance: Appearance, private val cb: Callbacks) : View(context) {
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
    private var lastX = 0f
    private var lastY = 0f

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bg)
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
                lastX = e.rawX
                lastY = e.rawY
            }

            MotionEvent.ACTION_MOVE -> {
                cb.onResize(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cb.onResizeEnd()
        }
        return true
    }
}
