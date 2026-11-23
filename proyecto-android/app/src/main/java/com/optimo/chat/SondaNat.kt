package com.optimo.chat

import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pruebas P4 (clasificacion de NAT) y P5 (encuentro por DHT y hole punching) de la fase 0.
 * Son bloqueantes: llamar desde un hilo de fondo. Cada funcion usa el [Puerto] del [Dht] recibido.
 */
object SondaNat {

    /**
     * P4. Pregunta desde UN solo socket a varios nodos distintos y compara el puerto externo que cada uno ve.
     * Mismo puerto hacia todos = mapeo estable (el punching es probable). Puerto distinto = simetrico.
     */
    fun clasificar(dht: Dht, log: (String) -> Unit, semillasDadas: List<InetSocketAddress>? = null): String {
        val semillas = semillasDadas ?: dht.resolverArranque()
        if (semillas.isEmpty()) return "NAT: sin nodos de arranque (sin DNS o sin red)"
        log("Arranque: ${semillas.size} direcciones")

        val destinos = LinkedHashMap<String, InetSocketAddress>()
        semillas.forEach { destinos[it.toString()] = it }
        // Ampliar con nodos que respondan, para llegar a 6 destinos con IPs distintas.
        for (s in semillas) {
            if (destinos.size >= 8) break
            val r = dht.buscarNodos(s) ?: continue
            r.nodos.forEach { n ->
                val k = n.dir.toString()
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

    class Resultado(val exito: Boolean, val remoto: InetSocketAddress?, val ms: Long, val texto: String)

    /**
     * P5. Con un secreto compartido: se anuncia y se busca en la DHT bajo el identificador derivado, y cuando aparece
     * el otro se lanzan rafagas UDP simultaneas desde el mismo [Puerto]. Exito = llega una sonda o un acuse del otro.
     */
    fun encuentro(
        dht: Dht, secreto: ByteArray, maxMs: Long, log: (String) -> Unit,
        semillasDadas: List<InetSocketAddress>? = null,
        rafagaMs: Long = 15_000, pausaMs: Long = 6_000,
        cancelar: () -> Boolean = { false }
    ): Resultado {
        val inicio = System.currentTimeMillis()
        val semillas = semillasDadas ?: dht.resolverArranque()
        if (semillas.isEmpty()) return Resultado(false, null, 0, "Punching: sin nodos de arranque")
        val ih = identificador(secreto)
        val azar = SecureRandom()
        var propia: InetSocketAddress? = null
        var ronda = 0

        fun exito(de: InetSocketAddress?, t0: Long, texto: String) =
            Resultado(true, de, System.currentTimeMillis() - t0, texto)

        while (System.currentTimeMillis() - inicio < maxMs && !cancelar()) {
            ronda++
            val loc = dht.localizar(ih, semillas, anunciar = (ronda % 3 == 1))
            if (loc.ipExterna != null) propia = loc.ipExterna
            val candidatos = loc.valores.filter { it != propia }
            log("Ronda $ronda: ${loc.consultados} nodos, anunciado en ${loc.anunciados}, " +
                "yo=${Informe.enmascarar(propia)}, candidatos=${candidatos.size}")

            if (candidatos.isNotEmpty()) {
                val t0 = System.currentTimeMillis()
                val nonce = ByteArray(4).also { azar.nextBytes(it) }
                while (System.currentTimeMillis() - t0 < rafagaMs && !cancelar()) {
                    for (c in candidatos) dht.puerto.enviar(c, paquete(SONDA, nonce))
                    val p = dht.puerto.recibir(200) ?: continue
                    if (!esNuestro(p.datos, p.datos.size)) continue
                    val de = p.de as? InetSocketAddress
                    repeat(3) { dht.puerto.enviar(p.de, paquete(ACUSE, nonce)) }
                    val ms = System.currentTimeMillis() - t0
                    return exito(de, t0, "Punching: EXITO en $ms ms con ${Informe.enmascarar(de)}" +
                        " (total ${(System.currentTimeMillis() - inicio) / 1000} s)")
                }
                log("Sin respuesta tras ${rafagaMs / 1000} s de rafagas; se vuelve a buscar")
            }
            // Pausa antes de otra ronda, atendiendo las sondas del otro mientras tanto.
            val finPausa = System.currentTimeMillis() + pausaMs
            while (System.currentTimeMillis() < finPausa && !cancelar()) {
                val p = dht.puerto.recibir(500) ?: continue
                if (esNuestro(p.datos, p.datos.size)) {
                    val de = p.de as? InetSocketAddress
                    val a = paquete(ACUSE, p.datos.copyOfRange(5, minOf(p.datos.size, 9)))
                    repeat(3) { dht.puerto.enviar(p.de, a) }
                    return exito(de, inicio, "Punching: EXITO (sonda recibida durante la espera) de " +
                        Informe.enmascarar(de))
                }
            }
        }
        return Resultado(false, null, System.currentTimeMillis() - inicio,
            "Punching: SIN EXITO en ${maxMs / 1000} s (propia=${Informe.enmascarar(propia)})")
    }

    fun encuentroYPunching(dht: Dht, secreto: ByteArray, maxMs: Long, log: (String) -> Unit): String =
        encuentro(dht, secreto, maxMs, log).texto
}
