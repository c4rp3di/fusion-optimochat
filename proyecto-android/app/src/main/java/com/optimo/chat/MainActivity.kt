package com.optimo.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fase 0: pantalla de pruebas de viabilidad (P1 a P7 de la especificacion). Sin chat todavia.
 * Todo lo que se muestra se acumula en el informe, que se copia con IPs enmascaradas.
 */
class MainActivity : Activity() {

    private lateinit var registro: TextView
    private lateinit var scroll: ScrollView
    private lateinit var campoIp: EditText
    private lateinit var campoHuella: EditText
    private lateinit var campoSecreto: EditText
    private lateinit var red: EstadoRed
    private val informe = StringBuilder()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        raiz.addView(TextView(this).apply {
            text = "OptimoChat 0.0.1 · fase 0 (pruebas)"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        campoIp = campo(raiz, "IP del otro movil (prueba TLS)", InputType.TYPE_CLASS_TEXT)
        campoHuella = campo(raiz, "Huella del otro (64 hex)", InputType.TYPE_CLASS_TEXT)
        campoSecreto = campo(raiz, "Secreto de prueba (igual en los dos)", InputType.TYPE_CLASS_TEXT)

        fila(raiz,
            boton("Identidad") { pruebaIdentidad() },
            boton("Red") { pruebaRed() }
        )
        fila(raiz,
            boton("NAT") { enHilo { pruebaNat() } },
            boton("Encuentro + punching") { enHilo { pruebaPunch() } }
        )
        fila(raiz,
            boton("TLS servidor") { enHilo { pruebaTlsServidor() } },
            boton("TLS cliente") { enHilo { pruebaTlsCliente() } }
        )
        fila(raiz,
            boton("Copiar informe") { copiarInforme() },
            boton("Borrar identidad") { Identidad.borrar(); anotar("Identidad borrada") }
        )

        registro = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(registro) }
        raiz.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)

        red = EstadoRed(this)
        anotar("Movil: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        anotar(red.actual.toString())
        // P2: cada cambio de red queda anotado con su hora.
        red.escuchar { anotar("CAMBIO DE RED: $it") }
    }

    override fun onDestroy() {
        red.parar()
        super.onDestroy()
    }

    // ---- Pruebas ----

    private fun pruebaIdentidad() {
        try {
            val nueva = Identidad.asegurar()
            anotar("P1 identidad: ${if (nueva) "creada" else "ya existia"}")
            anotar("  huella: ${Identidad.huellaTexto(Identidad.huella())}")
        } catch (e: Exception) {
            anotar("P1 identidad: ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun pruebaRed() = anotar("P2 red: ${red.actual}")

    private fun pruebaNat() {
        anotar("P4 clasificacion de NAT...")
        val dht = Dht()
        try {
            anotar(SondaNat.clasificar(dht, log = { anotar(it) }))
        } catch (e: Exception) {
            anotar("P4 ERROR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            dht.puerto.cerrar()
        }
    }

    private fun pruebaPunch() {
        val secreto = campoSecreto.text.toString().trim()
        if (secreto.length < 6) { anotar("Escribe un secreto de al menos 6 caracteres (el mismo en los dos moviles)"); return }
        anotar("P5 encuentro y punching (hasta 2 min). Lanzalo en los dos moviles a la vez.")
        anotar("  red: ${red.actual}")
        val dht = Dht()
        try {
            anotar(SondaNat.encuentroYPunching(dht, secreto.toByteArray(Charsets.UTF_8), 120_000) { anotar(it) })
        } catch (e: Exception) {
            anotar("P5 ERROR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            dht.puerto.cerrar()
        }
    }

    private fun pruebaTlsServidor() {
        val fijada = Identidad.huellaDesdeTexto(campoHuella.text.toString())
        anotar("P1 TLS servidor (huella fijada del cliente: ${if (fijada != null) "si" else "no, acepta cualquiera"})")
        try {
            anotar(TlsFijado.servidor(fijada) { anotar(it) })
        } catch (e: Exception) {
            anotar("P1 servidor ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun pruebaTlsCliente() {
        val ip = campoIp.text.toString().trim()
        val fijada = Identidad.huellaDesdeTexto(campoHuella.text.toString())
        if (ip.isEmpty() || fijada == null) { anotar("Falta la IP o la huella (64 hex) del servidor"); return }
        anotar("P1 TLS cliente hacia ${Informe.enmascarar(java.net.InetSocketAddress(ip, TlsFijado.PUERTO_PRUEBA))}")
        anotar(TlsFijado.cliente(ip, fijada) { anotar(it) })
    }

    // ---- Interfaz ----

    private fun anotar(texto: String) {
        val linea = "${hora.format(Date())} $texto\n"
        synchronized(informe) { informe.append(linea) }
        runOnUiThread {
            registro.append(linea)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun enHilo(trabajo: () -> Unit) {
        Thread { trabajo() }.start()
    }

    private fun copiarInforme() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val texto = synchronized(informe) { informe.toString() }
        cm.setPrimaryClip(ClipData.newPlainText("Informe OptimoChat", texto))
        Toast.makeText(this, "Informe copiado (IPs enmascaradas)", Toast.LENGTH_SHORT).show()
    }

    private fun campo(padre: LinearLayout, pista: String, tipo: Int): EditText {
        val e = EditText(this).apply {
            hint = pista
            inputType = tipo
            setSingleLine()
            textSize = 14f
        }
        padre.addView(e)
        return e
    }

    private fun boton(texto: String, accion: () -> Unit): Button =
        Button(this).apply {
            text = texto
            isAllCaps = false
            setOnClickListener { accion() }
        }

    private fun fila(padre: LinearLayout, a: Button, b: Button) {
        val f = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        f.addView(a, lp())
        f.addView(b, lp())
        padre.addView(f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
