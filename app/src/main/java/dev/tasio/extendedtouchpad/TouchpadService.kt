package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TouchpadService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cursor: ExternalCursor
    private lateinit var panel: TouchpadPanel
    private lateinit var injector: GestureInjector
    private lateinit var dragStroke: DragStroke
    private var dragging = false
    private lateinit var keyboard: OnScreenKeyboard
    private lateinit var remoteInput: RemoteInput

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = sync()
        override fun onDisplayRemoved(displayId: Int) = sync()
        override fun onDisplayChanged(displayId: Int) = sync()
    }

    private val panelListener = object : TouchpadPanel.Listener {
        override fun onMove(dx: Float, dy: Float) {
            if (!cursor.isAttached) return
            // Escala: recorrer el panel a velocidad lenta ≈ media pantalla del display externo.
            val scale = cursor.width / panel.padWidth.toFloat() * MOVE_SCALE
            cursor.moveBy(dx * scale, dy * scale)
            if (dragging) dragStroke.moveTo(cursor.x, cursor.y)
        }

        override fun onDragStart() {
            if (!cursor.isAttached) return
            dragging = true
            cursor.setPressed(true)
            dragStroke.begin(cursor.displayId, cursor.x, cursor.y)
        }

        override fun onDragEnd() = endDrag()

        override fun onTap() {
            if (!cursor.isAttached) return
            cursor.pulse()
            injector.tap(cursor.displayId, cursor.x, cursor.y)
        }

        override fun onScroll(dy: Float) {
            if (!cursor.isAttached) return
            injector.scroll(cursor.displayId, cursor.x, cursor.y, (cursor.height - 1).toFloat(), dy * SCROLL_GAIN)
        }

        override fun onScrollEnd() = injector.endScroll()

        override fun onKeyboard() {
            if (cursor.isAttached) keyboard.toggle()
        }

        override fun onClose() = setEnabled(false)
    }

    override fun onServiceConnected() {
        instance = this
        cursor = ExternalCursor(this)
        injector = GestureInjector(this, main)
        dragStroke = DragStroke(this, main)
        remoteInput = RemoteInput(this)
        keyboard = OnScreenKeyboard(this, remoteInput, getSharedPreferences(PREFS, MODE_PRIVATE)) { cursor.displayId }
        panel = TouchpadPanel(this, getSharedPreferences(PREFS, MODE_PRIVATE), panelListener)
        Displays.manager(this).registerDisplayListener(displayListener, main)
        ProbeLog.add("Servicio de accesibilidad conectado")
        sync()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Displays.manager(this).unregisterDisplayListener(displayListener)
        keyboard.hide()
        panel.hide()
        cursor.detach()
        instance = null
        ProbeLog.add("Servicio de accesibilidad desconectado")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Foco en un campo de otra app (no la barra del teclado, que es de este paquete).
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && event.packageName?.toString() != packageName) {
            if (cursor.isAttached) remoteInput.remember(event.source, cursor.displayId)
            keyboard.onRemoteFocusChanged()
        }
    }

    override fun onInterrupt() = Unit

    var isTouchpadEnabled: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        private set(value) = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()

    fun setEnabled(enabled: Boolean) {
        isTouchpadEnabled = enabled
        sync()
    }

    /** Hace que cursor y panel reflejen: ¿hay display externo? ¿está activado el touchpad? */
    private fun sync() {
        val external = Displays.external(this)
        if (external == null || !isTouchpadEnabled) {
            if (panel.isShown || cursor.isAttached) ProbeLog.add("Touchpad oculto (${if (external == null) "sin display externo" else "desactivado"})")
            endDrag()
            keyboard.hide()
            panel.hide()
            cursor.detach()
        } else {
            if (cursor.displayId != external.displayId) cursor.attach(external) else cursor.refreshBounds()
            panel.show()
        }
        ProbeLog.onChange?.invoke()
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        cursor.setPressed(false)
        dragStroke.end()
    }

    /** Prueba de diagnóstico: tap en el centro del display externo. */
    fun testTapCenter() {
        if (!cursor.isAttached) {
            ProbeLog.add("Tap de prueba: no hay cursor en un display externo")
            return
        }
        val x = cursor.width / 2f
        val y = cursor.height / 2f
        injector.tap(cursor.displayId, x, y)
        ProbeLog.add("Tap de prueba en (${x.toInt()}, ${y.toInt()})")
    }

    companion object {
        private const val PREFS = "touchpad"
        private const val KEY_ENABLED = "enabled"

        /** Px del display externo por px del panel, a velocidad lenta, relativo a ancho_externo/ancho_panel. */
        private const val MOVE_SCALE = 0.5f

        /** Px del display externo por px de desplazamiento de dos dedos en el panel. */
        private const val SCROLL_GAIN = 2f

        @Volatile
        var instance: TouchpadService? = null
            private set
    }
}
