package com.optimo.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Invitar: crea el enlace y el QR (10 minutos, un solo uso), espera al invitado y, cuando llega, pide comparar el codigo
 * de 6 digitos. Sin compilar. El QR lo lee la camara del sistema, que ofrece abrir el enlace en OptimoChat.
 */
class InvitarActivity : Activity() {

    private lateinit var campoNombre: android.widget.EditText
    private lateinit var qr: QrVista
    private lateinit var enlaceTxt: TextView
    private lateinit var estado: TextView
    private lateinit var caduca: TextView
    private lateinit var panel: LinearLayout
    private lateinit var botonCrear: android.widget.Button

    private val manejador = Handler(Looper.getMainLooper())
    private var anfitrion: Emparejamiento.Anfitrion? = null
    private var dht: Dht? = null
    private var enlace: String? = null
    private var caducaS = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val raiz = Ui.raiz(this)
        raiz.addView(Ui.titulo(this, "Invitar a alguien"))
        raiz.addView(Ui.texto(this, "Tu nombre (lo verá tu contacto)"))
        campoNombre = Ui.campo(this, "Nombre", InputType.TYPE_CLASS_TEXT).apply { setText(Servicios.nombre(this@InvitarActivity)) }
        raiz.addView(campoNombre, Ui.ancho())
        botonCrear = Ui.boton(this, "Crear invitación") { crear() }
        raiz.addView(botonCrear, Ui.ancho())

        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        qr = QrVista(this)
        panel.addView(qr, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        caduca = Ui.texto(this, "")
        panel.addView(caduca)
        panel.addView(Ui.texto(this, "Que la otra persona escanee el QR con la cámara del móvil, o envíale el enlace:"))
        enlaceTxt = Ui.texto(this, "", 11f).apply { setTextIsSelectable(true) }
        panel.addView(enlaceTxt)
        panel.addView(Ui.boton(this, "Compartir enlace") { compartir() }, Ui.ancho())
        panel.addView(Ui.boton(this, "Copiar enlace") { copiar() }, Ui.ancho())
        raiz.addView(panel)

        estado = Ui.texto(this, "")
        raiz.addView(estado)
        setContentView(ScrollView(this).apply { addView(raiz) })
    }

    private fun crear() {
        botonCrear.isEnabled = false
        campoNombre.isEnabled = false
        val nombre = campoNombre.text.toString().trim().ifEmpty { Servicios.nombre(this) }
        Servicios.guardarNombre(this, nombre)
        estado.text = "Preparando…"
        Thread {
            try {
                val huella = Identidad.huella()
                val inv = Invitacion.crear(huella, nombre)
                val matriz = QrCodigo.generar(inv.enlace)
                val d = Dht()
                dht = d
                val a = Emparejamiento.Anfitrion(
                    d, TlsFijado.contexto(null), huella, inv.datos, nombre,
                    { codigo, par -> Ui.confirmarCodigo(this, codigo, par) },
                    { t -> runOnUiThread { if (!isDestroyed) estado.text = t } }
                )
                anfitrion = a
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    enlace = inv.enlace; caducaS = inv.datos.caducaS
                    qr.matriz = matriz; enlaceTxt.text = inv.enlace
                    panel.visibility = View.VISIBLE
                    cuentaAtras()
                }
                val r = a.ejecutar()
                when (r) {
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
                        panel.visibility = View.GONE
                        botonCrear.isEnabled = true; campoNombre.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    estado.text = "Error: ${e.javaClass.simpleName}: ${e.message}"
                    botonCrear.isEnabled = true; campoNombre.isEnabled = true
                }
            } finally {
                dht?.puerto?.cerrar(); dht = null
            }
        }.start()
    }

    private fun cuentaAtras() {
        val resto = caducaS - System.currentTimeMillis() / 1000
        if (resto <= 0) { caduca.text = "La invitación ha caducado."; return }
        caduca.text = "Caduca en %d:%02d. Sirve para una sola persona.".format(resto / 60, resto % 60)
        manejador.postDelayed({ if (!isDestroyed) cuentaAtras() }, 1000)
    }

    private fun compartir() {
        val e = enlace ?: return
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, e) }
        startActivity(Intent.createChooser(i, "Enviar invitación"))
    }

    private fun copiar() {
        val e = enlace ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Invitación", e))
        Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)
        anfitrion?.cancelar()
        super.onDestroy()
    }
}
