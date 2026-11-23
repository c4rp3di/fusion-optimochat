package com.optimo.chat

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Aceptar una invitacion: llega por el enlace optimo://invitar (la camara del sistema abre el QR aqui) o pegada a mano.
 * Muestra de quien es, y al conectar pide comparar el codigo de 6 digitos. Sin compilar.
 */
class AceptarActivity : Activity() {

    private lateinit var campoEnlace: EditText
    private lateinit var campoNombre: EditText
    private lateinit var detalle: TextView
    private lateinit var estado: TextView
    private lateinit var botonConectar: android.widget.Button

    private var invitado: Emparejamiento.Invitado? = null
    private var dht: Dht? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val raiz = Ui.raiz(this)
        raiz.addView(Ui.titulo(this, "Aceptar una invitación"))
        raiz.addView(Ui.texto(this, "Enlace de invitación"))
        campoEnlace = Ui.campo(this, "optimo://invitar?...", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, true)
        raiz.addView(campoEnlace, Ui.ancho())
        raiz.addView(Ui.boton(this, "Pegar del portapapeles") { pegar() }, Ui.ancho())
        detalle = Ui.texto(this, "")
        raiz.addView(detalle)
        raiz.addView(Ui.texto(this, "Tu nombre (lo verá tu contacto)"))
        campoNombre = Ui.campo(this, "Nombre", InputType.TYPE_CLASS_TEXT).apply { setText(Servicios.nombre(this@AceptarActivity)) }
        raiz.addView(campoNombre, Ui.ancho())
        botonConectar = Ui.boton(this, "Conectar") { conectar() }
        raiz.addView(botonConectar, Ui.ancho())
        estado = Ui.texto(this, "")
        raiz.addView(estado)
        setContentView(ScrollView(this).apply { addView(raiz) })
        recibir(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recibir(intent)
    }

    private fun recibir(i: Intent?) {
        val url = i?.data?.toString() ?: return
        campoEnlace.setText(url)
        revisar()
    }

    private fun pegar() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) { Toast.makeText(this, "El portapapeles está vacío", Toast.LENGTH_SHORT).show(); return }
        campoEnlace.setText(t)
        revisar()
    }

    /** Muestra de quien es la invitacion o por que no vale. Devuelve los datos si son validos. */
    private fun revisar(): Invitacion.Datos? {
        val d = Invitacion.interpretar(campoEnlace.text.toString())
        if (d == null) {
            detalle.text = if (campoEnlace.text.isNullOrBlank()) "" else "Este enlace no es válido o ya ha caducado."
            return null
        }
        val resto = d.caducaS - System.currentTimeMillis() / 1000
        detalle.text = "Invitación de ${d.nombre.ifBlank { "alguien" }}\nHuella: ${Identidad.huellaTexto(d.huella).take(19)}…\nCaduca en %d:%02d".format(resto / 60, resto % 60)
        return d
    }

    private fun conectar() {
        val datos = revisar() ?: return
        botonConectar.isEnabled = false
        campoNombre.isEnabled = false
        val nombre = campoNombre.text.toString().trim().ifEmpty { Servicios.nombre(this) }
        Servicios.guardarNombre(this, nombre)
        estado.text = "Preparando…"
        Thread {
            try {
                val huella = Identidad.huella()
                val d = Dht()
                dht = d
                val inv = Emparejamiento.Invitado(
                    d, TlsFijado.contexto(datos.huella), huella, datos, nombre,
                    { codigo, par -> Ui.confirmarCodigo(this, codigo, par) },
                    { t -> runOnUiThread { if (!isDestroyed) estado.text = t } }
                )
                invitado = inv
                when (val r = inv.ejecutar()) {
                    is Emparejamiento.Resultado.Exito -> {
                        Servicios.contactos(this).guardar(r.contacto)
                        runOnUiThread {
                            Toast.makeText(this, "${r.contacto.nombre} añadido a tus contactos", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                    is Emparejamiento.Resultado.Fallo -> runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        estado.text = "No se ha completado: ${r.motivo}"
                        botonConectar.isEnabled = true; campoNombre.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    estado.text = "Error: ${e.javaClass.simpleName}: ${e.message}"
                    botonConectar.isEnabled = true; campoNombre.isEnabled = true
                }
            } finally {
                dht?.puerto?.cerrar(); dht = null
            }
        }.start()
    }

    override fun onDestroy() {
        invitado?.cancelar()
        super.onDestroy()
    }
}
