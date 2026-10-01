package dev.tasio.extendedtouchpad

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
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
        click(R.id.btn_auto_keyboard) { withService { it.toggleAutoOpenKeyboard(); refresh() } }
        click(R.id.btn_add_tile) { requestAddTile() }
        click(R.id.btn_appearance) { startActivity(Intent(this, SettingsActivity::class.java)) }
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

    /** Pide a Android (13+) agregar el mosaico "Touchpad" al centro de control. */
    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) {
            ProbeLog.add("Agrega el mosaico a mano: edita el centro de control y busca \"Touchpad\"")
            return
        }
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, TouchpadTileService::class.java),
            "Touchpad",
            Icon.createWithResource(this, R.drawable.ic_touchpad_tile),
            mainExecutor,
        ) { result ->
            ProbeLog.add(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Mosaico agregado al centro de control"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "El mosaico ya estaba en el centro de control"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "No se agregó el mosaico"
                    else -> "Resultado al agregar el mosaico: $result"
                },
            )
        }
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
        findViewById<Button>(R.id.btn_auto_keyboard).text =
            if (service?.isAutoOpenKeyboard == false) "Activar teclado automático" else "Desactivar teclado automático"
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
