package dev.tasio.extendedtouchpad

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Dispositivos de salida de audio: cuál se usa ahora, de qué tipo es y cómo llamarlo. Solo APIs públicas de Android. */
object AudioOutputs {
    private val EXCLUDED = setOf(
        AudioDeviceInfo.TYPE_TELEPHONY,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        AudioDeviceInfo.TYPE_BUS,
        AudioDeviceInfo.TYPE_FM,
        AudioDeviceInfo.TYPE_IP,
    )

    private val WIRELESS = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_HEARING_AID)
        add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        if (Build.VERSION.SDK_INT >= 33) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
    }

    // Nombres que informan los cables HDMI/DisplayPort cuando no dicen el modelo del monitor.
    private val GENERIC_NAMES = setOf("hdmi", "displayport", "dp", "hdmi_arc", "hdmi arc", "hdmi earc", "usb", "usb device", "unknown")

    private fun manager(context: Context) = context.getSystemService(AudioManager::class.java)

    private fun looksLikeHardwareNode(name: String): Boolean {
        val n = name.lowercase()
        return listOf(":", ",", "_", "qcom", "msm", "soc").any { it in n }
    }

    /** Dispositivos de salida disponibles ahora. */
    fun available(context: Context): List<AudioDeviceInfo> =
        manager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink && it.type !in EXCLUDED }

    /** Dispositivo por el que sale ahora el audio multimedia (el que usarían YouTube, Netflix, etc.), o null si no se sabe. */
    fun current(context: Context): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 33) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            manager(context).getAudioDevicesForAttributes(attributes).firstOrNull { it.type !in EXCLUDED }?.let { return it }
        }
        return available(context).firstOrNull()
    }

    fun isWireless(device: AudioDeviceInfo?) = device != null && device.type in WIRELESS

    /** Icono del mosaico: onda y cablecito, u onda y símbolo de Bluetooth. */
    fun iconRes(device: AudioDeviceInfo?) = if (isWireless(device)) R.drawable.ic_audio_bluetooth else R.drawable.ic_audio_wired

    /**
     * Nombre para mostrar: el real si el sistema lo ofrece (Bluetooth, monitor que informa su modelo) y, si no, uno
     * genérico según el tipo de conexión.
     */
    fun displayName(device: AudioDeviceInfo?): String {
        if (device == null) return "Salida de audio"
        val product = device.productName?.toString()?.trim().orEmpty()
        // Los nombres de nodos del hardware (p. ej. "soc:qcom,msm-ext-disp") no son un nombre de dispositivo real.
        val real = product.isNotEmpty() && product.lowercase() !in GENERIC_NAMES && !looksLikeHardwareNode(product)
        // Un dispositivo Bluetooth siempre informa su nombre de verdad; basta con que no esté vacío.
        val bluetoothName = product.isNotEmpty()
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Parlante interno"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares con cable"
            // Android clasifica también el DisplayPort como HDMI.
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> if (real) product else "Monitor HDMI"
            AudioDeviceInfo.TYPE_USB_HEADSET -> if (real) product else "Auriculares USB"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> if (real) product else "Dispositivo USB"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Salida de línea"
            in WIRELESS -> if (bluetoothName) product else "Dispositivo Bluetooth"
            else -> if (real) product else "Salida de audio"
        }
    }
}
