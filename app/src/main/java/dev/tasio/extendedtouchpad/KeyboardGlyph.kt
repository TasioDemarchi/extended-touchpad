package dev.tasio.extendedtouchpad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Icono de teclado: el mismo dibujo que el del mosaico del centro de control (ic_keyboard_tile):
 * un rectángulo redondeado con dos filas de teclas y la barra espaciadora. Con [active] se rellena con el
 * color de acento y se dibuja en blanco.
 */
class KeyboardGlyph(private val appearance: Appearance, private val density: Float) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    /** Dibuja el icono centrado en ([cx], [cy]). El dibujo original ocupa 24 unidades; aquí 1 unidad = 0,95 dp. */
    fun draw(canvas: Canvas, cx: Float, cy: Float, active: Boolean) {
        val s = 0.95f * density
        fun x(v: Float) = cx + (v - 12f) * s
        fun y(v: Float) = cy + (v - 12f) * s

        val ink = if (active) Color.WHITE else appearance.textColor
        stroke.color = ink
        dots.color = ink
        box.set(x(3f), y(6f), x(21f), y(18f))
        if (active) {
            fill.color = appearance.accent
            canvas.drawRoundRect(box, 2.4f * s, 2.4f * s, fill)
        } else {
            stroke.strokeWidth = 1.8f * s
            canvas.drawRoundRect(box, 2.4f * s, 2.4f * s, stroke)
        }
        for (row in listOf(10f, 13f)) {
            for (col in listOf(6f, 10f, 14f, 18f)) canvas.drawCircle(x(col), y(row), 1f * s, dots)
        }
        stroke.strokeWidth = 2f * s
        canvas.drawLine(x(8f), y(16f), x(16f), y(16f), stroke)
    }
}
