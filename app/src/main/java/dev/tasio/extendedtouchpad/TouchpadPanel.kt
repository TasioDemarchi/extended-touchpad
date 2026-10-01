package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Panel flotante del touchpad, en el display principal (la tablet). */
class TouchpadPanel(
    private val service: AccessibilityService,
    private val prefs: SharedPreferences,
    private val appearance: Appearance,
    private val listener: Listener,
) {
    interface Listener {
        /** Desplazamiento en px del panel, ya con aceleración aplicada. */
        fun onMove(dx: Float, dy: Float)
        fun onTap()

        /** Desplazamiento vertical en px del panel de los dos dedos (positivo = hacia abajo). */
        fun onScroll(dy: Float)
        fun onScrollEnd()

        /** Tap y, sin soltar, dedo apoyado de nuevo: empieza un arrastre en la posición del cursor. */
        fun onDragStart()
        fun onDragEnd()

        /** Abre o cierra el teclado (icono de la franja inferior). */
        fun onKeyboard()

        /** Abre la pantalla de ajustes de la app. */
        fun onSettings()
        fun onClose()
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var card: LinearLayout? = null
    private var handleView: View? = null // contenedor de la barra superior (barra normal + barra de transparencia)
    private var handleBar: View? = null
    private var opacitySlider: View? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val closeSlider = Runnable { hideOpacitySlider() }
    private var padView: View? = null
    private var footerView: View? = null

    /** Ancho del área táctil en px; el servicio lo usa para escalar el movimiento. */
    var padWidth = 0
        private set
    private var padHeight = 0

    val isShown get() = root != null

    fun show() {
        if (root != null) {
            relayout()
            return
        }
        val margin = dp(MARGIN_DP)
        loadSize()

        val pad = PadView(service, appearance, object : PadView.Callbacks {
            override fun onMove(dx: Float, dy: Float) = listener.onMove(dx, dy)
            override fun onTap() = listener.onTap()
            override fun onScroll(dy: Float) = listener.onScroll(dy)
            override fun onScrollEnd() = listener.onScrollEnd()
            override fun onDragStart() = listener.onDragStart()
            override fun onDragEnd() = listener.onDragEnd()
        })
        val handle = HandleView(service, appearance, object : HandleView.Callbacks {
            override fun onDrag(dx: Float, dy: Float) = dragBy(dx, dy)
            override fun onDragEnd() = savePosition()
            override fun onOpacity() = showOpacitySlider()

            override fun onTheme() = appearance.setDark(!appearance.dark)
            override fun onSettings() = listener.onSettings()

            override fun onClose() = listener.onClose()
        })
        val slider = OpacitySliderView(
            service, appearance,
            getValue = { appearance.padOpacity },
            setValue = { appearance.padOpacity = it },
            onClose = { hideOpacitySlider() },
            onTouchStart = { main.removeCallbacks(closeSlider) },
            onTouchEnd = { scheduleSliderClose() },
        ).apply { visibility = View.GONE }
        val handleHolder = FrameLayout(service).apply {
            addView(handle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(slider, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val footer = ResizeGripView(service, appearance, object : ResizeGripView.Callbacks {
            override fun onResize(dx: Float, dy: Float) = resizeBy(dx, dy)
            override fun onResizeEnd() = saveSize()
        }, onKeyboard = { listener.onKeyboard() })

        val cardView = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(appearance.cardColor)
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
            addView(handleHolder, LinearLayout.LayoutParams(padWidth, dp(HANDLE_DP)))
            addView(pad, LinearLayout.LayoutParams(padWidth, padHeight))
            addView(footer, LinearLayout.LayoutParams(padWidth, dp(FOOTER_DP)))
        }
        val container = FrameLayout(service).apply {
            alpha = appearance.padOpacity / 100f
            addView(cardView, FrameLayout.LayoutParams(padWidth, totalHeight()).apply { setMargins(margin, margin, margin, margin) })
        }

        val bounds = wm.maximumWindowMetrics.bounds
        val w = padWidth + 2 * margin
        val h = totalHeight() + 2 * margin
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Sin FLAG_NOT_TOUCHABLE: el panel recibe toques; la ventana mide solo lo que ocupa el panel,
            // así que todo lo de fuera sigue llegando a la app de debajo.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, bounds.width() - w - dp(24))
            y = prefs.getInt(KEY_Y, bounds.height() - h - dp(24))
        }
        clamp(params, bounds.width(), bounds.height())
        try {
            wm.addView(container, params)
            root = container
            lp = params
            card = cardView
            handleView = handleHolder
            handleBar = handle
            opacitySlider = slider
            padView = pad
            footerView = footer
        } catch (t: Throwable) {
            ProbeLog.add("Panel: no se pudo crear el overlay: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Aplica la opacidad actual sin reconstruir el panel. */
    fun applyAlpha() {
        root?.alpha = appearance.padOpacity / 100f
    }

    private fun showOpacitySlider() {
        handleBar?.visibility = View.GONE
        opacitySlider?.visibility = View.VISIBLE
        opacitySlider?.invalidate()
        scheduleSliderClose()
    }

    private fun hideOpacitySlider() {
        main.removeCallbacks(closeSlider)
        opacitySlider?.visibility = View.GONE
        handleBar?.visibility = View.VISIBLE
    }

    /** La barra de transparencia se cierra sola tras unos segundos sin tocarla. */
    private fun scheduleSliderClose() {
        main.removeCallbacks(closeSlider)
        main.postDelayed(closeSlider, SLIDER_TIMEOUT_MS)
    }

    fun hide() {
        main.removeCallbacks(closeSlider)
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (_: Throwable) {
        }
        root = null
        lp = null
        card = null
        handleView = null
        handleBar = null
        opacitySlider = null
        padView = null
        footerView = null
    }

    /** Tras una rotación o cambio de display: mantiene el panel dentro de la pantalla. */
    private fun relayout() {
        val params = lp ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        padWidth = padWidth.coerceIn(minWidth(), maxWidth(bounds.width(), 0))
        padHeight = padHeight.coerceIn(minHeight(), maxHeight(bounds.height(), 0))
        applySize(params)
        clamp(params, bounds.width(), bounds.height())
        updateWindow(params)
    }

    // ---------------------------------------------------------------- tamaño

    private fun loadSize() {
        val bounds = wm.maximumWindowMetrics.bounds
        val defaultW = max(bounds.width() / 4, dp(MIN_PAD_DP))
        padWidth = prefs.getInt(KEY_W, defaultW).coerceIn(minWidth(), maxWidth(bounds.width(), 0))
        padHeight = prefs.getInt(KEY_H, (defaultW * PAD_ASPECT).toInt()).coerceIn(minHeight(), maxHeight(bounds.height(), 0))
    }

    /** Cambia el tamaño del área táctil arrastrando la esquina; la esquina superior izquierda no se mueve. */
    private fun resizeBy(dx: Float, dy: Float) {
        val params = lp ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        padWidth = (padWidth + dx.toInt()).coerceIn(minWidth(), maxWidth(bounds.width(), params.x))
        padHeight = (padHeight + dy.toInt()).coerceIn(minHeight(), maxHeight(bounds.height(), params.y))
        applySize(params)
        updateWindow(params)
    }

    private fun applySize(params: WindowManager.LayoutParams) {
        val margin = dp(MARGIN_DP)
        params.width = padWidth + 2 * margin
        params.height = totalHeight() + 2 * margin
        card?.layoutParams = FrameLayout.LayoutParams(padWidth, totalHeight()).apply { setMargins(margin, margin, margin, margin) }
        handleView?.layoutParams = LinearLayout.LayoutParams(padWidth, dp(HANDLE_DP))
        padView?.layoutParams = LinearLayout.LayoutParams(padWidth, padHeight)
        footerView?.layoutParams = LinearLayout.LayoutParams(padWidth, dp(FOOTER_DP))
    }

    private fun saveSize() {
        prefs.edit().putInt(KEY_W, padWidth).putInt(KEY_H, padHeight).apply()
    }

    private fun totalHeight() = dp(HANDLE_DP) + padHeight + dp(FOOTER_DP)

    private fun minWidth() = dp(MIN_PAD_DP)
    private fun minHeight() = dp(MIN_PAD_HEIGHT_DP)

    /** Ancho máximo del área táctil: ≤ 70 % de la pantalla y sin salirse por la derecha desde [x]. */
    private fun maxWidth(screenW: Int, x: Int) = max(minWidth(), min((screenW * 0.7f).toInt(), screenW - x - 2 * dp(MARGIN_DP)))

    private fun maxHeight(screenH: Int, y: Int) =
        max(minHeight(), min((screenH * 0.8f).toInt(), screenH - y - 2 * dp(MARGIN_DP) - dp(HANDLE_DP) - dp(FOOTER_DP)))

    // ---------------------------------------------------------------- posición

    private fun dragBy(dx: Float, dy: Float) {
        val params = lp ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        params.x += dx.toInt()
        params.y += dy.toInt()
        clamp(params, bounds.width(), bounds.height())
        updateWindow(params)
    }

    private fun updateWindow(params: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(root, params)
        } catch (_: Throwable) {
        }
    }

    private fun savePosition() {
        val params = lp ?: return
        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
    }

    private fun clamp(params: WindowManager.LayoutParams, screenW: Int, screenH: Int) {
        params.x = params.x.coerceIn(0, max(0, screenW - params.width))
        params.y = params.y.coerceIn(0, max(0, screenH - params.height))
    }

    private fun dp(v: Int) = (v * density).toInt()

    /** Área táctil: un dedo mueve, tap = clic, dos dedos = scroll. */
    private class PadView(
        context: android.content.Context,
        private val appearance: Appearance,
        private val cb: Callbacks,
    ) : View(context) {
        interface Callbacks {
            fun onMove(dx: Float, dy: Float)
            fun onTap()
            fun onScroll(dy: Float)
            fun onScrollEnd()
            fun onDragStart()
            fun onDragEnd()
        }

        private enum class Mode { IDLE, MOVE, SCROLL, DRAG }

        private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop * 2f
        private var mode = Mode.IDLE
        private var lastX = 0f
        private var lastY = 0f
        private var lastT = 0L
        private var downT = 0L
        private var travelled = 0f
        private var maxPointers = 0
        private var lastCentroidY = 0f
        private var lastTapUpT = 0L
        private var lastWasTap = false
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = appearance.hintColor
            textAlign = Paint.Align.CENTER
            textSize = 14f * resources.displayMetrics.scaledDensity
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(appearance.padColor)
            canvas.drawText("touchpad", width / 2f, height / 2f, hint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Tap seguido de un nuevo apoyo rápido = tap-drag.
                    val drag = lastWasTap && e.eventTime - lastTapUpT <= DRAG_WINDOW_MS
                    lastWasTap = false
                    mode = if (drag) Mode.DRAG else Mode.MOVE
                    if (drag) cb.onDragStart()
                    downT = e.eventTime
                    travelled = 0f
                    maxPointers = 1
                    lastX = e.x
                    lastY = e.y
                    lastT = e.eventTime
                }

                MotionEvent.ACTION_POINTER_DOWN -> if (mode != Mode.DRAG) {
                    maxPointers = max(maxPointers, e.pointerCount)
                    mode = Mode.SCROLL
                    lastCentroidY = centroidY(e, skip = -1)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (mode == Mode.SCROLL) lastCentroidY = centroidY(e, skip = e.actionIndex)
                }

                MotionEvent.ACTION_MOVE -> when (mode) {
                    Mode.MOVE, Mode.DRAG -> move(e)
                    Mode.SCROLL -> {
                        val cy = centroidY(e, skip = -1)
                        cb.onScroll(cy - lastCentroidY)
                        lastCentroidY = cy
                    }

                    Mode.IDLE -> Unit
                }

                MotionEvent.ACTION_UP -> {
                    if (mode == Mode.MOVE && maxPointers == 1 &&
                        e.eventTime - downT <= TAP_TIMEOUT_MS && travelled < tapSlop
                    ) {
                        cb.onTap()
                        lastWasTap = true
                        lastTapUpT = e.eventTime
                    }
                    if (mode == Mode.SCROLL) cb.onScrollEnd()
                    if (mode == Mode.DRAG) cb.onDragEnd()
                    mode = Mode.IDLE
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (mode == Mode.SCROLL) cb.onScrollEnd()
                    if (mode == Mode.DRAG) cb.onDragEnd()
                    mode = Mode.IDLE
                }
            }
            return true
        }

        private fun move(e: MotionEvent) {
            val dx = e.x - lastX
            val dy = e.y - lastY
            val dist = hypot(dx, dy)
            val dt = max(1L, e.eventTime - lastT)
            travelled += dist
            lastX = e.x
            lastY = e.y
            lastT = e.eventTime
            // Aceleración simple: de 1x (lento) a MAX_ACCEL x (rápido), lineal en la velocidad.
            val speed = dist / dt // px/ms
            val accel = 1f + (MAX_ACCEL - 1f) * min(speed / FAST_PX_PER_MS, 1f)
            cb.onMove(dx * accel, dy * accel)
        }

        private fun centroidY(e: MotionEvent, skip: Int): Float {
            var sum = 0f
            var n = 0
            for (i in 0 until e.pointerCount) {
                if (i == skip) continue
                sum += e.getY(i)
                n++
            }
            return if (n == 0) 0f else sum / n
        }

        private companion object {
            const val TAP_TIMEOUT_MS = 250L

            /** Tiempo máximo entre soltar un tap y volver a apoyar para que sea un arrastre. */
            const val DRAG_WINDOW_MS = 300L
            const val MAX_ACCEL = 3f
            const val FAST_PX_PER_MS = 2f
        }
    }

    /**
     * Asa superior. De izquierda a derecha: transparencia, tema, puntos de arrastre (mueven el panel),
     * ajustes y cerrar.
     */
    private class HandleView(
        context: android.content.Context,
        private val appearance: Appearance,
        private val cb: Callbacks,
    ) : View(context) {
        interface Callbacks {
            fun onDrag(dx: Float, dy: Float)
            fun onDragEnd()
            fun onOpacity()
            fun onTheme()
            fun onSettings()
            fun onClose()
        }

        private enum class Zone { OPACITY, THEME, DRAG, SETTINGS, CLOSE }

        private val d = resources.displayMetrics.density
        private val bg = Paint().apply { color = appearance.barColor }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.subtleColor }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = appearance.textColor
            strokeWidth = 2f * d
            strokeCap = Paint.Cap.ROUND
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = appearance.textColor
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
        }
        private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.textColor }
        private val moon = Path()
        private val moonCut = Path()
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var zone = Zone.DRAG

        private fun zoneWidth() = height * 1.1f

        override fun onDraw(canvas: Canvas) {
            canvas.drawPaint(bg)
            val cy = height / 2f
            val z = zoneWidth()

            drawOpacity(canvas, z * 0.5f, cy)
            drawTheme(canvas, z * 1.5f, cy)
            drawSettings(canvas, width - z * 1.5f, cy)
            drawClose(canvas, width - z * 0.5f, cy)

            // Puntos de arrastre: solo los que caben en el espacio libre entre los iconos, centrados ahí.
            val freeStart = 2f * z
            val freeEnd = width - 2f * z
            val spacing = 10f * d
            val count = ((freeEnd - freeStart - 2f * d) / spacing).toInt().coerceIn(1, 5)
            val centerX = (freeStart + freeEnd) / 2f
            for (i in 0 until count) {
                canvas.drawCircle(centerX + (i - (count - 1) / 2f) * spacing, cy, 2.5f * d, dot)
            }
        }

        /** Círculo con la mitad rellena. */
        private fun drawOpacity(canvas: Canvas, cx: Float, cy: Float) {
            val r = 7f * d
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 90f, 180f, true, solid)
        }

        /** Con el tema claro muestra una luna (pasar a oscuro); con el oscuro, un sol (pasar a claro). */
        private fun drawTheme(canvas: Canvas, cx: Float, cy: Float) {
            val r = 7f * d
            if (appearance.dark) {
                canvas.drawCircle(cx, cy, r * 0.5f, solid)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val s = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * r * 0.8f, cy + s * r * 0.8f, cx + c * r * 1.2f, cy + s * r * 1.2f, line)
                }
            } else {
                moon.rewind()
                moonCut.rewind()
                moon.addCircle(cx, cy, r, Path.Direction.CW)
                moonCut.addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW)
                moon.op(moonCut, Path.Op.DIFFERENCE)
                canvas.drawPath(moon, solid)
            }
        }

        /** Tuerca: anillo con dientes y un hueco central. */
        private fun drawSettings(canvas: Canvas, cx: Float, cy: Float) {
            val r = 5.5f * d
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val c = Math.cos(a).toFloat()
                val s = Math.sin(a).toFloat()
                canvas.drawLine(cx + c * r, cy + s * r, cx + c * (r + 3f * d), cy + s * (r + 3f * d), line.apply { strokeWidth = 3f * d })
            }
            line.strokeWidth = 2f * d
            ring.strokeWidth = 2.2f * d
            canvas.drawCircle(cx, cy, r, ring)
            ring.strokeWidth = 1.6f * d
        }

        private fun drawClose(canvas: Canvas, cx: Float, cy: Float) {
            val r = 6f * d
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, line)
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, line)
        }

        private fun zoneAt(x: Float): Zone {
            val z = zoneWidth()
            return when {
                x <= z -> Zone.OPACITY
                x <= 2 * z -> Zone.THEME
                x >= width - z -> Zone.CLOSE
                x >= width - 2 * z -> Zone.SETTINGS
                else -> Zone.DRAG
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zone = zoneAt(e.x)
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                }

                MotionEvent.ACTION_MOVE -> if (zone == Zone.DRAG) {
                    cb.onDrag(e.rawX - lastRawX, e.rawY - lastRawY)
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                }

                MotionEvent.ACTION_UP -> {
                    if (zone == Zone.DRAG) {
                        cb.onDragEnd()
                    } else if (zoneAt(e.x) == zone) {
                        when (zone) {
                            Zone.OPACITY -> cb.onOpacity()
                            Zone.THEME -> cb.onTheme()
                            Zone.SETTINGS -> cb.onSettings()
                            Zone.CLOSE -> cb.onClose()
                            Zone.DRAG -> Unit
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> if (zone == Zone.DRAG) cb.onDragEnd()
            }
            return true
        }
    }

    private companion object {
        const val MARGIN_DP = 10
        const val SLIDER_TIMEOUT_MS = 3000L
        const val HANDLE_DP = 36
        const val FOOTER_DP = 28
        const val MIN_PAD_DP = 220
        const val MIN_PAD_HEIGHT_DP = 110
        const val PAD_ASPECT = 0.62f
        const val KEY_X = "panel_x"
        const val KEY_Y = "panel_y"
        const val KEY_W = "pad_w"
        const val KEY_H = "pad_h"
    }
}
