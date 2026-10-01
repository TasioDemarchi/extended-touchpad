package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Icono de tuerca (ajustes) para la barra del teclado; el mismo dibujo que el de la barra del panel. */
class SettingsIconView(context: Context, appearance: Appearance) : View(context) {
    private val d = resources.displayMetrics.density
    private val teeth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 5.5f * d
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c = Math.cos(a).toFloat()
            val s = Math.sin(a).toFloat()
            canvas.drawLine(cx + c * r, cy + s * r, cx + c * (r + 3f * d), cy + s * (r + 3f * d), teeth)
        }
        canvas.drawCircle(cx, cy, r, ring)
    }
}
