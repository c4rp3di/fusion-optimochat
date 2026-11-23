import com.optimo.chat.AlmacenMemoria
import com.optimo.chat.Canal
import com.optimo.chat.Chat
import com.optimo.chat.Estado
import com.optimo.chat.Protocolo
import com.optimo.chat.Reintentos
import com.optimo.chat.UdpFiable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

var f5 = 0
fun ok5(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f5++ }
fun ByteArray.hx(): String = joinToString("") { "%02x".format(it) }

fun esperar(ms: Long, cond: () -> Boolean): Boolean {
    val fin = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < fin) { if (cond()) return true; Thread.sleep(10) }
    return cond()
}

/** Canal en memoria entre dos extremos, determinista y sin perdidas. */
class CanalMem(private val ent: LinkedBlockingQueue<ByteArray>, private val sal: LinkedBlockingQueue<ByteArray>) : Canal {
    private var actual: ByteArray? = null
    private var off = 0
    @Volatile private var cerrado = false

    override fun leer(b: ByteArray, desde: Int, largo: Int): Int {
        if (actual == null || off >= actual!!.size) {
            val c = ent.poll(20, TimeUnit.SECONDS) ?: throw IOException("tiempo agotado")
            if (c.isEmpty()) { ent.put(c); return -1 }
            actual = c; off = 0
        }
        val n = minOf(largo, actual!!.size - off)
        System.arraycopy(actual!!, off, b, desde, n)
        off += n
        return n
    }

    override fun escribir(b: ByteArray, desde: Int, largo: Int) {
        if (cerrado) throw IOException("cerrado")
        sal.put(b.copyOfRange(desde, desde + largo))
    }

    override fun cerrar() { cerrado = true; sal.put(ByteArray(0)) }
}

fun parMem(): Pair<CanalMem, CanalMem> {
    val x = LinkedBlockingQueue<ByteArray>(); val y = LinkedBlockingQueue<ByteArray>()
    return CanalMem(x, y) to CanalMem(y, x)
}

class Lado(confirmar: Boolean = true, ritmoMs: Long = 25) {
    val almacen = AlmacenMemoria()
    val recibidos: MutableList<Protocolo.Mensaje> = Collections.synchronizedList(ArrayList())
    val cambios: MutableList<Pair<String, Estado>> = Collections.synchronizedList(ArrayList())
    val chat = Chat(almacen, { recibidos.add(it) }, { id, e -> cambios.add(id.hx() to e) }, confirmar, ritmoMs = ritmoMs)
    @Volatile var motivo: String? = null
    fun iniciar(c: Canal): Thread = thread { motivo = chat.atender(c) }
    fun todosEntregados(): Boolean = almacen.estados().all { it == Estado.ENTREGADO || it == Estado.LEIDO }
}

fun trama(tipo: Int, carga: ByteArray = ByteArray(0)): ByteArray {
    val b = ByteBuffer.allocate(5 + carga.size); b.putInt(1 + carga.size); b.put(tipo.toByte()); b.put(carga); return b.array()
}

fun helloBruto(version: Int = 1, caps: Int = 0, ultimo: Long = 0): ByteArray =
    ByteBuffer.allocate(10).put(version.toByte()).put(caps.toByte()).putLong(ultimo).array()

fun msgBruto(id: ByteArray, seq: Long, cuerpo: ByteArray, declarado: Int = cuerpo.size): ByteArray =
    ByteBuffer.allocate(16 + 8 + 8 + 1 + 2 + cuerpo.size).put(id).putLong(seq).putLong(0).put(0).putShort(declarado.toShort()).put(cuerpo).array()

fun enviarBruto(c: Canal, datos: ByteArray) = c.escribir(datos, 0, datos.size)

fun esperarBye(raw: Canal): Int {
    while (true) {
        val t = Protocolo.leerTrama(raw) ?: return -1
        if (t.tipo == Protocolo.T_BYE) return t.carga[0].toInt()
    }
}

fun mainChat(): Int {
    // 1. Conversacion sobre UDP con perdidas: 50 mensajes en orden, sin duplicados, todos ENTREGADOS
    run {
        val e = Enlace(0.15, 0.05, 10, 201)
        val ua = nuevo(e.a); val ub = nuevo(e.b)
        ua.conectar(5000); ub.conectar(5000)
        val a = Lado(); val b = Lado()
        val ha = a.iniciar(ua); val hb = b.iniciar(ub)
        repeat(50) { a.chat.enviar("mensaje $it de A"); Thread.sleep(5) }
        val llego = esperar(30_000) { b.recibidos.size == 50 && a.todosEntregados() }
        ok5(llego, "50 mensajes sobre UDP con 15 % de perdidas: recibidos ${b.recibidos.size}, entregados=${a.todosEntregados()}")
        ok5(b.recibidos.map { it.cuerpo } == (0 until 50).map { "mensaje $it de A" }, "llegan en orden y sin duplicados")
        ua.cerrar(); ub.cerrar(); ha.join(5000); hb.join(5000)
    }

    // 2. Cola de salida: mensajes escritos sin conexion salen al conectar, en orden
    run {
        val a = Lado(); val b = Lado()
        repeat(10) { a.chat.enviar("pendiente $it") }
        ok5(a.almacen.estados().all { it == Estado.PENDIENTE }, "sin conexion los mensajes quedan PENDIENTES")
        val (ca, cb) = parMem()
        a.iniciar(ca); b.iniciar(cb)
        val llego = esperar(10_000) { b.recibidos.size == 10 && a.todosEntregados() }
        ok5(llego && b.recibidos.map { it.cuerpo } == (0 until 10).map { "pendiente $it" }, "al conectar salen los 10 en orden y se confirman")
        ca.cerrar(); cb.cerrar()
    }

    // 3. Caida a mitad y reanudacion en otra sesion: todo llega exactamente una vez
    run {
        val e1 = Enlace(0.0, 0.0, 3, 202)
        val ua = UdpFiable(e1.a, rtoMinMs = 20, keepaliveMs = 300, inactividadMs = 1500)
        val ub = UdpFiable(e1.b, rtoMinMs = 20, keepaliveMs = 300, inactividadMs = 1500)
        ua.conectar(3000); ub.conectar(3000)
        val a = Lado(); val b = Lado()
        val h1 = a.iniciar(ua); val h2 = b.iniciar(ub)
        repeat(30) { a.chat.enviar("m$it") }
        esperar(5000) { b.recibidos.size >= 10 }   // parte llega antes de la caida
        e1.cortado = true                         // la red se cae con mensajes sin confirmar
        repeat(20) { a.chat.enviar("m${30 + it}") } // se escriben sin conexion
        h1.join(15_000); h2.join(15_000)
        ok5(a.motivo?.startsWith("error") == true, "la sesion termina al caerse la red: ${a.motivo}")
        val antes = b.recibidos.size
        val e2 = Enlace(0.05, 0.0, 3, 203)
        val ua2 = nuevo(e2.a); val ub2 = nuevo(e2.b)
        ua2.conectar(5000); ub2.conectar(5000)
        a.iniciar(ua2); b.iniciar(ub2)
        val llego = esperar(30_000) { b.recibidos.size == 50 && a.todosEntregados() }
        val seqs = b.recibidos.map { it.seq }.sorted()
        ok5(llego && seqs == (1L..50L).toList() && antes in 10..49, "tras reconectar llegan los 50 exactamente una vez (antes de la caida: $antes)")
        ok5(b.recibidos.map { it.id.hx() }.toSet().size == 50, "ningun duplicado entregado a la interfaz")
        ua2.cerrar(); ub2.cerrar()
    }

    // 4. Duplicado inyectado: se guarda una vez y se confirma las dos
    run {
        val (cl, raw) = parMem()
        val l = Lado(); l.iniciar(cl)
        Protocolo.leerTrama(raw) // HELLO del chat
        enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto()))
        val id = ByteArray(16) { 7 }
        val m = msgBruto(id, 1, "hola".toByteArray())
        enviarBruto(raw, trama(Protocolo.T_MSG, m)); enviarBruto(raw, trama(Protocolo.T_MSG, m))
        var acks = 0
        while (acks < 2) { val t = Protocolo.leerTrama(raw)!!; if (t.tipo == Protocolo.T_ACK && t.carga.contentEquals(id)) acks++ }
        ok5(l.recibidos.size == 1 && acks == 2, "mensaje duplicado: 1 entrega a la interfaz y 2 ACK")
        raw.cerrar(); cl.cerrar()
    }

    // 5. Entradas invalidas: el chat cierra con BYE y no entrega nada
    fun invalido(nombre: String, codigoEsperado: Int, motivoContiene: String, guion: (Canal) -> Unit) {
        val (cl, raw) = parMem()
        val l = Lado(); val h = l.iniciar(cl)
        Protocolo.leerTrama(raw)
        guion(raw)
        val bye = try { esperarBye(raw) } catch (_: IOException) { -2 }
        h.join(10_000)
        ok5((codigoEsperado < 0 || bye == codigoEsperado) && l.motivo?.contains(motivoContiene) == true && l.recibidos.isEmpty(),
            "$nombre: BYE=$bye, motivo='${l.motivo}'")
        raw.cerrar(); cl.cerrar()
    }
    invalido("cuerpo de 9 KB declarado", Protocolo.BYE_PROTOCOLO, "mensaje invalido") { raw ->
        enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto()))
        val grande = ByteArray(9000) { 65 }
        enviarBruto(raw, trama(Protocolo.T_MSG, msgBruto(ByteArray(16) { 1 }, 1, grande)))
    }
    invalido("longitud declarada que no coincide", Protocolo.BYE_PROTOCOLO, "mensaje invalido") { raw ->
        enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto()))
        enviarBruto(raw, trama(Protocolo.T_MSG, msgBruto(ByteArray(16) { 2 }, 1, ByteArray(5) { 66 }, declarado = 50)))
    }
    invalido("UTF-8 mal formado", Protocolo.BYE_PROTOCOLO, "mensaje invalido") { raw ->
        enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto()))
        enviarBruto(raw, trama(Protocolo.T_MSG, msgBruto(ByteArray(16) { 3 }, 1, byteArrayOf(0xC3.toByte(), 0x28))))
    }
    invalido("version desconocida", Protocolo.BYE_VERSION, "version 2") { raw ->
        enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto(version = 2)))
    }
    invalido("mensaje antes del HELLO", Protocolo.BYE_PROTOCOLO, "antes de HELLO") { raw ->
        enviarBruto(raw, trama(Protocolo.T_MSG, msgBruto(ByteArray(16) { 4 }, 1, "x".toByteArray())))
    }
    invalido("HELLO repetido", Protocolo.BYE_PROTOCOLO, "HELLO repetido") { raw ->
        enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto())); enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto()))
    }
    invalido("inundacion de tramas", Protocolo.BYE_LIMITE, "limite de tasa") { raw ->
        enviarBruto(raw, trama(Protocolo.T_HELLO, helloBruto()))
        repeat(400) { enviarBruto(raw, trama(Protocolo.T_PING)) }
    }
    invalido("longitud de trama absurda", -1, "Longitud de trama") { raw ->
        enviarBruto(raw, byteArrayOf(0x7F, -1, -1, -1))
    }

    // 6. Confirmaciones de lectura: solo si los dos lados las permiten
    for (confirmaB in listOf(true, false)) {
        val a = Lado(); val b = Lado(confirmar = confirmaB)
        val (ca, cb) = parMem()
        a.iniciar(ca); b.iniciar(cb)
        esperar(5000) { a.chat.haySesion && b.chat.haySesion }
        val m = a.chat.enviar("lee esto")
        esperar(5000) { b.recibidos.size == 1 }
        b.chat.marcarLeido(m.id)
        val leido = esperar(2000) { a.almacen.estado(m.id) == Estado.LEIDO }
        ok5(leido == confirmaB, "confirmacion de lectura ${if (confirmaB) "permitida: A ve LEIDO" else "desactivada en B: A no la recibe"}")
        ca.cerrar(); cb.cerrar()
    }

    // 7. Envios concurrentes desde 4 hilos con la sesion activa
    run {
        val a = Lado(); val b = Lado()
        val (ca, cb) = parMem()
        a.iniciar(ca); b.iniciar(cb)
        esperar(5000) { a.chat.haySesion && b.chat.haySesion }
        val hs = (0 until 4).map { h -> thread { repeat(25) { a.chat.enviar("h$h-$it") } } }
        hs.forEach { it.join() }
        val llego = esperar(15_000) { b.recibidos.size == 100 && a.todosEntregados() }
        ok5(llego && b.recibidos.map { it.id.hx() }.toSet().size == 100, "100 mensajes desde 4 hilos: todos una vez y confirmados")
        ok5(b.recibidos.map { it.seq }.sorted() == (1L..100L).toList(), "los numeros de secuencia son 1..100 sin huecos")
        ca.cerrar(); cb.cerrar()
    }

    // 8. Una cola larga acumulada sin conexion no dispara el limite de tasa del otro lado
    run {
        val a = Lado(); val b = Lado()
        repeat(200) { a.chat.enviar("acumulado $it") }
        val (ca, cb) = parMem()
        val ha = a.iniciar(ca); val hb = b.iniciar(cb)
        val llego = esperar(30_000) { b.recibidos.size == 200 && a.todosEntregados() }
        ok5(llego && a.motivo == null && b.motivo == null, "200 mensajes acumulados salen a ritmo controlado sin que cierren la sesion (${b.recibidos.size})")
        ca.cerrar(); cb.cerrar(); ha.join(5000); hb.join(5000)
    }

    // 9. UTF-8 y validaciones de enviar
    run {
        val a = Lado(); val b = Lado()
        val (ca, cb) = parMem()
        a.iniciar(ca); b.iniciar(cb)
        val texto = "Ñandú ✓ 日本語 😀"
        a.chat.enviar(texto, respuestaA = ByteArray(16) { 9 })
        ok5(esperar(5000) { b.recibidos.size == 1 } && b.recibidos[0].cuerpo == texto && b.recibidos[0].respuestaA?.contentEquals(ByteArray(16) { 9 }) == true,
            "acentos, ideogramas, emoji y respuesta a otro mensaje viajan intactos")
        fun lanza(f: () -> Unit) = try { f(); false } catch (_: IllegalArgumentException) { true }
        ok5(lanza { a.chat.enviar("") } && lanza { a.chat.enviar("x".repeat(Protocolo.MAX_CUERPO + 1)) }, "rechaza mensajes vacios o de mas de 8 KB")
        ok5(!lanza { a.chat.enviar("x".repeat(Protocolo.MAX_CUERPO)) }, "acepta uno de exactamente 8 KB")
        ca.cerrar(); cb.cerrar()
    }

    // 10. Reintentos con espera progresiva
    run {
        val r = (0..10).map { Reintentos.espera(it, 0.5) }
        ok5(r.take(4) == listOf(5000L, 10_000L, 20_000L, 40_000L), "espera progresiva 5, 10, 20, 40 s: $r".take(80))
        ok5(r.last() == 900_000L && r.zipWithNext().all { it.second >= it.first }, "tope de 15 minutos y no decrece")
        ok5(Reintentos.espera(0, 0.0) == 4000L && Reintentos.espera(0, 0.999) in 5990L..6000L, "variacion de +-20 %")
        ok5(Reintentos.espera(10_000, 0.5) == 900_000L && Reintentos.espera(-3, 0.5) == 5000L, "intentos extremos no desbordan")
    }

    // 11. Integracion: el chat completo sobre TLS mutuo sobre UDP con perdidas
    run {
        val dir = java.nio.file.Files.createTempDirectory("optimochat-chat").toFile()
        val idA = crearIdentidad(dir, "a"); val idB = crearIdentidad(dir, "b")
        val s = abrirTls(Enlace(0.10, 0.02, 8, 204), com.optimo.chat.Confianza.contexto(idA.claves, idB.huella),
            com.optimo.chat.Confianza.contexto(idB.claves, idA.huella))
        ok5(s.errA == null && s.errB == null, "TLS abierto para la prueba de integracion")
        val a = Lado(); val b = Lado()
        a.iniciar(s.a); b.iniciar(s.b)
        repeat(30) { a.chat.enviar("de A $it"); b.chat.enviar("de B $it") }
        val llego = esperar(40_000) { a.recibidos.size == 30 && b.recibidos.size == 30 && a.todosEntregados() && b.todosEntregados() }
        ok5(llego, "chat bidireccional (30 + 30 mensajes) sobre TLS y UDP con perdidas, todo confirmado")
        cerrarSesion(s)
        dir.deleteRecursively()
    }

    println(if (f5 == 0) "TODO OK (5)" else "FALLOS: $f5")
    return f5
}
