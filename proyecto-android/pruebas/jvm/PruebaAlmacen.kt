import com.optimo.chat.AlmacenCifrado
import com.optimo.chat.AlmacenFilas
import com.optimo.chat.AlmacenMemoria
import com.optimo.chat.AlmacenMensajes
import com.optimo.chat.Chat
import com.optimo.chat.Estado
import com.optimo.chat.FilaCruda
import com.optimo.chat.Protocolo
import com.optimo.chat.SelladorAesGcm
import java.util.Collections
import javax.crypto.KeyGenerator
import kotlin.concurrent.thread

var f9 = 0
fun ok9(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f9++ }

/** Filas en memoria para probar AlmacenCifrado sin SQLite. Permite ver y manipular lo "guardado". */
class FilasMemoria : AlmacenFilas {
    val datos = LinkedHashMap<String, FilaCruda>()
    private val meta = HashMap<String, Long>()
    @Synchronized override fun insertar(f: FilaCruda): Boolean { if (datos.containsKey(f.id)) return false; datos[f.id] = f; return true }
    @Synchronized override fun obtener(id: String) = datos[id]
    @Synchronized override fun primeraPendiente(despuesDeSeq: Long) =
        datos.values.filter { it.propio && it.estado == 0 && it.seq > despuesDeSeq }.minByOrNull { it.seq }
    @Synchronized override fun cambiarEstado(id: String, desde: List<Int>, a: Int): Boolean {
        val f = datos[id] ?: return false
        if (!f.propio || f.estado !in desde) return false
        datos[id] = FilaCruda(f.id, f.propio, f.seq, a, f.blob); return true
    }
    @Synchronized override fun cambiarEstadoHasta(seq: Long, desde: List<Int>, a: Int): List<String> {
        val r = ArrayList<String>()
        for (f in datos.values.filter { it.propio && it.seq <= seq && it.estado in desde }.sortedBy { it.seq }) {
            datos[f.id] = FilaCruda(f.id, f.propio, f.seq, a, f.blob); r.add(f.id)
        }
        return r
    }
    @Synchronized override fun reiniciarEnviados() {
        for (f in datos.values.toList()) if (f.propio && f.estado == 1) datos[f.id] = FilaCruda(f.id, true, f.seq, 0, f.blob)
    }
    @Synchronized override fun existeRecibidoSeq(seq: Long) = datos.values.any { !it.propio && it.seq == seq }
    @Synchronized override fun ultimas(limite: Int) = datos.values.toList().takeLast(limite)
    @Synchronized override fun meta(clave: String) = meta[clave]
    @Synchronized override fun ponerMeta(clave: String, valor: Long) { meta[clave] = valor }
    @Synchronized fun reemplazarBlob(id: String, blob: ByteArray) { val f = datos[id]!!; datos[id] = FilaCruda(f.id, f.propio, f.seq, f.estado, blob) }
    @Synchronized fun intercambiarBlobs(a: String, b: String) {
        val x = datos[a]!!; val y = datos[b]!!; reemplazarBlob(a, y.blob); reemplazarBlob(b, x.blob)
    }
}

fun clave() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
fun msg(n: Int, seq: Long, cuerpo: String = "texto $n", resp: ByteArray? = null) =
    Protocolo.Mensaje(ByteArray(16) { (n * 16 + it).toByte() }, seq, 1_700_000_000_000L + n, cuerpo, resp)

fun contrato(nombre: String, nuevo: () -> AlmacenMensajes) {
    val a = nuevo()
    ok9((1..3).map { a.siguienteSeq() } == listOf(1L, 2L, 3L), "$nombre: siguienteSeq cuenta desde 1")
    val m1 = msg(1, 1); val m2 = msg(2, 2); val m3 = msg(3, 3)
    a.guardarEnviado(m1); a.guardarEnviado(m2); a.guardarEnviado(m3)
    ok9(a.siguientePendiente()?.seq == 1L && a.estado(m1.id) == Estado.PENDIENTE, "$nombre: el pendiente mas antiguo sale primero")
    a.marcarEnviado(m1.id)
    ok9(a.siguientePendiente()?.seq == 2L && a.estado(m1.id) == Estado.ENVIADO, "$nombre: tras marcar enviado sale el siguiente")
    ok9(a.marcarEntregado(m1.id) && !a.marcarEntregado(m1.id) && a.estado(m1.id) == Estado.ENTREGADO, "$nombre: entregar cambia una sola vez")
    a.marcarEnviado(m2.id); a.reiniciarEnviados()
    ok9(a.estado(m2.id) == Estado.PENDIENTE && a.estado(m1.id) == Estado.ENTREGADO, "$nombre: reiniciar devuelve lo ENVIADO a PENDIENTE")
    a.marcarEnviado(m2.id)
    val hasta = a.marcarEntregadosHasta(2)
    ok9(hasta.size == 1 && hasta[0].contentEquals(m2.id) && a.estado(m3.id) == Estado.PENDIENTE, "$nombre: entregar hasta seq 2")
    ok9(a.marcarLeidoPorPar(m1.id) && !a.marcarLeidoPorPar(m1.id) && !a.marcarLeidoPorPar(ByteArray(16) { 99 }), "$nombre: leido por el par")
    ok9(a.estado(m1.id) == Estado.LEIDO, "$nombre: estado LEIDO")

    val r1 = msg(10, 1); val r2 = msg(11, 2); val r4 = msg(12, 4); val r3 = msg(13, 3)
    ok9(a.guardarRecibido(r1) && !a.guardarRecibido(r1), "$nombre: un recibido duplicado se rechaza")
    a.guardarRecibido(r2); a.guardarRecibido(r4)
    ok9(a.ultimoSeqRecibido() == 2L, "$nombre: ultimo seq sin huecos = 2 con hueco en 3")
    a.guardarRecibido(r3)
    ok9(a.ultimoSeqRecibido() == 4L, "$nombre: al llenar el hueco salta a 4")
    ok9(!a.marcarEntregado(r1.id) && a.estado(r1.id) == null, "$nombre: un ACK no afecta a mensajes recibidos")
}

fun mainAlmacen(): Int {
    contrato("memoria") { AlmacenMemoria() }
    contrato("cifrado") { AlmacenCifrado(FilasMemoria(), SelladorAesGcm(clave())) }

    // Historial y campos
    run {
        val k = clave(); val f = FilasMemoria(); val a = AlmacenCifrado(f, SelladorAesGcm(k))
        val resp = ByteArray(16) { 5 }
        a.guardarEnviado(msg(1, 1, "Ñandú ✓ 日本語", resp)); a.guardarRecibido(msg(2, 1, "respuesta")); a.guardarEnviado(msg(3, 2, ""))
        val h = a.historial(10)
        ok9(h.size == 3 && h[0].propio && !h[1].propio && h[2].propio, "cifrado: historial en orden de insercion con direccion")
        ok9(h[0].mensaje.cuerpo == "Ñandú ✓ 日本語" && h[0].mensaje.respuestaA!!.contentEquals(resp) && h[0].mensaje.horaMs == 1_700_000_000_001L,
            "cifrado: cuerpo UTF-8, hora y respuesta se recuperan")
        ok9(h[2].mensaje.cuerpo == "" && h[2].mensaje.respuestaA == null, "cifrado: cuerpo vacio y sin respuesta")
        ok9(a.historial(2).size == 2 && a.historial(2)[1].mensaje.seq == 2L, "cifrado: historial limitado a las ultimas")

        // Persistencia: otra instancia sobre las mismas filas y la misma clave
        a.siguienteSeq(); a.siguienteSeq()
        val b = AlmacenCifrado(f, SelladorAesGcm(k))
        ok9(b.siguienteSeq() == 3L && b.ultimoSeqRecibido() == 1L && b.historial(10).size == 3, "cifrado: reabrir conserva contadores y mensajes")

        // Nada en claro
        val texto = "texto-secreto-que-no-debe-verse-" + "x".repeat(20)
        a.guardarEnviado(msg(4, 3, texto))
        val crudo = f.datos.values.flatMap { it.blob.toList() }.toByteArray()
        fun contiene(h: ByteArray, n: ByteArray) = (0..h.size - n.size).any { i -> n.indices.all { h[i + it] == n[it] } }
        ok9(!contiene(crudo, "texto-secreto".toByteArray()) && !contiene(crudo, "Ñandú ✓ 日本語".toByteArray(Charsets.UTF_8)) && !contiene(crudo, "respuesta".toByteArray()),
            "cifrado: ningun cuerpo aparece en claro en lo guardado")
        a.guardarEnviado(msg(5, 4, texto))
        val x = f.datos[msg(4, 3).idTexto]!!.blob; val y = f.datos[msg(5, 4).idTexto]!!.blob
        ok9(!x.contentEquals(y), "cifrado: el mismo texto da bloques distintos (IV aleatorio)")
    }

    // Manipulacion
    run {
        val k = clave(); val f = FilasMemoria(); val a = AlmacenCifrado(f, SelladorAesGcm(k))
        a.guardarEnviado(msg(1, 1, "uno")); a.guardarEnviado(msg(2, 2, "dos")); a.guardarEnviado(msg(3, 3, "tres"))
        val m1 = msg(1, 1); val m2 = msg(2, 2)
        val b = f.datos[m1.idTexto]!!.blob.copyOf(); b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        f.reemplazarBlob(m1.idTexto, b)
        ok9(a.siguientePendiente()?.seq == 2L, "manipulacion: una fila alterada se salta y la cola sigue")
        ok9(a.historial(10).size == 2, "manipulacion: la fila alterada no aparece en el historial")
        f.intercambiarBlobs(m2.idTexto, msg(3, 3).idTexto)
        ok9(a.historial(10).isEmpty() && a.siguientePendiente() == null, "manipulacion: bloques cambiados entre filas se rechazan (AAD)")
        val otra = AlmacenCifrado(f, SelladorAesGcm(clave()))
        ok9(otra.historial(10).isEmpty(), "manipulacion: otra clave no lee nada")
        val corto = SelladorAesGcm(k)
        ok9(corto.abrir(ByteArray(3), ByteArray(5)) == null && corto.abrir(ByteArray(3), ByteArray(40)) == null, "sellador: entradas invalidas devuelven null")
    }

    // Concurrencia
    run {
        val a = AlmacenCifrado(FilasMemoria(), SelladorAesGcm(clave()))
        val vistos = Collections.synchronizedSet(HashSet<Long>())
        (1..8).map { thread { repeat(100) { vistos.add(a.siguienteSeq()) } } }.forEach { it.join() }
        ok9(vistos.size == 800, "concurrencia: 800 numeros de secuencia sin repetir (${vistos.size})")
    }

    // Chat completo con almacenes cifrados
    run {
        val fa = FilasMemoria(); val fb = FilasMemoria()
        val aa = AlmacenCifrado(fa, SelladorAesGcm(clave())); val ab = AlmacenCifrado(fb, SelladorAesGcm(clave()))
        val recA = Collections.synchronizedList(ArrayList<Protocolo.Mensaje>()); val recB = Collections.synchronizedList(ArrayList<Protocolo.Mensaje>())
        val ca = Chat(aa, { recA.add(it) }, ritmoMs = 5); val cb = Chat(ab, { recB.add(it) }, ritmoMs = 5)
        val ms = (0 until 40).map { ca.enviar("de A $it") }
        repeat(40) { cb.enviar("de B $it") }
        val (x, y) = parMem()
        val ha = thread { ca.atender(x) }; val hb = thread { cb.atender(y) }
        val llego = esperar(30_000) { recA.size == 40 && recB.size == 40 && ms.all { aa.estado(it.id).let { e -> e == Estado.ENTREGADO || e == Estado.LEIDO } } }
        ok9(llego, "chat sobre almacen cifrado: 40 mensajes cada lado entregados (${recA.size}/${recB.size})")
        ok9(recB.map { it.cuerpo } == (0 until 40).map { "de A $it" }, "chat sobre almacen cifrado: orden correcto")
        val h = aa.historial(200)
        ok9(h.count { it.propio } == 40 && h.count { !it.propio } == 40, "chat sobre almacen cifrado: el historial tiene 80 entradas")
        ok9(aa.ultimoSeqRecibido() == 40L && ab.ultimoSeqRecibido() == 40L, "chat sobre almacen cifrado: ultimo seq recibido 40 en ambos")
        x.cerrar(); y.cerrar(); ha.join(5000); hb.join(5000)
    }

    println(if (f9 == 0) "Almacen: todo OK" else "Almacen: $f9 FALLOS")
    return f9
}
