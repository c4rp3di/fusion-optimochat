package com.optimo.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Dibuja un QR (matriz de [QrCodigo]) en blanco y negro con zona de silencio, sea cual sea el tema. Sin compilar. */
class QrVista(contexto: Context) : View(contexto) {
    var matriz: Array<BooleanArray>? = null
        set(v) { field = v; invalidate() }

    private val pincel = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    override fun onMeasure(anchoSpec: Int, altoSpec: Int) {
        val ancho = MeasureSpec.getSize(anchoSpec)
        val lado = if (ancho > 0) minOf(ancho, (resources.displayMetrics.density * 320).toInt()) else (resources.displayMetrics.density * 280).toInt()
        setMeasuredDimension(lado, lado)
    }

    override fun onDraw(c: Canvas) {
        pincel.color = Color.WHITE
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pincel)
        val m = matriz ?: return
        val total = m.size + 8                      // 4 modulos de silencio por lado
        val esc = (minOf(width, height) / total).coerceAtLeast(1)
        val ox = (width - esc * total) / 2 + 4 * esc
        val oy = (height - esc * total) / 2 + 4 * esc
        pincel.color = Color.BLACK
        for (y in m.indices) for (x in m.indices) {
            if (m[y][x]) c.drawRect((ox + x * esc).toFloat(), (oy + y * esc).toFloat(), (ox + (x + 1) * esc).toFloat(), (oy + (y + 1) * esc).toFloat(), pincel)
        }
    }
}
