package dev.tasio.extendedtouchpad

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Registro en memoria de lo que pasa en las pruebas de la Fase 1, para verlo en la pantalla de estado. */
object ProbeLog {
    private const val TAG = "ExtTouchpad"
    private const val MAX_LINES = 80

    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var onChange: (() -> Unit)? = null

    fun add(message: String) {
        Log.i(TAG, message)
        synchronized(lines) {
            lines.addFirst("${clock.format(Date())}  $message")
            while (lines.size > MAX_LINES) lines.removeLast()
        }
        main.post { onChange?.invoke() }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
