package com.optimo.chat

import com.optimo.chat.Protocolo.Mensaje

enum class Estado { PENDIENTE, ENVIADO, ENTREGADO, LEIDO }

/**
 * Persistencia de la conversacion con UN contacto. La implementacion real (fase 3) usa SQLite con el cuerpo cifrado
 * fila a fila; [AlmacenMemoria] sirve para pruebas. Las implementaciones deben ser seguras entre hilos.
 */
interface AlmacenMensajes {
    /** Numero de secuencia del proximo mensaje propio (empieza en 1). Cada llamada reserva uno. */
    fun siguienteSeq(): Long

    /** Guarda un mensaje propio en estado PENDIENTE. */
    fun guardarEnviado(m: Mensaje)

    /** El mensaje propio mas antiguo que sigue PENDIENTE (aun sin escribir en ningun canal). */
    fun siguientePendiente(): Mensaje?

    /** PENDIENTE pasa a ENVIADO. */
    fun marcarEnviado(id: ByteArray)

    /** Devuelve true si el estado cambio (PENDIENTE o ENVIADO pasan a ENTREGADO). */
    fun marcarEntregado(id: ByteArray): Boolean

    /** Marca como entregados los propios con seq <= [seq] que no lo estaban. Devuelve sus ids. */
    fun marcarEntregadosHasta(seq: Long): List<ByteArray>

    /** Al empezar una sesion, lo ENVIADO sin confirmar vuelve a PENDIENTE para reenviarse en orden. */
    fun reiniciarEnviados()

    /** El otro lado lo ha leido. Devuelve true si el estado cambio. */
    fun marcarLeidoPorPar(id: ByteArray): Boolean

    /** Guarda un mensaje recibido. False si ya estaba (duplicado). */
    fun guardarRecibido(m: Mensaje): Boolean

    /** Mayor seq recibido sin huecos desde 1. */
    fun ultimoSeqRecibido(): Long

    fun estado(id: ByteArray): Estado?
}

class AlmacenMemoria : AlmacenMensajes {
    private class Fila(val m: Mensaje, var estado: Estado)

    private val propios = java.util.LinkedHashMap<String, Fila>()
    private val recibidosIds = HashSet<String>()
    private val recibidosSeq = HashSet<Long>()
    private var ultimoSeq = 0L
    private var seqPropio = 0L

    val recibidos = ArrayList<Mensaje>()

    @Synchronized override fun siguienteSeq(): Long = ++seqPropio

    @Synchronized override fun guardarEnviado(m: Mensaje) {
        propios[m.idTexto] = Fila(m, Estado.PENDIENTE)
    }

    @Synchronized override fun siguientePendiente(): Mensaje? =
        propios.values.filter { it.estado == Estado.PENDIENTE }.minByOrNull { it.m.seq }?.m

    @Synchronized override fun marcarEnviado(id: ByteArray) {
        val f = propios[hex(id)] ?: return
        if (f.estado == Estado.PENDIENTE) f.estado = Estado.ENVIADO
    }

    @Synchronized override fun marcarEntregado(id: ByteArray): Boolean {
        val f = propios[hex(id)] ?: return false
        if (f.estado == Estado.PENDIENTE || f.estado == Estado.ENVIADO) { f.estado = Estado.ENTREGADO; return true }
        return false
    }

    @Synchronized override fun marcarEntregadosHasta(seq: Long): List<ByteArray> {
        val r = ArrayList<ByteArray>()
        for (f in propios.values) {
            if (f.m.seq <= seq && (f.estado == Estado.PENDIENTE || f.estado == Estado.ENVIADO)) {
                f.estado = Estado.ENTREGADO
                r.add(f.m.id)
            }
        }
        return r
    }

    @Synchronized override fun reiniciarEnviados() {
        for (f in propios.values) if (f.estado == Estado.ENVIADO) f.estado = Estado.PENDIENTE
    }

    @Synchronized override fun marcarLeidoPorPar(id: ByteArray): Boolean {
        val f = propios[hex(id)] ?: return false
        if (f.estado != Estado.LEIDO) { f.estado = Estado.LEIDO; return true }
        return false
    }

    @Synchronized override fun guardarRecibido(m: Mensaje): Boolean {
        if (!recibidosIds.add(m.idTexto)) return false
        recibidosSeq.add(m.seq)
        while (recibidosSeq.contains(ultimoSeq + 1)) ultimoSeq++
        recibidos.add(m)
        return true
    }

    @Synchronized override fun ultimoSeqRecibido(): Long = ultimoSeq

    @Synchronized override fun estado(id: ByteArray): Estado? = propios[hex(id)]?.estado

    @Synchronized fun estados(): List<Estado> = propios.values.sortedBy { it.m.seq }.map { it.estado }

    private fun hex(id: ByteArray) = id.joinToString("") { "%02x".format(it) }
}
