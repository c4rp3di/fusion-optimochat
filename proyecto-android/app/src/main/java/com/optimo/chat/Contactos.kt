package com.optimo.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Un contacto emparejado: su nombre, la huella de su clave y el secreto compartido para encontrarse (logica pura). */
class Contacto(val nombre: String, val huella: ByteArray, val secreto: ByteArray, val creadoMs: Long) {
    init {
        require(huella.size == 32) { "huella de 32 bytes" }
        require(secreto.size == 32) { "secreto de 32 bytes" }
    }

    /** Identificador estable: 8 primeros bytes de la huella en hex. Sirve de nombre de archivo de su conversacion. */
    val id: String get() = huella.copyOf(8).joinToString("") { "%02x".format(it) }

    fun conNombre(nuevo: String) = Contacto(nuevo, huella, secreto, creadoMs)
}

interface Contactos {
    fun listar(): List<Contacto>
    fun obtener(id: String): Contacto?

    /** Anade o, si ya existia uno con la misma huella, lo sustituye (re-emparejar renueva el secreto). */
    fun guardar(c: Contacto)

    fun borrar(id: String): Boolean
}

class ContactosMemoria : Contactos {
    private val m = LinkedHashMap<String, Contacto>()
    @Synchronized override fun listar() = m.values.toList()
    @Synchronized override fun obtener(id: String) = m[id]
    @Synchronized override fun guardar(c: Contacto) { m[c.id] = c }
    @Synchronized override fun borrar(id: String) = m.remove(id) != null
}

/**
 * Lista de contactos en UN archivo cifrado con [Sellador] (AES-256-GCM, el nombre del archivo va como dato autenticado).
 * Escritura atomica: se escribe un temporal y se renombra, asi un corte de luz no deja el archivo a medias.
 * Si el archivo existe pero no se puede abrir (clave perdida, alterado), [corrupto] es true y NO se sobrescribe:
 * guardar o borrar lanzan IllegalStateException para no destruir datos recuperables.
 */
class ContactosArchivo(private val archivo: File, private val sellador: Sellador) : Contactos {

    private val contexto = "optimochat-contactos-v1".toByteArray(Charsets.UTF_8)
    private var datos: LinkedHashMap<String, Contacto>? = null

    @Volatile var corrupto = false; private set

    @Synchronized private fun cargar(): LinkedHashMap<String, Contacto> {
        datos?.let { return it }
        val r = LinkedHashMap<String, Contacto>()
        if (archivo.exists()) {
            val claro = sellador.abrir(contexto, archivo.readBytes())
            if (claro == null) corrupto = true else try {
                val d = DataInputStream(ByteArrayInputStream(claro))
                if (d.readUnsignedByte() != 1) throw IllegalStateException("version")
                repeat(d.readInt().also { require(it in 0..100_000) }) {
                    val nombre = d.readUTF()
                    val h = ByteArray(32).also { d.readFully(it) }
                    val s = ByteArray(32).also { d.readFully(it) }
                    val c = Contacto(nombre, h, s, d.readLong())
                    r[c.id] = c
                }
            } catch (_: Exception) { corrupto = true; r.clear() }
        }
        datos = r
        return r
    }

    private fun escribir(m: Map<String, Contacto>) {
        check(!corrupto) { "El archivo de contactos no se puede leer; no se sobrescribe" }
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        d.writeByte(1); d.writeInt(m.size)
        for (c in m.values) { d.writeUTF(c.nombre); d.write(c.huella); d.write(c.secreto); d.writeLong(c.creadoMs) }
        val sellado = sellador.sellar(contexto, o.toByteArray())
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.writeBytes(sellado)
        Files.move(tmp.toPath(), archivo.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized override fun listar(): List<Contacto> = cargar().values.toList()
    @Synchronized override fun obtener(id: String): Contacto? = cargar()[id]

    @Synchronized override fun guardar(c: Contacto) {
        val m = cargar()
        check(!corrupto) { "El archivo de contactos no se puede leer; no se sobrescribe" }
        val copia = LinkedHashMap(m); copia[c.id] = c
        escribir(copia); datos = copia
    }

    @Synchronized override fun borrar(id: String): Boolean {
        val m = cargar()
        check(!corrupto) { "El archivo de contactos no se puede leer; no se sobrescribe" }
        if (!m.containsKey(id)) return false
        val copia = LinkedHashMap(m); copia.remove(id)
        escribir(copia); datos = copia
        return true
    }
}
