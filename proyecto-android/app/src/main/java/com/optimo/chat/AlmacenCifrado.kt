package com.optimo.chat

import com.optimo.chat.Protocolo.Mensaje
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifra y descifra con autenticacion. El contexto (AAD) ata el texto cifrado a su fila: moverlo a otra lo invalida. */
interface Sellador {
    fun sellar(contexto: ByteArray, claro: ByteArray): ByteArray

    /** Null si la clave no es la buena, el contexto no coincide o el dato esta alterado. */
    fun abrir(contexto: ByteArray, sellado: ByteArray): ByteArray?
}

/**
 * AES-256-GCM con la API de la plataforma. Sirve con una clave en memoria (pruebas) y con una clave de Android
 * Keystore (que no sale del chip): en los dos casos el proveedor genera el IV al cifrar y se lee con `cipher.iv`.
 * Formato: [version u8 = 1][iv 12][texto cifrado + etiqueta 16].
 */
class SelladorAesGcm(private val clave: SecretKey) : Sellador {
    companion object { const val VERSION = 1; const val TAM_IV = 12 }

    override fun sellar(contexto: ByteArray, claro: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, clave)
        c.updateAAD(contexto)
        val iv = c.iv
        check(iv.size == TAM_IV) { "IV inesperado" }
        val ct = c.doFinal(claro)
        return ByteBuffer.allocate(1 + TAM_IV + ct.size).put(VERSION.toByte()).put(iv).put(ct).array()
    }

    override fun abrir(contexto: ByteArray, sellado: ByteArray): ByteArray? {
        if (sellado.size < 1 + TAM_IV + 16 || sellado[0].toInt() != VERSION) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(128, sellado, 1, TAM_IV))
            c.updateAAD(contexto)
            c.doFinal(sellado, 1 + TAM_IV, sellado.size - 1 - TAM_IV)
        } catch (_: Exception) { null }
    }
}

/**
 * Una fila tal como se guarda. En claro solo van los datos que el almacen necesita para consultar (id aleatorio,
 * direccion, numero de secuencia y estado); la hora, el cuerpo y la respuesta van cifrados en [blob].
 */
class FilaCruda(val id: String, val propio: Boolean, val seq: Long, val estado: Int, val blob: ByteArray)

/** Almacenamiento sin cifrado ni logica de chat: SQLite en Android ([FilasSqlite]) y memoria en las pruebas. */
interface AlmacenFilas {
    /** False si ya existe una fila con ese id. */
    fun insertar(f: FilaCruda): Boolean
    fun obtener(id: String): FilaCruda?

    /** Fila PROPIA con estado 0 (pendiente) y menor seq, con seq > [despuesDeSeq]. */
    fun primeraPendiente(despuesDeSeq: Long): FilaCruda?

    /** Cambia el estado de una fila PROPIA si el actual esta en [desde]. True si cambio. */
    fun cambiarEstado(id: String, desde: List<Int>, a: Int): Boolean

    /** Igual para todas las filas PROPIAS con seq <= [seq]. Devuelve los ids cambiados. */
    fun cambiarEstadoHasta(seq: Long, desde: List<Int>, a: Int): List<String>

    fun reiniciarEnviados()
    fun existeRecibidoSeq(seq: Long): Boolean

    /** Las ultimas [limite] filas por orden de insercion, de la mas antigua a la mas reciente. */
    fun ultimas(limite: Int): List<FilaCruda>

    fun meta(clave: String): Long?
    fun ponerMeta(clave: String, valor: Long)
}

/**
 * [AlmacenMensajes] persistente: delega en un [AlmacenFilas] y cifra lo sensible con un [Sellador]. Seguro entre hilos.
 * Una fila alterada, movida o cifrada con otra clave se ignora (no se entrega ni se reenvia) en vez de romper la cola.
 */
class AlmacenCifrado(private val filas: AlmacenFilas, private val sellador: Sellador) : AlmacenMensajes {

    class Entrada(val mensaje: Mensaje, val propio: Boolean, val estado: Estado)

    private val P = Estado.PENDIENTE.ordinal
    private val E = Estado.ENVIADO.ordinal
    private val D = Estado.ENTREGADO.ordinal
    private val L = Estado.LEIDO.ordinal

    private fun contexto(id: String, propio: Boolean, seq: Long) =
        "optimochat-msg-v1|$id|${if (propio) 1 else 0}|$seq".toByteArray(Charsets.UTF_8)

    private fun hex(id: ByteArray) = id.joinToString("") { "%02x".format(it) }

    private fun claro(m: Mensaje): ByteArray {
        val cuerpo = m.cuerpo.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(8 + 1 + (if (m.respuestaA != null) Protocolo.TAM_ID else 0) + cuerpo.size)
        b.putLong(m.horaMs)
        if (m.respuestaA != null) { b.put(1); b.put(m.respuestaA) } else b.put(0)
        b.put(cuerpo)
        return b.array()
    }

    private fun mensajeDe(f: FilaCruda): Mensaje? {
        val c = sellador.abrir(contexto(f.id, f.propio, f.seq), f.blob) ?: return null
        return try {
            val b = ByteBuffer.wrap(c)
            val hora = b.long
            val resp = if (b.get().toInt() == 1) ByteArray(Protocolo.TAM_ID).also { b.get(it) } else null
            val cuerpo = ByteArray(b.remaining()).also { b.get(it) }
            val id = ByteArray(f.id.length / 2) { i -> f.id.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            Mensaje(id, f.seq, hora, String(cuerpo, Charsets.UTF_8), resp)
        } catch (_: Exception) { null }
    }

    private fun fila(m: Mensaje, propio: Boolean, estado: Estado): FilaCruda =
        FilaCruda(m.idTexto, propio, m.seq, estado.ordinal, sellador.sellar(contexto(m.idTexto, propio, m.seq), claro(m)))

    @Synchronized override fun siguienteSeq(): Long {
        val v = (filas.meta("seqPropio") ?: 0L) + 1
        filas.ponerMeta("seqPropio", v)
        return v
    }

    @Synchronized override fun guardarEnviado(m: Mensaje) { filas.insertar(fila(m, true, Estado.PENDIENTE)) }

    @Synchronized override fun siguientePendiente(): Mensaje? {
        var cursor = 0L
        while (true) {
            val f = filas.primeraPendiente(cursor) ?: return null
            mensajeDe(f)?.let { return it }
            cursor = f.seq
        }
    }

    @Synchronized override fun marcarEnviado(id: ByteArray) { filas.cambiarEstado(hex(id), listOf(P), E) }

    @Synchronized override fun marcarEntregado(id: ByteArray): Boolean = filas.cambiarEstado(hex(id), listOf(P, E), D)

    @Synchronized override fun marcarEntregadosHasta(seq: Long): List<ByteArray> =
        filas.cambiarEstadoHasta(seq, listOf(P, E), D).map { h ->
            ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        }

    @Synchronized override fun reiniciarEnviados() { filas.reiniciarEnviados() }

    @Synchronized override fun marcarLeidoPorPar(id: ByteArray): Boolean = filas.cambiarEstado(hex(id), listOf(P, E, D), L)

    @Synchronized override fun guardarRecibido(m: Mensaje): Boolean {
        if (!filas.insertar(fila(m, false, Estado.ENTREGADO))) return false
        var u = filas.meta("ultimoSeq") ?: 0L
        while (filas.existeRecibidoSeq(u + 1)) u++
        filas.ponerMeta("ultimoSeq", u)
        return true
    }

    @Synchronized override fun ultimoSeqRecibido(): Long = filas.meta("ultimoSeq") ?: 0L

    @Synchronized override fun estado(id: ByteArray): Estado? =
        filas.obtener(hex(id))?.takeIf { it.propio }?.let { Estado.values()[it.estado] }

    /** Para la interfaz: las ultimas [limite] entradas en orden de llegada. Las filas ilegibles se omiten. */
    @Synchronized fun historial(limite: Int): List<Entrada> =
        filas.ultimas(limite).mapNotNull { f ->
            mensajeDe(f)?.let { Entrada(it, f.propio, Estado.values()[f.estado]) }
        }
}
