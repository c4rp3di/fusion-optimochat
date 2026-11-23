package com.optimo.chat

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

/**
 * Mantiene la conexion con UN contacto: encuentro por DHT, punching, transporte fiable, TLS con huella fijada y
 * [Chat.atender]. Cuando la sesion termina o falla, espera con [Reintentos] y vuelve a empezar. Logica pura.
 *
 * El contexto TLS debe tener la huella del contacto fijada ([Confianza.contexto]). Si el otro presenta otra clave el
 * apreton falla y se cuenta como intento fallido: nunca se llega a [Chat.atender] con un desconocido.
 * Quien tiene la huella menor hace de servidor TLS, para que los dos lados se pongan de acuerdo sin hablar.
 */
class GestorConexion(
    private val dht: Dht,
    private val chat: Chat,
    private val contexto: SSLContext,
    private val miHuella: ByteArray,
    private val huellaPar: ByteArray,
    private val secreto: ByteArray,
    private val semillas: List<InetSocketAddress>? = null,
    private val alEstado: (String) -> Unit = {},
    private val espera: (Int) -> Long = { Reintentos.espera(it) },
    private val encuentroMs: Long = 120_000,
    private val rafagaMs: Long = 15_000,
    private val pausaMs: Long = 6_000,
    private val sesionMinimaMs: Long = 30_000,
    private val protocolos: Array<String>? = null
) {
    @Volatile private var parado = false
    @Volatile private var hilo: Thread? = null
    @Volatile private var actual: Canal? = null
    @Volatile var intentosFallidos = 0; private set
    @Volatile var sesiones = 0; private set

    companion object {
        /** Comparacion sin signo, byte a byte. */
        fun menor(a: ByteArray, b: ByteArray): Boolean {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a[i].toInt() and 0xFF; val y = b[i].toInt() and 0xFF
                if (x != y) return x < y
            }
            return a.size < b.size
        }
    }

    fun iniciar() {
        if (hilo != null) return
        hilo = Thread({ bucle() }, "gestor-conexion").apply { isDaemon = true; start() }
    }

    /** Termina la sesion actual (si la hay) y no vuelve a intentarlo. */
    fun parar() {
        parado = true
        try { actual?.cerrar() } catch (_: Exception) {}
        hilo?.interrupt()
        hilo?.join(5000)
    }

    /** Corta la sesion en curso sin parar el gestor: se reconectara tras la espera. Util al cambiar de red. */
    fun cortar() { try { actual?.cerrar() } catch (_: Exception) {} }

    private fun bucle() {
        while (!parado) {
            val inicio = System.currentTimeMillis()
            val motivo = try { unaSesion() } catch (e: InterruptedException) { return } catch (e: Exception) {
                "error: ${e.javaClass.simpleName}"
            }
            if (parado) return
            val duro = System.currentTimeMillis() - inicio >= sesionMinimaMs
            if (duro) intentosFallidos = 0 else intentosFallidos++
            val e = espera(intentosFallidos)
            alEstado("Sin conexion ($motivo); reintento en ${e / 1000} s")
            try { Thread.sleep(e) } catch (_: InterruptedException) { return }
        }
    }

    private fun unaSesion(): String {
        alEstado("Buscando al contacto")
        val r = SondaNat.encuentro(dht, secreto, encuentroMs, {}, semillas, rafagaMs, pausaMs) { parado }
        if (!r.exito || r.remoto == null) return "no encontrado"
        alEstado("Contacto encontrado; abriendo canal")
        val dg = dht.puerto.canal(r.remoto)
        val udp = UdpFiable(dg)
        actual = udp
        try {
            if (!udp.conectar(10_000)) return "el transporte no conecto"
            alEstado("Transporte listo; negociando TLS")
            val tls = TlsCanal(udp, contexto, servidor = menor(miHuella, huellaPar), protocolos = protocolos)
            actual = tls
            try { tls.apretonDeManos() } catch (e: Exception) { return "TLS rechazado (${e.javaClass.simpleName})" }
            sesiones++
            alEstado("Conectado")
            return chat.atender(tls)
        } finally {
            try { actual?.cerrar() } catch (_: Exception) {}
            try { udp.cerrar() } catch (_: Exception) {}
            actual = null
        }
    }
}
