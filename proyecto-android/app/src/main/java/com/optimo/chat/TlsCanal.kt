package com.optimo.chat

import java.io.IOException
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

/**
 * TLS sobre cualquier [Canal] (UDP fiable o TCP) con SSLEngine. Logica sin APIs de Android: se prueba en la JVM.
 *
 * El contexto ([SSLContext]) lo aporta quien llama: en el movil lleva la clave del Keystore y la confianza fijada
 * por huella (ver [Confianza] y [TlsFijado.contexto]). Ambos extremos exigen certificado del otro.
 * Una lectura y una escritura pueden ejecutarse a la vez desde hilos distintos; dos lecturas o dos escrituras no.
 */
class TlsCanal(
    private val bajo: Canal,
    contexto: SSLContext,
    servidor: Boolean,
    protocolos: Array<String>? = null
) : Canal {

    private val engine: SSLEngine = contexto.createSSLEngine().apply {
        useClientMode = !servidor
        if (servidor) needClientAuth = true
        if (protocolos != null) enabledProtocols = protocolos
    }

    private val lockLectura = Any()
    private val lockEscritura = Any()

    // netIn y appIn permanecen en "modo escritura" entre llamadas (position = bytes guardados).
    private val netIn = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
    private val appIn = ByteBuffer.allocate(engine.session.applicationBufferSize)
    @Volatile private var cerradoPar = false

    /** Version negociada, por ejemplo TLSv1.3. Solo valida tras [apretonDeManos]. */
    val protocolo: String get() = engine.session.protocol

    /** Huella (SHA-256 de la clave publica) del certificado del otro extremo, tras [apretonDeManos]. */
    val huellaPar: ByteArray?
        get() = try {
            (engine.session.peerCertificates.firstOrNull() as? X509Certificate)?.let { Confianza.huellaDe(it) }
        } catch (_: Exception) { null }

    /** Bloquea hasta completar el handshake. Lanza SSLException si el otro extremo no es el esperado. */
    fun apretonDeManos() {
        try {
            engine.beginHandshake()
            while (true) {
                val e = engine.handshakeStatus
                when {
                    e == HandshakeStatus.NEED_TASK -> tareas()
                    e == HandshakeStatus.NEED_WRAP -> envolverVacio()
                    e == HandshakeStatus.NEED_UNWRAP || e.name == "NEED_UNWRAP_AGAIN" ->
                        if (!desenvolverUnaVez()) throw IOException("Conexion cortada durante el handshake")
                    else -> return // FINISHED o NOT_HANDSHAKING
                }
            }
        } catch (e: SSLException) {
            // Intenta enviar la alerta TLS para que el otro extremo falle al instante en vez de esperar.
            try { envolverVacio() } catch (_: Exception) { }
            throw e
        }
    }

    // ---- Canal ----

    override fun escribir(b: ByteArray, desde: Int, largo: Int) {
        synchronized(lockEscritura) {
            val origen = ByteBuffer.wrap(b, desde, largo)
            while (origen.hasRemaining()) {
                val salida = ByteBuffer.allocate(engine.session.packetBufferSize)
                val r = engine.wrap(origen, salida)
                volcar(salida)
                tareas()
                if (r.status == Status.CLOSED) throw IOException("TLS cerrado")
            }
        }
    }

    override fun leer(b: ByteArray, desde: Int, largo: Int): Int {
        synchronized(lockLectura) {
            while (true) {
                appIn.flip()
                if (appIn.hasRemaining()) {
                    val n = minOf(largo, appIn.remaining())
                    appIn.get(b, desde, n)
                    appIn.compact()
                    return n
                }
                appIn.compact()
                if (cerradoPar) return -1
                if (!desenvolverUnaVez()) {
                    if (cerradoPar) return -1
                    throw SSLException("Conexion cortada sin close_notify")
                }
                // Tras un registro de handshake posterior (tickets, cambio de claves) puede haber que responder.
                if (engine.handshakeStatus == HandshakeStatus.NEED_WRAP) envolverVacio()
            }
        }
    }

    override fun cerrar() {
        try {
            synchronized(lockEscritura) {
                engine.closeOutbound()
                var vueltas = 0
                while (!engine.isOutboundDone && vueltas++ < 4) {
                    val salida = ByteBuffer.allocate(engine.session.packetBufferSize)
                    engine.wrap(ByteBuffer.allocate(0), salida)
                    volcar(salida)
                }
            }
        } catch (_: Exception) {
            // Se cierra igualmente el canal de abajo.
        }
        bajo.cerrar()
    }

    // ---- Internos ----

    private fun tareas() {
        while (true) {
            val t = engine.delegatedTask ?: break
            t.run()
        }
    }

    private fun volcar(salida: ByteBuffer) {
        salida.flip()
        if (salida.hasRemaining()) {
            bajo.escribir(salida.array(), salida.arrayOffset() + salida.position(), salida.remaining())
        }
    }

    private fun envolverVacio() {
        synchronized(lockEscritura) {
            val salida = ByteBuffer.allocate(engine.session.packetBufferSize)
            engine.wrap(ByteBuffer.allocate(0), salida)
            volcar(salida)
            tareas()
        }
    }

    /** Intenta desenvolver un registro, leyendo del canal de abajo lo que falte. False si el canal llego a su fin. */
    private fun desenvolverUnaVez(): Boolean {
        while (true) {
            netIn.flip()
            val r = try { engine.unwrap(netIn, appIn) } finally { netIn.compact() }
            when (r.status) {
                Status.OK -> { tareas(); return true }
                Status.CLOSED -> { cerradoPar = true; return true }
                Status.BUFFER_UNDERFLOW -> {
                    if (netIn.remaining() == 0) throw SSLException("Registro TLS mayor que el buffer")
                    val n = bajo.leer(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
                    if (n < 0) return false
                    netIn.position(netIn.position() + n)
                }
                Status.BUFFER_OVERFLOW -> throw SSLException("Buffer de aplicacion insuficiente")
            }
        }
    }
}
