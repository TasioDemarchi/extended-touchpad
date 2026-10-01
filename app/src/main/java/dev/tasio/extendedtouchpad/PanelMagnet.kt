package dev.tasio.extendedtouchpad

import android.graphics.Point
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/** Un panel flotante que puede imantarse a otro. Las coordenadas son de la tarjeta visible (sin el margen de la sombra). */
interface MagnetPanel {
    /** Rectángulo de la tarjeta en px de pantalla, o null si el panel no se muestra. */
    fun cardRect(): Rect?

    /** Mueve el panel para que la esquina superior izquierda de su tarjeta quede en ([x], [y]), de golpe o deslizando. */
    fun moveCardTo(x: Int, y: Int, animate: Boolean)

    /** Guarda la posición actual como la que se recupera al reabrir. */
    fun persistPosition()
}

/**
 * Imán entre el panel del touchpad y el teclado. Cuando uno se acerca al otro por cualquier lado, se pega por el
 * borde, centrado sobre el borde compartido. Mientras están pegados ("acoplados"), si cambia el tamaño de
 * cualquiera de los dos el acoplado se vuelve a colocar pegado y centrado. Arrastrar uno de los dos los separa.
 */
class PanelMagnet(
    /** Zona de pantalla donde puede estar una tarjeta (la pantalla menos el margen de la sombra). */
    private val screen: () -> Rect,
    density: Float,
    private val enabled: () -> Boolean,
) {
    lateinit var touchpad: MagnetPanel
    lateinit var keyboard: MagnetPanel

    /** Lado en el que está el panel acoplado respecto al otro (el ancla). */
    private enum class Side { LEFT, RIGHT, TOP, BOTTOM }

    private class Dock(val moving: MagnetPanel, val side: Side)

    private var dock: Dock? = null
    private var dragSide: Side? = null

    /** Tras [dragPosition]: true si acaba de pegarse, despegarse o cambiar de lado (el panel debe deslizar, no saltar). */
    var dragStateChanged = false
        private set

    private val snapPx = (SNAP_DP * density).toInt()
    private val releasePx = (RELEASE_DP * density).toInt()

    private fun other(p: MagnetPanel) = if (p === touchpad) keyboard else touchpad

    fun clear() {
        dock = null
        dragSide = null
    }

    // ---------------------------------------------------------------- arrastre

    fun onDragStart() {
        clear()
    }

    /**
     * Posición de la tarjeta de [moving] mientras se arrastra: la del dedo ([x], [y]) o, si está cerca del otro panel,
     * la posición pegada y centrada. Una vez pegado, se mantiene así hasta alejarse más del margen de suelta.
     */
    fun dragPosition(moving: MagnetPanel, x: Int, y: Int): Point {
        val raw = Point(x, y)
        dragStateChanged = false
        if (!enabled()) return raw
        val anchor = other(moving).cardRect()
        val own = moving.cardRect()
        if (anchor == null || own == null) {
            dragSide = null
            return raw
        }
        val desired = Rect(x, y, x + own.width(), y + own.height())
        val found = bestSnap(desired, anchor, dragSide)
        dragStateChanged = found?.first != dragSide
        dragSide = found?.first
        return found?.second ?: raw
    }

    /** Terminó el arrastre: si quedó pegado, los dos paneles pasan a estar acoplados. */
    fun onDragEnd(moving: MagnetPanel) {
        val side = dragSide
        dragSide = null
        dock = if (enabled() && side != null) Dock(moving, side) else null
    }

    // ---------------------------------------------------------------- recolocación

    /** Vuelve a pegar y centrar el panel acoplado (tras cambiar el tamaño de cualquiera de los dos). */
    fun reapply() {
        val d = dock ?: return
        if (!enabled()) {
            dock = null
            return
        }
        val own = d.moving.cardRect()
        val anchor = other(d.moving).cardRect()
        if (own == null || anchor == null) {
            dock = null
            return
        }
        val target = place(d.side, own.width(), own.height(), anchor, screen())
        if (target == null) {
            dock = null // ya no cabe pegado por ese lado
            return
        }
        if (target.x != own.left || target.y != own.top) d.moving.moveCardTo(target.x, target.y, animate = false)
    }

    /**
     * Tras abrir un panel o cambiar su tamaño o posición fuera de un arrastre: si ya están acoplados se
     * recolocan; si no, [who] se pega al otro si está lo bastante cerca. Guarda las posiciones resultantes.
     */
    fun settle(who: MagnetPanel) {
        if (!enabled()) {
            dock = null
            return
        }
        reapply()
        if (dock == null) snapIfNear(who)
        touchpad.persistPosition()
        keyboard.persistPosition()
    }

    private fun snapIfNear(who: MagnetPanel) {
        val own = who.cardRect() ?: return
        val anchor = other(who).cardRect() ?: return
        val found = bestSnap(own, anchor, null) ?: return
        who.moveCardTo(found.second.x, found.second.y, animate = true)
        dock = Dock(who, found.first)
    }

    // ---------------------------------------------------------------- geometría

    /** El lado más cercano al que [desired] puede pegarse a [anchor] (con su posición pegada y centrada), si hay alguno. */
    private fun bestSnap(desired: Rect, anchor: Rect, current: Side?): Pair<Side, Point>? {
        val screenRect = screen()
        var best: Triple<Side, Int, Point>? = null
        for (side in Side.entries) {
            val gap: Int
            val overlapsAlongEdge: Boolean
            when (side) {
                Side.RIGHT -> {
                    gap = abs(desired.left - anchor.right)
                    overlapsAlongEdge = verticalOverlap(desired, anchor)
                }

                Side.LEFT -> {
                    gap = abs(desired.right - anchor.left)
                    overlapsAlongEdge = verticalOverlap(desired, anchor)
                }

                Side.BOTTOM -> {
                    gap = abs(desired.top - anchor.bottom)
                    overlapsAlongEdge = horizontalOverlap(desired, anchor)
                }

                Side.TOP -> {
                    gap = abs(desired.bottom - anchor.top)
                    overlapsAlongEdge = horizontalOverlap(desired, anchor)
                }
            }
            val limit = if (side == current) releasePx else snapPx
            if (!overlapsAlongEdge || gap > limit) continue
            val p = place(side, desired.width(), desired.height(), anchor, screenRect) ?: continue
            if (best == null || gap < best.second) best = Triple(side, gap, p)
        }
        return best?.let { it.first to it.third }
    }

    private fun verticalOverlap(a: Rect, b: Rect) = a.top < b.bottom + snapPx && a.bottom > b.top - snapPx
    private fun horizontalOverlap(a: Rect, b: Rect) = a.left < b.right + snapPx && a.right > b.left - snapPx

    /**
     * Posición de la tarjeta de tamaño ([w], [h]) pegada a [anchor] por [side] y centrada sobre el borde compartido.
     * El eje del borde se ajusta a la pantalla; si no cabe pegado por ese lado, devuelve null.
     */
    private fun place(side: Side, w: Int, h: Int, anchor: Rect, screenRect: Rect): Point? {
        var x: Int
        var y: Int
        when (side) {
            Side.RIGHT -> {
                x = anchor.right
                y = anchor.centerY() - h / 2
            }

            Side.LEFT -> {
                x = anchor.left - w
                y = anchor.centerY() - h / 2
            }

            Side.BOTTOM -> {
                y = anchor.bottom
                x = anchor.centerX() - w / 2
            }

            Side.TOP -> {
                y = anchor.top - h
                x = anchor.centerX() - w / 2
            }
        }
        when (side) {
            Side.LEFT, Side.RIGHT -> {
                if (x < screenRect.left || x + w > screenRect.right) return null
                y = y.coerceIn(screenRect.top, max(screenRect.top, screenRect.bottom - h))
            }

            Side.TOP, Side.BOTTOM -> {
                if (y < screenRect.top || y + h > screenRect.bottom) return null
                x = x.coerceIn(screenRect.left, max(screenRect.left, screenRect.right - w))
            }
        }
        return Point(x, y)
    }

    companion object {
        /** Margen transparente alrededor de la tarjeta de cada panel. Sin sombras no hace falta: la ventana es la tarjeta. */
        const val CARD_MARGIN_DP = 0

        /** Distancia entre bordes a la que un panel se pega al otro, y a la que se suelta una vez pegado. */
        private const val SNAP_DP = 12
        private const val RELEASE_DP = 24
    }
}
