package com.optimo.chat

/**
 * Generador de codigos QR (logica pura, sin dependencias). Modo byte (UTF-8), correccion de errores M (~15 %),
 * versiones 1 a 20 (hasta 666 bytes). La app solo GENERA el QR de la invitacion; leerlo lo hace la camara del sistema.
 *
 * [generar] devuelve la matriz de modulos (true = oscuro) sin zona de silencio: al pintarla hay que dejar 4 modulos
 * claros alrededor y usar una escala entera.
 */
object QrCodigo {

    const val MAX_VERSION = 20

    // Nivel M: bytes de correccion por bloque y numero de bloques, por version (1..20).
    private val EC_M = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26)
    private val BLOQUES_M = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16)

    private fun codewordsBrutos(v: Int): Int {
        var r = (16 * v + 128) * v + 64
        if (v >= 2) {
            val n = v / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (v >= 7) r -= 36
        }
        return r / 8
    }

    private fun codewordsDatos(v: Int) = codewordsBrutos(v) - EC_M[v - 1] * BLOQUES_M[v - 1]

    /** Cuantos bytes UTF-8 caben como maximo. */
    fun capacidadBytes(): Int = bytesCaben(MAX_VERSION)

    private fun bytesCaben(v: Int): Int {
        val bitsCuenta = if (v >= 10) 16 else 8
        return (codewordsDatos(v) * 8 - 4 - bitsCuenta) / 8
    }

    fun generar(texto: String): Array<BooleanArray> {
        val datos = texto.toByteArray(Charsets.UTF_8)
        var version = 1
        while (version <= MAX_VERSION && datos.size > bytesCaben(version)) version++
        require(version <= MAX_VERSION) { "Texto demasiado largo para el QR (${datos.size} bytes)" }

        // Flujo de bits: modo byte (0100), cuenta, datos, terminador y relleno.
        val bits = ArrayList<Int>()
        fun poner(valor: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((valor ushr i) and 1) }
        poner(4, 4)
        poner(datos.size, if (version >= 10) 16 else 8)
        for (b in datos) poner(b.toInt() and 0xFF, 8)
        val capacidadBits = codewordsDatos(version) * 8
        poner(0, minOf(4, capacidadBits - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        val cw = ArrayList<Int>()
        for (i in 0 until bits.size step 8) {
            var x = 0
            for (j in 0 until 8) x = (x shl 1) or bits[i + j]
            cw.add(x)
        }
        var pad = 0xEC
        while (cw.size < codewordsDatos(version)) { cw.add(pad); pad = pad xor (0xEC xor 0x11) }

        val finales = intercalar(cw.map { it.toByte() }.toByteArray(), version)
        return dibujar(finales, version)
    }

    // ---- Reed-Solomon ----

    private fun mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun divisor(grado: Int): IntArray {
        val r = IntArray(grado); r[grado - 1] = 1
        var raiz = 1
        for (i in 0 until grado) {
            for (j in 0 until grado) {
                r[j] = mul(r[j], raiz)
                if (j + 1 < grado) r[j] = r[j] xor r[j + 1]
            }
            raiz = mul(raiz, 2)
        }
        return r
    }

    private fun resto(datos: ByteArray, div: IntArray): IntArray {
        val r = IntArray(div.size)
        for (b in datos) {
            val f = (b.toInt() and 0xFF) xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor mul(div[i], f)
        }
        return r
    }

    private fun intercalar(datos: ByteArray, v: Int): ByteArray {
        val nBloques = BLOQUES_M[v - 1]
        val ecLen = EC_M[v - 1]
        val brutos = codewordsBrutos(v)
        val nCortos = nBloques - brutos % nBloques
        val largoCorto = brutos / nBloques
        val div = divisor(ecLen)
        val bloques = ArrayList<ByteArray>()
        val ecs = ArrayList<IntArray>()
        var k = 0
        for (i in 0 until nBloques) {
            val n = largoCorto - ecLen + (if (i < nCortos) 0 else 1)
            val d = datos.copyOfRange(k, k + n)
            k += n
            bloques.add(d)
            ecs.add(resto(d, div))
        }
        val salida = ArrayList<Byte>()
        for (i in 0..largoCorto - ecLen) {
            for (j in 0 until nBloques) {
                if (i < bloques[j].size) salida.add(bloques[j][i])
            }
        }
        for (i in 0 until ecLen) for (j in 0 until nBloques) salida.add(ecs[j][i].toByte())
        check(salida.size == brutos) { "Error interno del QR" }
        return salida.toByteArray()
    }

    // ---- Matriz ----

    private class Matriz(val v: Int) {
        val n = v * 4 + 17
        val m = Array(n) { BooleanArray(n) }
        val fn = Array(n) { BooleanArray(n) }

        fun poner(x: Int, y: Int, oscuro: Boolean) { m[y][x] = oscuro; fn[y][x] = true }

        fun patrones() {
            for (i in 0 until n) { poner(6, i, i % 2 == 0); poner(i, 6, i % 2 == 0) }
            buscador(3, 3); buscador(n - 4, 3); buscador(3, n - 4)
            val pos = posicionesAlineacion()
            for (i in pos.indices) for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
                alineacion(pos[i], pos[j])
            }
            formato(0)
            version()
        }

        fun posicionesAlineacion(): List<Int> {
            if (v == 1) return emptyList()
            val k = v / 7 + 2
            val paso = (v * 4 + k * 2 + 1) / (k * 2 - 2) * 2
            val r = ArrayList<Int>(); r.add(6)
            var p = n - 7
            repeat(k - 1) { r.add(1, p); p -= paso }
            return r
        }

        fun buscador(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                val x = cx + dx; val y = cy + dy
                if (x in 0 until n && y in 0 until n) poner(x, y, d != 2 && d != 4)
            }
        }

        fun alineacion(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) poner(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        fun formato(mascara: Int) {
            val dato = mascara // nivel M = 00
            var r = dato
            repeat(10) { r = (r shl 1) xor ((r ushr 9) * 0x537) }
            val bits = ((dato shl 10) or r) xor 0x5412
            fun b(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) poner(8, i, b(i))
            poner(8, 7, b(6)); poner(8, 8, b(7)); poner(7, 8, b(8))
            for (i in 9..14) poner(14 - i, 8, b(i))
            for (i in 0..7) poner(n - 1 - i, 8, b(i))
            for (i in 8..14) poner(8, n - 15 + i, b(i))
            poner(8, n - 8, true)
        }

        fun version() {
            if (v < 7) return
            var r = v
            repeat(12) { r = (r shl 1) xor ((r ushr 11) * 0x1F25) }
            val bits = (v shl 12) or r
            for (i in 0 until 18) {
                val b = ((bits ushr i) and 1) != 0
                val a = n - 11 + i % 3
                val c = i / 3
                poner(a, c, b); poner(c, a, b)
            }
        }

        fun codewords(datos: ByteArray) {
            var i = 0
            var derecha = n - 1
            while (derecha >= 1) {
                if (derecha == 6) derecha = 5
                for (vert in 0 until n) for (j in 0..1) {
                    val x = derecha - j
                    val arriba = ((derecha + 1) and 2) == 0
                    val y = if (arriba) n - 1 - vert else vert
                    if (!fn[y][x] && i < datos.size * 8) {
                        m[y][x] = ((datos[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                derecha -= 2
            }
        }

        fun mascara(k: Int) {
            for (y in 0 until n) for (x in 0 until n) {
                val inv = when (k) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (!fn[y][x] && inv) m[y][x] = !m[y][x]
            }
        }

        fun penalizacion(): Int {
            var p = 0
            for (pasada in 0..1) {
                for (a in 0 until n) {
                    var racha = 1
                    for (b in 1 until n) {
                        val x = if (pasada == 0) m[a][b] else m[b][a]
                        val y = if (pasada == 0) m[a][b - 1] else m[b - 1][a]
                        if (x == y) { racha++; if (racha == 5) p += 3 else if (racha > 5) p += 1 } else racha = 1
                    }
                }
            }
            for (y in 0 until n - 1) for (x in 0 until n - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
            }
            val pat = booleanArrayOf(true, false, true, true, true, false, true)
            for (pasada in 0..1) for (a in 0 until n) for (b in 0..n - 7) {
                var ok = true
                for (k in 0..6) {
                    val c = if (pasada == 0) m[a][b + k] else m[b + k][a]
                    if (c != pat[k]) { ok = false; break }
                }
                if (ok) p += 40
            }
            var oscuros = 0
            for (y in 0 until n) for (x in 0 until n) if (m[y][x]) oscuros++
            val total = n * n
            p += (((Math.abs(oscuros * 20 - total * 10) + total - 1) / total) - 1) * 10
            return p
        }
    }

    private fun dibujar(datos: ByteArray, v: Int): Array<BooleanArray> {
        val q = Matriz(v)
        q.patrones()
        q.codewords(datos)
        var mejor = 0
        var mejorP = Int.MAX_VALUE
        for (k in 0..7) {
            q.mascara(k); q.formato(k)
            val p = q.penalizacion()
            if (p < mejorP) { mejorP = p; mejor = k }
            q.mascara(k)
        }
        q.mascara(mejor); q.formato(mejor)
        return q.m
    }
}
