package dev.tasio.extendedtouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Barra de transparencia que sustituye temporalmente a la barra superior del panel o del teclado.
 * De izquierda a derecha: el icono (al tocarlo se cierra), la pista con su control y el valor en %.
 */
class OpacitySliderView(
    context: Context,
    private val appearance: Appearance,
    private val getValue: () -> Int,
    private val setValue: (Int) -> Unit,
    private val onClose: () -> Unit,
    /** El dedo se apoyó: el cierre automático debe detenerse mientras se usa la barra. */
    private val onTouchStart: () -> Unit,
    /** El dedo se levantó: el cierre automático vuelve a contar. */
    private val onTouchEnd: () -> Unit,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val bg = Paint().apply { color = appearance.barColor }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.textColor }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.subtleColor
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.accent
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.accent }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.textColor
        textAlign = Paint.Align.RIGHT
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    private var closing = false
    private var dragging = false

    private fun zone() = height * 1.1f
    private fun trackStart() = zone() + 6f * d
    private fun trackEnd() = width - 48f * d

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bg)
        val cy = height / 2f

        // Icono de transparencia (el mismo de la barra normal).
        val ix = zone() / 2f
        val r = 7f * d
        canvas.drawCircle(ix, cy, r, ring)
        canvas.drawArc(ix - r, cy - r, ix + r, cy + r, 90f, 180f, true, solid)

        val value = getValue()
        val fraction = (value - Appearance.MIN_OPACITY) / (100f - Appearance.MIN_OPACITY)
        val x0 = trackStart()
        val x1 = trackEnd()
        val tx = x0 + (x1 - x0) * fraction
        canvas.drawLine(x0, cy, x1, cy, track)
        canvas.drawLine(x0, cy, tx, cy, filled)
        canvas.drawCircle(tx, cy, 9f * d, thumb)
        canvas.drawCircle(tx, cy, 9f * d, thumbRing)

        canvas.drawText("$value%", width - 8f * d, cy + label.textSize / 3f, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchStart()
                closing = e.x < zone()
                dragging = !closing
                if (dragging) update(e.x)
            }

            MotionEvent.ACTION_MOVE -> if (dragging) update(e.x)

            MotionEvent.ACTION_UP -> {
                if (closing && e.x < zone()) onClose() else onTouchEnd()
                closing = false
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                closing = false
                dragging = false
                onTouchEnd()
            }
        }
        return true
    }

    private fun update(x: Float) {
        val fraction = ((x - trackStart()) / (trackEnd() - trackStart())).coerceIn(0f, 1f)
        val value = Appearance.MIN_OPACITY + (fraction * (100 - Appearance.MIN_OPACITY)).roundToInt()
        setValue(value)
        invalidate()
    }
}
