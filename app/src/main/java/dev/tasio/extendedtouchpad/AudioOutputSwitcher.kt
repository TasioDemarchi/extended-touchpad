package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Cambia el audio al siguiente dispositivo de salida. Android no deja a una app normal elegir por dónde sale el audio
 * de otras apps, pero sí abrir el selector del sistema (el diálogo «Dispositivos disponibles para salida de audio»).
 * Este componente abre ese diálogo, lee sus filas con el servicio de accesibilidad, toca la del siguiente dispositivo
 * como lo haría el usuario y cierra el diálogo. El cambio real lo hace el sistema.
 */
class AudioOutputSwitcher(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private class Row(val name: String, val node: AccessibilityNodeInfo, val bounds: Rect)

    private class Dialog(val current: Row, val others: List<Row>, val done: AccessibilityNodeInfo?)

    private var busy = false

    fun cycle() {
        if (busy) return
        busy = true
        val before = AudioOutputs.current(service)
        ProbeLog.add("Audio: abriendo el selector del sistema (actual: ${AudioOutputs.displayName(before)})")
        openSystemDialog(service)
        poll(attempts = 30, delayMs = 100, probe = ::findDialog) { dialog ->
            if (dialog == null) {
                finish("No se pudo abrir el selector de audio")
                return@poll
            }
            val target = nextTarget(dialog)
            if (target == null) {
                ProbeLog.add("Audio: no hay otro dispositivo (${dialog.current.name})")
                close(dialog)
                finish("No hay otro dispositivo de salida")
                return@poll
            }
            ProbeLog.add("Audio: ${dialog.current.name} -> ${target.name}")
            target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // El sistema tarda un instante en aplicar el cambio; se cierra el diálogo y se espera a ver el nuevo dispositivo.
            handler.postDelayed({
                close(dialog)
                poll(attempts = 15, delayMs = 120, probe = {
                    AudioOutputs.current(service)?.takeIf { it.id != before?.id }
                }) { changed ->
                    val now = changed ?: AudioOutputs.current(service)
                    if (changed != null) {
                        finish("Audio: ${AudioOutputs.displayName(now)}")
                    } else {
                        ProbeLog.add("Audio: el sistema no cambió el dispositivo")
                        finish("No se pudo cambiar el audio")
                    }
                }
            }, APPLY_DELAY_MS)
        }
    }

    private fun finish(message: String) {
        ProbeLog.add("Audio: resultado «$message» (ahora: ${AudioOutputs.displayName(AudioOutputs.current(service))})")
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        AudioOutputTileService.requestRefresh(service)
        busy = false
    }

    /** Reintenta [probe] cada [delayMs] hasta que devuelva algo o se agoten los [attempts]. */
    private fun <T> poll(attempts: Int, delayMs: Long, probe: () -> T?, onResult: (T?) -> Unit) {
        val result = probe()
        if (result != null || attempts <= 1) {
            onResult(result)
        } else {
            handler.postDelayed({ poll(attempts - 1, delayMs, probe, onResult) }, delayMs)
        }
    }

    // ---------------------------------------------------------------- lectura del diálogo

    /** Busca, entre las ventanas de SystemUI de la tablet, la que tiene la lista de dispositivos de salida. */
    private fun findDialog(): Dialog? {
        val windows = service.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY) ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != SYSTEM_UI) continue
            parse(root)?.let { return it }
        }
        return null
    }

    private class Summary(val texts: MutableList<String> = mutableListOf(), var hasSeekBar: Boolean = false)

    private fun summarize(node: AccessibilityNodeInfo, into: Summary = Summary()): Summary {
        if (node.className?.toString()?.endsWith("SeekBar") == true) into.hasSeekBar = true
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { into.texts += it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { summarize(it, into) }
        return into
    }

    /**
     * Estructura del diálogo (sin identificadores de vista, así que se reconoce por forma): una fila tocable con el
     * control de volumen es el dispositivo actual; debajo, una fila tocable con un único texto por cada otro
     * dispositivo; la última puede ser «Conectar un dispositivo», que no es un dispositivo; y un botón «Listo».
     */
    private fun parse(root: AccessibilityNodeInfo): Dialog? {
        var current: Row? = null
        val candidates = mutableListOf<Row>()
        var done: AccessibilityNodeInfo? = null

        fun visit(node: AccessibilityNodeInfo) {
            val isButton = node.className?.toString()?.endsWith("Button") == true
            if (isButton && node.isClickable && !node.text.isNullOrEmpty()) done = node
            if (node.isClickable && !isButton) {
                val summary = summarize(node)
                if (summary.texts.size == 1) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    val row = Row(summary.texts[0], node, bounds)
                    if (summary.hasSeekBar) {
                        current = row
                        return
                    }
                    candidates += row
                    return
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)

        val now = current ?: return null
        // Solo cuentan las filas por debajo del dispositivo actual (así se descarta la cabecera del diálogo).
        val below = candidates.filter { it.bounds.top >= now.bounds.bottom }.sortedBy { it.bounds.top }.toMutableList()
        if (below.isNotEmpty() && CONNECT_ROW.containsMatchIn(below.last().name)) below.removeAt(below.lastIndex)
        return Dialog(now, below, done)
    }

    // ---------------------------------------------------------------- orden del ciclo

    /**
     * El diálogo pone el dispositivo actual arriba y reordena tras cada cambio, así que el ciclo usa un orden propio y
     * estable: el de primera aparición, que se recuerda. Devuelve la fila del siguiente dispositivo.
     */
    private fun nextTarget(dialog: Dialog): Row? {
        val names = (listOf(dialog.current.name) + dialog.others.map { it.name }).distinct()
        val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val order = (prefs.getString(KEY_ORDER, "").orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }).toMutableList()
        names.forEach { if (it !in order) order += it }
        prefs.edit().putString(KEY_ORDER, order.joinToString(SEPARATOR)).apply()

        val ring = order.filter { it in names }
        if (ring.size < 2) return null
        val next = ring[(ring.indexOf(dialog.current.name) + 1) % ring.size]
        return dialog.others.firstOrNull { it.name == next }
    }

    private fun close(dialog: Dialog) {
        val done = dialog.done
        if (done != null) done.performAction(AccessibilityNodeInfo.ACTION_CLICK) else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val ACTION_OUTPUT_DIALOG = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
        private const val APPLY_DELAY_MS = 600L
        private const val PREFS = "touchpad"
        private const val KEY_ORDER = "audio_order"
        private const val SEPARATOR = "\u0001"

        // «Conectar un dispositivo» (y equivalentes en otros idiomas): la fila que no es un dispositivo.
        private val CONNECT_ROW = Regex("conect|connect|pair|empar|associ|verbind|colleg|koppel|anslut", RegexOption.IGNORE_CASE)

        /** Abre el selector de salida de audio del sistema (el mismo diálogo que usa «Audio Output Switcher»). */
        fun openSystemDialog(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_OUTPUT_DIALOG).setPackage(SYSTEM_UI).putExtra("package_name", context.packageName),
            )
        }
    }
}
