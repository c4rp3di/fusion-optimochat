import com.optimo.chat.Confianza
import com.optimo.chat.Demux
import com.optimo.chat.Dht
import com.optimo.chat.Estado
import com.optimo.chat.GestorConexion
import java.io.File
import java.net.DatagramSocket

var f7 = 0
fun ok7(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f7++ }

class Extremo7(val nodo: NodoFalso, val id: Identidad4, val huellaPar: ByteArray, secreto: ByteArray) {
    val lado = Lado(ritmoMs = 5)
    val dht = Dht(Demux(DatagramSocket()))
    val estados = java.util.Collections.synchronizedList(ArrayList<String>())
    val gestor = GestorConexion(
        dht, lado.chat, Confianza.contexto(id.claves, huellaPar), id.huella, huellaPar, secreto,
        listOf(nodo.dir), { estados.add(it) }, espera = { 300L },
        encuentroMs = 20_000, rafagaMs = 2500, pausaMs = 300, sesionMinimaMs = 1000
    )
    fun cerrar() { gestor.parar(); dht.puerto.cerrar() }
}

fun mainGestor(): Int {
    val dir = java.nio.file.Files.createTempDirectory("gestor").toFile()
    val ia = crearIdentidad(dir, "ga"); val ib = crearIdentidad(dir, "gb"); val ic = crearIdentidad(dir, "gc")
    ok7(GestorConexion.menor(byteArrayOf(1, 2), byteArrayOf(1, 3)) && !GestorConexion.menor(byteArrayOf(-1), byteArrayOf(1)),
        "menor(): comparacion sin signo")

    // 1. Mensajes en cola antes de conectar se entregan solos
    run {
        val nodo = NodoFalso(); val sec = "s1".toByteArray()
        val a = Extremo7(nodo, ia, ib.huella, sec); val b = Extremo7(nodo, ib, ia.huella, sec)
        val m = a.lado.chat.enviar("hola antes de conectar")
        ok7(a.lado.almacen.estado(m.id) == Estado.PENDIENTE, "gestor: el mensaje queda PENDIENTE sin conexion")
        a.gestor.iniciar(); Thread.sleep(300); b.gestor.iniciar()
        ok7(esperar(30_000) { a.lado.almacen.estado(m.id) == Estado.ENTREGADO || a.lado.almacen.estado(m.id) == Estado.LEIDO },
            "gestor: se conectan solos y el mensaje se entrega")
        ok7(b.lado.recibidos.size == 1 && b.lado.recibidos[0].cuerpo == "hola antes de conectar", "gestor: B lo recibe una vez")

        // 2. Corte de sesion: reconectan y entregan un mensaje nuevo
        val antes = a.gestor.sesiones
        a.gestor.cortar()
        val m2 = b.lado.chat.enviar("tras el corte")
        ok7(esperar(40_000) { a.gestor.sesiones > antes && a.lado.recibidos.any { it.cuerpo == "tras el corte" } },
            "gestor: tras cortar la sesion reconecta y entrega (sesiones ${a.gestor.sesiones})")
        ok7(esperar(5000) { b.lado.almacen.estado(m2.id).let { it == Estado.ENTREGADO || it == Estado.LEIDO } }, "gestor: B ve su mensaje confirmado")
        ok7(b.lado.recibidos.size == 1, "gestor: sin duplicados tras reconectar")

        // 3. parar() termina el hilo
        val t0 = System.currentTimeMillis()
        a.cerrar(); b.cerrar()
        ok7(System.currentTimeMillis() - t0 < 10_000, "gestor: parar() termina rapido (${System.currentTimeMillis() - t0} ms)")
        nodo.cerrar()
    }

    // 4. Clave distinta de la fijada: nunca se llega al chat
    run {
        val nodo = NodoFalso(); val sec = "s2".toByteArray()
        val a = Extremo7(nodo, ia, ic.huella, sec)   // A espera a C...
        val b = Extremo7(nodo, ib, ia.huella, sec)   // ...pero quien aparece es B
        val m = a.lado.chat.enviar("secreto")
        a.gestor.iniciar(); b.gestor.iniciar()
        esperar(30_000) { a.estados.any { it.contains("TLS rechazado") } }; Thread.sleep(1500)
        ok7(a.lado.almacen.estado(m.id) == Estado.PENDIENTE && b.lado.recibidos.isEmpty(), "impostor: el mensaje no sale con otra clave")
        ok7(a.gestor.sesiones == 0, "impostor: ninguna sesion establecida")
        ok7(a.estados.any { it.contains("TLS rechazado") }, "impostor: se anota TLS rechazado")
        a.cerrar(); b.cerrar(); nodo.cerrar()
    }

    println(if (f7 == 0) "Gestor: todo OK" else "Gestor: $f7 FALLOS")
    return f7
}
