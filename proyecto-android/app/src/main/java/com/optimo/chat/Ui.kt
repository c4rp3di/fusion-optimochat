package com.optimo.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Ayudas de interfaz sin XML (Android, sin compilar). */
object Ui {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun raiz(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(c, 16)
        setPadding(p, p, p, p)
    }

    fun titulo(c: Context, t: String) = TextView(c).apply {
        text = t; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(c, 8))
    }

    fun texto(c: Context, t: String, tam: Float = 14f) = TextView(c).apply {
        text = t; textSize = tam
        setPadding(0, dp(c, 4), 0, dp(c, 4))
    }

    fun boton(c: Context, t: String, accion: () -> Unit) = Button(c).apply {
        text = t; isAllCaps = false
        setOnClickListener { accion() }
    }

    fun campo(c: Context, pista: String, tipo: Int, multilinea: Boolean = false) = EditText(c).apply {
        hint = pista; inputType = tipo; textSize = 14f
        if (!multilinea) setSingleLine() else minLines = 3
    }

    fun ancho() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /**
     * Pregunta, desde un hilo de fondo, si el codigo de 6 digitos coincide con el del otro movil. Bloquea hasta que la
     * persona responde. Si la pantalla se cierra, cuenta como que no.
     */
    fun confirmarCodigo(a: Activity, codigo: String, nombrePar: String): Boolean {
        val respuesta = AtomicBoolean(false)
        val espera = CountDownLatch(1)
        a.runOnUiThread {
            if (a.isFinishing || a.isDestroyed) { espera.countDown(); return@runOnUiThread }
            val grupo = codigo.chunked(3).joinToString(" ")
            AlertDialog.Builder(a)
                .setTitle("Comprueba el código")
                .setMessage("$nombrePar debe ver exactamente este código en su pantalla:\n\n$grupo\n\n" +
                    "Si coincide, confirma. Si no, cancela: alguien podría haberse interpuesto.")
                .setCancelable(false)
                .setPositiveButton("Coincide") { _, _ -> respuesta.set(true); espera.countDown() }
                .setNegativeButton("No coincide") { _, _ -> espera.countDown() }
                .show()
        }
        espera.await()
        return respuesta.get()
    }
}
