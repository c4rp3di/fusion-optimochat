package com.optimo.chat

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Transporte de datagramas hacia UN extremo concreto. En las pruebas se sustituye por una red simulada. */
interface Datagramas {
    fun enviar(datos: ByteArray)

    /** Espera hasta [esperaMs] un datagrama del extremo. Null si no llega ninguno. */
    fun recibir(esperaMs: Int): ByteArray?

    fun cerrar() {}
}

/** Flujo de bytes ordenado y fiable. TLS corre encima sin saber si es TCP o UDP fiable. */
interface Canal {
    /** Bloquea hasta que haya datos. Devuelve -1 al llegar el fin ordenado. Lanza IOException si el canal se rompe. */
    fun leer(b: ByteArray, desde: Int, largo: Int): Int

    /** Bloquea mientras la ventana de envio este llena. */
    fun escribir(b: ByteArray, desde: Int, largo: Int)

    fun cerrar()
}

/** Datagramas sobre un socket UDP real, filtrando por la direccion del contacto. Solo para sockets exclusivos. */
class DatagramasUdp(private val socket: DatagramSocket, private val remoto: SocketAddress) : Datagramas {
    override fun enviar(datos: ByteArray) {
        socket.send(DatagramPacket(datos, datos.size, remoto))
    }

    override fun recibir(esperaMs: Int): ByteArray? {
        val fin = System.currentTimeMillis() + esperaMs
        val buf = ByteArray(2048)
        while (true) {
            val resto = (fin - System.currentTimeMillis()).toInt()
            if (resto <= 0) return null
            socket.soTimeout = resto
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (_: SocketTimeoutException) { return null }
            if (p.socketAddress == remoto && UdpFiable.esPaquete(p.data, p.length)) return p.data.copyOf(p.length)
        }
    }

    override fun cerrar() = socket.close()
}

/**
 * Flujo fiable y ordenado sobre datagramas (el "mini TCP" de OptimoChat). Logica pura, probable en la JVM.
 *
 * Paquete: [0xA0|tipo (1)] [id del emisor (4)] [secuencia (4)] [acuse (4)] [longitud (2)] [carga]
 *   - El primer byte tiene el nibble alto 0xA, distinto de 'd' (0x64, DHT) y de 'O' (0x4F, sondas de punching).
 *   - Tipos: HELLO (apertura simetrica: los dos extremos pueden empezar a la vez), DATA, ACK (con mapa SACK de
 *     32 bits), FIN (cierre ordenado).
 *   - Ventana deslizante, acuse acumulado + SACK, retransmision por RTO (RFC 6298) y retransmision rapida,
 *     control de congestion AIMD, latidos y deteccion de extremo caido.
 *   - Numeros de secuencia de 31 bits: unos 2 TB por conexion, mas que suficiente (no se contempla el desborde).
 *
 * No autentica ni cifra: eso lo hace TLS encima. Un atacante que inyecte paquetes solo puede cortar la conexion.
 */
class UdpFiable(
    private val dg: Datagramas,
    private val ventanaMax: Int = 64,
    private val rtoMinMs: Long = 200,
    private val keepaliveMs: Long = 15_000,
    private val inactividadMs: Long = 60_000,
    private val reloj: () -> Long = { System.nanoTime() / 1_000_000 }
) : Canal {

    companion object {
        const val CARGA_MAX = 1100
        const val CABECERA = 15
        private const val BASE = 0xA0
        private const val T_HELLO = 1
        private const val T_DATA = 2
        private const val T_ACK = 3
        private const val T_FIN = 4
        private const val VENTANA_RX = 256
        private const val TOPE_LECTURA = 256 * 1024
        private const val INTENTOS_MAX = 12

        fun esPaquete(d: ByteArray, n: Int): Boolean = n >= CABECERA && (d[0].toInt() and 0xF0) == BASE

        private fun leerInt(d: ByteArray, o: Int): Int =
            ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
                ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

        private fun escribirInt(d: ByteArray, o: Int, v: Int) {
            d[o] = (v ushr 24).toByte(); d[o + 1] = (v ushr 16).toByte()
            d[o + 2] = (v ushr 8).toByte(); d[o + 3] = v.toByte()
        }
    }

    private class Pendiente(
        val tipo: Int, val seq: Int, val carga: ByteArray,
        var ultimoEnvio: Long, val primerEnvio: Long, var intentos: Int
    )

    private class Rx(val fin: Boolean, val carga: ByteArray)

    private val lock = ReentrantLock()
    private val cambio = lock.newCondition()

    private val idLocal: Int = SecureRandom().nextInt()
    private var idRemoto = 0
    private var conocidoRemoto = false
    private var remotoVioMio = false
    private var abierto = false
    private var cerradoLocal = false
    private var roto: IOException? = null
    @Volatile private var parar = false

    // Envio
    private var sigSeq = 0
    private val enVuelo = TreeMap<Int, Pendiente>()
    private var cwnd = 4.0
    private var ssthresh = 64.0
    private var srtt = -1.0
    private var rttvar = 0.0
    private var rto = 1000L

    // Recepcion
    private var esperado = 0
    private val fuera = TreeMap<Int, Rx>()
    private val lectura = java.util.ArrayDeque<ByteArray>()
    private var offLectura = 0
    private var bytesLectura = 0
    private var finRecibido = false

    private var inicio = 0L
    private var ultimoHelloEnviado = 0L
    private var ultimoHelloRespondido = 0L
    private var ultimoEnvio = 0L
    private var ultimoRecibido = 0L

    /** Estadisticas para pruebas y para el informe. */
    var retransmisiones = 0
        private set
    val ventanaActual: Double get() = lock.withLock { cwnd }

    init {
        val ahora = reloj()
        inicio = ahora; ultimoEnvio = ahora; ultimoRecibido = ahora
        Thread({ bucle() }, "udp-fiable").apply { isDaemon = true; start() }
    }

    /** Espera a que los dos extremos se hayan visto. Devuelve false si no ocurre en [esperaMs]. */
    fun conectar(esperaMs: Long): Boolean = lock.withLock {
        val limite = reloj() + esperaMs
        while (!abierto && roto == null && !cerradoLocal) {
            val resto = limite - reloj()
            if (resto <= 0) return false
            cambio.await(resto, TimeUnit.MILLISECONDS)
        }
        abierto
    }

    // ---- API de Canal ----

    override fun escribir(b: ByteArray, desde: Int, largo: Int) {
        var pos = desde
        val fin = desde + largo
        lock.withLock {
            while (pos < fin) {
                comprobarEscritura()
                if (!abierto || enVuelo.size >= maxOf(1, minOf(cwnd.toInt(), ventanaMax))) {
                    cambio.await(100, TimeUnit.MILLISECONDS)
                    continue
                }
                val n = minOf(CARGA_MAX, fin - pos)
                val ahora = reloj()
                val p = Pendiente(T_DATA, sigSeq++, b.copyOfRange(pos, pos + n), ahora, ahora, 1)
                enVuelo[p.seq] = p
                emitir(p)
                pos += n
            }
        }
    }

    private fun comprobarEscritura() {
        roto?.let { throw it }
        if (cerradoLocal) throw IOException("Canal cerrado")
    }

    override fun leer(b: ByteArray, desde: Int, largo: Int): Int = lock.withLock {
        while (lectura.isEmpty()) {
            if (finRecibido || cerradoLocal) return -1
            roto?.let { throw it }
            cambio.await(100, TimeUnit.MILLISECONDS)
        }
        var copiados = 0
        while (copiados < largo && lectura.isNotEmpty()) {
            val actual = lectura.peek()
            val n = minOf(largo - copiados, actual.size - offLectura)
            System.arraycopy(actual, offLectura, b, desde + copiados, n)
            copiados += n
            offLectura += n
            bytesLectura -= n
            if (offLectura == actual.size) { lectura.poll(); offLectura = 0 }
        }
        copiados
    }

    /** Cierre ordenado: envia FIN y espera (hasta 3 s) a que se confirme todo lo pendiente. */
    override fun cerrar() {
        lock.withLock {
            if (cerradoLocal) return
            cerradoLocal = true
            if (abierto && roto == null) {
                val ahora = reloj()
                val p = Pendiente(T_FIN, sigSeq++, ByteArray(0), ahora, ahora, 1)
                enVuelo[p.seq] = p
                try { emitir(p) } catch (_: IOException) { }
                val limite = ahora + 3_000
                while (enVuelo.isNotEmpty() && roto == null && reloj() < limite) {
                    cambio.await(50, TimeUnit.MILLISECONDS)
                }
            }
            parar = true
            cambio.signalAll()
        }
        try { dg.cerrar() } catch (_: Exception) { }
    }

    // ---- Hilo interno ----

    private fun bucle() {
        try {
            while (!parar) {
                val d = dg.recibir(20)
                lock.withLock {
                    if (parar) return
                    val ahora = reloj()
                    if (d != null && esPaquete(d, d.size)) procesar(d, ahora)
                    temporizadores(ahora)
                }
            }
        } catch (e: Exception) {
            lock.withLock { fallar(IOException("Canal UDP: ${e.message}", e)) }
        }
    }

    private fun fallar(e: IOException) {
        if (roto == null) roto = e
        parar = true
        cambio.signalAll()
    }

    private fun armar(tipo: Int, seq: Int, ack: Int, carga: ByteArray): ByteArray {
        val p = ByteArray(CABECERA + carga.size)
        p[0] = (BASE or tipo).toByte()
        escribirInt(p, 1, idLocal)
        escribirInt(p, 5, seq)
        escribirInt(p, 9, ack)
        p[13] = (carga.size ushr 8).toByte()
        p[14] = carga.size.toByte()
        System.arraycopy(carga, 0, p, CABECERA, carga.size)
        return p
    }

    private fun enviarPaquete(p: ByteArray, ahora: Long) {
        dg.enviar(p)
        ultimoEnvio = ahora
    }

    private fun emitir(p: Pendiente) {
        val ahora = reloj()
        p.ultimoEnvio = ahora
        enviarPaquete(armar(p.tipo, p.seq, esperado, p.carga), ahora)
    }

    private fun retransmitir(p: Pendiente, ahora: Long) {
        p.intentos++
        p.ultimoEnvio = ahora
        retransmisiones++
        enviarPaquete(armar(p.tipo, p.seq, esperado, p.carga), ahora)
    }

    private fun enviarHello(ahora: Long) {
        val flag = byteArrayOf(if (conocidoRemoto) 1 else 0)
        enviarPaquete(armar(T_HELLO, 0, 0, flag), ahora)
        ultimoHelloEnviado = ahora
    }

    private fun enviarAck(ahora: Long) {
        var sack = 0
        for (i in 0 until 32) if (fuera.containsKey(esperado + 1 + i)) sack = sack or (1 shl i)
        val carga = ByteArray(4)
        escribirInt(carga, 0, sack)
        enviarPaquete(armar(T_ACK, 0, esperado, carga), ahora)
    }

    private fun revisarApertura() {
        if (!abierto && conocidoRemoto && remotoVioMio) {
            abierto = true
            cambio.signalAll()
        }
    }

    private fun procesar(d: ByteArray, ahora: Long) {
        val tipo = d[0].toInt() and 0x0F
        val id = leerInt(d, 1)
        val seq = leerInt(d, 5)
        val ack = leerInt(d, 9)
        val len = ((d[13].toInt() and 0xFF) shl 8) or (d[14].toInt() and 0xFF)
        if (CABECERA + len > d.size) return
        if (conocidoRemoto && id != idRemoto) return

        if (tipo == T_HELLO) {
            ultimoRecibido = ahora
            if (!conocidoRemoto) { idRemoto = id; conocidoRemoto = true }
            if (len >= 1 && d[CABECERA].toInt() == 1) remotoVioMio = true
            revisarApertura()
            // Si el otro aun esta abriendo, se le contesta (como mucho 5 veces por segundo).
            if (ahora - ultimoHelloRespondido >= 200) {
                ultimoHelloRespondido = ahora
                enviarHello(ahora)
            }
            return
        }
        if (!conocidoRemoto) return
        ultimoRecibido = ahora
        remotoVioMio = true
        revisarApertura()

        when (tipo) {
            T_ACK -> {
                val tieneSack = len >= 4
                aplicarAck(ack, if (tieneSack) leerInt(d, CABECERA) else 0, tieneSack, ahora)
            }
            T_DATA, T_FIN -> {
                aplicarAck(ack, 0, false, ahora)
                recibirDatos(tipo, seq, d, len, ahora)
            }
        }
    }

    private fun muestraRtt(r: Long) {
        val m = r.toDouble()
        if (srtt < 0) { srtt = m; rttvar = m / 2 }
        else { rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - m); srtt = 0.875 * srtt + 0.125 * m }
        rto = minOf(8_000L, maxOf(rtoMinMs, (srtt + 4 * rttvar).toLong()))
    }

    private fun aplicarAck(ack: Int, sack: Int, tieneSack: Boolean, ahora: Long) {
        var nuevos = 0
        val it = enVuelo.entries.iterator()
        while (it.hasNext()) {
            val p = it.next().value
            val sacado = tieneSack && p.seq > ack && p.seq - ack - 1 < 32 && ((sack ushr (p.seq - ack - 1)) and 1) == 1
            if (p.seq < ack || sacado) {
                if (p.intentos == 1) muestraRtt(ahora - p.primerEnvio)
                it.remove()
                nuevos++
            }
        }
        if (nuevos > 0) {
            repeat(nuevos) { cwnd += if (cwnd < ssthresh) 1.0 else 1.0 / cwnd }
            cwnd = minOf(cwnd, ventanaMax.toDouble())
            cambio.signalAll()
        }
        // Retransmision rapida: lo que quedo por debajo del mayor paquete confirmado por SACK es un hueco.
        if (tieneSack && sack != 0) {
            val mayor = ack + 1 + (31 - Integer.numberOfLeadingZeros(sack))
            val espera = maxOf(rtoMinMs / 2, (maxOf(srtt, 0.0) * 1.5).toLong())
            for (p in enVuelo.values) {
                if (p.seq < mayor && ahora - p.ultimoEnvio >= espera) retransmitir(p, ahora)
            }
        }
    }

    private fun recibirDatos(tipo: Int, seq: Int, d: ByteArray, len: Int, ahora: Long) {
        if (seq < esperado) { enviarAck(ahora); return }
        if (seq >= esperado + VENTANA_RX) return
        // Contrapresion: si quien lee va lento, no se acepta mas; el emisor reintentara.
        if (bytesLectura > TOPE_LECTURA) { enviarAck(ahora); return }
        if (!fuera.containsKey(seq)) fuera[seq] = Rx(tipo == T_FIN, d.copyOfRange(CABECERA, CABECERA + len))
        while (true) {
            val r = fuera.remove(esperado) ?: break
            if (r.fin) finRecibido = true
            else if (r.carga.isNotEmpty()) { lectura.add(r.carga); bytesLectura += r.carga.size }
            esperado++
        }
        cambio.signalAll()
        enviarAck(ahora)
    }

    private fun temporizadores(ahora: Long) {
        if (!abierto) {
            if (ahora - ultimoHelloEnviado >= 250) enviarHello(ahora)
            if (ahora - inicio > 30_000) fallar(IOException("No se pudo abrir el canal"))
            return
        }
        var hubo = false
        for (p in enVuelo.values) {
            val limite = minOf(8_000L, rto shl minOf(p.intentos - 1, 6))
            if (ahora - p.ultimoEnvio >= limite) {
                if (p.intentos >= INTENTOS_MAX) { fallar(IOException("Sin respuesta del otro extremo")); return }
                retransmitir(p, ahora)
                hubo = true
            }
        }
        if (hubo) {
            cwnd = maxOf(2.0, cwnd / 2)
            ssthresh = cwnd
        }
        if (ahora - ultimoEnvio >= keepaliveMs) enviarAck(ahora)
        if (ahora - ultimoRecibido > inactividadMs) fallar(IOException("Inactividad: el otro extremo no responde"))
    }
}
