package com.optimo.chat

import java.math.BigInteger
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Cliente minimo de la DHT de BitTorrent (BEP 5), solo IPv4 en la fase 0.
 * Se usa como "agenda de encuentro": anunciarse y buscar bajo un identificador que solo conocen los contactos.
 *
 * IMPORTANTE: el [Puerto] comparte UN socket UDP con el transporte (hole punching). El mapeo del NAT que
 * la DHT registra con implied_port=1 es el de ese socket; con otro socket el puerto externo seria distinto.
 * Los paquetes de la DHT empiezan por 'd' (bencode); los del transporte propio, por otro byte (ver [Demux]).
 *
 * Esta clase es bloqueante: se usa desde un hilo de fondo. Sin dependencias.
 */
class Dht(val puerto: Puerto = Demux(DatagramSocket())) {

    class Nodo(val id: ByteArray, val dir: InetSocketAddress)

    class Respuesta(
        val t: ByteArray,
        /** IP y puerto externos del propio movil, tal como los ve el nodo (campo "ip" de BEP 42). */
        val ip: InetSocketAddress?,
        val token: ByteArray?,
        val valores: List<InetSocketAddress>,
        val nodos: List<Nodo>,
        val error: String?
    )

    class Localizacion(
        val valores: List<InetSocketAddress>,
        val ipExterna: InetSocketAddress?,
        val consultados: Int,
        val anunciados: Int
    )

    val idNodo: ByteArray = ByteArray(20).also { SecureRandom().nextBytes(it) }
    private val azar = SecureRandom()

    companion object {
        /** Nodos de arranque publicos. La lista debe poder actualizarse sin cambiar la app. */
        val ARRANQUE = listOf(
            "router.bittorrent.com" to 6881,
            "dht.transmissionbt.com" to 6881,
            "router.utorrent.com" to 6881,
            "dht.libtorrent.org" to 25401
        )

        fun distancia(a: ByteArray, b: ByteArray): BigInteger =
            BigInteger(1, ByteArray(20) { i -> (a[i].toInt() xor b[i].toInt()).toByte() })

        fun direccion(b: ByteArray, desde: Int = 0): InetSocketAddress {
            val ip = InetAddress.getByAddress(b.copyOfRange(desde, desde + 4))
            val puerto = ((b[desde + 4].toInt() and 0xFF) shl 8) or (b[desde + 5].toInt() and 0xFF)
            return InetSocketAddress(ip, puerto)
        }
    }

    val puertoLocal: Int get() = puerto.puertoLocal

    /** Resuelve los nombres de arranque (solo IPv4). */
    fun resolverArranque(): List<InetSocketAddress> {
        val r = ArrayList<InetSocketAddress>()
        for ((host, puerto) in ARRANQUE) {
            try {
                for (a in InetAddress.getAllByName(host)) {
                    if (a.address.size == 4) r.add(InetSocketAddress(a, puerto))
                }
            } catch (_: Exception) {
                // Sin DNS para ese nombre: se sigue con los demas.
            }
        }
        return r
    }

    /** Envia una consulta KRPC y devuelve su identificador de transaccion. */
    fun enviar(destino: InetSocketAddress, q: String, args: Map<String, Any?>): ByteArray {
        val t = ByteArray(2).also { azar.nextBytes(it) }
        val a = HashMap<String, Any?>(args)
        a["id"] = idNodo
        val msg = mapOf<String, Any?>("t" to t, "y" to "q", "q" to q, "a" to a)
        puerto.enviar(destino, Bencode.codificar(msg))
        return t
    }

    /** Interpreta un datagrama recibido. Devuelve null si no es una respuesta KRPC valida. */
    fun interpretar(datos: ByteArray): Respuesta? {
        val raiz = try { Bencode.decodificar(datos).comoMapa() } catch (_: Exception) { null } ?: return null
        val t = raiz["t"].comoBytes() ?: return null
        val y = raiz["y"].comoBytes()?.let { String(it, Charsets.ISO_8859_1) }
        if (y == "e") return Respuesta(t, null, null, emptyList(), emptyList(), "error remoto")
        if (y != "r") return null
        val cuerpo = raiz["r"].comoMapa() ?: return null

        val ipBytes = raiz["ip"].comoBytes()
        val ip = if (ipBytes != null && ipBytes.size == 6) direccion(ipBytes) else null

        val valores = ArrayList<InetSocketAddress>()
        (cuerpo["values"] as? List<*>)?.forEach { v ->
            val b = v.comoBytes()
            if (b != null && b.size == 6) valores.add(direccion(b))
        }

        val nodos = ArrayList<Nodo>()
        cuerpo["nodes"].comoBytes()?.let { b ->
            var i = 0
            while (i + 26 <= b.size) {
                nodos.add(Nodo(b.copyOfRange(i, i + 20), direccion(b, i + 20)))
                i += 26
            }
        }
        return Respuesta(t, ip, cuerpo["token"].comoBytes(), valores, nodos, null)
    }

    /** Consulta y espera la respuesta (ignora otros paquetes). Null si no llega a tiempo. */
    fun consulta(destino: InetSocketAddress, q: String, args: Map<String, Any?>, esperaMs: Int = 1200): Respuesta? {
        val t = enviar(destino, q, args)
        val fin = System.currentTimeMillis() + esperaMs
        while (true) {
            val resto = (fin - System.currentTimeMillis()).toInt()
            if (resto <= 0) return null
            val recibido = puerto.recibir(resto) ?: return null
            val r = interpretar(recibido.datos) ?: continue
            if (r.t.contentEquals(t)) return r
        }
    }

    fun buscarNodos(destino: InetSocketAddress): Respuesta? =
        consulta(destino, "find_node", mapOf("target" to idNodo))

    /**
     * Busqueda iterativa de pares para el identificador [ih] (20 bytes). Si [anunciar] es true, termina con
     * announce_peer (implied_port=1) a los nodos mas cercanos que dieron token.
     */
    fun localizar(ih: ByteArray, semillas: List<InetSocketAddress>, anunciar: Boolean): Localizacion {
        val desconocido = ByteArray(20) { 0xFF.toByte() }
        val conocidos = LinkedHashMap<String, Nodo>()
        val consultados = HashSet<String>()
        val tokens = HashMap<String, ByteArray>()
        val valores = LinkedHashSet<InetSocketAddress>()
        var ipExterna: InetSocketAddress? = null

        semillas.forEach { conocidos[it.toString()] = Nodo(desconocido, it) }

        repeat(6) {
            val ronda = conocidos.values
                .filter { it.dir.toString() !in consultados }
                .sortedBy { distancia(it.id, ih) }
                .take(6)
            if (ronda.isEmpty()) return@repeat
            for (n in ronda) {
                val clave = n.dir.toString()
                consultados.add(clave)
                val r = consulta(n.dir, "get_peers", mapOf("info_hash" to ih)) ?: continue
                if (r.ip != null) ipExterna = r.ip
                r.token?.let { tokens[clave] = it }
                valores.addAll(r.valores)
                r.nodos.forEach { x -> conocidos.putIfAbsent(x.dir.toString(), x) }
            }
        }

        var anunciados = 0
        if (anunciar) {
            val destinos = conocidos.values
                .filter { tokens.containsKey(it.dir.toString()) }
                .sortedBy { distancia(it.id, ih) }
                .take(8)
            for (n in destinos) {
                val r = consulta(
                    n.dir, "announce_peer",
                    mapOf(
                        "info_hash" to ih,
                        "port" to puertoLocal,
                        "implied_port" to 1,
                        "token" to tokens[n.dir.toString()]
                    )
                )
                if (r != null && r.error == null) anunciados++
            }
        }
        return Localizacion(valores.toList(), ipExterna, consultados.size, anunciados)
    }
}
