package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import kotlin.math.abs

/**
 * Arrastre en el display externo: un dedo que se apoya en el cursor ([begin]), sigue al cursor
 * ([moveTo]) y se suelta al terminar ([end]). Usa un único trazo continuo (willContinue).
 */
class DragStroke(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private var stroke: StrokeDescription? = null
    private var active = false
    private var inFlight = false
    private var ending = false
    private var displayId = -1
    private var curX = 0f
    private var curY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var lastDispatchMs = 0L

    fun begin(displayId: Int, x: Float, y: Float) {
        reset()
        this.displayId = displayId
        curX = x
        curY = y
        targetX = x
        targetY = y
        active = true
        dispatchSegment(x, y, willContinue = true)
    }

    fun moveTo(x: Float, y: Float) {
        if (!active) return
        targetX = x
        targetY = y
        pump()
    }

    /** Suelta el dedo en cuanto termine el segmento en vuelo y se alcance el último punto. */
    fun end() {
        if (!active) return
        ending = true
        pump()
    }

    private fun pump() {
        if (inFlight || !active) return
        if (abs(targetX - curX) >= MIN_MOVE_PX || abs(targetY - curY) >= MIN_MOVE_PX) {
            dispatchSegment(targetX, targetY, willContinue = true)
        } else if (ending) {
            dispatchSegment(curX, curY, willContinue = false)
        }
    }

    private fun dispatchSegment(toX: Float, toY: Float, willContinue: Boolean) {
        val now = SystemClock.uptimeMillis()
        val duration = when {
            !willContinue -> FINISH_MS
            stroke == null -> PRESS_MS
            else -> (now - lastDispatchMs).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        }
        lastDispatchMs = now

        val path = Path().apply {
            moveTo(curX, curY)
            if (toX != curX || toY != curY) lineTo(toX, toY)
        }
        val previous = stroke
        val next = if (previous == null) {
            StrokeDescription(path, 0, duration, willContinue)
        } else {
            previous.continueStroke(path, 0, duration, willContinue)
        }
        stroke = if (willContinue) next else null
        if (!willContinue) active = false
        curX = toX
        curY = toY

        val gesture = GestureDescription.Builder().setDisplayId(displayId).addStroke(next).build()
        inFlight = true
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                reset()
            }
        }
        if (!service.dispatchGesture(gesture, callback, handler)) {
            reset()
            ProbeLog.add("Drag rechazado por dispatchGesture")
        }
    }

    private fun reset() {
        stroke = null
        active = false
        inFlight = false
        ending = false
    }

    private companion object {
        /** Duración del toque inicial (el dedo se apoya y queda pulsado hasta el siguiente segmento). */
        const val PRESS_MS = 40L
        const val MIN_SEGMENT_MS = 30L
        const val MAX_SEGMENT_MS = 90L
        const val FINISH_MS = 20L
        const val MIN_MOVE_PX = 1f
    }
}
