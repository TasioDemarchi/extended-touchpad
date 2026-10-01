package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
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
    private var hadExternalDisplay = false

    // Teclado del sistema visible en el TV (ventana de tipo teclado) y pausa del auto-abrir tras cerrar el teclado.
    private var tvImeVisible = false
    private var suppressAutoOpenUntil = 0L
    private lateinit var keyboard: OnScreenKeyboard
    private lateinit var appearance: Appearance
    private lateinit var settings: SharedPreferences
    private lateinit var remoteInput: RemoteInput
    private lateinit var keyguard: KeyguardManager

    // true desde que la pantalla se apaga hasta poco después de encenderla: así no se muestra nada en el
    // hueco en que el keyguard aún no se ha confirmado. El resto del tiempo manda KeyguardManager.
    private var screenOff = false

    private val screenOnCheck = Runnable {
        screenOff = false
        sync()
    }
    private val lockRecheck = Runnable { sync() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ProbeLog.add("Pantalla: ${intent.action?.substringAfterLast('.')} (keyguard=${keyguard.isKeyguardLocked})")
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    main.removeCallbacks(screenOnCheck)
                    screenOff = true
                }

                Intent.ACTION_SCREEN_ON -> {
                    main.removeCallbacks(screenOnCheck)
                    main.postDelayed(screenOnCheck, SCREEN_ON_CHECK_MS)
                }

                Intent.ACTION_USER_PRESENT -> {
                    main.removeCallbacks(screenOnCheck)
                    screenOff = false
                }
            }
            sync()
        }
    }

    // La opacidad se aplica en el acto; el resto de la apariencia reconstruye las vistas (con una pequeña
    // espera para agrupar los cambios de un deslizador).
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Appearance.KEY_PAD_OPACITY -> panel.applyAlpha()
            Appearance.KEY_KB_OPACITY -> keyboard.applyAlpha()
            Appearance.KEY_DARK, Appearance.KEY_ACCENT, Appearance.KEY_CURSOR_DP, Appearance.KEY_CURSOR_COLOR,
            Appearance.KEY_CURSOR_SHAPE,
            -> {
                main.removeCallbacks(rebuild)
                main.postDelayed(rebuild, REBUILD_DELAY_MS)
            }
        }
    }

    private val rebuild = Runnable {
        val keyboardWasShown = keyboard.isShown
        val cursorX = cursor.x
        val cursorY = cursor.y
        val hadCursor = cursor.isAttached
        keyboard.hide()
        panel.hide()
        cursor.detach()
        sync()
        if (hadCursor && cursor.isAttached) cursor.restorePosition(cursorX, cursorY)
        if (keyboardWasShown && isKeyboardAvailable()) keyboard.show()
    }

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

        override fun onKeyboard() = toggleKeyboard()

        override fun onSettings() {
            startActivity(Intent(this@TouchpadService, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun onClose() = setEnabled(false)
    }

    override fun onServiceConnected() {
        instance = this
        settings = getSharedPreferences(PREFS, MODE_PRIVATE)
        appearance = Appearance(settings)
        settings.registerOnSharedPreferenceChangeListener(settingsListener)
        cursor = ExternalCursor(this, appearance)
        injector = GestureInjector(this, main)
        dragStroke = DragStroke(this, main)
        remoteInput = RemoteInput(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        screenOff = false
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
        keyboard = OnScreenKeyboard(this, remoteInput, settings, appearance, { panel.bounds() }) { externalDisplayId() }
        keyboard.onVisibilityChanged = {
            // Tras cerrar el teclado no se reabre solo al instante (el teclado del TV puede seguir en pantalla).
            if (!keyboard.isShown) suppressAutoOpenUntil = SystemClock.uptimeMillis() + AUTO_OPEN_PAUSE_MS
            KeyboardTileService.requestRefresh(this)
        }
        panel = TouchpadPanel(this, settings, appearance, panelListener)
        panel.onSettled = { keyboard.avoidPanel() }
        Displays.manager(this).registerDisplayListener(displayListener, main)
        ProbeLog.add("Servicio de accesibilidad conectado")
        sync()
        TouchpadTileService.requestRefresh(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Displays.manager(this).unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        settings.unregisterOnSharedPreferenceChangeListener(settingsListener)
        main.removeCallbacks(rebuild)
        main.removeCallbacks(screenOnCheck)
        main.removeCallbacks(lockRecheck)
        keyboard.hide()
        panel.hide()
        cursor.detach()
        instance = null
        ProbeLog.add("Servicio de accesibilidad desconectado")
        TouchpadTileService.requestRefresh(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> checkTvKeyboard()
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> onFieldEvent(event)
        }
    }

    /** Foco o clic en una vista: si es un campo de texto del TV, se recuerda y se abre el teclado. */
    private fun onFieldEvent(event: AccessibilityEvent) {
        // Ignora los eventos de este paquete (el teclado propio y el panel).
        if (event.packageName?.toString() == packageName || isLocked()) return
        // Basta con que haya pantalla externa: el teclado funciona aunque el touchpad esté desactivado.
        val externalId = Displays.external(this)?.displayId ?: return
        val source = event.source
        if (remoteInput.remember(source, externalId)) {
            openKeyboardAutomatically("foco o clic en un campo del TV")
        } else if (source != null && source.isEditable) {
            // La lista de ventanas del TV puede ir un instante por detrás del evento: se reintenta una vez.
            main.postDelayed({
                if (remoteInput.remember(source, externalId)) openKeyboardAutomatically("campo del TV (reintento)")
            }, FIELD_RETRY_MS)
        }
        keyboard.onRemoteFocusChanged()
    }

    /** Cambió la lista de ventanas: si apareció el teclado del sistema en el TV, hay un campo esperando texto. */
    private fun checkTvKeyboard() {
        val externalId = Displays.external(this)?.displayId
        if (externalId == null) {
            tvImeVisible = false
            return
        }
        val visible = windowsOnAllDisplays.get(externalId)?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        val appeared = visible && !tvImeVisible
        tvImeVisible = visible
        if (appeared && remoteInput.focusedInput(externalId) != null) {
            openKeyboardAutomatically("teclado del TV")
        }
    }

    private fun openKeyboardAutomatically(reason: String) {
        if (!isAutoOpenKeyboard || keyboard.isShown || isLocked()) return
        if (SystemClock.uptimeMillis() < suppressAutoOpenUntil) return
        ProbeLog.add("Teclado abierto automáticamente ($reason)")
        keyboard.show()
    }

    override fun onInterrupt() = Unit

    var isTouchpadEnabled: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        private set(value) = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()

    val isKeyboardShown: Boolean get() = keyboard.isShown

    /** Abre o cierra el teclado a mano (icono del panel y mosaico del centro de control). */
    fun toggleKeyboard() {
        if (!isKeyboardAvailable()) {
            ProbeLog.add("Teclado: no hay pantalla externa conectada o la tablet está bloqueada")
            return
        }
        keyboard.toggle()
    }

    /** El teclado solo necesita una pantalla externa y la tablet desbloqueada; no depende del touchpad. */
    fun isKeyboardAvailable() = Displays.external(this) != null && !isLocked()

    private fun externalDisplayId() = Displays.external(this)?.displayId ?: -1

    var isAutoOpenKeyboard: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_KEYBOARD, true)
        private set(value) = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()

    fun toggleAutoOpenKeyboard() {
        isAutoOpenKeyboard = !isAutoOpenKeyboard
    }

    fun setEnabled(enabled: Boolean) {
        isTouchpadEnabled = enabled
        sync()
        TouchpadTileService.requestRefresh(this)
    }

    /** Hace que cursor y panel reflejen: ¿hay display externo? ¿está activado el touchpad? */
    private fun sync() {
        val external = Displays.external(this)
        if ((external != null) != hadExternalDisplay) {
            hadExternalDisplay = external != null
            TouchpadTileService.requestRefresh(this)
        }
        val locked = isLocked()
        if (external == null || !isTouchpadEnabled || locked) {
            if (panel.isShown || cursor.isAttached) {
                val reason = when {
                    locked -> "pantalla bloqueada"
                    external == null -> "sin display externo"
                    else -> "desactivado"
                }
                ProbeLog.add("Touchpad oculto ($reason)")
            }
            endDrag()
            panel.hide()
            cursor.detach()
        } else {
            if (cursor.displayId != external.displayId) cursor.attach(external) else cursor.refreshBounds()
            // Si el panel aparece con el teclado ya abierto (se activó el touchpad), el teclado se aparta.
            if (panel.show() && keyboard.isShown) keyboard.avoidPanel()
        }
        // El teclado se mantiene con el touchpad desactivado; solo se cierra sin pantalla externa o con bloqueo.
        if (external == null || locked) keyboard.hide() else keyboard.relayout()
        // USER_PRESENT no llega en todos los dispositivos: con la pantalla encendida y bloqueada se
        // vuelve a comprobar hasta que el sistema indique que ya no hay bloqueo.
        main.removeCallbacks(lockRecheck)
        if (locked && !screenOff) main.postDelayed(lockRecheck, LOCK_RECHECK_MS)
        ProbeLog.onChange?.invoke()
    }

    /** Los overlays de accesibilidad se dibujan sobre la pantalla de bloqueo: con ella activa no se muestra nada. */
    private fun isLocked() = screenOff || keyguard.isKeyguardLocked

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
        private const val SCREEN_ON_CHECK_MS = 300L
        private const val LOCK_RECHECK_MS = 500L
        private const val REBUILD_DELAY_MS = 150L
        private const val FIELD_RETRY_MS = 200L
        private const val AUTO_OPEN_PAUSE_MS = 1500L
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_KEYBOARD = "auto_keyboard"

        /** Px del display externo por px del panel, a velocidad lenta, relativo a ancho_externo/ancho_panel. */
        private const val MOVE_SCALE = 0.5f

        /** Px del display externo por px de desplazamiento de dos dedos en el panel. */
        private const val SCROLL_GAIN = 2f

        @Volatile
        var instance: TouchpadService? = null
            private set
    }
}
