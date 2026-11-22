package com.optimo.chat

import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pruebas P4 (clasificacion de NAT) y P5 (encuentro por DHT y hole punching) de la fase 0.
 * Son bloqueantes: llamar desde un hilo de fondo. Cada funcion usa el socket del [Dht] recibido.
 */
object SondaNat {

    /**
     * P4. Pregunta desde UN solo socket a varios nodos distintos y compara el puerto externo que cada uno ve.
     * Mismo puerto hacia todos = mapeo estable (el punching es probable). Puerto distinto = simetrico.
     */
    fun clasificar(dht: Dht, log: (String) -> Unit): String {
        val semillas = dht.resolverArranque()
        if (semillas.isEmpty()) return "NAT: sin nodos de arranque (sin DNS o sin red)"
        log("Arranque: ${semillas.size} direcciones")

        val destinos = LinkedHashMap<String, InetSocketAddress>()
        semillas.forEach { destinos[it.address.hostAddress ?: it.toString()] = it }
        // Ampliar con nodos que respondan, para llegar a 6 destinos con IPs distintas.
        for (s in semillas) {
            if (destinos.size >= 8) break
            val r = dht.buscarNodos(s) ?: continue
            r.nodos.forEach { n ->
                val k = n.dir.address.hostAddress ?: n.dir.toString()
                if (destinos.size < 8) destinos.putIfAbsent(k, n.dir)
            }
        }

        val vistos = ArrayList<Pair<InetSocketAddress, InetSocketAddress>>()
        for (d in destinos.values) {
            if (vistos.size >= 6) break
            val r = dht.buscarNodos(d) ?: continue
            val ip = r.ip ?: continue
            vistos.add(d to ip)
            log("  ${Informe.enmascarar(d)} ve ${Informe.enmascarar(ip)}")
        }
        if (vistos.size < 2) return "NAT: indeterminado (respondieron ${vistos.size} nodos)"

        val puertos = vistos.map { it.second.port }.toSet()
        val ips = vistos.map { it.second.address.hostAddress }.toSet()
        val veredicto = if (puertos.size == 1) {
            "mapeo ESTABLE (mismo puerto hacia ${vistos.size} destinos): punching probable"
        } else {
            "mapeo DISTINTO por destino (${puertos.size} puertos): NAT simetrico, punching improbable"
        }
        return "NAT: $veredicto; IPs externas distintas: ${ips.size}; puerto local ${dht.puertoLocal}"
    }

    /** Identificador de encuentro de 20 bytes: HMAC-SHA256(secreto, version + ventana de 2 h) truncado. */
    fun identificador(secreto: ByteArray, ventana: Long = System.currentTimeMillis() / 1000 / 7200): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secreto, "HmacSHA256"))
        return mac.doFinal("optimochat-rdv-v1:$ventana".toByteArray(Charsets.UTF_8)).copyOf(20)
    }

    private val MAGIA = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte(), '0'.code.toByte())
    private const val SONDA = '0'.code.toByte()
    private const val ACUSE = '1'.code.toByte()

    private fun paquete(tipo: Byte, nonce: ByteArray): ByteArray = MAGIA + byteArrayOf(tipo) + nonce

    private fun esNuestro(d: ByteArray, n: Int): Boolean =
        n >= 5 && d[0] == MAGIA[0] && d[1] == MAGIA[1] && d[2] == MAGIA[2] && d[3] == MAGIA[3]

    /**
     * P5. Con un secreto compartido de prueba (el mismo texto en los dos moviles): se anuncia y se busca en la DHT
     * bajo el identificador derivado, y cuando aparece el otro se lanzan rafagas UDP simultaneas desde el mismo
     * socket. Exito = llega un paquete de sonda o de acuse del otro movil.
     */
    fun encuentroYPunching(dht: Dht, secreto: ByteArray, maxMs: Long, log: (String) -> Unit): String {
        val inicio = System.currentTimeMillis()
        val semillas = dht.resolverArranque()
        if (semillas.isEmpty()) return "Punching: sin nodos de arranque"
        val ih = identificador(secreto)
        val azar = SecureRandom()
        var propia: InetSocketAddress? = null
        var ronda = 0

        while (System.currentTimeMillis() - inicio < maxMs) {
            ronda++
            val loc = dht.localizar(ih, semillas, anunciar = (ronda % 3 == 1))
            if (loc.ipExterna != null) propia = loc.ipExterna
            val candidatos = loc.valores.filter { it != propia }
            log("Ronda $ronda: ${loc.consultados} nodos, anunciado en ${loc.anunciados}, " +
                "yo=${Informe.enmascarar(propia)}, candidatos=${candidatos.size}")

            if (candidatos.isNotEmpty()) {
                val t0 = System.currentTimeMillis()
                val nonce = ByteArray(4).also { azar.nextBytes(it) }
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() - t0 < 15_000) {
                    for (c in candidatos) {
                        val s = paquete(SONDA, nonce)
                        dht.socket.send(DatagramPacket(s, s.size, c))
                    }
                    dht.socket.soTimeout = 200
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        dht.socket.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val d = p.data.copyOf(p.length)
                    if (!esNuestro(d, p.length)) continue
                    // Responder con acuses para que el otro lado tambien vea trafico entrante.
                    repeat(3) {
                        val a = paquete(ACUSE, nonce)
                        dht.socket.send(DatagramPacket(a, a.size, p.socketAddress))
                    }
                    val ms = System.currentTimeMillis() - t0
                    return "Punching: EXITO en $ms ms con ${Informe.enmascarar(p.socketAddress as? InetSocketAddress)}" +
                        " (total ${(System.currentTimeMillis() - inicio) / 1000} s)"
                }
                log("Sin respuesta tras 15 s de rafagas; se vuelve a buscar")
            }
            // Pausa corta antes de otra ronda, atendiendo paquetes de sonda del otro mientras tanto.
            val finPausa = System.currentTimeMillis() + 6_000
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < finPausa) {
                dht.socket.soTimeout = 500
                val p = DatagramPacket(buf, buf.size)
                try { dht.socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                val d = p.data.copyOf(p.length)
                if (esNuestro(d, p.length)) {
                    val a = paquete(ACUSE, d.copyOfRange(5, minOf(d.size, 9)))
                    repeat(3) { dht.socket.send(DatagramPacket(a, a.size, p.socketAddress)) }
                    return "Punching: EXITO (sonda recibida durante la espera) de " +
                        Informe.enmascarar(p.socketAddress as? InetSocketAddress)
                }
            }
        }
        return "Punching: SIN EXITO en ${maxMs / 1000} s (propia=${Informe.enmascarar(propia)})"
    }
}
