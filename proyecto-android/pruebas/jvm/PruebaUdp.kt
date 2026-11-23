import com.optimo.chat.Canal
import com.optimo.chat.Datagramas
import com.optimo.chat.UdpFiable
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

var f3 = 0
fun ok3(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f3++ }

/** Red simulada entre dos extremos: perdida, duplicacion y retraso aleatorio (que provoca reordenacion). */
class Enlace(val perdida: Double, val dup: Double, val retrasoMax: Int, semilla: Long) {
    private val rnd = Random(semilla)
    private val sched = Executors.newScheduledThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
    @Volatile var cortado = false
    private fun azar(): Double = synchronized(rnd) { rnd.nextDouble() }
    private fun retraso(): Int = synchronized(rnd) { rnd.nextInt(retrasoMax + 1) }

    inner class Extremo : Datagramas {
        val cola = LinkedBlockingQueue<ByteArray>()
        lateinit var otro: Extremo
        override fun enviar(datos: ByteArray) {
            if (cortado) return
            val copias = if (azar() < dup) 2 else 1
            repeat(copias) {
                if (azar() >= perdida) {
                    val copia = datos.copyOf()
                    sched.schedule({ if (!cortado) otro.cola.add(copia) }, retraso().toLong(), TimeUnit.MILLISECONDS)
                }
            }
        }
        override fun recibir(esperaMs: Int): ByteArray? = cola.poll(esperaMs.toLong(), TimeUnit.MILLISECONDS)
    }

    val a = Extremo()
    val b = Extremo()
    init { a.otro = b; b.otro = a }
}

fun nuevo(e: Datagramas, keepalive: Long = 15_000, inactividad: Long = 60_000) =
    UdpFiable(e, rtoMinMs = 20, keepaliveMs = keepalive, inactividadMs = inactividad)

fun leerExacto(c: Canal, n: Int, retardoInicialMs: Long = 0): ByteArray {
    if (retardoInicialMs > 0) Thread.sleep(retardoInicialMs)
    val out = ByteArray(n)
    var pos = 0
    while (pos < n) {
        val k = c.leer(out, pos, minOf(4096, n - pos))
        if (k < 0) break
        pos += k
    }
    return if (pos == n) out else out.copyOf(pos)
}

fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun aleatorio(n: Int, semilla: Long): ByteArray = ByteArray(n).also { Random(semilla).nextBytes(it) }

fun mainUdp(): Int {
    // 1. Apertura simultanea, sin perdidas
    run {
        val e = Enlace(0.0, 0.0, 2, 1)
        val a = nuevo(e.a); val b = nuevo(e.b)
        ok3(a.conectar(3000) && b.conectar(3000), "apertura simetrica sin perdidas")
        a.cerrar(); b.cerrar()
    }

    // 2. Apertura con 60 % de perdidas y un extremo que arranca tarde
    run {
        val e = Enlace(0.6, 0.0, 5, 2)
        val a = nuevo(e.a)
        Thread.sleep(500)
        val b = nuevo(e.b)
        ok3(a.conectar(15_000) && b.conectar(15_000), "apertura con 60 % de perdidas y arranque desfasado")
        a.cerrar(); b.cerrar()
    }

    // 3. Transferencia bidireccional con perdida 20 %, duplicados 5 % y reordenacion
    run {
        val e = Enlace(0.20, 0.05, 20, 3)
        val a = nuevo(e.a); val b = nuevo(e.b)
        ok3(a.conectar(5000) && b.conectar(5000), "conecta con 20 % de perdidas")
        val datosA = aleatorio(300_000, 11)
        val datosB = aleatorio(200_000, 12)
        var recibidoEnB: ByteArray? = null
        var recibidoEnA: ByteArray? = null
        val t0 = System.currentTimeMillis()
        val hilos = listOf(
            thread { a.escribir(datosA, 0, datosA.size) },
            thread { b.escribir(datosB, 0, datosB.size) },
            thread { recibidoEnB = leerExacto(b, datosA.size) },
            thread { recibidoEnA = leerExacto(a, datosB.size) }
        )
        hilos.forEach { it.join(90_000) }
        val ms = System.currentTimeMillis() - t0
        ok3(recibidoEnB != null && sha(recibidoEnB!!) == sha(datosA), "A a B: 300 KB intactos con 20 % de perdidas ($ms ms)")
        ok3(recibidoEnA != null && sha(recibidoEnA!!) == sha(datosB), "B a A: 200 KB intactos a la vez")
        println("   retransmisiones A=${a.retransmisiones} B=${b.retransmisiones}, ventana A=${"%.1f".format(a.ventanaActual)}")
        ok3(a.retransmisiones > 0, "hubo retransmisiones (la prueba ejercita la recuperacion)")
        a.cerrar(); b.cerrar()
    }

    // 4. Cierre ordenado: el otro lado lee todo y despues recibe el fin (-1)
    run {
        val e = Enlace(0.10, 0.0, 10, 4)
        val a = nuevo(e.a); val b = nuevo(e.b)
        a.conectar(5000); b.conectar(5000)
        val datos = aleatorio(60_000, 21)
        var leidos = ByteArray(0)
        var fin = false
        val lector = thread {
            val buf = ByteArray(5000)
            val acumulado = java.io.ByteArrayOutputStream()
            while (true) {
                val k = b.leer(buf, 0, buf.size)
                if (k < 0) { fin = true; break }
                acumulado.write(buf, 0, k)
            }
            leidos = acumulado.toByteArray()
        }
        a.escribir(datos, 0, datos.size)
        a.cerrar()
        lector.join(20_000)
        ok3(fin && sha(leidos) == sha(datos), "cierre ordenado: datos completos y luego fin (${leidos.size} bytes)")
        b.cerrar()
    }

    // 5. Extremo caido: se detecta y se lanza IOException
    run {
        val e = Enlace(0.0, 0.0, 1, 5)
        val a = nuevo(e.a, keepalive = 300, inactividad = 1500)
        val b = nuevo(e.b, keepalive = 300, inactividad = 1500)
        a.conectar(3000); b.conectar(3000)
        Thread.sleep(2200) // sin trafico: los latidos mantienen viva la conexion
        val dato = aleatorio(100, 31)
        var sigueViva = true
        try { a.escribir(dato, 0, dato.size) } catch (_: IOException) { sigueViva = false }
        ok3(sigueViva, "los latidos mantienen viva una conexion inactiva (2,2 s con limite de 1,5 s)")
        e.cortado = true
        val t0 = System.currentTimeMillis()
        var detectado = false
        try { a.leer(ByteArray(10), 0, 10) } catch (_: IOException) { detectado = true }
        val ms = System.currentTimeMillis() - t0
        ok3(detectado && ms < 5000, "detecta el extremo caido en $ms ms")
        var escrituraFalla = false
        try { a.escribir(dato, 0, dato.size) } catch (_: IOException) { escrituraFalla = true }
        ok3(escrituraFalla, "escribir tras la caida lanza IOException")
        a.cerrar(); b.cerrar()
    }

    // 6. Paquetes ajenos o basura no alteran el flujo
    run {
        val e = Enlace(0.05, 0.0, 5, 6)
        val a = nuevo(e.a); val b = nuevo(e.b)
        a.conectar(5000); b.conectar(5000)
        val rnd = Random(77)
        val datos = aleatorio(50_000, 41)
        var recibido: ByteArray? = null
        val lector = thread { recibido = leerExacto(b, datos.size) }
        val inyector = thread {
            repeat(300) {
                val junk = ByteArray(20 + rnd.nextInt(80)).also { rnd.nextBytes(it) }
                junk[0] = (0xA0 or (1 + rnd.nextInt(4))).toByte()     // parece del transporte, con id ajeno
                junk[13] = 0; junk[14] = 3                              // longitud pequena
                e.b.cola.add(junk)
                e.b.cola.add(byteArrayOf(1, 2, 3))                      // demasiado corto / otro tipo
                Thread.sleep(2)
            }
        }
        a.escribir(datos, 0, datos.size)
        lector.join(30_000); inyector.join()
        ok3(recibido != null && sha(recibido!!) == sha(datos), "ignora 600 paquetes ajenos o basura sin corromper el flujo")
        a.cerrar(); b.cerrar()
    }

    // 7. Contrapresion: lector lento, 1 MB, sin perdida de datos
    run {
        val e = Enlace(0.02, 0.0, 3, 7)
        val a = nuevo(e.a); val b = nuevo(e.b)
        a.conectar(5000); b.conectar(5000)
        val datos = aleatorio(1_000_000, 51)
        var recibido: ByteArray? = null
        val t0 = System.currentTimeMillis()
        val lector = thread { recibido = leerExacto(b, datos.size, retardoInicialMs = 1500) }
        val escritor = thread { a.escribir(datos, 0, datos.size) }
        escritor.join(60_000); lector.join(60_000)
        val ms = System.currentTimeMillis() - t0
        ok3(recibido != null && sha(recibido!!) == sha(datos), "1 MB con lector lento (1,5 s de retraso) intacto ($ms ms)")
        a.cerrar(); b.cerrar()
    }

    println(if (f3 == 0) "TODO OK (3)" else "FALLOS: $f3")
    return f3
}
