package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Icono de touchpad de la barra del teclado: resaltado con el color de acento cuando el touchpad está activo. */
class TouchpadIconView(
    context: Context,
    private val appearance: Appearance,
    private val isActive: () -> Boolean,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.accent }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - 12f * d, cy - 8.5f * d, cx + 12f * d, cy + 8.5f * d)
        val active = isActive()
        if (active) canvas.drawRoundRect(box, 4f * d, 4f * d, fill)
        stroke.color = if (active) Color.WHITE else appearance.textColor
        if (!active) canvas.drawRoundRect(box, 4f * d, 4f * d, stroke)
        // Franja de botones del touchpad: una línea cerca del borde inferior con una división central.
        val lineY = box.bottom - 5f * d
        canvas.drawLine(box.left + 1.5f * d, lineY, box.right - 1.5f * d, lineY, stroke)
        canvas.drawLine(cx, lineY, cx, box.bottom - 1.5f * d, stroke)
    }
}
