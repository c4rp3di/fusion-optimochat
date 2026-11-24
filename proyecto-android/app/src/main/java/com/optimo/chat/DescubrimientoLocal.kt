package com.optimo.chat

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Descubrimiento en la red local (misma Wi-Fi). Dos moviles tras el mismo router ven en la DHT la MISMA IP publica, y
 * la mayoria de routers no devuelven a la red interna los paquetes dirigidos a su propia IP publica (sin "hairpin"),
 * asi que el punching por DHT falla justo cuando estan al lado. Aqui cada movil difunde por la red local una baliza
 * con un identificador derivado del secreto (el mismo que usa la DHT, solo sus 8 primeros bytes) y el puerto de su socket
 * principal, y escucha las del otro. La IP del otro se toma del origen del paquete: es su IP privada, alcanzable
 * directamente.
 *
 * Baliza (22 bytes): "OCHL" | id 8 | nonce de instancia 8 | puerto principal u16. Solo se aceptan balizas con el
 * mismo id y distinto nonce. La baliza no revela nada util: el id cambia cada 2 horas y no se puede invertir.
 * Si el router aisla a los clientes (Wi-Fi de invitados) no llega nada y el encuentro sigue por la DHT.
 */
class DescubrimientoLocal(
    id: ByteArray,
    private val puertoPrincipal: Int,
    private val destinos: List<InetSocketAddress>,
    private val puertoEscucha: Int = PUERTO,
    private val intervaloMs: Long = 1000,
    private val vigenciaMs: Long = 30_000
) : Closeable {

    companion object {
        const val PUERTO = 47475
        private val MAGIA = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte(), 'L'.code.toByte())

        /** Direccion de difusion global y la de cada interfaz IPv4 activa. */
        fun destinosBroadcast(puerto: Int = PUERTO): List<InetSocketAddress> {
            val r = LinkedHashSet<InetAddress>()
            try { r.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) ia.broadcast?.let { r.add(it) }
                }
            } catch (_: Exception) {}
            return r.map { InetSocketAddress(it, puerto) }
        }

        /** Para uso normal: difusion por la red local en el puerto estandar. */
        fun estandar(puertoPrincipal: Int, identificador: ByteArray) =
            DescubrimientoLocal(identificador, puertoPrincipal, destinosBroadcast())
    }

    private val id8 = id.copyOf(8)
    private val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
    private val vistos = ConcurrentHashMap<InetSocketAddress, Long>()
    @Volatile private var cerrado = false
    private var escucha: DatagramSocket? = null
    private var envio: DatagramSocket? = null

    /** Motivo por el que no se pudo escuchar (puerto ocupado, sin permiso...). Null si escucha. */
    @Volatile var errorEscucha: String? = null; private set
    @Volatile var enviadas = 0; private set
    @Volatile var recibidas = 0; private set

    fun iniciar(): DescubrimientoLocal {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.soTimeout = 500
            s.bind(InetSocketAddress(puertoEscucha))
            escucha = s
            Thread({ escuchar(s) }, "descubrimiento-escucha").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            errorEscucha = e.message ?: e.javaClass.simpleName
        }
        try {
            val s = DatagramSocket()
            s.broadcast = true
            envio = s
            Thread({ emitir(s) }, "descubrimiento-baliza").apply { isDaemon = true; start() }
        } catch (_: Exception) {}
        return this
    }

    private fun baliza(): ByteArray {
        val p = puertoPrincipal
        return MAGIA + id8 + nonce + byteArrayOf((p shr 8).toByte(), p.toByte())
    }

    private fun emitir(s: DatagramSocket) {
        val b = baliza()
        while (!cerrado) {
            for (d in destinos) {
                try { s.send(DatagramPacket(b, b.size, d)); enviadas++ } catch (_: Exception) { /* red sin difusion: se ignora */ }
            }
            try { Thread.sleep(intervaloMs) } catch (_: InterruptedException) { return }
        }
    }

    private fun escuchar(s: DatagramSocket) {
        val buf = ByteArray(64)
        while (!cerrado) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { if (cerrado) return else continue }
            if (p.length != 22) continue
            val d = p.data
            if (!(0..3).all { d[it] == MAGIA[it] }) continue
            if (!(0..7).all { d[4 + it] == id8[it] }) continue
            if ((0..7).all { d[12 + it] == nonce[it] }) continue          // nuestra propia baliza
            val puerto = ((d[20].toInt() and 0xFF) shl 8) or (d[21].toInt() and 0xFF)
            if (puerto == 0) continue
            recibidas++
            vistos[InetSocketAddress(p.address, puerto)] = System.currentTimeMillis()
        }
    }

    /** Moviles vistos hace menos de [vigenciaMs], con su IP de la red local y el puerto de su socket principal. */
    fun candidatos(): List<InetSocketAddress> {
        val ahora = System.currentTimeMillis()
        return vistos.filter { ahora - it.value < vigenciaMs }.keys.toList()
    }

    override fun close() {
        cerrado = true
        try { escucha?.close() } catch (_: Exception) {}
        try { envio?.close() } catch (_: Exception) {}
    }
}
