import com.optimo.chat.Bencode
import com.optimo.chat.Demux
import com.optimo.chat.Dht
import com.optimo.chat.SondaNat
import com.optimo.chat.comoBytes
import com.optimo.chat.comoMapa
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

var f6 = 0
fun ok6(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f6++ }

/** Nodo DHT de mentira en loopback: find_node, get_peers (con token) y announce_peer (implied_port). */
class NodoFalso(private val mentirPuerto: Int = 0) {
    val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val dir = InetSocketAddress("127.0.0.1", socket.localPort)
    private val pares = HashMap<String, LinkedHashSet<InetSocketAddress>>()
    @Volatile private var activo = true
    private val id = ByteArray(20) { 7 }

    init { thread(isDaemon = true, name = "nodo-falso") { bucle() } }

    private fun sext(a: InetSocketAddress, puertoForzado: Int = 0): ByteArray {
        val p = if (puertoForzado != 0) puertoForzado else a.port
        return a.address.address + byteArrayOf((p shr 8).toByte(), p.toByte())
    }

    private fun bucle() {
        val buf = ByteArray(2048)
        while (activo) {
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (_: Exception) { return }
            val origen = p.socketAddress as InetSocketAddress
            val raiz = try { Bencode.decodificar(p.data.copyOf(p.length)).comoMapa() } catch (_: Exception) { null } ?: continue
            val t = raiz["t"].comoBytes() ?: continue
            val q = raiz["q"].comoBytes()?.let { String(it) } ?: continue
            val a = raiz["a"].comoMapa() ?: continue
            val r = HashMap<String, Any?>()
            r["id"] = id
            when (q) {
                "find_node" -> r["nodes"] = ByteArray(0)
                "get_peers" -> {
                    val ih = a["info_hash"].comoBytes() ?: continue
                    r["token"] = "tok".toByteArray()
                    val v = synchronized(pares) { pares[String(ih, Charsets.ISO_8859_1)]?.toList() ?: emptyList() }
                    if (v.isNotEmpty()) r["values"] = v.map { sext(it) }
                    r["nodes"] = ByteArray(0)
                }
                "announce_peer" -> {
                    val ih = a["info_hash"].comoBytes() ?: continue
                    if (a["token"].comoBytes()?.let { String(it) } != "tok") continue
                    val implied = (a["implied_port"] as? Long) == 1L
                    val puerto = if (implied) origen.port else ((a["port"] as? Long)?.toInt() ?: continue)
                    synchronized(pares) {
                        pares.getOrPut(String(ih, Charsets.ISO_8859_1)) { LinkedHashSet() }
                            .add(InetSocketAddress(origen.address, puerto))
                    }
                }
                else -> continue
            }
            val resp = mapOf<String, Any?>("t" to t, "y" to "r", "ip" to sext(origen, mentirPuerto), "r" to r)
            val bytes = Bencode.codificar(resp)
            try { socket.send(DatagramPacket(bytes, bytes.size, origen)) } catch (_: Exception) { return }
        }
    }

    fun cerrar() { activo = false; socket.close() }
}

private fun loop(p: Int) = InetSocketAddress("127.0.0.1", p)

fun mainDemux(): Int {
    // 1. Transporte fiable sobre dos Demux reales, 500 KB en cada sentido
    run {
        val a = Demux(DatagramSocket()); val b = Demux(DatagramSocket())
        val ua = nuevo(a.canal(loop(b.puertoLocal))); val ub = nuevo(b.canal(loop(a.puertoLocal)))
        ok6(ua.conectar(3000) && ub.conectar(3000), "UdpFiable conecta sobre sockets UDP reales")
        val da = aleatorio(500_000, 21); val db = aleatorio(500_000, 22)
        var ra: ByteArray? = null; var rb: ByteArray? = null
        val t0 = System.currentTimeMillis()
        listOf(
            thread { ua.escribir(da, 0, da.size) }, thread { ub.escribir(db, 0, db.size) },
            thread { rb = leerExacto(ub, da.size) }, thread { ra = leerExacto(ua, db.size) }
        ).forEach { it.join(60_000) }
        ok6(rb != null && sha(rb!!) == sha(da), "A a B: 500 KB intactos (${System.currentTimeMillis() - t0} ms)")
        ok6(ra != null && sha(ra!!) == sha(db), "B a A: 500 KB intactos")
        ua.cerrar(); ub.cerrar(); a.cerrar(); b.cerrar()
    }

    // 2. Trafico mezclado: DHT y sondas no corrompen el flujo, y el flujo no se cuela en recibir()
    run {
        val a = Demux(DatagramSocket()); val b = Demux(DatagramSocket())
        val ua = nuevo(a.canal(loop(b.puertoLocal))); val ub = nuevo(b.canal(loop(a.puertoLocal)))
        ok6(ua.conectar(3000) && ub.conectar(3000), "mezcla: conecta")
        val intruso = DatagramSocket()
        val bencode = Bencode.codificar(mapOf<String, Any?>("t" to "aa".toByteArray(), "y" to "r", "r" to mapOf<String, Any?>("id" to ByteArray(20))))
        val sonda = "OCH0".toByteArray() + byteArrayOf('0'.code.toByte(), 1, 2, 3, 4)
        val datos = aleatorio(200_000, 31)
        var rb: ByteArray? = null
        val lector = thread { rb = leerExacto(ub, datos.size) }
        val emisor = thread { ua.escribir(datos, 0, datos.size) }
        repeat(20) {
            intruso.send(DatagramPacket(bencode, bencode.size, loop(b.puertoLocal)))
            intruso.send(DatagramPacket(sonda, sonda.size, loop(b.puertoLocal)))
            Thread.sleep(5)
        }
        emisor.join(30_000); lector.join(30_000)
        ok6(rb != null && sha(rb!!) == sha(datos), "mezcla: 200 KB intactos con DHT y sondas intercaladas")
        var bencodes = 0; var sondas = 0; var otros = 0
        while (true) {
            val r = b.recibir(300) ?: break
            when (r.datos[0].toInt()) { 'd'.code -> bencodes++; 'O'.code -> sondas++; else -> otros++ }
        }
        ok6(bencodes == 20 && sondas == 20, "mezcla: recibir() entrega las 20 DHT y las 20 sondas ($bencodes/$sondas)")
        ok6(otros == 0, "mezcla: ningun paquete del transporte aparece en recibir() ($otros)")
        intruso.close(); ua.cerrar(); ub.cerrar(); a.cerrar(); b.cerrar()
    }

    // 3. Paquetes tempranos: B crea su transporte 700 ms despues
    run {
        val a = Demux(DatagramSocket()); val b = Demux(DatagramSocket())
        val ua = nuevo(a.canal(loop(b.puertoLocal)))
        Thread.sleep(700)
        val ub = nuevo(b.canal(loop(a.puertoLocal)))
        ok6(ua.conectar(5000) && ub.conectar(5000), "paquetes tempranos: B arranca 700 ms tarde y conecta")
        ua.cerrar(); ub.cerrar(); a.cerrar(); b.cerrar()
    }

    // 4. Spam desde muchos origenes no registrados
    run {
        val a = Demux(DatagramSocket()); val b = Demux(DatagramSocket())
        val ua = nuevo(a.canal(loop(b.puertoLocal)))
        val ub = nuevo(b.canal(loop(a.puertoLocal)))
        val falso = ByteArray(40).also { it[0] = 0xA1.toByte() }
        val socks = List(80) { DatagramSocket() }
        socks.forEach { it.send(DatagramPacket(falso, falso.size, loop(b.puertoLocal))) }
        Thread.sleep(500)
        ok6(b.remotosConCola <= Demux.MAX_REMOTOS_SIN_REGISTRAR + 1,
            "spam: 80 origenes falsos ocupan como mucho ${Demux.MAX_REMOTOS_SIN_REGISTRAR} colas (${b.remotosConCola})")
        ok6(ua.conectar(5000) && ub.conectar(5000), "spam: el canal registrado sigue funcionando")
        val d = aleatorio(50_000, 41)
        var rb: ByteArray? = null
        val t = thread { rb = leerExacto(ub, d.size) }
        ua.escribir(d, 0, d.size); t.join(20_000)
        ok6(rb != null && sha(rb!!) == sha(d), "spam: 50 KB intactos tras el spam")
        socks.forEach { it.close() }; ua.cerrar(); ub.cerrar(); a.cerrar(); b.cerrar()
    }

    // 5. Dht.localizar contra un nodo falso
    run {
        val n = NodoFalso()
        val dht = Dht(Demux(DatagramSocket()))
        val ih = SondaNat.identificador("secreto".toByteArray(), 1)
        val l1 = dht.localizar(ih, listOf(n.dir), anunciar = true)
        ok6(l1.anunciados == 1, "DHT falsa: announce_peer aceptado (${l1.anunciados})")
        ok6(l1.ipExterna?.port == dht.puertoLocal, "DHT falsa: el campo ip da el puerto propio (${l1.ipExterna?.port} = ${dht.puertoLocal})")
        val otra = Dht(Demux(DatagramSocket()))
        val l2 = otra.localizar(ih, listOf(n.dir), anunciar = false)
        ok6(l2.valores.contains(loop(dht.puertoLocal)), "DHT falsa: otro cliente encuentra al primero (${l2.valores.size} valores)")
        val l3 = otra.localizar(SondaNat.identificador("otro".toByteArray(), 1), listOf(n.dir), anunciar = false)
        ok6(l3.valores.isEmpty(), "DHT falsa: otro identificador no devuelve nada")
        dht.puerto.cerrar(); otra.puerto.cerrar(); n.cerrar()
    }

    // 6. Flujo completo: encuentro, punching y transporte sobre canal(remoto)
    run {
        val n = NodoFalso()
        val d1 = Dht(Demux(DatagramSocket())); val d2 = Dht(Demux(DatagramSocket()))
        val sec = "secreto-compartido".toByteArray()
        var r1: SondaNat.Resultado? = null; var r2: SondaNat.Resultado? = null
        val t1 = thread { r1 = SondaNat.encuentro(d1, sec, 30_000, {}, listOf(n.dir), rafagaMs = 3000, pausaMs = 500) }
        Thread.sleep(300)
        val t2 = thread { r2 = SondaNat.encuentro(d2, sec, 30_000, {}, listOf(n.dir), rafagaMs = 3000, pausaMs = 500) }
        t1.join(40_000); t2.join(40_000)
        ok6(r1?.exito == true && r2?.exito == true, "flujo: ambos logran el punching (${r1?.ms} / ${r2?.ms} ms)")
        ok6(r1?.remoto == loop(d2.puertoLocal) && r2?.remoto == loop(d1.puertoLocal), "flujo: cada uno ve el puerto del otro")
        if (r1?.exito == true && r2?.exito == true) {
            val u1 = nuevo(d1.puerto.canal(r1!!.remoto!!)); val u2 = nuevo(d2.puerto.canal(r2!!.remoto!!))
            ok6(u1.conectar(5000) && u2.conectar(5000), "flujo: el transporte conecta sobre el mismo socket")
            val dat = aleatorio(200_000, 51)
            var rb: ByteArray? = null
            val t = thread { rb = leerExacto(u2, dat.size) }
            u1.escribir(dat, 0, dat.size); t.join(30_000)
            ok6(rb != null && sha(rb!!) == sha(dat), "flujo: 200 KB intactos tras el punching")
            u1.cerrar(); u2.cerrar()
        }
        // Secretos distintos: no hay encuentro
        val d3 = Dht(Demux(DatagramSocket())); val d4 = Dht(Demux(DatagramSocket()))
        var r3: SondaNat.Resultado? = null; var r4: SondaNat.Resultado? = null
        val t3 = thread { r3 = SondaNat.encuentro(d3, "uno".toByteArray(), 4000, {}, listOf(n.dir), rafagaMs = 1000, pausaMs = 300) }
        val t4 = thread { r4 = SondaNat.encuentro(d4, "dos".toByteArray(), 4000, {}, listOf(n.dir), rafagaMs = 1000, pausaMs = 300) }
        t3.join(20_000); t4.join(20_000)
        ok6(r3?.exito == false && r4?.exito == false, "flujo: con secretos distintos no hay encuentro")
        listOf(d1, d2, d3, d4).forEach { it.puerto.cerrar() }; n.cerrar()
    }

    // 7. clasificar con nodos falsos
    run {
        val ns = List(3) { NodoFalso() }
        val dht = Dht(Demux(DatagramSocket()))
        val v = SondaNat.clasificar(dht, {}, ns.map { it.dir })
        ok6(v.contains("ESTABLE"), "clasificar: 3 nodos coherentes -> estable ($v)")
        dht.puerto.cerrar(); ns.forEach { it.cerrar() }

        val ms = listOf(NodoFalso(), NodoFalso(), NodoFalso(mentirPuerto = 9999))
        val dht2 = Dht(Demux(DatagramSocket()))
        val v2 = SondaNat.clasificar(dht2, {}, ms.map { it.dir })
        ok6(v2.contains("simetrico"), "clasificar: un nodo ve otro puerto -> simetrico ($v2)")
        dht2.puerto.cerrar(); ms.forEach { it.cerrar() }
    }

    println(if (f6 == 0) "Demux: todo OK" else "Demux: $f6 FALLOS")
    return f6
}
