package dev.tasio.extendedtouchpad

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Reactiva el servicio de accesibilidad cuando Android lo apaga (por ejemplo, al forzar la detención de la app).
 * Solo funciona si el usuario concedió una vez, por ADB, el permiso WRITE_SECURE_SETTINGS:
 *
 *     adb shell pm grant dev.tasio.extendedtouchpad android.permission.WRITE_SECURE_SETTINGS
 *
 * Sin ese permiso no hace nada. Se invoca al abrir la app, al desplegar un mosaico del centro de control y al
 * arrancar el sistema: tras una detención forzada la app no puede ejecutar código por sí sola hasta entonces.
 */
object ServiceKeeper {
    /**
     * Se ejecuta en una PC con la tablet conectada por USB. Reinicia ADB y espera a la tablet antes de conceder el
     * permiso, porque el servidor de ADB suele perder el dispositivo si pasa tiempo entre un comando y otro.
     */
    const val GRANT_COMMAND = "adb kill-server\n" +
        "adb start-server\n" +
        "adb wait-for-device shell pm grant dev.tasio.extendedtouchpad android.permission.WRITE_SECURE_SETTINGS"

    private const val PREFS = "touchpad"
    private const val KEY_ENABLED = "keep_service_alive"

    private fun component(context: Context) = ComponentName(context, TouchpadService::class.java)

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** El usuario puede apagar la reactivación automática desde la app (por defecto, activa). */
    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun enabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotEmpty() }.orEmpty()

    /** ¿Figura el servicio en la lista de servicios de accesibilidad activados del sistema? */
    fun isListedAsEnabled(context: Context): Boolean {
        val self = component(context)
        return enabledList(context).any { ComponentName.unflattenFromString(it) == self }
    }

    /** Añade el servicio a la lista de activados si falta. Devuelve true si el servicio queda activado en los ajustes. */
    fun ensureEnabled(context: Context): Boolean {
        if (!hasPermission(context) || !isEnabled(context)) return false
        return try {
            if (!isListedAsEnabled(context)) {
                val flat = component(context).flattenToString()
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (enabledList(context) + flat).joinToString(":"))
                ProbeLog.add("Servicio de accesibilidad reactivado automáticamente")
            }
            if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            true
        } catch (e: SecurityException) {
            ProbeLog.add("No se pudo reactivar el servicio: ${e.message}")
            false
        }
    }

    /**
     * Si el servicio figura como activado pero no llega a conectarse, se quita y se vuelve a poner para que Android lo
     * vuelva a enlazar. [onDone] se invoca tras la segunda escritura.
     */
    fun rebind(context: Context, handler: android.os.Handler, onDone: () -> Unit = {}) {
        if (!hasPermission(context) || !isEnabled(context)) return
        try {
            val self = component(context)
            val others = enabledList(context).filter { ComponentName.unflattenFromString(it) != self }
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            handler.postDelayed({
                ensureEnabled(context)
                onDone()
            }, 400)
        } catch (e: SecurityException) {
            ProbeLog.add("No se pudo volver a enlazar el servicio: ${e.message}")
        }
    }
}
