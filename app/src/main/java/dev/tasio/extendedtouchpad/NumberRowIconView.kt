package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Icono "123" de la barra del teclado: resaltado con el color de acento cuando la fila de números está activa. */
class NumberRowIconView(
    context: Context,
    private val appearance: Appearance,
    private val isActive: () -> Boolean,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.accent }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - 14f * d, cy - 9f * d, cx + 14f * d, cy + 9f * d)
        if (isActive()) {
            canvas.drawRoundRect(box, 4f * d, 4f * d, fill)
            text.color = Color.WHITE
        } else {
            canvas.drawRoundRect(box, 4f * d, 4f * d, outline)
            text.color = appearance.textColor
        }
        canvas.drawText("123", cx, cy + text.textSize * 0.35f, text)
    }
}
