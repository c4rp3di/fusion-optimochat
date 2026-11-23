import com.optimo.chat.Invitacion
import com.optimo.chat.QrCodigo
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

var f8 = 0
fun ok8(c: Boolean, m: String) { println((if (c) "OK    " else "FALLO ") + m); if (!c) f8++ }

fun guardarQr(dir: File, nombre: String, texto: String) {
    val q = QrCodigo.generar(texto)
    val esc = 6; val zona = 4; val n = q.size + 2 * zona
    val img = BufferedImage(n * esc, n * esc, BufferedImage.TYPE_BYTE_GRAY)
    for (y in 0 until n * esc) for (x in 0 until n * esc) {
        val mx = x / esc - zona; val my = y / esc - zona
        val osc = mx in q.indices && my in q.indices && q[my][mx]
        img.raster.setSample(x, y, 0, if (osc) 0 else 255)
    }
    ImageIO.write(img, "png", File(dir, "$nombre.png"))
    File(dir, "$nombre.txt").writeText(texto, Charsets.UTF_8)
}

fun mainQr(): Int {
    ok8(QrCodigo.generar("a").size == 21, "version 1 mide 21 modulos")
    ok8(QrCodigo.generar("x".repeat(15)).size == 25 || QrCodigo.generar("x".repeat(15)).size == 21, "tamano coherente con la capacidad")
    ok8(QrCodigo.capacidadBytes() == 666, "capacidad maxima 666 bytes (${QrCodigo.capacidadBytes()})")
    ok8(try { QrCodigo.generar("x".repeat(700)); false } catch (_: IllegalArgumentException) { true }, "rechaza texto demasiado largo")
    val a = QrCodigo.generar("hola"); val b = QrCodigo.generar("hola")
    ok8(a.indices.all { a[it].contentEquals(b[it]) }, "es determinista")

    val dir = java.nio.file.Files.createTempDirectory("qr").toFile()
    val inv = Invitacion.crear(ByteArray(32) { it.toByte() }, "Ana García").enlace
    val textos = LinkedHashMap<String, String>()
    textos["invitacion"] = inv
    textos["corto"] = "optimo"
    textos["utf8"] = "Ñandú ✓ 日本語"
    for (n in listOf(1, 14, 15, 26, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 363, 367, 380, 400, 412, 425, 458, 520, 586, 644, 666)) {
        textos["len$n"] = (0 until n).joinToString("") { ('a' + (it * 7 % 26)).toString() }
    }
    textos.forEach { (k, v) -> guardarQr(dir, k, v) }

    val p = ProcessBuilder("python3", "-I", File("pruebas/jvm/qr_verificar.py").absolutePath, dir.path).redirectErrorStream(true).start()
    val salida = p.inputStream.bufferedReader().readText(); p.waitFor()
    if (salida.contains("SIN_OPENCV") || !p.isAlive && salida.isBlank()) {
        println("AVISO: sin OpenCV, no se decodifican los QR")
    } else {
        val lineas = salida.lines().filter { it.startsWith("OKQR") || it.startsWith("MALQR") }
        ok8(lineas.size == textos.size, "decodificador: ${lineas.size} de ${textos.size} imagenes procesadas")
        lineas.forEach { ok8(it.startsWith("OKQR"), "QR decodificado por OpenCV: ${it.substringAfter(' ')} bytes") }
    }
    println(if (f8 == 0) "QR: todo OK" else "QR: $f8 FALLOS")
    return f8
}
