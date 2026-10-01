package dev.tasio.extendedtouchpad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Ajustes de apariencia. Los cambios se escriben en las preferencias y el servicio los aplica en vivo. */
class SettingsActivity : Activity() {
    private lateinit var appearance: Appearance
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = Appearance(getSharedPreferences("touchpad", MODE_PRIVATE))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            // targetSdk 36 dibuja edge-to-edge: se compensan las barras del sistema.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        build()
    }

    private fun build() {
        content.removeAllViews()
        title("Apariencia")
        note("Los cambios se ven al instante en el panel, el teclado y el cursor si el touchpad está activo.")

        section("Paneles")
        val magnetSwitch = android.widget.Switch(this).apply {
            text = "Imantar los paneles"
            isChecked = appearance.magnet
            setOnCheckedChangeListener { _, checked -> appearance.setMagnet(checked) }
        }
        content.addView(magnetSwitch)
        note("Cuando el panel del touchpad y el teclado están cerca, se pegan por el borde, centrados. Si cambias el tamaño de uno, el otro se vuelve a centrar. Arrastra uno lejos para separarlos.")

        section("Transparencia del panel")
        opacitySlider(
            get = { appearance.padOpacity },
            set = { appearance.padOpacity = it },
        )
        section("Transparencia del teclado")
        opacitySlider(
            get = { appearance.keyboardOpacity },
            set = { appearance.keyboardOpacity = it },
        )

        section("Tema")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val light = RadioButton(this).apply { text = "Claro"; id = View.generateViewId() }
        val dark = RadioButton(this).apply { text = "Oscuro"; id = View.generateViewId() }
        group.addView(light)
        group.addView(dark)
        group.check(if (appearance.dark) dark.id else light.id)
        group.setOnCheckedChangeListener { _, checked -> appearance.setDark(checked == dark.id) }
        content.addView(group)

        section("Color de acento")
        swatches(Appearance.ACCENTS, appearance.accent) { appearance.setAccent(it) }

        section("Cursor en el TV")
        label("Forma").also { content.addView(it) }
        shapes()
        label("Tamaño").also { content.addView(it) }
        val sizeLabel = label("")
        val size = SeekBar(this).apply {
            max = Appearance.MAX_CURSOR_DP - Appearance.MIN_CURSOR_DP
            progress = appearance.cursorDp - Appearance.MIN_CURSOR_DP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    sizeLabel.text = "${p + Appearance.MIN_CURSOR_DP} dp"
                    if (fromUser) appearance.setCursorDp(p + Appearance.MIN_CURSOR_DP)
                }

                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        sizeLabel.text = "${appearance.cursorDp} dp"
        content.addView(size)
        content.addView(sizeLabel)
        label("Color").also { content.addView(it) }
        swatches(Appearance.CURSOR_COLORS, appearance.cursorColor) { appearance.setCursorColor(it) }

        content.addView(
            Button(this).apply {
                text = "Restablecer valores"
                setOnClickListener {
                    appearance.resetAll()
                    build()
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        // La transparencia también se cambia desde el panel y el teclado: se refrescan los deslizadores.
        build()
    }

    private fun opacitySlider(get: () -> Int, set: (Int) -> Unit) {
        val valueLabel = label("")
        val bar = SeekBar(this).apply {
            max = 100 - Appearance.MIN_OPACITY
            progress = get() - Appearance.MIN_OPACITY
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    valueLabel.text = "${p + Appearance.MIN_OPACITY} %"
                    if (fromUser) set(p + Appearance.MIN_OPACITY)
                }

                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        valueLabel.text = "${get()} %"
        content.addView(bar)
        content.addView(valueLabel)
    }

    private fun shapes() {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (shape in CursorShape.entries) {
            val selected = shape == appearance.cursorShape
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener {
                    appearance.setCursorShape(shape)
                    build()
                }
            }
            tile.addView(
                ShapeSample(this, shape, appearance.cursorColor, appearance.cursorOutline, selected),
                LinearLayout.LayoutParams(dp(64), dp(64)),
            )
            tile.addView(TextView(this).apply {
                text = shape.label
                textSize = 12f
                gravity = Gravity.CENTER
            })
            row.addView(tile, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), dp(8), dp(6)) })
        }
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        content.addView(scroller)
    }

    /** Muestra de una forma de cursor sobre un fondo gris medio, con un borde si está seleccionada. */
    private class ShapeSample(
        context: android.content.Context,
        private val shape: CursorShape,
        private val color: Int,
        private val outline: Int,
        private val selected: Boolean,
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val frame = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f * d
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawColor(Color.rgb(120, 124, 130))
            frame.color = if (selected) Color.rgb(33, 150, 243) else Color.rgb(90, 94, 100)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), frame)
            // El cursor se dibuja en un cuadrado centrado de 40 dp.
            val side = 40f * d
            canvas.save()
            canvas.translate((width - side) / 2f, (height - side) / 2f)
            CursorGlyph.draw(canvas, side, d, shape, color, outline)
            canvas.restore()
        }
    }

    private fun swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (color in colors) {
            val isSelected = color == selected
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(if (isSelected) dp(4) else dp(1), if (isSelected) Color.rgb(33, 33, 33) else Color.GRAY)
                }
                setOnClickListener {
                    onPick(color)
                    build()
                }
            }
            row.addView(swatch, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, dp(6), dp(12), dp(6)) })
        }
        content.addView(row)
    }

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        textSize = 22f
    })

    private fun note(text: String) = content.addView(TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(4), 0, dp(8))
    })

    private fun section(text: String) = content.addView(TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    })

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
