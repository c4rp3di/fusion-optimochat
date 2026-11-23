package com.optimo.chat

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Formato de tramas y mensajes que viajan dentro de TLS (logica pura, probable en la JVM).
 *
 * Trama: [longitud u32 = 1 + carga] [tipo u8] [carga]. Una longitud nula o mayor que [MAX_TRAMA] cierra la conexion.
 *
 *   HELLO  version u8 | capacidades u8 | ultimo seq recibido del otro u64
 *   MSG    id 16 | seq u64 | hora u64 (ms) | flags u8 (bit0 = responde a otro) | [id respuesta 16] | n u16 | cuerpo UTF-8
 *   ACK    id 16       (lo recibi y lo guarde)
 *   READ   id 16       (el usuario lo ha leido)
 *   PING   vacia
 *   BYE    codigo u8
 */
object Protocolo {

    const val VERSION = 1
    const val CAP_LEIDO = 1

    const val T_HELLO = 1
    const val T_MSG = 2
    const val T_ACK = 3
    const val T_READ = 4
    const val T_PING = 5
    const val T_BYE = 6

    const val BYE_NORMAL = 0
    const val BYE_VERSION = 1
    const val BYE_PROTOCOLO = 2
    const val BYE_LIMITE = 3

    const val MAX_TRAMA = 64 * 1024
    const val MAX_CUERPO = 8 * 1024
    const val TAM_ID = 16

    class Trama(val tipo: Int, val carga: ByteArray)

    class Mensaje(
        val id: ByteArray,
        val seq: Long,
        val horaMs: Long,
        val cuerpo: String,
        val respuestaA: ByteArray? = null
    ) {
        val idTexto: String get() = id.joinToString("") { "%02x".format(it) }
    }

    class Hello(val version: Int, val capacidades: Int, val ultimoSeq: Long)

    // ---- Tramas ----

    fun escribirTrama(c: Canal, tipo: Int, carga: ByteArray = ByteArray(0)) {
        require(carga.size + 1 <= MAX_TRAMA) { "Trama demasiado grande" }
        val b = ByteBuffer.allocate(5 + carga.size)
        b.putInt(1 + carga.size)
        b.put(tipo.toByte())
        b.put(carga)
        // Una sola escritura por trama: dos hilos no pueden mezclar sus bytes si quien llama sincroniza.
        c.escribir(b.array(), 0, b.capacity())
    }

    /** Lee una trama completa. Null si el canal termina limpiamente entre tramas. */
    fun leerTrama(c: Canal): Trama? {
        val cab = ByteArray(4)
        if (!leerExacto(c, cab, 4, permitirFin = true)) return null
        val largo = ByteBuffer.wrap(cab).int
        if (largo < 1 || largo > MAX_TRAMA) throw IOException("Longitud de trama no valida: $largo")
        val cuerpo = ByteArray(largo)
        leerExacto(c, cuerpo, largo, permitirFin = false)
        return Trama(cuerpo[0].toInt() and 0xFF, cuerpo.copyOfRange(1, largo))
    }

    private fun leerExacto(c: Canal, d: ByteArray, n: Int, permitirFin: Boolean): Boolean {
        var pos = 0
        while (pos < n) {
            val k = c.leer(d, pos, n - pos)
            if (k < 0) {
                if (pos == 0 && permitirFin) return false
                throw IOException("Canal cerrado a mitad de una trama")
            }
            pos += k
        }
        return true
    }

    // ---- Cargas ----

    fun codificarHello(capacidades: Int, ultimoSeq: Long): ByteArray =
        ByteBuffer.allocate(10).put(VERSION.toByte()).put(capacidades.toByte()).putLong(ultimoSeq).array()

    fun decodificarHello(d: ByteArray): Hello? {
        if (d.size != 10) return null
        val b = ByteBuffer.wrap(d)
        return Hello(b.get().toInt() and 0xFF, b.get().toInt() and 0xFF, b.long)
    }

    fun codificarMensaje(m: Mensaje): ByteArray {
        require(m.id.size == TAM_ID) { "id de 16 bytes" }
        val cuerpo = m.cuerpo.toByteArray(Charsets.UTF_8)
        require(cuerpo.size <= MAX_CUERPO) { "Mensaje demasiado largo" }
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        d.write(m.id)
        d.writeLong(m.seq)
        d.writeLong(m.horaMs)
        if (m.respuestaA != null) {
            require(m.respuestaA.size == TAM_ID)
            d.writeByte(1)
            d.write(m.respuestaA)
        } else {
            d.writeByte(0)
        }
        d.writeShort(cuerpo.size)
        d.write(cuerpo)
        return o.toByteArray()
    }

    /** Null si la carga no es exactamente un mensaje valido. */
    fun decodificarMensaje(d: ByteArray): Mensaje? {
        if (d.size < TAM_ID + 8 + 8 + 1 + 2) return null
        val b = ByteBuffer.wrap(d)
        val id = ByteArray(TAM_ID).also { b.get(it) }
        val seq = b.long
        val hora = b.long
        val flags = b.get().toInt() and 0xFF
        if (flags and 0xFE != 0) return null
        var resp: ByteArray? = null
        if (flags and 1 != 0) {
            if (b.remaining() < TAM_ID + 2) return null
            resp = ByteArray(TAM_ID).also { b.get(it) }
        }
        if (b.remaining() < 2) return null
        val n = b.short.toInt() and 0xFFFF
        if (n > MAX_CUERPO || b.remaining() != n) return null
        if (seq < 0) return null
        val txt = ByteArray(n).also { b.get(it) }
        val cuerpo = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(txt)).toString()
        } catch (_: Exception) { return null }
        return Mensaje(id, seq, hora, cuerpo, resp)
    }

    fun codificarId(id: ByteArray): ByteArray = id.copyOf()

    fun decodificarId(d: ByteArray): ByteArray? = if (d.size == TAM_ID) d.copyOf() else null
}
