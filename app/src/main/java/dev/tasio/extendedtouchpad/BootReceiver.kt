package dev.tasio.extendedtouchpad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al arrancar el sistema, se asegura de que el servicio de accesibilidad siga activado (si hay permiso para ello). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ServiceKeeper.ensureEnabled(context)
    }
}
