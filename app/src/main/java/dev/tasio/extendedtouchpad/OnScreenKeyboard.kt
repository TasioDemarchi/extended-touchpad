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

    private var shift = Shift.OFF
    private var symbols = false

    // En campos de contraseña Android no entrega el texto real: se lleva aquí lo escrito.
    private var passwordNode: AccessibilityNodeInfo? = null
    private val passwordBuffer = StringBuilder()

    val isShown get() = root != null

    fun toggle() = if (isShown) hide() else show()

    fun show() {
        if (root != null) return
        val bounds = wm.maximumWindowMetrics.bounds
        val margin = dp(MARGIN_DP)
        val width = min((bounds.width() * 0.62f).toInt(), dp(MAX_WIDTH_DP))

        val title = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.argb(200, 0, 0, 0))
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(200, 0, 0, 0))
            setOnClickListener { hide() }
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(176, 176, 176))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(closeBtn, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        installDrag(title)

        val keys = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(214, 214, 214))
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
            addView(bar, LinearLayout.LayoutParams(width, dp(BAR_DP)))
            addView(keys, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val container = FrameLayout(ctx).apply {
            addView(card, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
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
        shift = Shift.OFF
        symbols = false
        buildKeys()
        refreshPreview()
        clampToScreen()
        hideSystemKeyboard()
    }

    fun hide() {
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (_: Throwable) {
        }
        root = null
        lp = null
        keysBox = null
        preview = null
        passwordNode = null
        passwordBuffer.clear()
        restoreSystemKeyboard()
    }

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
                addView(keyView(key), LinearLayout.LayoutParams(0, dp(KEY_DP), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
        }

    private fun keyView(key: Key): View {
        val special = key !is Key.Text || key.value == " "
        val normal = if (special) Color.rgb(189, 189, 189) else Color.rgb(250, 250, 250)
        val bg = GradientDrawable().apply {
            setColor(if (key == Key.ShiftKey && shift != Shift.OFF) Color.rgb(144, 202, 249) else normal)
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
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
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
                        bg.setColor(Color.rgb(144, 202, 249))
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
                edit { text, start, end -> Edit(text.replaceRange(start, end, key.value), start + key.value.length) }
                if (shift == Shift.ONCE && key.value.firstOrNull()?.isLetter() == true) {
                    shift = Shift.OFF
                    buildKeys()
                }
            }

            Key.Backspace -> edit { text, start, end ->
                when {
                    start != end -> Edit(text.removeRange(start, end), start)
                    start > 0 -> Edit(text.removeRange(start - 1, start), start - 1)
                    else -> Edit(text, 0)
                }
            }

            Key.Enter -> {
                val node = remote.focusedInput(externalDisplayId())
                if (node == null) showMessage("No hay un campo de texto enfocado en el TV") else if (!remote.enter(node)) {
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
    private fun edit(change: (text: String, start: Int, end: Int) -> Edit) {
        val node = remote.focusedInput(externalDisplayId())
        if (node == null) {
            ProbeLog.add("Teclado: ${remote.lastDiagnosis}")
            showMessage("No hay un campo de texto enfocado en el TV")
            return
        }
        val text: String
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
                }

                MotionEvent.ACTION_MOVE -> {
                    val params = lp ?: return@setOnTouchListener true
                    params.x += (e.rawX - lastX).toInt()
                    params.y += (e.rawY - lastY).toInt()
                    lastX = e.rawX
                    lastY = e.rawY
                    clampToScreen()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lp?.let {
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
        const val MARGIN_DP = 10
        const val MAX_WIDTH_DP = 720
        const val BAR_DP = 36
        const val KEY_DP = 48
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 60L
        const val KEY_X = "kb_x"
        const val KEY_Y = "kb_y"
    }
}
