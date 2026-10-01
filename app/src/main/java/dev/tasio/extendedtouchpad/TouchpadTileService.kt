package dev.tasio.extendedtouchpad

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Mosaico de ajustes rápidos (centro de control) para activar y desactivar el touchpad. */
class TouchpadTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val service = TouchpadService.instance
        if (service == null) {
            // El servicio de accesibilidad está apagado: lleva a sus ajustes para activarlo.
            openAccessibilitySettings()
            return
        }
        service.setEnabled(!service.isTouchpadEnabled)
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

            !service.isTouchpadEnabled -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Desactivado"
            }

            Displays.external(this) == null -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Esperando pantalla externa"
            }

            else -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Activado"
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
        /** Pide al sistema que vuelva a consultar el estado del mosaico (si está agregado). */
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, TouchpadTileService::class.java))
        }
    }
}
