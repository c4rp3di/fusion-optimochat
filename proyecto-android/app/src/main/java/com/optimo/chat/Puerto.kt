package com.optimo.chat

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Un datagrama que no pertenece al transporte fiable (respuesta de la DHT, sonda de punching...). */
class Recibido(val datos: ByteArray, val de: SocketAddress)

/**
 * UN socket UDP compartido. La DHT, las sondas de punching y el transporte fiable usan el mismo puerto local, porque
 * el mapeo del NAT que la DHT registra es el de ese socket. [Demux] es la implementacion real.
 */
interface Puerto {
    val puertoLocal: Int

    fun enviar(destino: SocketAddress, datos: ByteArray)

    /** Siguiente datagrama que NO es del transporte fiable. Null si no llega ninguno en [esperaMs]. */
    fun recibir(esperaMs: Int): Recibido?

    /** Datagramas del transporte fiable hacia/desde [remoto]. Varias llamadas con el mismo remoto comparten cola. */
    fun canal(remoto: SocketAddress): Datagramas

    fun cerrar()
}

/**
 * Un hilo lee el socket y reparte: los paquetes del transporte (primer byte 0xA?) van a la cola de su remoto y el
 * resto (DHT 'd', sondas 'O') a una cola general. Sin dependencias.
 *
 * Limites: la cola general guarda 1024 datagramas (descarta el mas antiguo) y cada canal 512. Los paquetes de remotos
 * aun no registrados con [canal] se guardan para no perder el primer HELLO, pero solo de [MAX_REMOTOS_SIN_REGISTRAR]
 * remotos distintos, para que nadie agote la memoria con origenes falsos.
 */
class Demux(private val socket: DatagramSocket) : Puerto {

    companion object {
        const val MAX_REMOTOS_SIN_REGISTRAR = 32
    }

    private val colas = ConcurrentHashMap<SocketAddress, LinkedBlockingQueue<ByteArray>>()
    private val registrados = ConcurrentHashMap.newKeySet<SocketAddress>()
    private val otros = LinkedBlockingQueue<Recibido>(1024)
    @Volatile private var cerrado = false

    /** Cuantos remotos tienen cola ahora mismo (registrados o no). Para pruebas. */
    val remotosConCola: Int get() = colas.size

    override val puertoLocal: Int get() = socket.localPort

    init {
        Thread({ bucle() }, "demux-udp").apply { isDaemon = true; start() }
    }

    private fun bucle() {
        val buf = ByteArray(2048)
        while (!cerrado) {
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (_: Exception) {
                if (cerrado || socket.isClosed) return
                continue
            }
            val datos = p.data.copyOf(p.length)
            val de = p.socketAddress
            if (UdpFiable.esPaquete(datos, datos.size)) {
                var q = colas[de]
                if (q == null) {
                    val sinRegistrar = colas.size - registrados.size
                    if (sinRegistrar >= MAX_REMOTOS_SIN_REGISTRAR) continue
                    q = colas.computeIfAbsent(de) { LinkedBlockingQueue(512) }
                }
                q.offer(datos)
            } else {
                val r = Recibido(datos, de)
                if (!otros.offer(r)) { otros.poll(); otros.offer(r) }
            }
        }
    }

    override fun enviar(destino: SocketAddress, datos: ByteArray) {
        socket.send(DatagramPacket(datos, datos.size, destino))
    }

    override fun recibir(esperaMs: Int): Recibido? = otros.poll(esperaMs.toLong(), TimeUnit.MILLISECONDS)

    override fun canal(remoto: SocketAddress): Datagramas {
        registrados.add(remoto)
        val cola = colas.computeIfAbsent(remoto) { LinkedBlockingQueue(512) }
        return object : Datagramas {
            override fun enviar(datos: ByteArray) = this@Demux.enviar(remoto, datos)
            override fun recibir(esperaMs: Int): ByteArray? = cola.poll(esperaMs.toLong(), TimeUnit.MILLISECONDS)
            override fun cerrar() { colas.remove(remoto); registrados.remove(remoto) }
        }
    }

    override fun cerrar() {
        cerrado = true
        socket.close()
    }
}
