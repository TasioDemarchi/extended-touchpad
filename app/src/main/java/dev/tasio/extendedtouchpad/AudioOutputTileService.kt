package dev.tasio.extendedtouchpad

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Mosaico «Audio»: cada toque pasa al siguiente dispositivo de salida (parlante interno, monitor, auriculares,
 * Bluetooth…), dando la vuelta. Muestra el dispositivo actual con un icono de cable o de Bluetooth.
 */
class AudioOutputTileService : TileService() {
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    override fun onStartListening() {
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
        refresh()
    }

    override fun onStopListening() {
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioCallback)
    }

    override fun onClick() {
        val service = TouchpadService.instance
        if (service == null) {
            // Sin el servicio de accesibilidad no se puede tocar el diálogo por el usuario: se abre para que elija a mano.
            Toast.makeText(this, "Activa el servicio de accesibilidad para cambiar con un toque", Toast.LENGTH_LONG).show()
            AudioOutputSwitcher.openSystemDialog(this)
            return
        }
        service.cycleAudioOutput()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val device = AudioOutputs.current(this)
        ProbeLog.add("Mosaico Audio: dispositivo=${AudioOutputs.displayName(device)} tipo=${device?.type} inalámbrico=${AudioOutputs.isWireless(device)}")
        tile.label = "Audio"
        tile.subtitle = AudioOutputs.displayName(device)
        tile.icon = Icon.createWithResource(this, AudioOutputs.iconRes(device))
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    companion object {
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, AudioOutputTileService::class.java))
        }
    }
}
