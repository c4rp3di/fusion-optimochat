package com.optimo.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Pantalla principal: contactos emparejados y accesos a invitar, aceptar una invitacion y pruebas de red. Sin compilar. */
class ContactosActivity : Activity() {

    private lateinit var lista: LinearLayout
    private lateinit var aviso: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = Ui.raiz(this)
        raiz.addView(Ui.titulo(this, "OptimoChat"))

        aviso = Ui.texto(this, "").apply { visibility = View.GONE; setTextColor(0xFFB00020.toInt()) }
        raiz.addView(aviso)

        raiz.addView(Ui.boton(this, "Invitar a alguien") { startActivity(Intent(this, InvitarActivity::class.java)) }, Ui.ancho())
        raiz.addView(Ui.boton(this, "Aceptar una invitación") { startActivity(Intent(this, AceptarActivity::class.java)) }, Ui.ancho())

        raiz.addView(Ui.texto(this, "Contactos", 16f).apply { setPadding(0, Ui.dp(this@ContactosActivity, 12), 0, 0) })
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(ScrollView(this).apply { addView(lista) }, LinearLayout.LayoutParams(-1, 0, 1f))

        raiz.addView(Ui.boton(this, "Pruebas de red (fase 0)") { startActivity(Intent(this, MainActivity::class.java)) }, Ui.ancho())
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val resultado = try {
                val ilegibles = Servicios.contactosIlegibles(this)
                Pair(Servicios.contactos(this).listar(), ilegibles)
            } catch (e: Exception) { null }
            runOnUiThread { if (!isDestroyed) mostrar(resultado) }
        }.start()
    }

    private fun mostrar(r: Pair<List<Contacto>, Boolean>?) {
        lista.removeAllViews()
        if (r == null) { aviso.text = "No se pudo abrir la lista de contactos."; aviso.visibility = View.VISIBLE; return }
        if (r.second) {
            aviso.text = "La lista de contactos no se puede leer (clave perdida o archivo alterado). No se ha modificado."
            aviso.visibility = View.VISIBLE
        } else aviso.visibility = View.GONE
        if (r.first.isEmpty()) {
            lista.addView(Ui.texto(this, "Aún no tienes contactos. Invita a alguien o acepta su invitación."))
            return
        }
        for (c in r.first) {
            lista.addView(Ui.boton(this, "${c.nombre}\n${Identidad.huellaTexto(c.huella).take(19)}…") { detalle(c) }, Ui.ancho())
        }
    }

    private fun detalle(c: Contacto) {
        val fecha = DateFormat.getDateInstance().format(Date(c.creadoMs))
        AlertDialog.Builder(this)
            .setTitle(c.nombre)
            .setMessage("Emparejado el $fecha.\n\nHuella de su clave (puedes compararla con la que ve en su móvil):\n\n${Identidad.huellaTexto(c.huella)}\n\n" +
                "La conversación llegará en la siguiente fase.")
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("Borrar contacto") { _, _ ->
                AlertDialog.Builder(this).setTitle("¿Borrar a ${c.nombre}?")
                    .setMessage("Se quitará de la lista. Para volver a hablar tendréis que emparejaros otra vez.")
                    .setPositiveButton("Borrar") { _, _ ->
                        Thread {
                            try { Servicios.contactos(this).borrar(c.id) } catch (_: Exception) {}
                            runOnUiThread { onResume() }
                        }.start()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
            .show()
    }
}
