import com.optimo.chat.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

var fallos = 0
fun ok(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) fallos++ }
fun lanza(m: String, f: () -> Unit) { try { f(); ok(false, m + " (no lanzo)") } catch (e: IllegalArgumentException) { ok(true, m) } }

fun mainDht(): Int {
    // Bencode: vectores conocidos
    val m = Bencode.decodificar("d3:cow3:moo4:spam4:eggse".toByteArray()).comoMapa()!!
    ok(String(m["cow"].comoBytes()!!) == "moo" && String(m["spam"].comoBytes()!!) == "eggs", "decodifica diccionario")
    ok(Bencode.decodificar("i-42e".toByteArray()) == -42L, "decodifica entero negativo")
    val l = Bencode.decodificar("l4:spami42ee".toByteArray()) as List<*>
    ok(l.size == 2 && l[1] == 42L, "decodifica lista")
    ok(String(Bencode.codificar(mapOf("spam" to "eggs", "cow" to "moo"))) == "d3:cow3:moo4:spam4:eggse", "codifica con claves ordenadas")
    ok(String(Bencode.codificar(listOf(1, 2L, "a".toByteArray()))) == "li1ei2e1:ae", "codifica lista mixta")
    val bin = ByteArray(256) { it.toByte() }
    ok(Bencode.decodificar(Bencode.codificar(bin)).comoBytes()!!.contentEquals(bin), "ida y vuelta de 256 bytes")
    lanza("rechaza entero sin cierre") { Bencode.decodificar("i42".toByteArray()) }
    lanza("rechaza cadena mas larga que los datos") { Bencode.decodificar("10:abc".toByteArray()) }
    lanza("rechaza datos sobrantes") { Bencode.decodificar("i1ei2e".toByteArray()) }
    lanza("rechaza anidamiento excesivo") { Bencode.decodificar(("l".repeat(40) + "e".repeat(40)).toByteArray()) }
    lanza("rechaza vacio") { Bencode.decodificar(ByteArray(0)) }

    // Dht.interpretar con una respuesta fabricada
    val dht = Dht()
    val id = ByteArray(20) { 1 }
    val par = byteArrayOf(83.toByte(), 45, 12, 34, 0x13, 0x88.toByte())        // 83.45.12.34:5000
    val nodo = ByteArray(20) { 2 } + byteArrayOf(10, 0, 0, 1, 0x1A, 0xE1.toByte()) // 10.0.0.1:6881
    val resp = Bencode.codificar(mapOf(
        "t" to "aa", "y" to "r", "ip" to par,
        "r" to mapOf("id" to id, "token" to "tok".toByteArray(), "values" to listOf(par), "nodes" to nodo)))
    val r = dht.interpretar(resp)!!
    ok(r.ip.toString().endsWith("83.45.12.34:5000"), "lee la IP externa (campo ip): ${r.ip}")
    ok(String(r.token!!) == "tok", "lee el token")
    ok(r.valores.size == 1 && r.valores[0].port == 5000, "lee valores compactos")
    ok(r.nodos.size == 1 && r.nodos[0].dir.port == 6881 && r.nodos[0].dir.address.hostAddress == "10.0.0.1", "lee nodos compactos")
    ok(dht.interpretar("xx".toByteArray()) == null, "ignora basura")
    ok(dht.interpretar(Bencode.codificar(mapOf("t" to "aa", "y" to "e", "e" to listOf(201L, "x".toByteArray()))))!!.error != null, "reconoce error remoto")
    ok(dht.interpretar(Bencode.codificar(mapOf("t" to "aa", "y" to "q", "q" to "ping")) ) == null, "ignora consultas ajenas")

    // Dht.enviar: lo que sale por el socket es una consulta KRPC valida
    val receptor = DatagramSocket(0, InetAddress.getLoopbackAddress())
    receptor.soTimeout = 2000
    val t = dht.enviar(InetSocketAddress(InetAddress.getLoopbackAddress(), receptor.localPort), "get_peers", mapOf("info_hash" to ByteArray(20) { 7 }))
    val p = DatagramPacket(ByteArray(2048), 2048); receptor.receive(p)
    val q = Bencode.decodificar(p.data.copyOf(p.length)).comoMapa()!!
    val a = q["a"].comoMapa()!!
    ok(String(q["q"].comoBytes()!!) == "get_peers" && String(q["y"].comoBytes()!!) == "q", "consulta get_peers bien formada")
    ok(q["t"].comoBytes()!!.contentEquals(t) && a["id"].comoBytes()!!.size == 20 && a["info_hash"].comoBytes()!!.size == 20, "lleva t, id e info_hash de 20 bytes")
    ok(p.length < 100, "consulta pequena (${p.length} bytes)")

    // Identificador de encuentro
    val s = "secreto-de-prueba".toByteArray()
    val i1 = SondaNat.identificador(s, 100); val i2 = SondaNat.identificador(s, 100); val i3 = SondaNat.identificador(s, 101)
    val i4 = SondaNat.identificador("otro".toByteArray(), 100)
    ok(i1.size == 20 && i1.contentEquals(i2), "identificador de 20 bytes y determinista")
    ok(!i1.contentEquals(i3) && !i1.contentEquals(i4), "cambia con la ventana y con el secreto")

    // Distancia XOR y mascara
    ok(Dht.distancia(ByteArray(20), ByteArray(20)).signum() == 0, "distancia de un id a si mismo es 0")
    ok(Informe.enmascarar(InetSocketAddress(InetAddress.getByName("83.45.12.34"), 5000)) == "83.45.*.*:5000", "mascara IPv4")
    ok(Informe.enmascarar(InetSocketAddress(InetAddress.getByName("2a02:1234:5678::1"), 5000)).startsWith("2a02:1234:*"), "mascara IPv6")
    println(if (fallos == 0) "TODO OK" else "FALLOS: $fallos")
    return fallos
}
