package dev.tasio.extendedtouchpad

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Pantalla única de la app: guía de primeros pasos (permisos), tutorial de los mosaicos, ajustes del touchpad y del
 * teclado, apariencia de los paneles y diagnóstico. Los cambios de apariencia se escriben en las preferencias y el
 * servicio los aplica en vivo.
 */
class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var appearance: Appearance

    private lateinit var chipsHolder: LinearLayout
    private lateinit var setupHolder: LinearLayout
    private lateinit var keepAliveHolder: LinearLayout
    private lateinit var controlsHolder: LinearLayout
    private lateinit var appearanceHolder: LinearLayout
    private lateinit var diagnosticsHolder: LinearLayout

    private var showGuide = false
    private var showGrantHelp = false
    private var showDiagnostics = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDynamic()
        override fun onDisplayRemoved(displayId: Int) = refreshDynamic()
        override fun onDisplayChanged(displayId: Int) = refreshDynamic()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        appearance = Appearance(getSharedPreferences("touchpad", MODE_PRIVATE))

        val content = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isVerticalScrollBarEnabled = false
            addView(content)
            // targetSdk 36 dibuja edge-to-edge: se compensan las barras del sistema.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)

        chipsHolder = ui.horizontal().apply { gravity = Gravity.START }
        setupHolder = ui.card()
        keepAliveHolder = ui.card()
        controlsHolder = ui.card()
        appearanceHolder = ui.vertical()
        diagnosticsHolder = ui.card()

        content.addView(header())
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsHolder)
        }, ui.params(ui.match, ui.wrap, top = 16))
        content.addView(setupHolder, gap())
        content.addView(keepAliveHolder, gap())
        content.addView(tilesCard(), gap())
        content.addView(controlsHolder, gap())
        content.addView(appearanceHolder, gap())
        content.addView(diagnosticsHolder, gap())
        content.addView(
            ui.text("Extended Touchpad ${versionName()}", 12f, Palette.TEXT3).apply { gravity = Gravity.CENTER },
            ui.params(ui.match, ui.wrap, top = 24),
        )

        renderAppearance()
        refreshDynamic()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        ProbeLog.onChange = ::refreshDynamic
        refreshDynamic()
        reviveServiceIfNeeded()
    }

    /**
     * Si el servicio está apagado y se concedió el permiso por ADB, lo reactiva (p. ej. tras forzar la detención de la
     * app). Si figura como activado pero no se conecta, lo vuelve a enlazar.
     */
    private fun reviveServiceIfNeeded() {
        if (serviceActive() || !ServiceKeeper.hasPermission(this) || !ServiceKeeper.isEnabled(this)) return
        if (ServiceKeeper.ensureEnabled(this)) {
            window.decorView.postDelayed({
                if (!serviceActive() && !isDestroyed) ServiceKeeper.rebind(this, android.os.Handler(mainLooper)) { refreshDynamic() }
            }, 2500)
        }
        refreshDynamic()
    }

    override fun onResume() {
        super.onResume()
        // El servicio puede haberse activado en Ajustes, y la transparencia también se cambia desde los paneles.
        TouchpadService.instance?.refreshOverlayLayer() // por si se concedió el permiso «Mostrar sobre otras apps»
        refreshDynamic()
        renderAppearance()
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        ProbeLog.onChange = null
        super.onStop()
    }

    private fun gap() = ui.params(ui.match, ui.wrap, top = 14)

    private fun versionName(): String = try {
        "v" + packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: Exception) {
        ""
    }

    // ---------------------------------------------------------------- cabecera

    private fun header() = ui.vertical().apply {
        addView(ui.text("Extended Touchpad", 28f, Palette.TEXT, bold = true))
        addView(ui.text("Touchpad y teclado para tu pantalla externa", 14f, Palette.TEXT2), ui.params(ui.match, ui.wrap, top = 6))
    }

    // ---------------------------------------------------------------- partes que cambian solas

    /** Estado del servicio y de la pantalla externa: se vuelve a pintar al volver a la app o al conectar un display. */
    private fun refreshDynamic() {
        renderChips()
        renderSetup()
        renderKeepAlive()
        renderControls()
        renderDiagnostics()
    }

    private fun serviceActive() = TouchpadService.instance != null

    private fun renderChips() {
        chipsHolder.removeAllViews()
        val external = Displays.external(this)
        chipsHolder.addView(
            if (serviceActive()) ui.chip("Servicio activo", Palette.TEAL) else ui.chip("Servicio inactivo", Palette.AMBER),
            ui.params(ui.wrap, ui.wrap, end = 8),
        )
        chipsHolder.addView(
            if (external != null) {
                val (w, h) = Displays.size(external)
                ui.chip("Pantalla externa · ${w}×$h", Palette.TEAL)
            } else {
                ui.chip("Sin pantalla externa", Palette.TEXT3)
            },
        )
    }

    private fun renderSetup() {
        val serviceOk = serviceActive()
        val displayOk = Displays.external(this) != null
        setupHolder.removeAllViews()

        setupHolder.addView(ui.horizontal().apply {
            addView(ui.cardTitle("Primeros pasos", if (serviceOk) "La app está lista para usarse." else "Un permiso y listo."), ui.params(0, ui.wrap, weight = 1f))
            if (serviceOk) addView(ui.chip("Listo", Palette.TEAL))
        })

        if (serviceOk && !showGuide) {
            setupHolder.addView(ui.secondaryButton("Ver la guía") { showGuide = true; renderSetup() }, ui.params(ui.match, ui.wrap, top = 16))
            return
        }

        setupHolder.addView(
            ui.stepRow(
                1, "Activa el servicio de accesibilidad",
                "Permite dibujar el cursor en la pantalla externa y enviarle toques y texto. Es el único permiso que necesita la app.",
                done = serviceOk,
            ),
            ui.params(ui.match, ui.wrap, top = 18),
        )
        if (!serviceOk) {
            setupHolder.addView(
                ui.primaryButton("Abrir ajustes de accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                ui.params(ui.match, ui.wrap, top = 12, start = 42),
            )
            setupHolder.addView(
                ui.vertical().apply {
                    background = ui.shape(Palette.CARD_ALT, 14, Palette.STROKE)
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                    addView(ui.text("¿El interruptor sale bloqueado?", 13.5f, Palette.AMBER, bold = true))
                    addView(
                        ui.text(
                            "Android 13+ restringe las apps instaladas a mano. Abre la información de la app, toca ⋮ (arriba a la derecha) " +
                                "y elige «Permitir ajustes restringidos». Después vuelve a activar el servicio.",
                            12.5f, Palette.TEXT2,
                        ),
                        ui.params(ui.match, ui.wrap, top = 5),
                    )
                    addView(ui.secondaryButton("Abrir información de la app") {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }, ui.params(ui.match, ui.wrap, top = 10))
                },
                ui.params(ui.match, ui.wrap, top = 12, start = 42),
            )
        }

        setupHolder.addView(
            ui.stepRow(
                2, "Conecta una pantalla externa",
                "Usa el modo de pantalla extendida, no espejo. En cuanto se detecte, el touchpad aparece solo en la tablet.",
                done = displayOk,
            ),
            ui.params(ui.match, ui.wrap, top = 20),
        )

        if (serviceOk) {
            setupHolder.addView(ui.secondaryButton("Ocultar la guía") { showGuide = false; renderSetup() }, ui.params(ui.match, ui.wrap, top = 18))
        }
    }

    /** Reactivación automática del servicio tras una detención forzada: explicación y comando, o interruptor si ya hay permiso. */
    private fun renderKeepAlive() {
        keepAliveHolder.removeAllViews()
        val granted = ServiceKeeper.hasPermission(this)
        keepAliveHolder.addView(ui.horizontal().apply {
            addView(
                ui.cardTitle(
                    "Mantener el servicio activo",
                    if (granted) "Si Android apaga el servicio, la app lo reactiva sola." else "Android apaga el servicio si fuerzas la detención de la app. Un ajuste único desde una PC evita tener que reactivarlo a mano.",
                ),
                ui.params(0, ui.wrap, weight = 1f),
            )
            if (granted) addView(ui.chip("Permiso concedido", Palette.TEAL))
        })

        if (granted) {
            keepAliveHolder.addView(
                ui.switchRow(
                    "Reactivar el servicio automáticamente",
                    "Al abrir la app, al desplegar un mosaico del centro de control y al encender la tablet.",
                    ServiceKeeper.isEnabled(this),
                ) { ServiceKeeper.setEnabled(this, it) },
                ui.params(ui.match, ui.wrap, top = 16),
            )
            keepAliveHolder.addView(
                ui.secondaryButton(if (showGrantHelp) "Ocultar cómo se concede" else "Ver cómo se concede") {
                    showGrantHelp = !showGrantHelp
                    renderKeepAlive()
                },
                ui.params(ui.match, ui.wrap, top = 16),
            )
            if (showGrantHelp) addGrantInstructions()
            return
        }
        addGrantInstructions()
    }

    /** Pasos para conceder el permiso desde una PC (también útil tras reinstalar la app, que lo pierde). */
    private fun addGrantInstructions() {

        keepAliveHolder.addView(
            ui.stepRow(
                1, "Prepara una computadora",
                "Esto se hace desde una PC, no desde la tablet. Necesitas las herramientas ADB (platform-tools de Android) instaladas, " +
                    "la depuración USB activada en las opciones de desarrollador de la tablet y la tablet conectada por USB.",
            ),
            ui.params(ui.match, ui.wrap, top = 18),
        )
        keepAliveHolder.addView(
            ui.stepRow(
                2, "Ejecuta estos comandos en la terminal de la PC",
                "Pégalos juntos, una sola vez. Primero reinician ADB y esperan a la tablet, porque a veces pierde la conexión. " +
                    "Si la tablet pide «¿Permitir la depuración USB?», acéptalo. Si sale bien, no imprime nada.",
            ),
            ui.params(ui.match, ui.wrap, top = 16),
        )
        keepAliveHolder.addView(
            ui.mono(ServiceKeeper.GRANT_COMMAND, 11.5f, Palette.TEXT).apply {
                background = ui.shape(Palette.CARD_ALT, 12, Palette.STROKE)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                setTextIsSelectable(true)
            },
            ui.params(ui.match, ui.wrap, top = 12, start = 42),
        )
        keepAliveHolder.addView(
            ui.secondaryButton("Copiar los comandos") {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("adb", ServiceKeeper.GRANT_COMMAND))
                toast("Comandos copiados: pégalos en la terminal de la PC")
            },
            ui.params(ui.match, ui.wrap, top = 12, start = 42),
        )
        keepAliveHolder.addView(
            ui.text(
                "Si la PC no reconoce «adb», usa la ruta completa a la carpeta platform-tools (por ejemplo ~/Android/Sdk/platform-tools/adb).",
                12.5f, Palette.TEXT3,
            ),
            ui.params(ui.match, ui.wrap, top = 12, start = 42),
        )
        keepAliveHolder.addView(
            ui.text("Es opcional: sin esto, tras forzar la detención hay que volver a activar el servicio en Ajustes de accesibilidad.", 12.5f, Palette.TEXT3),
            ui.params(ui.match, ui.wrap, top = 14),
        )
    }

    private fun renderControls() {
        val service = TouchpadService.instance
        val active = service != null
        controlsHolder.removeAllViews()
        controlsHolder.addView(ui.cardTitle("Touchpad y teclado", if (active) null else "Activa el servicio de accesibilidad para usar estos controles."))

        fun row(view: View, top: Int = 0) = controlsHolder.addView(view, ui.params(ui.match, ui.wrap, top = top))
        fun divide() = controlsHolder.addView(ui.divider(), ui.params(ui.match, 1, top = 14, bottom = 14))

        controlsHolder.addView(View(this), ui.params(ui.match, 0, top = 8))
        row(
            ui.switchRow("Touchpad activado", "Muestra el panel y el cursor cuando hay una pantalla externa.", service?.isTouchpadEnabled == true, active) {
                TouchpadService.instance?.setEnabled(it)
            },
        )
        divide()
        row(
            ui.switchRow("Teclado automático", "Abre el teclado al enfocar un campo de texto en la pantalla externa, con el touchpad o con un mouse.", service?.isAutoOpenKeyboard != false, active) {
                if (TouchpadService.instance?.isAutoOpenKeyboard != it) TouchpadService.instance?.toggleAutoOpenKeyboard()
            },
        )
        divide()
        val canOverlay = OverlayLayer.hasPermission(this)
        row(
            ui.switchRow(
                "Paneles por debajo del centro de control",
                if (canOverlay) {
                    "El panel y el teclado quedan detrás de las notificaciones y del centro de control cuando los bajas."
                } else {
                    "Requiere el permiso «Mostrar sobre otras apps». Sin él, los paneles se ven por encima del centro de control."
                },
                OverlayLayer.isPreferred(this),
            ) { on ->
                OverlayLayer.setPreferred(this, on)
                if (on && !OverlayLayer.hasPermission(this)) openOverlayPermission()
                refreshDynamic()
            },
        )
        if (OverlayLayer.isPreferred(this) && !canOverlay) {
            row(ui.secondaryButton("Conceder el permiso «Mostrar sobre otras apps»") { openOverlayPermission() }, top = 12)
        }
        divide()
        row(
            ui.switchRow("Imantar los paneles", "Cerca uno del otro, el panel y el teclado se pegan por el centro de cada lado y se vuelven a centrar al cambiar de tamaño.", appearance.magnet) {
                appearance.setMagnet(it)
            },
        )
    }

    private fun openOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun renderDiagnostics() {
        diagnosticsHolder.removeAllViews()
        diagnosticsHolder.addView(ui.horizontal().apply {
            addView(ui.cardTitle("Diagnóstico", "Displays detectados y registro, por si algo falla."), ui.params(0, ui.wrap, weight = 1f))
            addView(ui.text(if (showDiagnostics) "▾" else "▸", 18f, Palette.TEXT2))
            setOnClickListener {
                showDiagnostics = !showDiagnostics
                renderDiagnostics()
            }
        })
        if (!showDiagnostics) return

        val displays = getSystemService(DisplayManager::class.java).displays
        diagnosticsHolder.addView(ui.sectionLabel("Displays"), ui.params(ui.match, ui.wrap, top = 18))
        diagnosticsHolder.addView(
            ui.mono(displays.joinToString("\n") { Displays.describe(it) }, 11.5f),
            ui.params(ui.match, ui.wrap, top = 8),
        )
        diagnosticsHolder.addView(ui.sectionLabel("Registro"), ui.params(ui.match, ui.wrap, top = 18))
        diagnosticsHolder.addView(
            ui.mono(ProbeLog.text().ifEmpty { "(vacío)" }, 11f, Palette.TEXT3),
            ui.params(ui.match, ui.wrap, top = 8),
        )
        diagnosticsHolder.addView(
            ui.secondaryButton("Tap de prueba en el centro de la pantalla externa") {
                val service = TouchpadService.instance
                if (service == null) ProbeLog.add("El servicio de accesibilidad no está activo") else service.testTapCenter()
            },
            ui.params(ui.match, ui.wrap, top = 16),
        )
    }

    // ---------------------------------------------------------------- tutorial de los mosaicos

    private fun tilesCard() = ui.card().apply {
        addView(ui.cardTitle("Atajos en el centro de control", "Activa el touchpad, abre el teclado o cambia la salida de audio sin entrar a la app."))
        addView(tilePreview(), ui.params(ui.match, ui.wrap, top = 16))
        addView(ui.stepRow(1, "Agrégalos", "Toca un botón de abajo y acepta el aviso de Android."), ui.params(ui.match, ui.wrap, top = 18))
        addView(
            ui.stepRow(2, "¿No aparece el aviso?", "Baja el centro de control, toca el lápiz (editar) y arrastra «Touchpad», «Teclado» y «Audio» a la zona de arriba."),
            ui.params(ui.match, ui.wrap, top = 16),
        )
        addView(
            ui.stepRow(3, "Úsalos", "«Touchpad» lo activa o desactiva. «Teclado» lo abre o lo cierra, aunque el touchpad esté apagado. «Audio» pasa al siguiente dispositivo de salida (parlante interno, monitor, auriculares, Bluetooth…): cada toque, uno más, y vuelve a empezar. Muestra un instante el selector de audio del sistema mientras lo cambia."),
            ui.params(ui.match, ui.wrap, top = 16),
        )
        addView(ui.horizontal().apply {
            addView(ui.primaryButton("Agregar Touchpad") { requestAddTile(TouchpadTileService::class.java, "Touchpad", R.drawable.ic_touchpad_tile) }, ui.params(0, ui.wrap, weight = 1f, end = 6))
            addView(ui.primaryButton("Agregar Teclado") { requestAddTile(KeyboardTileService::class.java, "Teclado", R.drawable.ic_keyboard_tile) }, ui.params(0, ui.wrap, weight = 1f, start = 6))
        }, ui.params(ui.match, ui.wrap, top = 20))
        addView(
            ui.primaryButton("Agregar Audio") { requestAddTile(AudioOutputTileService::class.java, "Audio", R.drawable.ic_audio_output) },
            ui.params(ui.match, ui.wrap, top = 12),
        )
    }

    /** Maqueta de los tres mosaicos tal como se ven en el centro de control. */
    private fun tilePreview() = ui.horizontal().apply {
        background = ui.shape(Palette.CARD_ALT, 16, Palette.STROKE)
        setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        fun tile(icon: Int, name: String, state: String, on: Boolean) = ui.horizontal().apply {
            background = ui.shape(if (on) Palette.VIOLET_DEEP else Palette.STROKE, 18)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            addView(
                ImageView(this@SettingsActivity).apply {
                    setImageResource(icon)
                    setColorFilter(if (on) Color.WHITE else Palette.TEXT2)
                },
                ui.params(ui.dp(22), ui.dp(22), end = 10),
            )
            addView(ui.vertical().apply {
                addView(ui.text(name, 13f, if (on) Color.WHITE else Palette.TEXT, bold = true))
                addView(ui.text(state, 11f, if (on) Color.argb(200, 255, 255, 255) else Palette.TEXT3))
            })
        }
        addView(tile(R.drawable.ic_touchpad_tile, "Touchpad", "Activado", true), ui.params(0, ui.wrap, weight = 1f, end = 6))
        addView(tile(R.drawable.ic_keyboard_tile, "Teclado", "Cerrado", false), ui.params(0, ui.wrap, weight = 1f, start = 6, end = 6))
        addView(tile(R.drawable.ic_audio_output, "Audio", "Parlante interno", false), ui.params(0, ui.wrap, weight = 1f, start = 6))
    }

    /** Pide a Android (13+) agregar un mosaico al centro de control. */
    private fun requestAddTile(service: Class<*>, label: String, iconRes: Int) {
        if (Build.VERSION.SDK_INT < 33) {
            toast("Agrégalo a mano: edita el centro de control y busca «$label»")
            return
        }
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, service),
            label,
            Icon.createWithResource(this, iconRes),
            mainExecutor,
        ) { result ->
            toast(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Mosaico «$label» agregado"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "El mosaico «$label» ya estaba agregado"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "No se agregó el mosaico «$label»"
                    else -> "No se pudo agregar el mosaico («$label»)"
                },
            )
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- apariencia

    /** Reconstruye la apariencia sin que el scroll salte: la altura anterior se conserva mientras se vuelve a pintar. */
    private fun renderAppearance() {
        val previous = appearanceHolder.height
        appearanceHolder.minimumHeight = previous
        appearanceHolder.removeAllViews()

        appearanceHolder.addView(ui.sectionLabel("Apariencia"), ui.params(ui.match, ui.wrap, bottom = 2, start = 4))

        appearanceHolder.addView(ui.card().apply {
            addView(ui.cardTitle("Transparencia"))
            addView(
                ui.sliderRow("Panel del touchpad", Appearance.MIN_OPACITY, 100, appearance.padOpacity, { "$it %" }) { appearance.padOpacity = it },
                ui.params(ui.match, ui.wrap, top = 16),
            )
            addView(
                ui.sliderRow("Teclado", Appearance.MIN_OPACITY, 100, appearance.keyboardOpacity, { "$it %" }) { appearance.keyboardOpacity = it },
                ui.params(ui.match, ui.wrap, top = 18),
            )
        }, ui.params(ui.match, ui.wrap, top = 10))

        appearanceHolder.addView(ui.card().apply {
            addView(ui.cardTitle("Tema y color de los paneles"))
            addView(themeSegments(), ui.params(ui.match, ui.wrap, top = 16))
            addView(ui.text("Color de acento", 15f), ui.params(ui.match, ui.wrap, top = 20))
            addView(swatches(Appearance.ACCENTS, appearance.accent) { appearance.setAccent(it); renderAppearance() }, ui.params(ui.match, ui.wrap, top = 8))
        }, gap())

        appearanceHolder.addView(ui.card().apply {
            addView(ui.cardTitle("Cursor en la pantalla externa"))
            addView(ui.text("Forma", 15f), ui.params(ui.match, ui.wrap, top = 16))
            addView(shapes(), ui.params(ui.match, ui.wrap, top = 8))
            addView(
                ui.sliderRow("Tamaño", Appearance.MIN_CURSOR_DP, Appearance.MAX_CURSOR_DP, appearance.cursorDp, { "$it dp" }) { appearance.setCursorDp(it) },
                ui.params(ui.match, ui.wrap, top = 20),
            )
            addView(ui.text("Color", 15f), ui.params(ui.match, ui.wrap, top = 20))
            addView(swatches(Appearance.CURSOR_COLORS, appearance.cursorColor) { appearance.setCursorColor(it); renderAppearance() }, ui.params(ui.match, ui.wrap, top = 8))
        }, gap())

        appearanceHolder.addView(
            ui.secondaryButton("Restablecer la apariencia") {
                appearance.resetAll()
                renderAppearance()
            },
            gap(),
        )
        appearanceHolder.post { appearanceHolder.minimumHeight = 0 }
    }

    private fun themeSegments(): LinearLayout = ui.horizontal().apply {
        background = ui.shape(Palette.CARD_ALT, 14, Palette.STROKE)
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        fun segment(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this@SettingsActivity).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = ui.dp(40)
            setTextColor(if (selected) Color.WHITE else Palette.TEXT2)
            if (selected) background = ui.shape(Palette.VIOLET_DEEP, 11)
            setOnClickListener { onClick() }
        }
        addView(segment("Claro", !appearance.dark) { appearance.setDark(false); renderAppearance() }, ui.params(0, ui.wrap, weight = 1f))
        addView(segment("Oscuro", appearance.dark) { appearance.setDark(true); renderAppearance() }, ui.params(0, ui.wrap, weight = 1f))
    }

    private fun swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit): LinearLayout = ui.horizontal().apply {
        for (color in colors) {
            val isSelected = color == selected
            val swatch = View(this@SettingsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(if (isSelected) ui.dp(3) else ui.dp(1), if (isSelected) Palette.TEXT else Palette.STROKE)
                }
                setOnClickListener { onPick(color) }
            }
            addView(swatch, ui.params(ui.dp(40), ui.dp(40), end = 12, top = 4, bottom = 4))
        }
    }

    private fun shapes(): View {
        val row = ui.horizontal()
        for (shape in CursorShape.entries) {
            val selected = shape == appearance.cursorShape
            val tile = ui.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener {
                    appearance.setCursorShape(shape)
                    renderAppearance()
                }
            }
            tile.addView(ShapeSample(this, shape, appearance.cursorColor, appearance.cursorOutline, selected), ui.params(ui.dp(64), ui.dp(64)))
            tile.addView(ui.text(shape.label, 12f, if (selected) Palette.TEXT else Palette.TEXT3), ui.params(ui.wrap, ui.wrap, top = 6))
            row.addView(tile, ui.params(ui.dp(76), ui.wrap, end = 8))
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** Muestra de una forma de cursor sobre un fondo gris medio, con un borde violeta si está seleccionada. */
    private class ShapeSample(
        context: android.content.Context,
        private val shape: CursorShape,
        private val color: Int,
        private val outline: Int,
        private val selected: Boolean,
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * d
        }
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 124, 134) }
        private val box = android.graphics.RectF()

        override fun onDraw(canvas: Canvas) {
            val r = 14f * d
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, r, r, back)
            frame.color = if (selected) Palette.VIOLET else Palette.STROKE
            box.inset(1.5f * d, 1.5f * d)
            canvas.drawRoundRect(box, r, r, frame)
            // El cursor se dibuja en un cuadrado centrado de 40 dp.
            val side = 40f * d
            canvas.save()
            canvas.translate((width - side) / 2f, (height - side) / 2f)
            CursorGlyph.draw(canvas, side, d, shape, color, outline)
            canvas.restore()
        }
    }
}
