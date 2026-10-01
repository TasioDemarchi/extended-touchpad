package dev.tasio.extendedtouchpad

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Mosaico de ajustes rápidos para abrir y cerrar el teclado, independiente del panel. */
class KeyboardTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val service = TouchpadService.instance
        if (service == null) {
            openAccessibilitySettings()
            return
        }
        service.toggleKeyboard()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val service = TouchpadService.instance
        when {
            service == null -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Servicio apagado"
            }

            service.isKeyboardShown -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Abierto"
            }

            !service.isKeyboardAvailable() -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Sin pantalla externa"
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Cerrado"
            }
        }
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, KeyboardTileService::class.java))
        }
    }
}
