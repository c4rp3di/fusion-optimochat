import com.optimo.chat.Canal
import com.optimo.chat.Confianza
import com.optimo.chat.Contacto
import com.optimo.chat.ContactosArchivo
import com.optimo.chat.ContactosMemoria
import com.optimo.chat.Demux
import com.optimo.chat.Dht
import com.optimo.chat.Emparejamiento
import com.optimo.chat.Emparejamiento.Resultado
import com.optimo.chat.Invitacion
import com.optimo.chat.Protocolo
import com.optimo.chat.SelladorAesGcm
import java.io.File
import java.net.DatagramSocket
import kotlin.concurrent.thread

var f10 = 0
fun ok10(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f10++ }

class ParSesion(val a: Resultado?, val b: Resultado?, val codigos: List<String>)

/** Ejecuta las dos mitades del protocolo sobre un canal en memoria. */
fun correrSesion(
    secA: ByteArray, secB: ByteArray, fpA: ByteArray, fpB: ByteArray, verA: ByteArray = fpB, verB: ByteArray = fpA,
    confA: Boolean = true, confB: Boolean = true, nombreA: String = "Ana", nombreB: String = "Beto", plazoMs: Long = 20_000
): ParSesion {
    val (x, y) = parMem()
    val codigos = java.util.Collections.synchronizedList(ArrayList<String>())
    var ra: Resultado? = null; var rb: Resultado? = null
    val ha = thread { ra = Emparejamiento.sesion(x, true, secA, fpA, verA, nombreA, { c, _ -> codigos.add(c); confA }, plazoMs) }
    val hb = thread { rb = Emparejamiento.sesion(y, false, secB, fpB, verB, nombreB, { c, _ -> codigos.add(c); confB }, plazoMs) }
    ha.join(30_000); hb.join(30_000)
    x.cerrar(); y.cerrar()
    return ParSesion(ra, rb, codigos.toList())
}

fun mainPar(): Int {
    val sec = ByteArray(16) { (it + 1).toByte() }
    val fpA = ByteArray(32) { 0x11 }; val fpB = ByteArray(32) { 0x22 }

    // 1. Camino feliz
    run {
        val r = correrSesion(sec, sec, fpA, fpB)
        ok10(r.a is Resultado.Exito && r.b is Resultado.Exito, "sesion: los dos terminan con exito")
        val a = r.a as? Resultado.Exito ?: return 1; val b = r.b as? Resultado.Exito ?: return 1
        ok10(a.contacto.nombre == "Beto" && b.contacto.nombre == "Ana", "sesion: cada uno guarda el nombre del otro")
        ok10(a.contacto.huella.contentEquals(fpB) && b.contacto.huella.contentEquals(fpA), "sesion: cada uno guarda la huella del otro")
        ok10(a.contacto.secreto.contentEquals(b.contacto.secreto) && a.contacto.secreto.size == 32, "sesion: mismo secreto de contacto de 32 bytes")
        ok10(a.contacto.secreto.contentEquals(Invitacion.secretoDeContacto(sec, fpA, fpB)), "sesion: el secreto sale de HKDF con las huellas en orden anfitrion/invitado")
        ok10(r.codigos.size == 2 && r.codigos[0] == r.codigos[1] && r.codigos[0].length == 6 && a.codigo == b.codigo, "sesion: el mismo codigo de 6 digitos en ambos (${r.codigos[0]})")
        ok10(!a.contacto.secreto.contentEquals(sec), "sesion: el secreto del contacto no es el de la invitacion")
    }
    // 2. Secreto distinto
    run {
        val r = correrSesion(sec, ByteArray(16) { 9 }, fpA, fpB)
        ok10(r.a is Resultado.Fallo && r.b is Resultado.Fallo && r.codigos.isEmpty(), "secreto distinto: fallan sin llegar a mostrar codigo")
        ok10((r.a as Resultado.Fallo).motivo.contains("prueba"), "secreto distinto: el motivo habla de la prueba")
    }
    // 3. Intermediario: cada lado ve en TLS una huella distinta de la que el otro cree tener
    run {
        val intruso = ByteArray(32) { 0x33 }
        val r = correrSesion(sec, sec, fpA, fpB, verA = intruso, verB = intruso)
        ok10(r.a is Resultado.Fallo && r.b is Resultado.Fallo && r.codigos.isEmpty(), "intermediario: las pruebas no coinciden y no hay codigo")
    }
    // 4. Rechazo de la persona
    run {
        val r = correrSesion(sec, sec, fpA, fpB, confA = false)
        ok10(r.a is Resultado.Fallo && (r.a as Resultado.Fallo).motivo.contains("No has confirmado"), "rechazo: quien rechaza lo sabe")
        ok10(r.b is Resultado.Fallo && (r.b as Resultado.Fallo).motivo.contains("otro lado"), "rechazo: el otro tambien falla")
    }
    // 5. Nombres
    run {
        ok10(Emparejamiento.limpiarNombre("  Ana\u0000\n\u202Ex  ".replace("\u202E", "")) == "Anax", "nombre: quita caracteres de control")
        ok10(Emparejamiento.limpiarNombre("x".repeat(100)).length == 40 && Emparejamiento.limpiarNombre(" \n ") == "Contacto", "nombre: se recorta y nunca queda vacio")
        val r = correrSesion(sec, sec, fpA, fpB, nombreA = "Ñandú ✓ 日本語")
        ok10((r.b as? Resultado.Exito)?.contacto?.nombre == "Ñandú ✓ 日本語", "nombre: UTF-8 viaja intacto")
    }
    // 6. Entradas malformadas del otro lado
    run {
        fun contra(enviar: (Canal) -> Unit): Resultado {
            val (x, y) = parMem()
            var r: Resultado? = null
            val h = thread { r = Emparejamiento.sesion(x, true, sec, fpA, fpB, "Ana", { _, _ -> true }, 5000) }
            enviar(y); h.join(10_000); x.cerrar(); y.cerrar()
            return r!!
        }
        ok10(contra { Protocolo.escribirTrama(it, Emparejamiento.T_HELLO, ByteArray(5)) } is Resultado.Fallo, "malformado: HELLO demasiado corto")
        ok10(contra { Protocolo.escribirTrama(it, Emparejamiento.T_HELLO, ByteArray(16) + byteArrayOf(200.toByte()) + ByteArray(3)) } is Resultado.Fallo, "malformado: longitud de nombre incoherente")
        ok10(contra { Protocolo.escribirTrama(it, Emparejamiento.T_HELLO, ByteArray(16) + byteArrayOf(2, 0xC3.toByte(), 0x28)) } is Resultado.Fallo, "malformado: UTF-8 invalido")
        ok10(contra { Protocolo.escribirTrama(it, Protocolo.T_MSG, ByteArray(30)) } is Resultado.Fallo, "malformado: tipo de trama equivocado")
        ok10(contra { Protocolo.escribirTrama(it, Emparejamiento.T_HELLO, ByteArray(16) + byteArrayOf(0)); Protocolo.escribirTrama(it, Emparejamiento.T_PRUEBA, ByteArray(7)) } is Resultado.Fallo, "malformado: prueba de largo incorrecto")
        ok10(contra { it.cerrar() } is Resultado.Fallo, "malformado: el otro cierra sin decir nada")
    }
    // 7. Plazo
    run {
        val (x, y) = parMem()
        val t0 = System.currentTimeMillis()
        val r = Emparejamiento.sesion(x, true, sec, fpA, fpB, "Ana", { _, _ -> true }, 800)
        ok10(r is Resultado.Fallo && (r as Resultado.Fallo).motivo == "Tiempo agotado" && System.currentTimeMillis() - t0 < 5000, "plazo: un par callado corta a tiempo (${System.currentTimeMillis() - t0} ms)")
        y.cerrar()
    }
    // 8. Plazo mientras la persona decide
    run {
        val (x, y) = parMem()
        var rb: Resultado? = null
        val hb = thread { rb = Emparejamiento.sesion(y, false, sec, fpB, fpA, "Beto", { _, _ -> true }, 20_000) }
        val ra = Emparejamiento.sesion(x, true, sec, fpA, fpB, "Ana", { _, _ -> Thread.sleep(3000); true }, 1500)
        ok10(ra is Resultado.Fallo, "plazo: si la persona tarda mas que el plazo falla")
        x.cerrar(); hb.join(10_000); y.cerrar()
    }

    // 9. Contactos: memoria y archivo cifrado
    run {
        val dir = java.nio.file.Files.createTempDirectory("cont").toFile()
        val k = SelladorAesGcm(clave())
        val c1 = Contacto("Ana García", ByteArray(32) { 1 }, ByteArray(32) { 2 }, 1000)
        val c2 = Contacto("Beto", ByteArray(32) { 3 }, ByteArray(32) { 4 }, 2000)
        for ((nombre, almacen) in listOf("memoria" to ContactosMemoria(), "archivo" to ContactosArchivo(File(dir, "c1.bin"), k))) {
            almacen.guardar(c1); almacen.guardar(c2)
            ok10(almacen.listar().map { it.nombre } == listOf("Ana García", "Beto"), "contactos ($nombre): lista en orden de alta")
            almacen.guardar(c1.conNombre("Ana G."))
            ok10(almacen.listar().size == 2 && almacen.obtener(c1.id)?.nombre == "Ana G.", "contactos ($nombre): misma huella sustituye en vez de duplicar")
            ok10(almacen.borrar(c2.id) && !almacen.borrar(c2.id) && almacen.listar().size == 1, "contactos ($nombre): borrar")
        }
        val f = File(dir, "c2.bin")
        val a = ContactosArchivo(f, k); a.guardar(c1); a.guardar(c2)
        val b = ContactosArchivo(f, k)
        ok10(b.listar().size == 2 && b.obtener(c2.id)!!.secreto.contentEquals(c2.secreto) && b.obtener(c2.id)!!.creadoMs == 2000L, "contactos (archivo): se recuperan al reabrir")
        val crudo = f.readBytes()
        ok10(String(crudo, Charsets.ISO_8859_1).indexOf("Beto") < 0 && !File(dir, "c2.bin.tmp").exists(), "contactos (archivo): nombres cifrados y sin temporales")
        val mal = crudo.copyOf(); mal[mal.size - 3] = (mal[mal.size - 3].toInt() xor 1).toByte(); File(dir, "c3.bin").writeBytes(mal)
        val c = ContactosArchivo(File(dir, "c3.bin"), k)
        ok10(c.listar().isEmpty() && c.corrupto, "contactos (archivo): alterado se marca corrupto")
        ok10(try { c.guardar(c1); false } catch (_: IllegalStateException) { true } && File(dir, "c3.bin").readBytes().contentEquals(mal), "contactos (archivo): corrupto no se sobrescribe")
        val d = ContactosArchivo(f, SelladorAesGcm(clave()))
        ok10(d.listar().isEmpty() && d.corrupto, "contactos (archivo): otra clave lo marca corrupto")
        ok10(ContactosArchivo(File(dir, "nuevo.bin"), k).let { it.listar().isEmpty() && !it.corrupto }, "contactos (archivo): archivo inexistente = lista vacia sin error")
    }

    // 10. De extremo a extremo: Anfitrion e Invitado con DHT falsa, UdpFiable y TLS reales
    run {
        val dir = java.nio.file.Files.createTempDirectory("par").toFile()
        val ia = crearIdentidad(dir, "pa"); val ib = crearIdentidad(dir, "pb"); val ic = crearIdentidad(dir, "pc")
        val t = Emparejamiento.Tiempos(rafagaMs = 2500, pausaMs = 300, plazoTlsMs = 5000, plazoSesionMs = 20_000)
        val inv = Invitacion.crear(ia.huella, "Ana")
        val nodo = NodoFalso()
        val dA = Dht(Demux(DatagramSocket())); val dB = Dht(Demux(DatagramSocket()))
        val codigos = java.util.Collections.synchronizedList(ArrayList<String>())
        val anf = Emparejamiento.Anfitrion(dA, Confianza.contexto(ia.claves, null), ia.huella, inv.datos, "Ana",
            { c, _ -> codigos.add(c); true }, semillas = listOf(nodo.dir), tiempos = t)
        val inviDatos = Invitacion.interpretar(inv.enlace)!!
        val invi = Emparejamiento.Invitado(dB, Confianza.contexto(ib.claves, inviDatos.huella), ib.huella, inviDatos, "Beto",
            { c, _ -> codigos.add(c); true }, semillas = listOf(nodo.dir), tiempos = t)
        var ra: Resultado? = null; var rb: Resultado? = null
        val h1 = thread { ra = anf.ejecutar() }; Thread.sleep(300); val h2 = thread { rb = invi.ejecutar() }
        h1.join(60_000); h2.join(60_000)
        val a = ra as? Resultado.Exito; val b = rb as? Resultado.Exito
        ok10(a != null && b != null, "extremo a extremo: emparejan por DHT + UDP fiable + TLS (${(ra as? Resultado.Fallo)?.motivo} / ${(rb as? Resultado.Fallo)?.motivo})")
        if (a != null && b != null) {
            ok10(a.contacto.huella.contentEquals(ib.huella) && b.contacto.huella.contentEquals(ia.huella), "extremo a extremo: las huellas guardadas son las reales de cada identidad")
            ok10(a.contacto.secreto.contentEquals(b.contacto.secreto) && codigos.size == 2 && codigos[0] == codigos[1], "extremo a extremo: mismo secreto y mismo codigo")
        }
        dA.puerto.cerrar(); dB.puerto.cerrar(); nodo.cerrar()

        // Invitado con una clave distinta de la del enlace (el enlace dice C, pero quien responde es A)
        val nodo2 = NodoFalso()
        val dA2 = Dht(Demux(DatagramSocket())); val dB2 = Dht(Demux(DatagramSocket()))
        val inv2 = Invitacion.crear(ia.huella, "Ana")
        val anf2 = Emparejamiento.Anfitrion(dA2, Confianza.contexto(ia.claves, null), ia.huella, inv2.datos, "Ana",
            { _, _ -> true }, semillas = listOf(nodo2.dir), tiempos = t)
        val falsa = Invitacion.Datos(ic.huella, inv2.datos.secreto, "Ana", inv2.datos.caducaS)
        val invi2 = Emparejamiento.Invitado(dB2, Confianza.contexto(ib.claves, falsa.huella), ib.huella, falsa, "Beto",
            { _, _ -> true }, semillas = listOf(nodo2.dir), tiempos = t)
        var rb2: Resultado? = null
        val g1 = thread { anf2.ejecutar() }; Thread.sleep(300); val g2 = thread { rb2 = invi2.ejecutar() }
        g2.join(60_000)
        ok10(rb2 is Resultado.Fallo && (rb2 as Resultado.Fallo).motivo.contains("no es con quien"), "huella del enlace distinta: el invitado se niega (${(rb2 as? Resultado.Fallo)?.motivo})")
        anf2.cancelar(); g1.join(20_000)
        ok10(!g1.isAlive, "anfitrion: cancelar() termina la espera")
        dA2.puerto.cerrar(); dB2.puerto.cerrar(); nodo2.cerrar()

        // Invitacion caducada
        val vieja = Invitacion.crear(ia.huella, "Ana", ahoraMs = System.currentTimeMillis() - 3_600_000)
        val dC = Dht(Demux(DatagramSocket()))
        val rc = Emparejamiento.Anfitrion(dC, Confianza.contexto(ia.claves, null), ia.huella, vieja.datos, "Ana", { _, _ -> true }, tiempos = t).ejecutar()
        ok10(rc is Resultado.Fallo && (rc as Resultado.Fallo).motivo.contains("caducado"), "invitacion caducada: el anfitrion ni empieza")
        val rd = Emparejamiento.Invitado(dC, Confianza.contexto(ib.claves, ia.huella), ib.huella, vieja.datos, "Beto", { _, _ -> true }, tiempos = t).ejecutar()
        ok10(rd is Resultado.Fallo && (rd as Resultado.Fallo).motivo.contains("caducado"), "invitacion caducada: el invitado tampoco")
        dC.puerto.cerrar()
    }


    // 11. Prueba de conexion con un contacto ya emparejado
    run {
        val dir = java.nio.file.Files.createTempDirectory("pc").toFile()
        val ia = crearIdentidad(dir, "qa"); val ib = crearIdentidad(dir, "qb"); val ic = crearIdentidad(dir, "qc")
        val sec = ByteArray(32) { (it * 3).toByte() }
        val deA = Contacto("Beto", ib.huella, sec, 1L); val deB = Contacto("Ana", ia.huella, sec, 1L)
        val prm = com.optimo.chat.PruebaContacto.Parametros(maxEncuentroMs = 30_000, rafagaMs = 2500, pausaMs = 300, plazoTlsMs = 5000, plazoEcoMs = 5000)
        val nodo = NodoFalso()
        val dA = Dht(Demux(DatagramSocket())); val dB = Dht(Demux(DatagramSocket()))
        var ra: com.optimo.chat.PruebaContacto.Resultado? = null; var rb: com.optimo.chat.PruebaContacto.Resultado? = null
        val h1 = thread { ra = com.optimo.chat.PruebaContacto.ejecutar(dA, deA, Confianza.contexto(ia.claves, deA.huella), ia.huella, prm, listOf(nodo.dir)) }
        Thread.sleep(300)
        val h2 = thread { rb = com.optimo.chat.PruebaContacto.ejecutar(dB, deB, Confianza.contexto(ib.claves, deB.huella), ib.huella, prm, listOf(nodo.dir)) }
        h1.join(60_000); h2.join(60_000)
        ok10(ra?.exito == true && rb?.exito == true, "probar contacto: los dos completan (${ra?.motivo} / ${rb?.motivo})")
        ok10((ra?.msEco ?: -1) >= 0 && (rb?.msEco ?: -1) >= 0 && ra?.protocolo?.startsWith("TLS") == true, "probar contacto: eco medido y protocolo TLS (${ra?.protocolo}, eco ${ra?.msEco} ms)")
        val l = com.optimo.chat.PruebaContacto.linea("12:00:00", "red=wifi", deA.id, ra!!)
        ok10(Regex("OK con \\d+\\.\\d+\\.\\*\\.\\*:\\d+").containsMatchIn(l) && !l.contains("Beto") && l.contains("contacto ${deA.id.take(4)}"), "probar contacto: la linea de informe enmascara la IP y no lleva el nombre ($l)")
        dA.puerto.cerrar(); dB.puerto.cerrar(); nodo.cerrar()

        // Huella del contacto distinta de la real: falla sin llegar al eco
        val nodo2 = NodoFalso()
        val dA2 = Dht(Demux(DatagramSocket())); val dB2 = Dht(Demux(DatagramSocket()))
        val malA = Contacto("Beto", ic.huella, sec, 1L)   // A cree que B es C
        var rc: com.optimo.chat.PruebaContacto.Resultado? = null
        val g1 = thread { rc = com.optimo.chat.PruebaContacto.ejecutar(dA2, malA, Confianza.contexto(ia.claves, malA.huella), ia.huella, prm, listOf(nodo2.dir)) }
        Thread.sleep(300)
        val g2 = thread { com.optimo.chat.PruebaContacto.ejecutar(dB2, deB, Confianza.contexto(ib.claves, deB.huella), ib.huella, prm, listOf(nodo2.dir)) }
        g1.join(60_000); g2.join(60_000)
        ok10(rc?.exito == false && rc?.msEco == -1L, "probar contacto: con otra huella falla sin eco (${rc?.motivo})")
        dA2.puerto.cerrar(); dB2.puerto.cerrar(); nodo2.cerrar()

        // Nadie al otro lado + cancelar
        val nodo3 = NodoFalso(); val dC = Dht(Demux(DatagramSocket()))
        val corto = com.optimo.chat.PruebaContacto.Parametros(maxEncuentroMs = 2500, rafagaMs = 1000, pausaMs = 200)
        val rs = com.optimo.chat.PruebaContacto.ejecutar(dC, deA, Confianza.contexto(ia.claves, deA.huella), ia.huella, corto, listOf(nodo3.dir))
        ok10(!rs.exito && rs.motivo!!.contains("No se ha encontrado"), "probar contacto: sin el otro movil termina con un motivo claro")
        var parar = false
        val t0 = System.currentTimeMillis()
        val hc = thread { com.optimo.chat.PruebaContacto.ejecutar(dC, deA, Confianza.contexto(ia.claves, deA.huella), ia.huella, prm, listOf(nodo3.dir), { parar }) }
        Thread.sleep(800); parar = true; hc.join(20_000)
        ok10(!hc.isAlive && System.currentTimeMillis() - t0 < 20_000, "probar contacto: cancelar corta la busqueda")
        dC.puerto.cerrar(); nodo3.cerrar()
    }

    println(if (f10 == 0) "Emparejamiento: todo OK" else "Emparejamiento: $f10 FALLOS")
    return f10
}
