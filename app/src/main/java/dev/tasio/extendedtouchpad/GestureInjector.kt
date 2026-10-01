package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import kotlin.math.abs

/** Despacha clics y scroll al display externo con dispatchGesture + setDisplayId. */
class GestureInjector(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    // --- Scroll: un único trazo continuo (dedo apoyado) al que se le van añadiendo segmentos. ---
    private var stroke: StrokeDescription? = null
    private var displayId = -1
    private var x = 0f
    private var curY = 0f
    private var cursorY = 0f
    private var maxY = 0f
    private var pending = 0f
    private var inFlight = false
    private var ending = false
    private var lastDispatchMs = 0L

    fun tap(displayId: Int, x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(StrokeDescription(path, 0, TAP_MS))
            .build()
        if (!service.dispatchGesture(gesture, null, handler)) {
            ProbeLog.add("Tap rechazado por dispatchGesture")
        }
    }

    /**
     * Acumula desplazamiento vertical en px del display externo. Positivo = el contenido baja
     * (los dedos bajan, como en una pantalla táctil).
     */
    fun scroll(displayId: Int, x: Float, y: Float, maxY: Float, deltaExt: Float) {
        ending = false
        this.displayId = displayId
        this.cursorY = y
        this.maxY = maxY
        if (stroke == null) this.x = x
        pending += deltaExt
        pump()
    }

    /** Se levantaron los dedos: suelta el trazo en cuanto termine el segmento en vuelo. */
    fun endScroll() {
        ending = true
        pump()
    }

    private fun pump() {
        if (inFlight) return
        val lo = maxY * EDGE_MARGIN
        val hi = maxY * (1f - EDGE_MARGIN)

        if (abs(pending) < MIN_SEGMENT_PX) {
            if (ending) {
                pending = 0f
                finishStroke()
            }
            return
        }

        val room = if (stroke == null) 0f else if (pending > 0) hi - curY else curY - lo
        if (stroke != null && room < MIN_SEGMENT_PX) {
            // Llegó al borde: suelta el trazo; el callback reanuda desde un punto con espacio.
            finishStroke()
            return
        }
        if (stroke == null) {
            // Empieza cerca del cursor, pero con al menos ~40% de recorrido en el sentido del scroll.
            val range = hi - lo
            curY = if (pending > 0) cursorY.coerceIn(lo, hi - range * MIN_ROOM) else cursorY.coerceIn(lo + range * MIN_ROOM, hi)
        }
        val available = if (pending > 0) hi - curY else curY - lo
        val step = if (pending > 0) minOf(pending, available) else maxOf(pending, -available)
        pending -= step
        dispatchSegment(curY, curY + step, willContinue = true)
    }

    private fun finishStroke() {
        if (stroke == null) return
        // Pequeña parada antes de soltar para que el sistema no lance un fling largo.
        dispatchSegment(curY, curY, willContinue = false)
    }

    private fun dispatchSegment(from: Float, to: Float, willContinue: Boolean) {
        val now = SystemClock.uptimeMillis()
        val duration = if (!willContinue) {
            FINISH_MS
        } else if (stroke == null) {
            FIRST_MS
        } else {
            (now - lastDispatchMs).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        }
        lastDispatchMs = now

        val path = Path().apply {
            moveTo(x, from)
            if (to != from) lineTo(x, to)
        }
        val previous = stroke
        val next = if (previous == null) {
            StrokeDescription(path, 0, duration, willContinue)
        } else {
            previous.continueStroke(path, 0, duration, willContinue)
        }
        stroke = if (willContinue) next else null
        curY = to

        val gesture = GestureDescription.Builder().setDisplayId(displayId).addStroke(next).build()
        inFlight = true
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                resetScroll()
            }
        }
        if (!service.dispatchGesture(gesture, callback, handler)) {
            resetScroll()
            ProbeLog.add("Scroll rechazado por dispatchGesture")
        }
    }

    private fun resetScroll() {
        stroke = null
        inFlight = false
        pending = 0f
        ending = false
    }

    private companion object {
        const val TAP_MS = 50L

        /** Duración del primer segmento (aún no hay un intervalo que medir). */
        const val FIRST_MS = 40L
        const val MIN_SEGMENT_MS = 30L
        const val MAX_SEGMENT_MS = 90L
        const val FINISH_MS = 20L
        const val MIN_SEGMENT_PX = 2f

        /** Fracción de la altura que se deja libre arriba y abajo (evita los gestos del sistema en los bordes). */
        const val EDGE_MARGIN = 0.08f
        const val MIN_ROOM = 0.4f
    }
}
