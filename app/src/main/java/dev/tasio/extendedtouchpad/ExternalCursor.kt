package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Cursor dibujado como overlay de accesibilidad en el display externo.
 * Las coordenadas (x, y) son la punta de la flecha, en píxeles del display externo.
 */
class ExternalCursor(private val service: AccessibilityService) {
    private var wm: WindowManager? = null
    private var view: CursorView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var updatePending = false

    var displayId = -1
        private set
    var width = 0
        private set
    var height = 0
        private set
    var x = 0f
        private set
    var y = 0f
        private set

    val isAttached get() = view != null

    fun attach(display: Display) {
        detach()
        try {
            // createWindowContext es lo que ata la ventana al display externo: con createDisplayContext
            // solo, la ventana queda con las medidas (y el display) de la tablet.
            val ctx: Context = service.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val manager = ctx.getSystemService(WindowManager::class.java)
            val bounds = manager.currentWindowMetrics.bounds
            val size = (CURSOR_DP * ctx.resources.displayMetrics.density).toInt()
            val cursorView = CursorView(ctx)
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }

            width = bounds.width()
            height = bounds.height()
            x = width / 2f
            y = height / 2f
            params.x = x.toInt()
            params.y = y.toInt()
            manager.addView(cursorView, params)

            wm = manager
            view = cursorView
            lp = params
            displayId = display.displayId
            ProbeLog.add("Cursor en display ${display.displayId} (${width}x$height)")
        } catch (t: Throwable) {
            ProbeLog.add("Cursor: no se pudo crear el overlay: ${t.javaClass.simpleName}: ${t.message}")
            detach()
        }
    }

    fun detach() {
        val v = view
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (_: Throwable) {
                // El display ya puede haber desaparecido y arrastrado la ventana.
            }
        }
        view = null
        wm = null
        lp = null
        displayId = -1
    }

    /** Relee el tamaño del display (cambio de resolución) y mantiene el cursor dentro. */
    fun refreshBounds() {
        val manager = wm ?: return
        val bounds = manager.currentWindowMetrics.bounds
        if (bounds.width() != width || bounds.height() != height) {
            width = bounds.width()
            height = bounds.height()
            moveBy(0f, 0f)
        }
    }

    fun moveBy(dx: Float, dy: Float) {
        val params = lp ?: return
        x = (x + dx).coerceIn(0f, (width - 1).toFloat())
        y = (y + dy).coerceIn(0f, (height - 1).toFloat())
        params.x = x.toInt()
        params.y = y.toInt()
        scheduleUpdate()
    }

    fun pulse() {
        view?.pulse()
    }

    /** Cambia el color del cursor mientras hay un arrastre en curso. */
    fun setPressed(pressed: Boolean) {
        view?.setDragging(pressed)
    }

    // Agrupa los movimientos en un solo updateViewLayout por frame.
    private fun scheduleUpdate() {
        val v = view ?: return
        if (updatePending) return
        updatePending = true
        v.postOnAnimation {
            updatePending = false
            val params = lp ?: return@postOnAnimation
            try {
                wm?.updateViewLayout(v, params)
            } catch (_: Throwable) {
            }
        }
    }

    private class CursorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path()

        init {
            pivotX = 0f
            pivotY = 0f
        }

        fun setDragging(dragging: Boolean) {
            fill.color = if (dragging) Color.rgb(129, 199, 255) else Color.WHITE
            invalidate()
        }

        fun pulse() {
            animate().cancel()
            scaleX = 0.7f
            scaleY = 0.7f
            animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val inset = stroke.strokeWidth
            arrow.rewind()
            arrow.moveTo(inset, inset)
            arrow.lineTo(inset, h * 0.82f)
            arrow.lineTo(w * 0.24f, h * 0.64f)
            arrow.lineTo(w * 0.40f, h * 0.97f)
            arrow.lineTo(w * 0.54f, h * 0.90f)
            arrow.lineTo(w * 0.38f, h * 0.58f)
            arrow.lineTo(w * 0.66f, h * 0.58f)
            arrow.close()
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, stroke)
        }
    }

    private companion object {
        const val CURSOR_DP = 28
    }
}
