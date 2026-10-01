package dev.tasio.extendedtouchpad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Formas de cursor disponibles. [centered]: el punto de clic está en el centro y no en la punta. */
enum class CursorShape(val label: String, val centered: Boolean) {
    CLASSIC("Clásico", false),
    OUTLINE("Contorno", false),
    MODERN("Moderno", false),
    DOT("Punto", true),
    CROSSHAIR("Mira", true),
    ;

    companion object {
        fun fromName(name: String?): CursorShape = entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

/** Dibujo de los cursores; lo usan el cursor del TV y las muestras de la pantalla de ajustes. */
object CursorGlyph {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    /** Margen entre el borde de la vista y la punta, para que el contorno no se recorte. */
    fun inset(density: Float) = 1.5f * density

    /** Punto de clic dentro de la vista cuadrada de lado [size], en px. */
    fun hotspot(shape: CursorShape, size: Float, density: Float): Pair<Float, Float> =
        if (shape.centered) size / 2f to size / 2f else inset(density) to inset(density)

    /** Dibuja [shape] en una vista cuadrada de lado [size]. [base] es el color principal; [outline], el contorno de contraste. */
    fun draw(canvas: Canvas, size: Float, density: Float, shape: CursorShape, base: Int, outline: Int) {
        when (shape) {
            CursorShape.CLASSIC -> {
                classicPath(size, density)
                fill(canvas, base)
                stroke(canvas, outline, 1.5f * density)
            }

            CursorShape.OUTLINE -> {
                classicPath(size, density)
                fill(canvas, Color.argb(90, 0, 0, 0))
                stroke(canvas, outline, 3.6f * density)
                stroke(canvas, base, 1.8f * density)
            }

            CursorShape.MODERN -> modern(canvas, size, density, base, outline)

            CursorShape.DOT -> {
                val c = size / 2f
                fillPaint.color = Color.argb(70, Color.red(base), Color.green(base), Color.blue(base))
                canvas.drawCircle(c, c, size * 0.44f, fillPaint)
                fillPaint.color = base
                canvas.drawCircle(c, c, size * 0.24f, fillPaint)
                strokePaint.color = outline
                strokePaint.strokeWidth = 1.5f * density
                canvas.drawCircle(c, c, size * 0.24f, strokePaint)
            }

            CursorShape.CROSSHAIR -> {
                val c = size / 2f
                val a = size * 0.1f
                val b = size * 0.9f
                val gap = size * 0.1f
                for ((color, width) in listOf(outline to 4.4f * density, base to 2f * density)) {
                    strokePaint.color = color
                    strokePaint.strokeWidth = width
                    canvas.drawLine(a, c, c - gap, c, strokePaint)
                    canvas.drawLine(c + gap, c, b, c, strokePaint)
                    canvas.drawLine(c, a, c, c - gap, strokePaint)
                    canvas.drawLine(c, c + gap, c, b, strokePaint)
                }
            }
        }
    }

    private fun classicPath(size: Float, density: Float) {
        val i = inset(density)
        val w = size
        val h = size
        path.rewind()
        path.moveTo(i, i)
        path.lineTo(i, h * 0.82f)
        path.lineTo(w * 0.24f, h * 0.64f)
        path.lineTo(w * 0.40f, h * 0.97f)
        path.lineTo(w * 0.54f, h * 0.90f)
        path.lineTo(w * 0.38f, h * 0.58f)
        path.lineTo(w * 0.66f, h * 0.58f)
        path.close()
    }

    /** Cuña de dos tonos: la cara izquierda más oscura que la derecha. */
    private fun modern(canvas: Canvas, size: Float, density: Float, base: Int, outline: Int) {
        val i = inset(density)
        val s = size - 2 * i
        val tipX = i
        val tipY = i
        val bottomX = i
        val bottomY = i + s * 0.95f
        val rightX = i + s * 0.63f
        val rightY = i + s * 0.73f
        val notchX = i + s * 0.20f
        val notchY = i + s * 0.665f

        // Cara izquierda (más oscura).
        path.rewind()
        path.moveTo(tipX, tipY)
        path.lineTo(bottomX, bottomY)
        path.lineTo(notchX, notchY)
        path.close()
        fillPaint.color = shade(base, 0.78f)
        canvas.drawPath(path, fillPaint)

        // Cara derecha.
        path.rewind()
        path.moveTo(tipX, tipY)
        path.lineTo(notchX, notchY)
        path.lineTo(rightX, rightY)
        path.close()
        fillPaint.color = base
        canvas.drawPath(path, fillPaint)

        // Contorno de toda la silueta.
        path.rewind()
        path.moveTo(tipX, tipY)
        path.lineTo(rightX, rightY)
        path.lineTo(notchX, notchY)
        path.lineTo(bottomX, bottomY)
        path.close()
        stroke(canvas, outline, 1.2f * density)
    }

    private fun fill(canvas: Canvas, color: Int) {
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    private fun stroke(canvas: Canvas, color: Int, width: Float) {
        strokePaint.color = color
        strokePaint.strokeWidth = width
        canvas.drawPath(path, strokePaint)
    }

    private fun shade(color: Int, factor: Float): Int =
        Color.rgb((Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())
}
