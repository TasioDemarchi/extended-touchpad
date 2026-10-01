package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Icono de transparencia: círculo con la mitad rellena. */
class OpacityIconView(context: Context, appearance: Appearance) : View(context) {
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.textColor }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 7f * d
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 90f, 180f, true, fill)
    }
}
