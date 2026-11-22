package com.optimo.chat

import java.io.ByteArrayOutputStream

/**
 * Bencode (formato de la DHT de BitTorrent). Logica pura, sin APIs de Android: se puede probar en la JVM.
 * Tipos: Long (entero), ByteArray (cadena de bytes), List<Any?>, Map<String, Any?> (claves en ISO-8859-1,
 * que conserva el orden de bytes exigido por el formato).
 */
object Bencode {

    private val LATIN1 = Charsets.ISO_8859_1

    fun codificar(v: Any?): ByteArray {
        val o = ByteArrayOutputStream()
        escribir(o, v)
        return o.toByteArray()
    }

    private fun escribir(o: ByteArrayOutputStream, v: Any?) {
        when (v) {
            is Int -> o.write("i${v}e".toByteArray(LATIN1))
            is Long -> o.write("i${v}e".toByteArray(LATIN1))
            is ByteArray -> {
                o.write("${v.size}:".toByteArray(LATIN1))
                o.write(v)
            }
            is String -> escribir(o, v.toByteArray(LATIN1))
            is List<*> -> {
                o.write('l'.code)
                for (x in v) escribir(o, x)
                o.write('e'.code)
            }
            is Map<*, *> -> {
                o.write('d'.code)
                for (k in v.keys.map { it as String }.sorted()) {
                    escribir(o, k)
                    escribir(o, v[k])
                }
                o.write('e'.code)
            }
            else -> throw IllegalArgumentException("Tipo no soportado: ${v?.javaClass}")
        }
    }

    /** Decodifica un valor completo. Lanza IllegalArgumentException si los datos no son bencode valido. */
    fun decodificar(datos: ByteArray): Any? {
        val l = Lector(datos)
        val v = l.valor(0)
        if (l.pos != datos.size) throw IllegalArgumentException("Datos sobrantes tras el valor")
        return v
    }

    private class Lector(val d: ByteArray) {
        var pos = 0

        fun valor(prof: Int): Any? {
            if (prof > 16) throw IllegalArgumentException("Anidamiento excesivo")
            if (pos >= d.size) throw IllegalArgumentException("Fin inesperado")
            return when (val c = d[pos].toInt().toChar()) {
                'i' -> entero()
                'l' -> {
                    pos++
                    val lista = ArrayList<Any?>()
                    while (peek() != 'e') lista.add(valor(prof + 1))
                    pos++
                    lista
                }
                'd' -> {
                    pos++
                    val mapa = LinkedHashMap<String, Any?>()
                    while (peek() != 'e') {
                        val k = cadena()
                        mapa[String(k, LATIN1)] = valor(prof + 1)
                    }
                    pos++
                    mapa
                }
                in '0'..'9' -> cadena()
                else -> throw IllegalArgumentException("Byte inesperado '$c' en $pos")
            }
        }

        private fun peek(): Char {
            if (pos >= d.size) throw IllegalArgumentException("Fin inesperado")
            return d[pos].toInt().toChar()
        }

        private fun entero(): Long {
            pos++ // 'i'
            val ini = pos
            while (peek() != 'e') pos++
            val txt = String(d, ini, pos - ini, LATIN1)
            pos++ // 'e'
            return txt.toLongOrNull() ?: throw IllegalArgumentException("Entero no valido")
        }

        private fun cadena(): ByteArray {
            val ini = pos
            while (peek() != ':') pos++
            val n = String(d, ini, pos - ini, LATIN1).toIntOrNull()
                ?: throw IllegalArgumentException("Longitud no valida")
            pos++ // ':'
            if (n < 0 || pos + n > d.size) throw IllegalArgumentException("Cadena fuera de rango")
            val r = d.copyOfRange(pos, pos + n)
            pos += n
            return r
        }
    }
}

/** Utilidades para leer los resultados de decodificar sin repetir conversiones. */
@Suppress("UNCHECKED_CAST")
fun Any?.comoMapa(): Map<String, Any?>? = this as? Map<String, Any?>

fun Any?.comoBytes(): ByteArray? = this as? ByteArray
