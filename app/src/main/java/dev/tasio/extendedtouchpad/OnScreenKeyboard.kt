package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Teclado en pantalla propio, flotante y sin foco, en la tablet. Cada tecla modifica el texto del
 * campo enfocado en el display externo con las acciones de accesibilidad. Al no tomar nunca el foco
 * del sistema, no compite con la ventana del TV (que es lo que rompía al usar el teclado del sistema).
 */
class OnScreenKeyboard(
    service: AccessibilityService,
    private val remote: RemoteInput,
    private val prefs: SharedPreferences,
    private val appearance: Appearance,
    /** Rectángulo del panel del touchpad si está en pantalla; el teclado intenta no taparlo al abrirse. */
    private val panelBounds: () -> android.graphics.Rect?,
    private val externalDisplayId: () -> Int,
) {
    private enum class Shift { OFF, ONCE, LOCK }

    private val ctx = service
    private val softKeyboard = service.softKeyboardController
    private var previousShowMode: Int? = null
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var keysBox: LinearLayout? = null
    private var preview: TextView? = null
    private var card: LinearLayout? = null
    private var bar: View? = null // contenedor de la barra superior (barra normal + barra de transparencia)
    private var normalBar: View? = null
    private var opacitySlider: View? = null
    private val closeSlider = Runnable { hideOpacitySlider() }
    private var footer: View? = null
    private var widthPx = 0

    private var shift = Shift.OFF
    private var symbols = false

    // En campos de contraseña Android no entrega el texto real: se lleva aquí lo escrito.
    private var passwordNode: AccessibilityNodeInfo? = null
    private val passwordBuffer = StringBuilder()

    // Se acaba de dar Enter: el contenido del campo pasa a ser otra cosa (p. ej. la URL de los resultados de una
    // búsqueda). La vista previa se vacía y la siguiente tecla de texto empieza de cero. No depende del nodo: al
    // lanzar la búsqueda la página se recarga y el campo pasa a ser otro nodo para Android.
    private var afterEnter = false

    val isShown get() = root != null

    /** Si está activa, las letras llevan encima una fila de números. Se recuerda entre aperturas. */
    private val numberRow: Boolean get() = prefs.getBoolean(KEY_NUMBERS, false)

    /** Imán entre los dos paneles; lo pone el servicio. */
    var magnet: PanelMagnet? = null

    val magnetPanel = object : MagnetPanel {
        override fun cardRect(): android.graphics.Rect? {
            val params = lp ?: return null
            val r = root ?: return null
            r.measure(
                View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val m = dp(MARGIN_DP)
            return android.graphics.Rect(params.x + m, params.y + m, params.x + params.width - m, params.y + r.measuredHeight - m)
        }

        override fun moveCardTo(x: Int, y: Int, animate: Boolean) {
            mover.moveTo(x - dp(MARGIN_DP), y - dp(MARGIN_DP), animate)
        }

        override fun persistPosition() {
            val params = lp ?: return
            prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
        }
    }

    // Mueve la ventana de golpe o deslizando (acoplamiento del imán).
    private val mover = WindowMover(
        current = { lp?.let { android.graphics.Point(it.x, it.y) } },
        apply = { x, y ->
            lp?.let { params ->
                params.x = x
                params.y = y
                clampToScreen()
            }
        },
    )

    // Posición de la ventana que marca el dedo al arrastrar, sin el efecto del imán.
    private var rawX = 0
    private var rawY = 0

    /** Estado del touchpad (activado o no) y acción para activarlo o desactivarlo; los pone el servicio. */
    var touchpadState: () -> Boolean = { false }
    var onTouchpadToggle: () -> Unit = {}

    /** Abre los ajustes de apariencia de la app; lo pone el servicio. */
    var onOpenSettings: () -> Unit = {}

    /** Se invoca al abrirse o cerrarse el teclado (para actualizar el mosaico del centro de control). */
    var onVisibilityChanged: (() -> Unit)? = null

    fun toggle() = if (isShown) hide() else show()

    fun show() {
        if (root != null) return
        val bounds = wm.maximumWindowMetrics.bounds
        val margin = dp(MARGIN_DP)
        widthPx = prefs.getInt(KEY_W, min((bounds.width() * 0.62f).toInt(), dp(MAX_WIDTH_DP)))
            .coerceIn(minWidth(), maxWidth(bounds.width(), 0))
        val width = widthPx

        val title = TextView(ctx).apply {
            textSize = 14f
            setTextColor(appearance.textColor)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val opacityBtn = OpacityIconView(ctx, appearance).apply {
            setOnClickListener { showOpacitySlider() }
        }
        val numbersBtn = NumberRowIconView(ctx, appearance) { numberRow }
        numbersBtn.setOnClickListener {
            prefs.edit().putBoolean(KEY_NUMBERS, !numberRow).apply()
            numbersBtn.invalidate()
            buildKeys()
            avoidPanel() // el teclado crece o se encoge una fila: se recoloca si pasa a tapar el panel
        }
        val settingsBtn = SettingsIconView(ctx, appearance).apply { setOnClickListener { onOpenSettings() } }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(appearance.textColor)
            setOnClickListener { hide() }
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(appearance.barColor)
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(opacityBtn, LinearLayout.LayoutParams(dp(ICON_DP), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(numbersBtn, LinearLayout.LayoutParams(dp(ICON_DP), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(settingsBtn, LinearLayout.LayoutParams(dp(ICON_DP), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(closeBtn, LinearLayout.LayoutParams(dp(ICON_DP), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        installDrag(title)
        val slider = OpacitySliderView(
            ctx, appearance,
            getValue = { appearance.keyboardOpacity },
            setValue = { appearance.keyboardOpacity = it },
            onClose = { hideOpacitySlider() },
            onTouchStart = { main.removeCallbacks(closeSlider) },
            onTouchEnd = { scheduleSliderClose() },
        ).apply { visibility = View.GONE }
        val barHolder = FrameLayout(ctx).apply {
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(slider, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val keys = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val grip = ResizeGripView(ctx, appearance, object : ResizeGripView.Callbacks {
            override fun onResize(dx: Float, dy: Float) = resizeBy(dx)
            override fun onResizeEnd() {
                prefs.edit().putInt(KEY_W, widthPx).apply()
                avoidPanel()
            }
        }, leftIcon = ResizeGripView.LeftIcon.TOUCHPAD, onLeftIcon = { onTouchpadToggle() }, leftActive = { touchpadState() })
        val cardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(appearance.cardColor)
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            addView(barHolder, LinearLayout.LayoutParams(width, dp(BAR_DP)))
            addView(keys, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(grip, LinearLayout.LayoutParams(width, dp(FOOTER_DP)))
        }
        val container = FrameLayout(ctx).apply {
            alpha = appearance.keyboardOpacity / 100f
            addView(cardView, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(margin, margin, margin, margin)
            })
        }

        val params = WindowManager.LayoutParams(
            width + 2 * margin, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE: nunca toma el foco ni el teclado del sistema.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(24))
            y = prefs.getInt(KEY_Y, bounds.height() - dp(300) - dp(24))
        }
        try {
            wm.addView(container, params)
        } catch (t: Throwable) {
            ProbeLog.add("Teclado: no se pudo crear el overlay: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        root = container
        lp = params
        keysBox = keys
        preview = title
        card = cardView
        this.bar = barHolder
        normalBar = bar
        opacitySlider = slider
        footer = grip
        shift = Shift.OFF
        symbols = false
        buildKeys()
        refreshPreview()
        placeAwayFromPanel()
        clampToScreen()
        if (appearance.magnet) magnet?.settle(magnetPanel)
        hideSystemKeyboard()
        onVisibilityChanged?.invoke()
    }

    /** El touchpad se activó o desactivó: refresca el icono. */
    fun onTouchpadStateChanged() {
        footer?.invalidate()
    }

    /** Aplica la opacidad actual sin reconstruir el teclado. */
    fun applyAlpha() {
        root?.alpha = appearance.keyboardOpacity / 100f
    }

    private fun showOpacitySlider() {
        normalBar?.visibility = View.GONE
        opacitySlider?.visibility = View.VISIBLE
        opacitySlider?.invalidate()
        scheduleSliderClose()
    }

    private fun hideOpacitySlider() {
        main.removeCallbacks(closeSlider)
        opacitySlider?.visibility = View.GONE
        normalBar?.visibility = View.VISIBLE
    }

    /** La barra de transparencia se cierra sola tras unos segundos sin tocarla. */
    private fun scheduleSliderClose() {
        main.removeCallbacks(closeSlider)
        main.postDelayed(closeSlider, SLIDER_TIMEOUT_MS)
    }

    fun hide() {
        mover.cancel()
        main.removeCallbacks(closeSlider)
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (_: Throwable) {
        }
        root = null
        lp = null
        keysBox = null
        preview = null
        card = null
        bar = null
        normalBar = null
        opacitySlider = null
        footer = null
        passwordNode = null
        passwordBuffer.clear()
        afterEnter = false
        restoreSystemKeyboard()
        onVisibilityChanged?.invoke()
    }

    // ---------------------------------------------------------------- colocación

    /** Recoloca el teclado si tapa el panel (tras mover o redimensionar el panel, o redimensionar el teclado). */
    fun avoidPanel() {
        if (!isShown) return
        if (appearance.magnet) {
            magnet?.settle(magnetPanel)
            return
        }
        placeAwayFromPanel()
        clampToScreen()
    }

    /**
     * Al abrirse, busca la posición más cercana a la habitual donde el teclado no tape el panel del touchpad.
     * Si no hay espacio para ninguna, elige la que lo tape menos: nunca queda cubierto por completo.
     * La posición guardada no se toca: el ajuste es solo para esta apertura.
     */
    private fun placeAwayFromPanel() {
        if (appearance.magnet) return // con el imán no se repelen: se pegan
        val params = lp ?: return
        val r = root ?: return
        val panel = panelBounds() ?: return
        val screen = wm.maximumWindowMetrics.bounds
        r.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = params.width
        val h = r.measuredHeight
        val maxX = max(0, screen.width() - w)
        val maxY = max(0, screen.height() - h)
        fun at(x: Int, y: Int) = android.graphics.Point(x.coerceIn(0, maxX), y.coerceIn(0, maxY))

        val wanted = at(params.x, params.y)
        val candidates = listOf(
            wanted,
            at(panel.left - w, wanted.y), // a la izquierda del panel
            at(panel.right, wanted.y), // a la derecha
            at(wanted.x, panel.top - h), // encima
            at(wanted.x, panel.bottom), // debajo
            at(0, maxY), at(maxX, maxY), at(0, 0), at(maxX, 0), // esquinas
            at(maxX / 2, maxY), at(maxX / 2, 0), // centro inferior y superior
        )

        fun overlap(p: android.graphics.Point): Long {
            val x = max(0, min(p.x + w, panel.right) - max(p.x, panel.left)).toLong()
            val y = max(0, min(p.y + h, panel.bottom) - max(p.y, panel.top)).toLong()
            return x * y
        }

        fun distance(p: android.graphics.Point): Long {
            val dx = (p.x - wanted.x).toLong()
            val dy = (p.y - wanted.y).toLong()
            return dx * dx + dy * dy
        }

        val best = candidates.minWith(compareBy({ overlap(it) }, { distance(it) }))
        params.x = best.x
        params.y = best.y
    }

    // ---------------------------------------------------------------- tamaño

    /** Cambia el ancho arrastrando la esquina; el alto de las teclas sigue al ancho. */
    private fun resizeBy(dx: Float) {
        val params = lp ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        val newWidth = (widthPx + dx.toInt()).coerceIn(minWidth(), maxWidth(bounds.width(), params.x))
        if (newWidth == widthPx) return
        widthPx = newWidth
        applyWidth(params)
    }

    /** Tras una rotación o cambio de display: ajusta el tamaño y la posición a la pantalla. */
    fun relayout() {
        val params = lp ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        val newWidth = widthPx.coerceIn(minWidth(), maxWidth(bounds.width(), 0))
        if (newWidth != widthPx) {
            widthPx = newWidth
            applyWidth(params)
        } else {
            clampToScreen()
        }
        magnet?.reapply()
    }

    private fun applyWidth(params: WindowManager.LayoutParams) {
        val margin = dp(MARGIN_DP)
        params.width = widthPx + 2 * margin
        card?.layoutParams = FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(margin, margin, margin, margin)
        }
        bar?.layoutParams = LinearLayout.LayoutParams(widthPx, dp(BAR_DP))
        keysBox?.layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        footer?.layoutParams = LinearLayout.LayoutParams(widthPx, dp(FOOTER_DP))
        buildKeys()
        clampToScreen()
        magnet?.reapply()
    }

    private fun minWidth() = dp(MIN_WIDTH_DP)

    /** Ancho máximo: ≤ 95 % de la pantalla y sin salirse por la derecha desde [x]. */
    private fun maxWidth(screenW: Int, x: Int) =
        max(minWidth(), min((screenW * 0.95f).toInt(), screenW - x - 2 * dp(MARGIN_DP)))

    private fun keyHeightPx() = ((widthPx - dp(8)) / 10f * KEY_ASPECT).toInt()

    /** Mientras este teclado está abierto, evita que se abra el del sistema (el del TV). */
    private fun hideSystemKeyboard() {
        if (previousShowMode != null) return
        val current = softKeyboard.showMode
        val ok = softKeyboard.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN)
        if (ok) previousShowMode = current
        ProbeLog.add("Teclado del sistema oculto: ${if (ok) "sí" else "el sistema rechazó el cambio"}")
    }

    private fun restoreSystemKeyboard() {
        val previous = previousShowMode ?: return
        previousShowMode = null
        softKeyboard.setShowMode(previous)
    }

    /** Cambió el foco en alguna ventana: actualiza la vista previa con el campo nuevo. */
    /** El usuario hizo clic en un campo del TV: ya no se está "justo después de un Enter". */
    fun onRemoteFieldClicked() {
        afterEnter = false
        if (isShown) refreshPreview()
    }

    fun onRemoteFocusChanged() {
        if (isShown) refreshPreview()
    }

    // ---------------------------------------------------------------- teclas

    private sealed interface Key {
        data class Text(val value: String) : Key
        data object Backspace : Key
        data object Enter : Key
        data object ShiftKey : Key
        data object Symbols : Key
        data object Blank : Key
    }

    private fun buildKeys() {
        val box = keysBox ?: return
        box.removeAllViews()
        val upper = shift != Shift.OFF
        val rows: List<List<Pair<Key, Float>>> = if (!symbols) {
            listOf(
                "qwertyuiop".map { Key.Text(letter(it, upper)) to 1f },
                "asdfghjklñ".map { Key.Text(letter(it, upper)) to 1f },
                listOf(Key.ShiftKey to 1.5f) + "zxcvbnm".map { Key.Text(letter(it, upper)) to 1f } + listOf(Key.Backspace to 1.5f),
            )
        } else {
            listOf(
                "1234567890".map { Key.Text(it.toString()) to 1f },
                "@#$%&-+()/".map { Key.Text(it.toString()) to 1f },
                listOf(Key.Blank to 1.5f) + "*\"':;!?".map { Key.Text(it.toString()) to 1f } + listOf(Key.Backspace to 1.5f),
            )
        }
        if (numberRow && !symbols) box.addView(rowOf("1234567890".map { Key.Text(it.toString()) to 1f }))
        for (row in rows) box.addView(rowOf(row))
        box.addView(
            rowOf(
                listOf(
                    Key.Symbols to 1.5f,
                    Key.Text(",") to 1f,
                    Key.Text(" ") to 5f,
                    Key.Text(".") to 1f,
                    Key.Enter to 1.5f,
                ),
            ),
        )
    }

    private fun letter(c: Char, upper: Boolean) = if (upper) c.uppercaseChar().toString() else c.toString()

    private fun rowOf(keys: List<Pair<Key, Float>>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((key, weight) in keys) {
                addView(keyView(key), LinearLayout.LayoutParams(0, keyHeightPx(), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
        }

    private fun keyView(key: Key): View {
        val special = key !is Key.Text || key.value == " "
        val normal = if (special) appearance.specialKeyColor else appearance.keyColor
        val bg = GradientDrawable().apply {
            setColor(if (key == Key.ShiftKey && shift != Shift.OFF) appearance.pressed(normal) else normal)
            cornerRadius = dp(6).toFloat()
        }
        val label = when (key) {
            is Key.Text -> if (key.value == " ") "" else key.value
            Key.Backspace -> "⌫"
            Key.Enter -> "↵"
            Key.ShiftKey -> if (shift == Shift.LOCK) "⇪" else "⇧"
            Key.Symbols -> if (symbols) "ABC" else "?123"
            Key.Blank -> ""
        }
        return TextView(ctx).apply {
            text = label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, keyHeightPx() * KEY_TEXT_RATIO)
            gravity = Gravity.CENTER
            setTextColor(appearance.keyTextColor)
            background = bg
            if (key == Key.Blank) return@apply
            val repeatTask = object : Runnable {
                override fun run() {
                    press(key)
                    main.postDelayed(this, REPEAT_MS)
                }
            }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        bg.setColor(appearance.pressed(normal))
                        if (key == Key.Backspace) {
                            press(key)
                            main.postDelayed(repeatTask, REPEAT_DELAY_MS)
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(repeatTask)
                        bg.setColor(normal)
                        if (key != Key.Backspace && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) press(key)
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(repeatTask)
                        bg.setColor(normal)
                    }
                }
                true
            }
        }
    }

    private fun press(key: Key) {
        when (key) {
            is Key.Text -> {
                edit(startFresh = true) { text, start, end -> Edit(text.replaceRange(start, end, key.value), start + key.value.length) }
                if (shift == Shift.ONCE && key.value.firstOrNull()?.isLetter() == true) {
                    shift = Shift.OFF
                    buildKeys()
                }
            }

            Key.Backspace -> edit(startFresh = false) { text, start, end ->
                when {
                    start != end -> Edit(text.removeRange(start, end), start)
                    start > 0 -> Edit(text.removeRange(start - 1, start), start - 1)
                    else -> Edit(text, 0)
                }
            }

            Key.Enter -> {
                val node = remote.focusedInput(externalDisplayId())
                if (node == null) {
                    showMessage("No hay un campo de texto enfocado en el TV")
                } else if (remote.enter(node)) {
                    afterEnter = true
                    passwordBuffer.clear()
                    showText("", false)
                } else {
                    ProbeLog.add("Teclado: ${remote.lastDiagnosis}")
                }
            }

            Key.ShiftKey -> {
                shift = when (shift) {
                    Shift.OFF -> Shift.ONCE
                    Shift.ONCE -> Shift.LOCK
                    Shift.LOCK -> Shift.OFF
                }
                buildKeys()
            }

            Key.Symbols -> {
                symbols = !symbols
                buildKeys()
            }

            Key.Blank -> Unit
        }
    }

    private data class Edit(val text: String, val caret: Int)

    /** Lee el texto actual del campo del TV, le aplica [change] y lo escribe de vuelta. */
    private fun edit(startFresh: Boolean, change: (text: String, start: Int, end: Int) -> Edit) {
        val node = remote.focusedInput(externalDisplayId())
        if (node == null) {
            ProbeLog.add("Teclado: ${remote.lastDiagnosis}")
            showMessage("No hay un campo de texto enfocado en el TV")
            return
        }
        // Tras un Enter, escribir empieza un texto nuevo; borrar sigue operando sobre el contenido real.
        val startAfterEnter = afterEnter
        afterEnter = false
        var text: String
        var start: Int
        var end: Int
        if (node.isPassword) {
            if (passwordNode != node) {
                passwordNode = node
                passwordBuffer.clear()
            }
            text = passwordBuffer.toString()
            start = text.length
            end = start
        } else {
            node.refresh()
            text = remote.read(node)
            start = node.textSelectionStart.let { if (it < 0) text.length else it.coerceAtMost(text.length) }
            end = node.textSelectionEnd.let { if (it < 0) text.length else it.coerceAtMost(text.length) }
        }
        if (start > end) start = end.also { end = start }
        if (startAfterEnter && startFresh) {
            text = ""
            start = 0
            end = 0
        }

        val result = change(text, start, end)
        if (node.isPassword) {
            passwordBuffer.setLength(0)
            passwordBuffer.append(result.text)
        }
        if (!remote.write(node, result.text, result.caret)) {
            ProbeLog.add("Teclado: ${remote.lastDiagnosis}")
            showMessage("El campo del TV no acepta texto desde aquí")
            return
        }
        showText(result.text, node.isPassword)
    }

    // ---------------------------------------------------------------- vista previa

    private fun refreshPreview() {
        val node = remote.focusedInput(externalDisplayId())
        if (node == null) {
            showMessage("Toca un campo de texto en el TV")
        } else if (afterEnter) {
            showText("", false)
        } else {
            showText(if (node.isPassword) passwordBuffer.takeIf { passwordNode == node }?.toString().orEmpty() else remote.read(node), node.isPassword)
        }
    }

    private fun showText(text: String, password: Boolean) {
        val shown = if (password) "•".repeat(text.length) else text
        preview?.text = if (shown.isEmpty()) "(campo vacío)" else shown
    }

    private fun showMessage(message: String) {
        preview?.text = message
    }

    // ---------------------------------------------------------------- arrastrar

    private fun installDrag(handle: View) {
        var lastX = 0f
        var lastY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    lp?.let {
                        rawX = it.x
                        rawY = it.y
                    }
                    magnet?.onDragStart()
                }

                MotionEvent.ACTION_MOVE -> {
                    val params = lp ?: return@setOnTouchListener true
                    val r = root ?: return@setOnTouchListener true
                    val bounds = wm.maximumWindowMetrics.bounds
                    r.measure(
                        View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                    rawX = (rawX + (e.rawX - lastX).toInt()).coerceIn(0, max(0, bounds.width() - params.width))
                    rawY = (rawY + (e.rawY - lastY).toInt()).coerceIn(0, max(0, bounds.height() - r.measuredHeight))
                    lastX = e.rawX
                    lastY = e.rawY
                    val snapper = magnet
                    if (snapper != null) {
                        val m = dp(MARGIN_DP)
                        val p = snapper.dragPosition(magnetPanel, rawX + m, rawY + m)
                        mover.moveTo(p.x - m, p.y - m, animate = snapper.dragStateChanged)
                    } else {
                        mover.moveTo(rawX, rawY, animate = false)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lp?.let {
                    magnet?.onDragEnd(magnetPanel)
                    prefs.edit().putInt(KEY_X, it.x).putInt(KEY_Y, it.y).apply()
                }
            }
            true
        }
    }

    private fun clampToScreen() {
        val params = lp ?: return
        val r = root ?: return
        val bounds = wm.maximumWindowMetrics.bounds
        r.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        params.x = params.x.coerceIn(0, max(0, bounds.width() - params.width))
        params.y = params.y.coerceIn(0, max(0, bounds.height() - r.measuredHeight))
        try {
            wm.updateViewLayout(r, params)
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val MARGIN_DP = PanelMagnet.CARD_MARGIN_DP
        const val MAX_WIDTH_DP = 720
        const val BAR_DP = 36
        const val SLIDER_TIMEOUT_MS = 3000L
        const val FOOTER_DP = 28
        const val MIN_WIDTH_DP = 340
        const val ICON_DP = 42
        const val KEY_ASPECT = 0.675f
        const val KEY_TEXT_RATIO = 0.4f
        const val KEY_W = "kb_w"
        const val KEY_NUMBERS = "kb_numbers"
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 60L
        const val KEY_X = "kb_x"
        const val KEY_Y = "kb_y"
    }
}
