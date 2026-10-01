package dev.tasio.extendedtouchpad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Icono de touchpad (rectángulo con la franja de botones). Con [active] se rellena con el acento y se dibuja en blanco. */
class TouchpadGlyph(private val appearance: Appearance, private val density: Float) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.accent }
    private val box = RectF()

    fun draw(canvas: Canvas, cx: Float, cy: Float, active: Boolean) {
        val d = density
        box.set(cx - 11f * d, cy - 8f * d, cx + 11f * d, cy + 8f * d)
        if (active) canvas.drawRoundRect(box, 4f * d, 4f * d, fill)
        stroke.color = if (active) Color.WHITE else appearance.textColor
        if (!active) canvas.drawRoundRect(box, 4f * d, 4f * d, stroke)
        // Franja de botones: una línea cerca del borde inferior con una división central.
        val lineY = box.bottom - 4.5f * d
        canvas.drawLine(box.left + 1.5f * d, lineY, box.right - 1.5f * d, lineY, stroke)
        canvas.drawLine(cx, lineY, cx, box.bottom - 1.5f * d, stroke)
    }
}
