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

    /** Siguiente sonda de punching (primer byte 'O'). Va aparte de [recibir] para que la busqueda en la DHT no se las coma. */
    fun recibirSonda(esperaMs: Int): Recibido?

    /** Origenes de paquetes del transporte que llegaron sin canal registrado: prueba de que el otro ya nos alcanza. */
    fun transporteEntrante(): List<SocketAddress>

    /** Descarta lo acumulado de origenes sin canal (de intentos anteriores). */
    fun olvidarNoRegistrados()

    /** Datagramas del transporte fiable hacia/desde [remoto]. Varias llamadas con el mismo remoto comparten cola. */
    fun canal(remoto: SocketAddress): Datagramas

    fun cerrar()

    /** Resumen de los envios (correctos y fallidos) para explicar un fallo de red al usuario. */
    val diagnostico: String
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
    private val sondas = LinkedBlockingQueue<Recibido>(256)
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
            } else if (datos[0] == 'O'.code.toByte()) {
                val r = Recibido(datos, de)
                if (!sondas.offer(r)) { sondas.poll(); sondas.offer(r) }
            } else {
                val r = Recibido(datos, de)
                if (!otros.offer(r)) { otros.poll(); otros.offer(r) }
            }
        }
    }

    @Volatile private var enviosOk = 0
    @Volatile private var enviosFallidos = 0
    @Volatile private var ultimoFallo: String? = null

    /**
     * UDP no garantiza nada: un envio que falla (destino no alcanzable, firewall del movil que rechaza un destino, nodo
     * DHT con direccion absurda) NO debe abortar quien llama. Se cuenta y se sigue; [diagnostico] lo resume.
     */
    override fun enviar(destino: SocketAddress, datos: ByteArray) {
        try {
            socket.send(DatagramPacket(datos, datos.size, destino))
            enviosOk++
        } catch (e: java.io.IOException) {
            enviosFallidos++
            ultimoFallo = "${e.message ?: e.javaClass.simpleName} -> ${Informe.enmascarar(destino as? java.net.InetSocketAddress)}"
        }
    }

    override val diagnostico: String
        get() = "UDP: $enviosOk envios correctos, $enviosFallidos fallidos" + (ultimoFallo?.let { " (ultimo fallo: $it)" } ?: "")

    override fun recibir(esperaMs: Int): Recibido? = otros.poll(esperaMs.toLong(), TimeUnit.MILLISECONDS)

    override fun recibirSonda(esperaMs: Int): Recibido? = sondas.poll(esperaMs.toLong(), TimeUnit.MILLISECONDS)

    override fun transporteEntrante(): List<SocketAddress> =
        colas.entries.filter { !registrados.contains(it.key) && it.value.isNotEmpty() }.map { it.key }

    override fun olvidarNoRegistrados() {
        for (k in colas.keys.toList()) if (!registrados.contains(k)) colas.remove(k)
        sondas.clear()
    }

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
