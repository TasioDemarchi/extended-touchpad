package dev.tasio.extendedtouchpad

import android.animation.ValueAnimator
import android.graphics.Point
import android.view.animation.DecelerateInterpolator

/**
 * Mueve la ventana de un panel a una posición: de golpe o con un deslizamiento corto. Mientras dura un
 * deslizamiento, los movimientos siguientes lo reconducen en vez de saltar, para que no haya tirones.
 */
class WindowMover(
    /** Posición actual de la ventana, o null si no se muestra. */
    private val current: () -> Point?,
    /** Coloca la ventana en esa posición (el panel la ajusta a la pantalla). */
    private val apply: (x: Int, y: Int) -> Unit,
) {
    private var animator: ValueAnimator? = null

    val isAnimating get() = animator?.isRunning == true

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    fun moveTo(x: Int, y: Int, animate: Boolean) {
        if (!animate && !isAnimating) {
            apply(x, y)
            return
        }
        val from = current() ?: return
        animator?.cancel()
        if (from.x == x && from.y == y) {
            apply(x, y)
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedFraction
                apply(from.x + ((x - from.x) * f).toInt(), from.y + ((y - from.y) * f).toInt())
            }
            start()
        }
    }

    private companion object {
        const val DURATION_MS = 140L
    }
}
