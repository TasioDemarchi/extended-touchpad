package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        fun onKeyboard()
        fun onClose()
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null

    /** Ancho del área táctil en px; el servicio lo usa para escalar el movimiento. */
    var padWidth = 0
        private set

    val isShown get() = root != null

    fun show() {
        if (root != null) {
            relayout()
            return
        }
        val margin = dp(MARGIN_DP)
        val handleH = dp(HANDLE_DP)
        computeSize()
        val padH = (padWidth * PAD_ASPECT).toInt()

        val pad = PadView(service, object : PadView.Callbacks {
            override fun onMove(dx: Float, dy: Float) = listener.onMove(dx, dy)
            override fun onTap() = listener.onTap()
            override fun onScroll(dy: Float) = listener.onScroll(dy)
            override fun onScrollEnd() = listener.onScrollEnd()
            override fun onDragStart() = listener.onDragStart()
            override fun onDragEnd() = listener.onDragEnd()
        })
        val handle = HandleView(service, object : HandleView.Callbacks {
            override fun onDrag(dx: Float, dy: Float) = dragBy(dx, dy)
            override fun onDragEnd() = savePosition()
            override fun onKeyboard() = listener.onKeyboard()
            override fun onClose() = listener.onClose()
        })

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(214, 214, 214))
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
            addView(handle, LinearLayout.LayoutParams(padWidth, handleH))
            addView(pad, LinearLayout.LayoutParams(padWidth, padH))
        }
        val container = FrameLayout(service).apply {
            addView(card, FrameLayout.LayoutParams(padWidth, handleH + padH).apply { setMargins(margin, margin, margin, margin) })
        }

        val bounds = wm.maximumWindowMetrics.bounds
        val w = padWidth + 2 * margin
        val h = handleH + padH + 2 * margin
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
        } catch (t: Throwable) {
            ProbeLog.add("Panel: no se pudo crear el overlay: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun hide() {
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (_: Throwable) {
        }
        root = null
        lp = null
    }

    /** Tras una rotación o cambio de display: mantiene el panel dentro de la pantalla. */
    private fun relayout() {
        val params = lp ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        clamp(params, bounds.width(), bounds.height())
        try {
            wm.updateViewLayout(root, params)
        } catch (_: Throwable) {
        }
    }

    private fun computeSize() {
        val bounds = wm.maximumWindowMetrics.bounds
        padWidth = max(bounds.width() / 4, dp(MIN_PAD_DP))
    }

    private fun dragBy(dx: Float, dy: Float) {
        val params = lp ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        params.x += dx.toInt()
        params.y += dy.toInt()
        clamp(params, bounds.width(), bounds.height())
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
    private class PadView(context: android.content.Context, private val cb: Callbacks) : View(context) {
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
            color = Color.argb(70, 0, 0, 0)
            textAlign = Paint.Align.CENTER
            textSize = 14f * resources.displayMetrics.scaledDensity
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(224, 224, 224))
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

    /** Asa superior: arrastrar mueve el panel; la X de la derecha lo cierra. */
    private class HandleView(context: android.content.Context, private val cb: Callbacks) : View(context) {
        interface Callbacks {
            fun onDrag(dx: Float, dy: Float)
            fun onDragEnd()
            fun onKeyboard()
            fun onClose()
        }

        private enum class Zone { DRAG, KEYBOARD, CLOSE }

        private val d = resources.displayMetrics.density
        private val bg = Paint().apply { color = Color.rgb(176, 176, 176) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            strokeWidth = 2f * d
            strokeCap = Paint.Cap.ROUND
        }
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var zone = Zone.DRAG
        private val key = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPaint(bg)
            val cy = height / 2f
            for (i in -2..2) canvas.drawCircle(width / 2f + i * 10f * d, cy, 2.5f * d, dot)
            val cx = width - closeZone() / 2f
            val r = 6f * d
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, cross)
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, cross)

            // Icono de teclado: rectángulo con tres filas de teclas.
            val kx = closeZone() / 2f
            val kw = 11f * d
            val kh = 7f * d
            canvas.drawRoundRect(kx - kw, cy - kh, kx + kw, cy + kh, 2f * d, 2f * d, key)
            for (row in -1..1) {
                val ry = cy + row * 3.2f * d
                for (col in -2..2) canvas.drawPoint(kx + col * 4f * d, ry, dot)
            }
        }

        private fun closeZone() = height * 1.3f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zone = when {
                        e.x >= width - closeZone() -> Zone.CLOSE
                        e.x <= closeZone() -> Zone.KEYBOARD
                        else -> Zone.DRAG
                    }
                    downRawX = e.rawX
                    downRawY = e.rawY
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                }

                MotionEvent.ACTION_MOVE -> if (zone == Zone.DRAG) {
                    cb.onDrag(e.rawX - lastRawX, e.rawY - lastRawY)
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                }

                MotionEvent.ACTION_UP -> {
                    when {
                        zone == Zone.CLOSE && e.x >= width - closeZone() -> cb.onClose()
                        zone == Zone.KEYBOARD && e.x <= closeZone() -> cb.onKeyboard()
                        else -> cb.onDragEnd()
                    }
                }

                MotionEvent.ACTION_CANCEL -> cb.onDragEnd()
            }
            return true
        }
    }

    private companion object {
        const val MARGIN_DP = 10
        const val HANDLE_DP = 36
        const val MIN_PAD_DP = 240
        const val PAD_ASPECT = 0.62f
        const val KEY_X = "panel_x"
        const val KEY_Y = "panel_y"
    }
}
