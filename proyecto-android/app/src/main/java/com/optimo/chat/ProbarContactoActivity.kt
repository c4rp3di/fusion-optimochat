package com.optimo.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prueba de conexion con un contacto, sin escribir nada: usa su secreto y su huella. Los DOS moviles pulsan
 * «Probar ahora» con menos de un par de minutos de diferencia. Cada intento se anade al informe (IPs enmascaradas,
 * sin nombres) con el tipo de red, para repetir los 10 intentos por pareja de redes del criterio de la fase 0.
 * Sin compilar.
 */
class ProbarContactoActivity : Activity() {

    companion object { const val EXTRA_ID = "contacto" }

    private lateinit var estado: TextView
    private lateinit var historial: TextView
    private lateinit var resumen: TextView
    private lateinit var botonProbar: android.widget.Button
    private lateinit var red: EstadoRed
    private var contacto: Contacto? = null
    private var dht: Dht? = null
    @Volatile private var cancelado = false
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        red = EstadoRed(this)
        val raiz = Ui.raiz(this)
        raiz.addView(Ui.titulo(this, "Probar conexión"))
        raiz.addView(Ui.texto(this, "Cargando contacto…").also { estado = it })
        botonProbar = Ui.boton(this, "Probar ahora") { probar() }.apply { isEnabled = false }
        raiz.addView(botonProbar, Ui.ancho())
        raiz.addView(Ui.boton(this, "Cancelar la búsqueda") { cancelado = true }, Ui.ancho())
        raiz.addView(Ui.boton(this, "Copiar informe") { copiar() }, Ui.ancho())
        raiz.addView(Ui.texto(this, "", 15f).also { resumen = it })
        raiz.addView(Ui.texto(this, "", 11f).also { historial = it })
        setContentView(ScrollView(this).apply { addView(raiz) })

        val id = intent.getStringExtra(EXTRA_ID)
        Thread {
            val c = try { id?.let { Servicios.contactos(this).obtener(it) } } catch (_: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                contacto = c
                if (c == null) estado.text = "No se encontró el contacto."
                else {
                    estado.text = "Contacto: ${c.nombre}. Pulsa «Probar ahora» en los dos móviles a la vez (red: ${red.actual.tipo})."
                    botonProbar.isEnabled = true
                }
                refrescar()
            }
        }.start()
    }

    private fun probar() {
        val c = contacto ?: return
        botonProbar.isEnabled = false
        cancelado = false
        Thread {
            val d = Dht()
            dht = d
            try {
                val miHuella = Identidad.huella()
                val redInicial = red.actual
                val r = PruebaContacto.ejecutar(
                    d, c, TlsFijado.contexto(c.huella), miHuella,
                    cancelar = { cancelado },
                    alEstado = { t -> runOnUiThread { if (!isDestroyed) estado.text = t } }
                )
                // Si la red cambio durante la prueba se anota: el resultado no sirve para la estadistica de esa pareja.
                val redFinal = red.actual
                val etiqueta = if (redInicial.tipo == redFinal.tipo) "red=${redInicial.tipo}${if (redInicial.vpn) "+vpn" else ""}"
                else "red=${redInicial.tipo}->${redFinal.tipo} (cambio durante la prueba)"
                Servicios.historialPruebas.add(PruebaContacto.linea(hora.format(Date()), etiqueta, c.id, r))
            } catch (e: Exception) {
                Servicios.historialPruebas.add("${hora.format(Date())} | ERROR ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                d.puerto.cerrar(); dht = null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                estado.text = "Terminado. Pulsa de nuevo en los dos móviles para otro intento."
                botonProbar.isEnabled = true
                refrescar()
            }
        }.start()
    }

    private fun refrescar() {
        val l = synchronized(Servicios.historialPruebas) { Servicios.historialPruebas.toList() }
        val ok = l.count { it.contains("| OK con") }
        resumen.text = if (l.isEmpty()) "" else "Intentos: ${l.size} · correctos: $ok (${ok * 100 / l.size} %)"
        historial.text = l.joinToString("\n\n")
    }

    private fun copiar() {
        val l = synchronized(Servicios.historialPruebas) { Servicios.historialPruebas.toList() }
        val texto = "OptimoChat · pruebas de conexión (${red.actual})\n" + l.joinToString("\n")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Informe", texto))
        Toast.makeText(this, "Informe copiado", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        cancelado = true
        red.parar()
        super.onDestroy()
    }
}
