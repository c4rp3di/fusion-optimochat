package com.optimo.chat

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fase 1 (logica pura, sin APIs de Android): enlace de invitacion, prueba de emparejamiento, codigo corto de
 * verificacion y derivacion del secreto de contacto. Solo usa HMAC-SHA256 y la composicion estandar HKDF (RFC 5869).
 *
 * Enlace: optimo://invitar?v=1&k=<huella, base32>&s=<secreto 16 bytes, base32>&n=<nombre>&e=<caducidad, s epoch>
 * El anfitrion (A) crea el enlace; el invitado (B) lo abre. Roles fijos en la prueba: A = anfitrion, B = invitado.
 */
object Invitacion {

    const val PREFIJO = "optimo://invitar"
    const val VIGENCIA_S = 600L
    private const val MAX_NOMBRE = 40

    class Datos(val huella: ByteArray, val secreto: ByteArray, val nombre: String, val caducaS: Long)

    class Creada(val enlace: String, val datos: Datos)

    fun crear(
        huella: ByteArray,
        nombre: String,
        ahoraMs: Long = System.currentTimeMillis(),
        secreto: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    ): Creada {
        require(huella.size == 32) { "La huella debe tener 32 bytes" }
        require(secreto.size == 16) { "El secreto debe tener 16 bytes" }
        val n = nombre.trim().take(MAX_NOMBRE)
        val caduca = ahoraMs / 1000 + VIGENCIA_S
        val enlace = "$PREFIJO?v=1&k=${base32(huella)}&s=${base32(secreto)}" +
            "&n=${URLEncoder.encode(n, "UTF-8")}&e=$caduca"
        return Creada(enlace, Datos(huella, secreto, n, caduca))
    }

    /** Devuelve null si el enlace no es valido, esta mal formado o ha caducado. */
    fun interpretar(enlace: String, ahoraMs: Long = System.currentTimeMillis()): Datos? {
        val e = enlace.trim()
        if (!e.startsWith("$PREFIJO?")) return null
        val params = HashMap<String, String>()
        for (par in e.substring(PREFIJO.length + 1).split('&')) {
            val i = par.indexOf('=')
            if (i <= 0) return null
            val clave = par.substring(0, i)
            if (params.containsKey(clave)) return null
            params[clave] = try { URLDecoder.decode(par.substring(i + 1), "UTF-8") } catch (_: Exception) { return null }
        }
        if (params["v"] != "1") return null
        val huella = desdeBase32(params["k"] ?: return null) ?: return null
        val secreto = desdeBase32(params["s"] ?: return null) ?: return null
        if (huella.size != 32 || secreto.size != 16) return null
        val caduca = params["e"]?.toLongOrNull() ?: return null
        if (ahoraMs / 1000 > caduca) return null
        val nombre = (params["n"] ?: "").take(MAX_NOMBRE)
        return Datos(huella, secreto, nombre, caduca)
    }

    private fun hmac(clave: ByteArray, vararg partes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (clave.isEmpty()) ByteArray(32) else clave, "HmacSHA256"))
        for (p in partes) mac.update(p)
        return mac.doFinal()
    }

    /**
     * Prueba que se intercambia dentro de TLS: une el secreto del enlace con las dos huellas realmente usadas y
     * dos nonces. Un intermediario que presente otra clave produce una prueba distinta en cada lado.
     */
    fun prueba(secreto: ByteArray, huellaA: ByteArray, huellaB: ByteArray, nonceA: ByteArray, nonceB: ByteArray): ByteArray =
        hmac(secreto, "optimochat-pair-v1".toByteArray(Charsets.UTF_8), huellaA, huellaB, nonceA, nonceB)

    /** Codigo de 6 digitos para comparar en persona o por voz. */
    fun codigoCorto(prueba: ByteArray): String {
        val v = ((prueba[0].toLong() and 0xFF) shl 24) or ((prueba[1].toLong() and 0xFF) shl 16) or
            ((prueba[2].toLong() and 0xFF) shl 8) or (prueba[3].toLong() and 0xFF)
        return "%06d".format(v % 1_000_000L)
    }

    /** Secreto compartido de contacto (32 bytes) para los identificadores de encuentro. */
    fun secretoDeContacto(secreto: ByteArray, huellaA: ByteArray, huellaB: ByteArray): ByteArray =
        hkdf(secreto, "optimochat-contacto-v1".toByteArray(Charsets.UTF_8), huellaA + huellaB, 32)

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, sal: ByteArray, info: ByteArray, largo: Int): ByteArray {
        require(largo in 1..(255 * 32)) { "Largo no valido" }
        val prk = hmac(sal, ikm)
        val salida = ByteArray(largo)
        var previo = ByteArray(0)
        var escrito = 0
        var contador = 1
        while (escrito < largo) {
            previo = hmac(prk, previo, info, byteArrayOf(contador.toByte()))
            val n = minOf(previo.size, largo - escrito)
            System.arraycopy(previo, 0, salida, escrito, n)
            escrito += n
            contador++
        }
        return salida
    }

    /** Comparacion en tiempo constante. */
    fun iguales(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---- Base32 (RFC 4648, minusculas, sin relleno) ----

    private const val ALFABETO = "abcdefghijklmnopqrstuvwxyz234567"

    fun base32(d: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in d) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALFABETO[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALFABETO[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun desdeBase32(t: String): ByteArray? {
        val salida = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in t.lowercase()) {
            val v = ALFABETO.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                salida.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return salida.toByteArray()
    }
}
