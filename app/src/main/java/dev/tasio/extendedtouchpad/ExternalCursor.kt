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
class ExternalCursor(
    private val service: AccessibilityService,
    private val appearance: Appearance,
) {
    private var wm: WindowManager? = null
    private var view: CursorView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var updatePending = false
    private var hotX = 0f
    private var hotY = 0f

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
            val size = (appearance.cursorDp * ctx.resources.displayMetrics.density).toInt()
            val cursorView = CursorView(ctx, appearance)
            val (hx, hy) = CursorGlyph.hotspot(appearance.cursorShape, size.toFloat(), ctx.resources.displayMetrics.density)
            hotX = hx
            hotY = hy
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
            params.x = (x - hotX).toInt()
            params.y = (y - hotY).toInt()
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
        params.x = (x - hotX).toInt()
        params.y = (y - hotY).toInt()
        scheduleUpdate()
    }

    /** Devuelve el cursor a una posición guardada (tras reconstruirlo con otra apariencia). */
    fun restorePosition(px: Float, py: Float) = moveBy(px - x, py - y)

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

    private class CursorView(context: Context, private val appearance: Appearance) : View(context) {
        private val shape = appearance.cursorShape
        private val outline = appearance.cursorOutline
        private val d = resources.displayMetrics.density
        private var fill = appearance.cursorColor

        // El pulso de clic escala alrededor del punto de clic.
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val (hx, hy) = CursorGlyph.hotspot(shape, w.toFloat(), d)
            pivotX = hx
            pivotY = hy
        }

        fun setDragging(dragging: Boolean) {
            fill = if (dragging) appearance.accent else appearance.cursorColor
            invalidate()
        }

        fun pulse() {
            animate().cancel()
            scaleX = 0.7f
            scaleY = 0.7f
            animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }

        override fun onDraw(canvas: Canvas) {
            CursorGlyph.draw(canvas, width.toFloat(), d, shape, fill, outline)
        }
    }
}
