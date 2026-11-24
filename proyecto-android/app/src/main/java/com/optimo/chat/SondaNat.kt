package com.optimo.chat

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
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
     * P5. Con un secreto compartido: se anuncia y se busca en la DHT bajo el identificador derivado, y a la vez se
     * busca al otro movil en la red local ([DescubrimientoLocal]). Cuando aparece se lanzan rafagas UDP simultaneas
     * desde el mismo [Puerto]. Exito = llega una sonda o un acuse del otro movil.
     * [local]: false desactiva la busqueda en la red local; [localExplicito] la sustituye (para pruebas).
     */
    fun encuentro(
        dht: Dht, secreto: ByteArray, maxMs: Long, log: (String) -> Unit,
        semillasDadas: List<InetSocketAddress>? = null,
        rafagaMs: Long = 15_000, pausaMs: Long = 6_000,
        local: Boolean = true,
        localExplicito: DescubrimientoLocal? = null,
        cancelar: () -> Boolean = { false }
    ): Resultado {
        val inicio = System.currentTimeMillis()
        val semillas = semillasDadas ?: dht.resolverArranque()
        val ih = identificador(secreto)
        val vecinos = localExplicito ?: if (local) DescubrimientoLocal.estandar(dht.puertoLocal, ih).iniciar() else null
        val azar = SecureRandom()
        val nonce = ByteArray(4).also { azar.nextBytes(it) }
        var propia: InetSocketAddress? = null
        var ronda = 0

        fun exito(de: InetSocketAddress?, t0: Long, texto: String) =
            Resultado(true, de, System.currentTimeMillis() - t0, texto)

        fun fallo(texto: String) = Resultado(false, null, System.currentTimeMillis() - inicio,
            texto + " [" + dht.puerto.diagnostico + (vecinos?.let { "; red local: ${it.recibidas} balizas recibidas, ${it.enviadas} enviadas" +
                (it.errorEscucha?.let { e -> ", sin escuchar: $e" } ?: "") } ?: "") + "]")

        // Un hilo atiende SIEMPRE las sondas del otro y las pruebas de que su transporte ya nos alcanza, tambien mientras
        // el hilo principal esta dentro de una busqueda en la DHT: antes esos paquetes se perdian y el otro movil, que ya
        // habia dado el encuentro por hecho, se quedaba sin respuesta.
        val hallado = AtomicReference<InetSocketAddress?>(null)
        val textoHallazgo = AtomicReference<String?>(null)
        val vigentes = AtomicReference<List<InetSocketAddress>>(emptyList())
        val fin = java.util.concurrent.atomic.AtomicBoolean(false)
        dht.puerto.olvidarNoRegistrados()
        val vigilante = Thread({
            while (!fin.get() && hallado.get() == null) {
                val p = dht.puerto.recibirSonda(100)
                if (p != null && esNuestro(p.datos, p.datos.size)) {
                    val de = p.de as? InetSocketAddress
                    if (p.datos[4] == SONDA) {
                        val a = paquete(ACUSE, p.datos.copyOfRange(5, minOf(p.datos.size, 9)))
                        repeat(3) { dht.puerto.enviar(p.de, a) }
                    }
                    textoHallazgo.set("sonda de ${Informe.enmascarar(de)}")
                    hallado.set(de)
                    break
                }
                val ips = vigentes.get().map { it.address }.toSet()
                val t = dht.puerto.transporteEntrante().firstOrNull { (it as? InetSocketAddress)?.address in ips }
                if (t != null) {
                    textoHallazgo.set("transporte de ${Informe.enmascarar(t as? InetSocketAddress)}")
                    hallado.set(t as? InetSocketAddress)
                    break
                }
            }
        }, "encuentro-vigilante").apply { isDaemon = true; start() }

        fun parada() = hallado.get() != null || cancelar() || System.currentTimeMillis() - inicio >= maxMs

        try {
            if (semillas.isEmpty() && vecinos == null) return fallo("Punching: sin nodos de arranque")
            if (vecinos != null) {
                // Si el otro esta en la misma red aparece en un par de segundos: no hace falta esperar a la DHT.
                val limite = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < limite && vecinos.candidatos().isEmpty() && !parada()) Thread.sleep(100)
            }

            while (!parada()) {
                ronda++
                val loc = if (semillas.isEmpty()) Dht.Localizacion(emptyList(), null, 0, 0)
                else dht.localizar(ih, semillas, anunciar = (ronda % 3 == 1), parar = { parada() })
                if (loc.ipExterna != null) propia = loc.ipExterna
                val desdeDht = loc.valores.filter { it != propia }
                fun candidatos(): List<InetSocketAddress> =
                    (desdeDht + (vecinos?.candidatos() ?: emptyList())).distinct().also { vigentes.set(it) }
                log("Ronda $ronda: ${loc.consultados} nodos, anunciado en ${loc.anunciados}, " +
                    "yo=${Informe.enmascarar(propia)}, candidatos=${candidatos().size} (red local: ${vecinos?.candidatos()?.size ?: 0})")

                if (candidatos().isNotEmpty()) {
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < rafagaMs && !parada()) {
                        for (c in candidatos()) dht.puerto.enviar(c, paquete(SONDA, nonce))
                        Thread.sleep(200)
                    }
                    if (!parada()) log("Sin respuesta tras ${rafagaMs / 1000} s de rafagas; se vuelve a buscar")
                }
                // Pausa antes de otra ronda, sondeando a los vecinos de la red local.
                val finPausa = System.currentTimeMillis() + pausaMs
                while (System.currentTimeMillis() < finPausa && !parada()) {
                    candidatos().filter { c -> vecinos?.candidatos()?.contains(c) == true }
                        .forEach { dht.puerto.enviar(it, paquete(SONDA, nonce)) }
                    Thread.sleep(300)
                }
            }
            val h = hallado.get()
            if (h != null) {
                return exito(h, inicio, "Punching: EXITO en ${System.currentTimeMillis() - inicio} ms con ${Informe.enmascarar(h)} (${textoHallazgo.get()})")
            }
            return fallo("Punching: SIN EXITO en ${maxMs / 1000} s (propia=${Informe.enmascarar(propia)})")
        } finally {
            fin.set(true)
            vigilante.join(500)
            if (localExplicito == null) vecinos?.close()
        }
    }

    fun encuentroYPunching(dht: Dht, secreto: ByteArray, maxMs: Long, log: (String) -> Unit): String =
        encuentro(dht, secreto, maxMs, log).texto
}
