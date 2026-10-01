package dev.tasio.extendedtouchpad

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Paleta de la app: grises azulados y violetas oscuros. */
object Palette {
    val BG = Color.parseColor("#0F1117")
    val CARD = Color.parseColor("#171A23")
    val CARD_ALT = Color.parseColor("#202433")
    val STROKE = Color.parseColor("#2A2F40")
    val TEXT = Color.parseColor("#E8EAF2")
    val TEXT2 = Color.parseColor("#9CA3B8")
    val TEXT3 = Color.parseColor("#6B7289")
    val VIOLET = Color.parseColor("#9A8CF8")
    val VIOLET_DEEP = Color.parseColor("#5B4BC4")
    val BLUE = Color.parseColor("#6E9BF2")
    val TEAL = Color.parseColor("#55BBA8")
    val AMBER = Color.parseColor("#DDA84A")
}

/** Constructores de las piezas de la pantalla de configuración (minimalista, oscura). */
class Ui(val ctx: Context) {
    private val d = ctx.resources.displayMetrics.density

    fun dp(v: Int) = (v * d).toInt()

    fun shape(color: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    private fun ripple(base: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), base, null)

    fun params(width: Int, height: Int, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

    val match = LinearLayout.LayoutParams.MATCH_PARENT
    val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

    fun text(text: CharSequence, sizeSp: Float, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

    fun mono(text: CharSequence, sizeSp: Float, color: Int = Palette.TEXT2) =
        text(text, sizeSp, color).apply { typeface = Typeface.MONOSPACE }

    fun vertical(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Tarjeta: superficie ligeramente más clara que el fondo, con un borde sutil. */
    fun card(): LinearLayout = vertical().apply {
        background = shape(Palette.CARD, 20, Palette.STROKE)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    fun cardTitle(title: String, subtitle: String? = null): LinearLayout = vertical().apply {
        addView(text(title, 17f, Palette.TEXT, bold = true))
        if (subtitle != null) addView(text(subtitle, 13f, Palette.TEXT2), params(match, wrap, top = 4))
    }

    fun sectionLabel(text: String) = text(text, 12f, Palette.TEXT3, bold = true).apply {
        letterSpacing = 0.08f
        this.text = text.uppercase()
    }

    fun divider(): View = View(ctx).apply { setBackgroundColor(Palette.STROKE) }

    /** Botón principal: violeta profundo. */
    fun primaryButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(46)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = ripple(shape(Palette.VIOLET_DEEP, 14))
        setOnClickListener { onClick() }
    }

    /** Botón secundario: gris azulado con borde. */
    fun secondaryButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
        minHeight = dp(46)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = ripple(shape(Palette.CARD_ALT, 14, Palette.STROKE))
        setOnClickListener { onClick() }
    }

    /** Pastilla de estado: un punto de color y una etiqueta. */
    fun chip(label: String, color: Int) = horizontal().apply {
        background = shape(Palette.CARD_ALT, 20, Palette.STROKE)
        setPadding(dp(12), dp(7), dp(14), dp(7))
        addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }, params(dp(8), dp(8), end = 8))
        addView(text(label, 12.5f, Palette.TEXT2))
    }

    /** Fila con interruptor: título, descripción y el control a la derecha. */
    fun switchRow(title: String, description: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): LinearLayout =
        horizontal().apply {
            val texts = vertical().apply {
                addView(text(title, 15f, if (enabled) Palette.TEXT else Palette.TEXT3))
                if (description != null) addView(text(description, 12.5f, Palette.TEXT3), params(match, wrap, top = 3))
            }
            addView(texts, params(0, wrap, weight = 1f, end = 12))
            addView(
                Switch(ctx).apply {
                    isChecked = checked
                    isEnabled = enabled
                    val on = intArrayOf(android.R.attr.state_checked)
                    val off = intArrayOf()
                    thumbTintList = ColorStateList(arrayOf(on, off), intArrayOf(Palette.VIOLET, Palette.TEXT3))
                    trackTintList = ColorStateList(arrayOf(on, off), intArrayOf(Color.argb(110, 154, 140, 248), Palette.STROKE))
                    setOnCheckedChangeListener { _, value -> onChange(value) }
                },
            )
        }

    /** Fila con deslizador: título, valor a la derecha y la barra debajo. */
    fun sliderRow(title: String, min: Int, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit): LinearLayout =
        vertical().apply {
            val valueLabel = text(format(value), 13f, Palette.VIOLET, bold = true)
            addView(
                horizontal().apply {
                    addView(text(title, 15f), params(0, wrap, weight = 1f))
                    addView(valueLabel)
                },
            )
            addView(
                SeekBar(ctx).apply {
                    this.max = max - min
                    progress = value - min
                    progressTintList = ColorStateList.valueOf(Palette.VIOLET)
                    progressBackgroundTintList = ColorStateList.valueOf(Palette.STROKE)
                    thumbTintList = ColorStateList.valueOf(Palette.VIOLET)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                            valueLabel.text = format(p + min)
                            if (fromUser) onChange(p + min)
                        }

                        override fun onStartTrackingTouch(sb: SeekBar) = Unit
                        override fun onStopTrackingTouch(sb: SeekBar) = Unit
                    })
                },
                params(match, wrap, top = 6),
            )
        }

    /** Paso numerado de una guía. Con [done] muestra una marca en vez del número. */
    fun stepRow(number: Int, title: String, description: String?, done: Boolean = false): LinearLayout = horizontal().apply {
        gravity = Gravity.TOP
        val badge = TextView(ctx).apply {
            text = if (done) "✓" else number.toString()
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (done) Palette.BG else Palette.VIOLET)
            background = if (done) shape(Palette.TEAL, 14) else shape(Palette.CARD_ALT, 14, Palette.VIOLET_DEEP)
        }
        addView(badge, params(dp(28), dp(28), end = 14))
        addView(
            vertical().apply {
                addView(text(title, 15f, Palette.TEXT, bold = true))
                if (description != null) addView(text(description, 13f, Palette.TEXT2), params(match, wrap, top = 4))
            },
            params(0, wrap, weight = 1f),
        )
    }
}
