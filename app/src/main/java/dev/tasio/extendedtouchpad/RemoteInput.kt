package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/** Acceso al campo de texto enfocado en el display externo, vía accesibilidad. */
class RemoteInput(private val service: AccessibilityService) {
    // Último campo editable que recibió el foco en el display externo. Cuando la barra de la tablet
    // toma el foco del sistema, la ventana del TV deja de ser la enfocada y findFocus ya no lo ve.
    private var lastFocused: AccessibilityNodeInfo? = null

    /** Último motivo por el que no se encontró un campo (para el registro de diagnóstico). */
    var lastDiagnosis = ""
        private set

    /**
     * Llamar con el origen de cada evento de foco o clic. Si es un campo editable del display externo
     * lo guarda y devuelve true.
     */
    fun remember(source: AccessibilityNodeInfo?, displayId: Int): Boolean {
        if (source == null || !source.isEditable) return false
        if (!isOnDisplay(source, displayId)) return false
        lastFocused = source
        return true
    }

    fun focusedInput(displayId: Int): AccessibilityNodeInfo? {
        val windows = service.windowsOnAllDisplays.get(displayId)
        if (windows == null || windows.isEmpty()) {
            lastDiagnosis = "sin ventanas visibles en el display $displayId (¿permiso de contenido de ventanas? reactiva el servicio)"
            return null
        }
        var roots = 0
        for (window in windows) {
            val root = window.root ?: continue
            roots++
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node != null && node.isEditable) {
                lastFocused = node
                return node
            }
        }
        val cached = lastFocused
        if (cached != null && cached.refresh() && cached.isEditable && isOnDisplay(cached, displayId)) {
            return cached
        }
        lastFocused = null
        lastDiagnosis = "${windows.size} ventanas en el display $displayId, $roots con contenido, " +
            "sin campo con foco ni campo recordado"
        return null
    }

    fun read(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        return node.text?.toString().orEmpty()
    }

    /** Pone [text] en el campo y deja el cursor en [caret] (solo se toca la selección si no es el final). */
    fun write(node: AccessibilityNodeInfo, text: CharSequence, caret: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            lastDiagnosis = "ACTION_SET_TEXT rechazado por ${node.className} (${node.viewIdResourceName})"
        } else if (caret != text.length) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    /** Equivalente a la tecla Enter / acción del teclado (buscar, ir, enviar). */
    fun enter(node: AccessibilityNodeInfo): Boolean {
        val ok = node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        if (!ok) lastDiagnosis = "ACTION_IME_ENTER rechazado por ${node.className}"
        return ok
    }

    private fun isOnDisplay(node: AccessibilityNodeInfo, displayId: Int): Boolean =
        service.windowsOnAllDisplays.get(displayId)?.any { it.id == node.windowId } == true
}
