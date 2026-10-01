package dev.tasio.extendedtouchpad

import android.app.Activity
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

class StatusActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            ProbeLog.add("Display conectado: id=$displayId")
            refresh()
        }

        override fun onDisplayRemoved(displayId: Int) {
            ProbeLog.add("Display desconectado: id=$displayId")
            refresh()
        }

        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        // targetSdk 36 dibuja edge-to-edge: se compensan las barras del sistema.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        click(R.id.btn_settings) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        click(R.id.btn_toggle) { withService { it.setEnabled(!it.isTouchpadEnabled) } }
        click(R.id.btn_test_tap) { withService { it.testTapCenter() } }
    }

    override fun onStart() {
        super.onStart()
        Displays.manager(this).registerDisplayListener(displayListener, null)
        ProbeLog.onChange = ::refresh
        refresh()
    }

    override fun onStop() {
        Displays.manager(this).unregisterDisplayListener(displayListener)
        ProbeLog.onChange = null
        super.onStop()
    }

    private fun click(id: Int, action: () -> Unit) =
        findViewById<Button>(id).setOnClickListener { action() }

    private fun withService(action: (TouchpadService) -> Unit) {
        val service = TouchpadService.instance
        if (service == null) {
            ProbeLog.add("El servicio de accesibilidad no está activo: actívalo en Ajustes")
        } else {
            action(service)
        }
    }

    private fun refresh() {
        val displays = Displays.manager(this).displays
        val external = Displays.external(this)
        val service = TouchpadService.instance
        findViewById<Button>(R.id.btn_toggle).text =
            if (service?.isTouchpadEnabled == false) "Activar touchpad" else "Desactivar touchpad"
        status.text = buildString {
            appendLine("Servicio de accesibilidad: ${if (TouchpadService.instance != null) "ACTIVO" else "inactivo"}")
            appendLine("Touchpad: ${if (service?.isTouchpadEnabled == true) "activado" else "desactivado"}")
            appendLine("Display externo: ${if (external != null) "detectado (id=${external.displayId})" else "no detectado"}")
            appendLine()
            displays.forEach { appendLine(Displays.describe(it)) }
        }
        log.text = ProbeLog.text()
    }
}
