import com.optimo.chat.*

fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
var f2 = 0
fun ok2(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f2++ }

fun mainInvitacion(): Int {
    // Base32 RFC 4648 (en minusculas, sin relleno)
    ok2(Invitacion.base32("foobar".toByteArray()) == "mzxw6ytboi", "base32 vector RFC 4648")
    ok2(Invitacion.base32("fo".toByteArray()) == "mzxq", "base32 con resto")
    ok2(String(Invitacion.desdeBase32("mzxw6ytboi")!!) == "foobar", "desde base32")
    ok2(Invitacion.desdeBase32("MZXW6YTBOI")!!.size == 6, "acepta mayusculas")
    ok2(Invitacion.desdeBase32("mz1")==null, "rechaza caracter fuera del alfabeto")
    val al = ByteArray(32) { (it * 7 + 3).toByte() }
    ok2(Invitacion.desdeBase32(Invitacion.base32(al))!!.contentEquals(al) && Invitacion.base32(al).length == 52, "ida y vuelta de 32 bytes = 52 caracteres")

    // HKDF: vector 1 del RFC 5869 (SHA-256)
    val okm = Invitacion.hkdf(ByteArray(22) { 0x0b }, unhex("000102030405060708090a0b0c"), unhex("f0f1f2f3f4f5f6f7f8f9"), 42)
    ok2(hex(okm) == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", "HKDF vector RFC 5869 caso 1")

    // Enlace
    val huella = ByteArray(32) { (it + 1).toByte() }
    val t0 = 1_700_000_000_000L
    val c = Invitacion.crear(huella, "Ana García", t0)
    println("   enlace: ${c.enlace}")
    val d = Invitacion.interpretar(c.enlace, t0 + 5_000)!!
    ok2(d.huella.contentEquals(huella) && d.secreto.contentEquals(c.datos.secreto) && d.nombre == "Ana García", "ida y vuelta del enlace (con acento)")
    ok2(Invitacion.interpretar(c.enlace, t0 + 601_000) == null, "caduca a los 10 minutos")
    ok2(Invitacion.interpretar(c.enlace, t0 + 599_000) != null, "vale hasta justo antes")
    ok2(Invitacion.interpretar(c.enlace.replace("v=1", "v=2"), t0) == null, "rechaza otra version")
    ok2(Invitacion.interpretar(c.enlace.replace("&e=", "&x="), t0) == null, "rechaza sin caducidad")
    ok2(Invitacion.interpretar(c.enlace + "&k=aaaa", t0) == null, "rechaza parametro repetido")
    ok2(Invitacion.interpretar("https://optimo/invitar?v=1", t0) == null, "rechaza otro esquema")
    ok2(Invitacion.interpretar(c.enlace.replace("&s=", "&s=aa"), t0) == null, "rechaza secreto de largo incorrecto")
    ok2(Invitacion.interpretar("optimo://invitar?v=1&k&s", t0) == null, "rechaza parametros sin valor")
    ok2(Invitacion.crear(huella, "x".repeat(100), t0).datos.nombre.length == 40, "recorta el nombre a 40")

    // Prueba, codigo y secreto de contacto
    val sec = c.datos.secreto
    val hA = ByteArray(32) { 1 }; val hB = ByteArray(32) { 2 }; val hM = ByteArray(32) { 3 }
    val nA = ByteArray(16) { 9 }; val nB = ByteArray(16) { 8 }
    val pA = Invitacion.prueba(sec, hA, hB, nA, nB)
    ok2(Invitacion.iguales(pA, Invitacion.prueba(sec, hA, hB, nA, nB)), "misma entrada = misma prueba")
    ok2(!Invitacion.iguales(pA, Invitacion.prueba(sec, hA, hM, nA, nB)), "intermediario (otra huella) = prueba distinta")
    ok2(!Invitacion.iguales(pA, Invitacion.prueba(ByteArray(16) { 5 }, hA, hB, nA, nB)), "otro secreto = prueba distinta")
    ok2(!Invitacion.iguales(pA, Invitacion.prueba(sec, hA, hB, nB, nA)), "nonces intercambiados = prueba distinta")
    val cod = Invitacion.codigoCorto(pA)
    ok2(cod.length == 6 && cod.all { it.isDigit() }, "codigo de 6 digitos: $cod")
    ok2(Invitacion.codigoCorto(ByteArray(32)) == "000000", "codigo con ceros a la izquierda")
    val sc = Invitacion.secretoDeContacto(sec, hA, hB)
    ok2(sc.size == 32 && !Invitacion.iguales(sc, Invitacion.secretoDeContacto(sec, hB, hA)), "secreto de contacto de 32 bytes y dependiente del orden")
    println(if (f2 == 0) "TODO OK (2)" else "FALLOS: $f2")
    return f2
}
