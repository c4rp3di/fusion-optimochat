package com.optimo.chat

import com.optimo.chat.Protocolo.Mensaje
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Ventana deslizante: como mucho [max] tramas cada [ventanaMs]. */
class LimiteTasa(private val max: Int, private val ventanaMs: Long = 1000) {
    private val marcas = java.util.ArrayDeque<Long>()

    fun permitir(ahora: Long): Boolean {
        while (marcas.isNotEmpty() && ahora - marcas.first() >= ventanaMs) marcas.removeFirst()
        if (marcas.size >= max) return false
        marcas.addLast(ahora)
        return true
    }
}

/** Espera entre intentos de reconexion: 5 s duplicando hasta 15 min, con +-20 % de variacion. */
object Reintentos {
    const val BASE_MS = 5_000L
    const val TOPE_MS = 900_000L

    /** [intento] empieza en 0. [azar] debe estar en [0, 1). */
    fun espera(intento: Int, azar: Double = Math.random()): Long {
        val base = minOf(TOPE_MS, BASE_MS shl minOf(intento.coerceAtLeast(0), 20))
        return (base * (0.8 + 0.4 * azar)).toLong()
    }
}

/**
 * Conversacion con un contacto: cola de salida, estados, duplicados y protocolo de sesion (logica pura).
 *
 * Una [Chat] vive mientras exista el contacto. Cada vez que hay un canal (TLS ya abierto) se llama a [atender] en un
 * hilo propio; devuelve cuando la sesion termina y quien la gestiona decide cuando reconectar ([Reintentos]).
 * Los mensajes se guardan siempre antes de intentar enviarlos y se reenvian en orden en cada sesion hasta recibir su
 * ACK. Los callbacks se invocan desde hilos internos: la interfaz debe pasarlos al hilo principal.
 */
class Chat(
    private val almacen: AlmacenMensajes,
    private val alRecibir: (Mensaje) -> Unit = {},
    private val alCambiarEstado: (ByteArray, Estado) -> Unit = { _, _ -> },
    private val confirmarLectura: Boolean = true,
    private val reloj: () -> Long = { System.currentTimeMillis() },
    private val ritmoMs: Long = 25,
    private val maxTramasPorSegundo: Int = 200,
    private val maxMensajesPorSegundo: Int = 60
) {
    private val candado = ReentrantLock()
    private val hayTrabajo = candado.newCondition()
    private val candadoEscritura = Any()
    private val azar = SecureRandom()

    private var activo: Canal? = null
    private var helloPar = false
    private var capsPar = 0

    /**
     * Guarda el mensaje y avisa al hilo de envio de la sesion activa, que los escribe en orden y a ritmo controlado.
     * Lanza IllegalArgumentException si el texto esta vacio o es demasiado largo.
     */
    fun enviar(texto: String, respuestaA: ByteArray? = null): Mensaje {
        val largo = texto.toByteArray(Charsets.UTF_8).size
        require(largo in 1..Protocolo.MAX_CUERPO) { "El mensaje debe tener entre 1 y ${Protocolo.MAX_CUERPO} bytes" }
        candado.withLock {
            val id = ByteArray(Protocolo.TAM_ID).also { azar.nextBytes(it) }
            val m = Mensaje(id, almacen.siguienteSeq(), reloj(), texto, respuestaA)
            almacen.guardarEnviado(m)
            hayTrabajo.signalAll()
            return m
        }
    }

    /** Avisa al otro de que el usuario ha leido el mensaje recibido [id] (si ambos lo permiten y hay sesion). */
    fun marcarLeido(id: ByteArray) {
        if (!confirmarLectura) return
        candado.withLock {
            val c = activo ?: return
            if (helloPar && (capsPar and Protocolo.CAP_LEIDO) != 0) {
                try { enviarTrama(c, Protocolo.T_READ, id) } catch (_: IOException) { }
            }
        }
    }

    val haySesion: Boolean get() = candado.withLock { activo != null && helloPar }

    /** Ejecuta el protocolo sobre [canal] hasta que termine. Devuelve el motivo, para el registro. */
    fun atender(canal: Canal): String {
        candado.withLock { activo = canal; helloPar = false; capsPar = 0 }
        val limite = LimiteTasa(maxTramasPorSegundo)
        val limiteMensajes = LimiteTasa(maxMensajesPorSegundo)
        try {
            enviarTrama(
                canal, Protocolo.T_HELLO,
                Protocolo.codificarHello(if (confirmarLectura) Protocolo.CAP_LEIDO else 0, almacen.ultimoSeqRecibido())
            )
            var recibioHello = false
            while (true) {
                val t = Protocolo.leerTrama(canal) ?: return "fin"
                if (!limite.permitir(reloj())) return despedir(canal, Protocolo.BYE_LIMITE, "limite de tasa")

                if (t.tipo == Protocolo.T_HELLO) {
                    if (recibioHello) return despedir(canal, Protocolo.BYE_PROTOCOLO, "HELLO repetido")
                    val h = Protocolo.decodificarHello(t.carga)
                        ?: return despedir(canal, Protocolo.BYE_PROTOCOLO, "HELLO invalido")
                    if (h.version != Protocolo.VERSION) return despedir(canal, Protocolo.BYE_VERSION, "version ${h.version}")
                    recibioHello = true
                    alRecibirHello(canal, h)
                    continue
                }
                if (!recibioHello) return despedir(canal, Protocolo.BYE_PROTOCOLO, "trama antes de HELLO")

                when (t.tipo) {
                    Protocolo.T_MSG -> {
                        if (!limiteMensajes.permitir(reloj())) return despedir(canal, Protocolo.BYE_LIMITE, "limite de mensajes")
                        val m = Protocolo.decodificarMensaje(t.carga)
                            ?: return despedir(canal, Protocolo.BYE_PROTOCOLO, "mensaje invalido")
                        if (almacen.guardarRecibido(m)) alRecibir(m)
                        enviarTrama(canal, Protocolo.T_ACK, m.id)
                    }
                    Protocolo.T_ACK -> {
                        val id = Protocolo.decodificarId(t.carga)
                            ?: return despedir(canal, Protocolo.BYE_PROTOCOLO, "ACK invalido")
                        if (almacen.marcarEntregado(id)) alCambiarEstado(id, Estado.ENTREGADO)
                    }
                    Protocolo.T_READ -> {
                        val id = Protocolo.decodificarId(t.carga)
                            ?: return despedir(canal, Protocolo.BYE_PROTOCOLO, "READ invalido")
                        if (confirmarLectura && almacen.marcarLeidoPorPar(id)) alCambiarEstado(id, Estado.LEIDO)
                    }
                    Protocolo.T_PING -> { }
                    Protocolo.T_BYE -> return "bye ${t.carga.firstOrNull()?.toInt() ?: 0}"
                    else -> { } // tipos desconocidos: se ignoran (compatibilidad hacia adelante)
                }
            }
        } catch (e: IOException) {
            return "error: ${e.message}"
        } finally {
            candado.withLock {
                if (activo === canal) { activo = null; helloPar = false }
                hayTrabajo.signalAll() // despierta al hilo de envio para que termine
            }
        }
    }

    // ---- Internos ----

    private fun enviarTrama(c: Canal, tipo: Int, carga: ByteArray) {
        synchronized(candadoEscritura) { Protocolo.escribirTrama(c, tipo, carga) }
    }

    private fun despedir(c: Canal, codigo: Int, motivo: String): String {
        try { enviarTrama(c, Protocolo.T_BYE, byteArrayOf(codigo.toByte())) } catch (_: IOException) { }
        return "cerrada: $motivo"
    }

    /** Escribe un mensaje y lo marca ENVIADO. Si falla, queda PENDIENTE y la sesion caera por su cuenta. */
    private fun escribirMensaje(c: Canal, m: Mensaje): Boolean {
        return try {
            enviarTrama(c, Protocolo.T_MSG, Protocolo.codificarMensaje(m))
            almacen.marcarEnviado(m.id)
            alCambiarEstado(m.id, Estado.ENVIADO)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun alRecibirHello(canal: Canal, h: Protocolo.Hello) {
        candado.withLock {
            capsPar = h.capacidades
            for (id in almacen.marcarEntregadosHasta(h.ultimoSeq)) alCambiarEstado(id, Estado.ENTREGADO)
            almacen.reiniciarEnviados()
            helloPar = true
        }
        Thread({ enviador(canal) }, "chat-envio").apply { isDaemon = true; start() }
    }

    /**
     * Unico hilo que escribe mensajes en esta sesion: los saca de la cola en orden y, tras una racha de mas de 10
     * seguidos, los espacia [ritmoMs] para no disparar el limite de mensajes del otro lado.
     */
    private fun enviador(canal: Canal) {
        var seguidos = 0
        while (true) {
            val m: Mensaje = candado.withLock {
                while (activo === canal && almacen.siguientePendiente() == null) {
                    hayTrabajo.await()
                    seguidos = 0
                }
                if (activo !== canal) return
                almacen.siguientePendiente() ?: return
            }
            if (!escribirMensaje(canal, m)) return
            seguidos++
            if (seguidos > 10 && ritmoMs > 0) Thread.sleep(ritmoMs)
        }
    }
}
