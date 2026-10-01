package dev.tasio.extendedtouchpad

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.view.Display
import android.view.WindowManager
import android.widget.ImageView

/**
 * «Foto congelada» de la pantalla de la tablet: se saca una captura y se muestra a pantalla completa, por encima de
 * todo, mientras el selector de audio del sistema se abre, se toca y se cierra por debajo. Así el diálogo no se ve.
 * La capa no recibe toques. Si la captura falla (o la API no existe) no se muestra nada y el cambio sigue igual.
 */
class ScreenFreeze(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private var view: ImageView? = null
    private val wm = service.getSystemService(WindowManager::class.java)
    private val failsafe = Runnable { hide() }

    /** Congela la pantalla y, cuando está lista (o ha fallado), invoca [onReady]. */
    fun show(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT < 30 || view != null) {
            onReady()
            return
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            screenshot.hardwareBuffer.close()
                            if (bitmap != null) attach(bitmap)
                        } catch (t: Throwable) {
                            ProbeLog.add("Audio: no se pudo congelar la pantalla (${t.javaClass.simpleName})")
                        }
                        onReady()
                    }

                    override fun onFailure(errorCode: Int) {
                        ProbeLog.add("Audio: captura de pantalla fallida (código $errorCode); se verá el selector")
                        onReady()
                    }
                },
            )
        } catch (t: Throwable) {
            ProbeLog.add("Audio: sin captura de pantalla (${t.javaClass.simpleName}); se verá el selector")
            onReady()
        }
    }

    private fun attach(bitmap: Bitmap) {
        val image = ImageView(service).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        wm.addView(image, params)
        view = image
        // Seguro por si algo se queda colgado: la foto nunca se queda en pantalla más de unos segundos.
        handler.postDelayed(failsafe, FAILSAFE_MS)
    }

    fun hide() {
        handler.removeCallbacks(failsafe)
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val FAILSAFE_MS = 6000L
    }
}
