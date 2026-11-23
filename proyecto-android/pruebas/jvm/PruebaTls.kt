import com.optimo.chat.Confianza
import com.optimo.chat.Datagramas
import com.optimo.chat.TlsCanal
import com.optimo.chat.UdpFiable
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

var f4 = 0
fun ok4(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f4++ }

class Identidad4(val claves: Array<KeyManager>, val huella: ByteArray)

/** Genera una identidad de prueba (EC P-256 autofirmada) con keytool, la herramienta del JDK. */
fun crearIdentidad(dir: File, nombre: String): Identidad4 {
    val f = File(dir, "$nombre.p12")
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val p = ProcessBuilder(
        keytool, "-genkeypair", "-alias", nombre, "-keyalg", "EC", "-keysize", "256", "-sigalg", "SHA256withECDSA",
        "-dname", "CN=$nombre", "-validity", "30", "-keystore", f.path, "-storetype", "PKCS12",
        "-storepass", "changeit", "-keypass", "changeit"
    ).redirectErrorStream(true).start()
    p.inputStream.readBytes()
    check(p.waitFor() == 0) { "keytool fallo" }
    val ks = KeyStore.getInstance("PKCS12")
    f.inputStream().use { ks.load(it, "changeit".toCharArray()) }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, "changeit".toCharArray())
    return Identidad4(kmf.keyManagers, Confianza.huellaDe(ks.getCertificate(nombre) as X509Certificate))
}

/** Observa y, si se pide, altera los paquetes que salen por un extremo. */
class Espia(private val real: Datagramas, private val alterar: ((ByteArray) -> ByteArray)? = null) : Datagramas {
    val vistos: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    override fun enviar(datos: ByteArray) {
        vistos.add(datos.copyOf())
        real.enviar(alterar?.invoke(datos.copyOf()) ?: datos)
    }
    override fun recibir(esperaMs: Int): ByteArray? = real.recibir(esperaMs)
}

class Sesion(val a: TlsCanal, val b: TlsCanal, val errA: Throwable?, val errB: Throwable?, val ua: UdpFiable, val ub: UdpFiable)

fun abrirTls(
    e: Enlace, ctxA: SSLContext, ctxB: SSLContext, protocolos: Array<String>? = null,
    envoltorioB: (Datagramas) -> Datagramas = { it }
): Sesion {
    val ua = nuevo(e.a)
    val ub = nuevo(envoltorioB(e.b))
    ua.conectar(10_000); ub.conectar(10_000)
    val ta = TlsCanal(ua, ctxA, servidor = true, protocolos = protocolos)
    val tb = TlsCanal(ub, ctxB, servidor = false, protocolos = protocolos)
    var errA: Throwable? = null
    var errB: Throwable? = null
    val h1 = thread { try { ta.apretonDeManos() } catch (t: Throwable) { errA = t } }
    val h2 = thread { try { tb.apretonDeManos() } catch (t: Throwable) { errB = t } }
    h1.join(20_000); h2.join(20_000)
    return Sesion(ta, tb, errA, errB, ua, ub)
}

fun cerrarSesion(s: Sesion) { try { s.a.cerrar() } catch (_: Exception) {}; try { s.b.cerrar() } catch (_: Exception) {} }

fun intercambio(s: Sesion, bytesA: Int, bytesB: Int, semilla: Long): Boolean {
    val datosA = aleatorio(bytesA, semilla); val datosB = aleatorio(bytesB, semilla + 1)
    var enB: ByteArray? = null; var enA: ByteArray? = null
    val hs = listOf(
        thread { try { s.a.escribir(datosA, 0, datosA.size) } catch (_: Exception) {} },
        thread { try { s.b.escribir(datosB, 0, datosB.size) } catch (_: Exception) {} },
        thread { try { enB = leerExacto(s.b, datosA.size) } catch (_: Exception) {} },
        thread { try { enA = leerExacto(s.a, datosB.size) } catch (_: Exception) {} }
    )
    hs.forEach { it.join(60_000) }
    return enB != null && enA != null && sha(enB!!) == sha(datosA) && sha(enA!!) == sha(datosB)
}

fun mainTls(): Int {
    val dir = java.nio.file.Files.createTempDirectory("optimochat-tls").toFile()
    val idA = crearIdentidad(dir, "a"); val idB = crearIdentidad(dir, "b"); val idC = crearIdentidad(dir, "intruso")
    ok4(!idA.huella.contentEquals(idB.huella), "identidades de prueba creadas con keytool")

    fun ctx(propia: Identidad4?, fijada: ByteArray?) = Confianza.contexto(propia?.claves, fijada)

    // 1. Handshake mutuo y datos en los dos sentidos sobre UDP fiable con 15 % de perdidas
    for (protos in listOf<Array<String>?>(null, arrayOf("TLSv1.2"))) {
        val etiqueta = protos?.joinToString() ?: "negociado"
        val e = Enlace(0.15, 0.05, 15, 91)
        val s = abrirTls(e, ctx(idA, idB.huella), ctx(idB, idA.huella), protos)
        ok4(s.errA == null && s.errB == null, "handshake mutuo ($etiqueta) sobre UDP con 15 % de perdidas")
        ok4(s.a.huellaPar?.contentEquals(idB.huella) == true && s.b.huellaPar?.contentEquals(idA.huella) == true,
            "cada extremo ve la huella esperada del otro (${s.a.protocolo})")
        if (protos != null) ok4(s.a.protocolo == "TLSv1.2", "TLS 1.2 forzado (el de Android 8 y 9)")
        ok4(intercambio(s, 200_000, 150_000, 5), "200 KB y 150 KB cifrados intactos en los dos sentidos ($etiqueta)")
        cerrarSesion(s)
    }

    // 2. El contenido no viaja en claro: se busca una marca conocida en todos los paquetes
    run {
        val marca = "SECRETO-OPTIMOCHAT-0123456789".toByteArray()
        val dato = ByteArray(20_000) { marca[it % marca.size] }
        fun contiene(paquetes: List<ByteArray>): Boolean = paquetes.any { p ->
            (0..p.size - marca.size).any { i -> (0 until marca.size).all { j -> p[i + j] == marca[j] } }
        }
        // Control: sin TLS la marca SI aparece (comprueba que la prueba detecta fugas)
        val e0 = Enlace(0.0, 0.0, 2, 92)
        val espia0 = Espia(e0.a)
        val u0 = nuevo(espia0); val u1 = nuevo(e0.b)
        u0.conectar(3000); u1.conectar(3000)
        val l0 = thread { leerExacto(u1, dato.size) }
        u0.escribir(dato, 0, dato.size); l0.join(10_000)
        ok4(contiene(espia0.vistos), "control: sin TLS la marca conocida SI aparece en los paquetes")
        u0.cerrar(); u1.cerrar()

        val e1 = Enlace(0.0, 0.0, 2, 93)
        val espia1 = Espia(e1.a)
        val ua = nuevo(espia1); val ub = nuevo(e1.b)
        ua.conectar(3000); ub.conectar(3000)
        val ta = TlsCanal(ua, ctx(idA, idB.huella), true); val tb = TlsCanal(ub, ctx(idB, idA.huella), false)
        val h = thread { try { ta.apretonDeManos() } catch (_: Exception) {} }
        tb.apretonDeManos(); h.join(10_000)
        val l1 = thread { leerExacto(tb, dato.size) }
        ta.escribir(dato, 0, dato.size); l1.join(10_000)
        ok4(espia1.vistos.size > 10 && !contiene(espia1.vistos), "con TLS la marca no aparece en ninguno de ${espia1.vistos.size} paquetes")
        ta.cerrar(); tb.cerrar()
    }

    // 3. Huella incorrecta fijada por el cliente
    run {
        val s = abrirTls(Enlace(0.0, 0.0, 3, 94), ctx(idA, idB.huella), ctx(idB, idC.huella))
        ok4(s.errB != null, "el cliente rechaza un servidor con huella distinta de la fijada: ${s.errB?.javaClass?.simpleName}")
        ok4(s.errA != null, "el servidor tambien falla (recibe la alerta): ${s.errA?.javaClass?.simpleName}")
        cerrarSesion(s)
    }

    // 4. Intruso que se presenta con otra identidad
    run {
        val s = abrirTls(Enlace(0.0, 0.0, 3, 95), ctx(idA, idB.huella), ctx(idC, idA.huella))
        ok4(s.errA != null, "el servidor rechaza a un intruso con otra clave: ${s.errA?.javaClass?.simpleName}")
        cerrarSesion(s)
    }

    // 5. Cliente sin certificado
    run {
        val s = abrirTls(Enlace(0.0, 0.0, 3, 96), ctx(idA, idB.huella), ctx(null, idA.huella))
        ok4(s.errA != null, "el servidor exige certificado de cliente: ${s.errA?.javaClass?.simpleName}")
        cerrarSesion(s)
    }

    // 6. Un paquete alterado en el camino no llega como dato valido
    run {
        var contador = 0
        val alterar: (ByteArray) -> ByteArray = { p ->
            if (p.size > 200 && (p[0].toInt() and 0xF) == 2) {
                contador++
                if (contador == 60) p[UdpFiable.CABECERA + 50] = (p[UdpFiable.CABECERA + 50].toInt() xor 0x01).toByte()
            }
            p
        }
        val e = Enlace(0.0, 0.0, 2, 97)
        val s = abrirTls(e, ctx(idA, idB.huella), ctx(idB, idA.huella), null) { Espia(it, alterar) }
        ok4(s.errA == null && s.errB == null, "handshake previo a la alteracion")
        contador = 0 // solo cuenta el trafico de datos posterior
        val datos = aleatorio(100_000, 77)
        var excepcion: Throwable? = null
        val recibido = java.io.ByteArrayOutputStream()
        val lector = thread {
            try {
                val buf = ByteArray(4096)
                while (recibido.size() < datos.size) {
                    val k = s.a.leer(buf, 0, buf.size)
                    if (k < 0) break
                    recibido.write(buf, 0, k)
                }
            } catch (t: Throwable) { excepcion = t }
        }
        try { s.b.escribir(datos, 0, datos.size) } catch (_: Exception) {}
        lector.join(15_000)
        val prefijoOk = recibido.size() <= datos.size && recibido.toByteArray().contentEquals(datos.copyOf(recibido.size()))
        ok4(excepcion is IOException, "TLS detecta el paquete alterado y corta: ${excepcion?.javaClass?.simpleName}")
        ok4(prefijoOk && recibido.size() > 0 && recibido.size() < datos.size, "lo recibido antes del fallo es identico al original (${recibido.size()} de ${datos.size} bytes)")
        cerrarSesion(s)
    }

    // 7. Cierre ordenado con close_notify
    run {
        val s = abrirTls(Enlace(0.05, 0.0, 5, 98), ctx(idA, idB.huella), ctx(idB, idA.huella))
        val datos = aleatorio(30_000, 12)
        var fin = false
        var leidos = 0
        val lector = thread {
            try {
                val buf = ByteArray(4096)
                while (true) { val k = s.b.leer(buf, 0, buf.size); if (k < 0) { fin = true; break }; leidos += k }
            } catch (_: Exception) {}
        }
        s.a.escribir(datos, 0, datos.size)
        s.a.cerrar()
        lector.join(15_000)
        ok4(fin && leidos == datos.size, "close_notify: el otro lado lee los $leidos bytes y luego recibe el fin")
        s.b.cerrar()
    }

    dir.deleteRecursively()
    println(if (f4 == 0) "TODO OK (4)" else "FALLOS: $f4")
    return f4
}
