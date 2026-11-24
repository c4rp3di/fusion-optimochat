package com.optimo.chat

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

/**
 * Prueba de conexion con un contacto ya emparejado, sin escribir nada a mano (logica pura, probada en la JVM).
 * Es la P5 de la fase 0 con los datos del contacto: encuentro por DHT con su secreto, punching, UDP fiable, TLS con su
 * huella fijada y un eco para medir la latencia. Los DOS moviles la ejecutan a la vez (en menos de un par de minutos).
 */
object PruebaContacto {

    class Resultado(
        val exito: Boolean,
        val remoto: InetSocketAddress?,
        val msEncuentro: Long,
        val msTransporte: Long,
        val msTls: Long,
        val msEco: Long,
        val protocolo: String?,
        val motivo: String?
    )

    class Parametros(
        val maxEncuentroMs: Long = 120_000,
        val rafagaMs: Long = 15_000,
        val pausaMs: Long = 6_000,
        val plazoTlsMs: Long = 20_000,
        val plazoEcoMs: Long = 10_000
    )

    private fun fallo(motivo: String, remoto: InetSocketAddress? = null, enc: Long = 0, tr: Long = 0, tls: Long = 0) =
        Resultado(false, remoto, enc, tr, tls, -1, null, motivo)

    fun ejecutar(
        dht: Dht,
        contacto: Contacto,
        contexto: SSLContext,     // con las claves propias y la huella del contacto fijada
        miHuella: ByteArray,
        p: Parametros = Parametros(),
        semillas: List<InetSocketAddress>? = null,
        cancelar: () -> Boolean = { false },
        alEstado: (String) -> Unit = {}
    ): Resultado {
        val t0 = System.currentTimeMillis()
        alEstado("Buscando a ${contacto.nombre} (el otro móvil debe pulsar también «Probar conexión»)")
        val enc = SondaNat.encuentro(dht, contacto.secreto, p.maxEncuentroMs, {}, semillas, p.rafagaMs, p.pausaMs, cancelar = cancelar)
        val msEnc = System.currentTimeMillis() - t0
        if (cancelar()) return fallo("Cancelado", null, msEnc)
        if (!enc.exito || enc.remoto == null) return fallo("No se ha encontrado al contacto (DHT o punching). ${enc.texto}", null, msEnc)
        val remoto = enc.remoto

        alEstado("Encontrado; abriendo el transporte")
        val t1 = System.currentTimeMillis()
        val udp = UdpFiable(dht.puerto.canal(remoto))
        try {
            if (!udp.conectar(10_000)) return fallo("El transporte UDP no conectó", remoto, msEnc)
            val msTr = System.currentTimeMillis() - t1

            alEstado("Negociando TLS")
            val t2 = System.currentTimeMillis()
            val tls = TlsCanal(udp, contexto, servidor = GestorConexion.menor(miHuella, contacto.huella))
            val terminado = AtomicBoolean(false)
            val vigilante = Thread({
                try { Thread.sleep(p.plazoTlsMs); if (!terminado.get()) tls.cerrar() } catch (_: InterruptedException) {}
            }, "prueba-tls").apply { isDaemon = true; start() }
            try { tls.apretonDeManos() } catch (e: Exception) {
                return fallo("TLS rechazado o sin respuesta (${e.javaClass.simpleName})", remoto, msEnc, msTr)
            } finally { terminado.set(true); vigilante.interrupt() }
            val msTls = System.currentTimeMillis() - t2
            val hp = tls.huellaPar
            if (hp == null || !Invitacion.iguales(hp, contacto.huella)) {
                return fallo("La clave del otro no es la del contacto", remoto, msEnc, msTr, msTls)
            }

            alEstado("Midiendo la latencia")
            val eco = eco(tls, p.plazoEcoMs)
            try { tls.cerrar() } catch (_: Exception) {}
            if (eco < 0) return fallo("TLS abierto pero el eco no volvió", remoto, msEnc, msTr, msTls)
            return Resultado(true, remoto, msEnc, msTr, msTls, eco, tls.protocolo, null)
        } finally {
            try { udp.cerrar() } catch (_: Exception) {}
        }
    }

    /**
     * Cada lado pregunta (flag 0 + nonce) y contesta a la pregunta del otro (flag 1 + el mismo nonce). Termina cuando
     * ha recibido SU respuesta y ha contestado la del otro. Devuelve el ida y vuelta en ms, o -1.
     */
    private fun eco(c: Canal, plazoMs: Long): Long {
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val terminado = AtomicBoolean(false)
        val vigilante = Thread({
            try { Thread.sleep(plazoMs); if (!terminado.get()) c.cerrar() } catch (_: InterruptedException) {}
        }, "prueba-eco").apply { isDaemon = true; start() }
        try {
            val ini = System.nanoTime()
            Protocolo.escribirTrama(c, Protocolo.T_PING, byteArrayOf(0) + nonce)
            var recibida = false
            var contestada = false
            var rtt = -1L
            while (!(recibida && contestada)) {
                val t = Protocolo.leerTrama(c) ?: return -1
                if (t.tipo != Protocolo.T_PING || t.carga.size != 9) return -1
                val n = t.carga.copyOfRange(1, 9)
                if (t.carga[0].toInt() == 0) {
                    Protocolo.escribirTrama(c, Protocolo.T_PING, byteArrayOf(1) + n); contestada = true
                } else {
                    if (!Invitacion.iguales(n, nonce)) return -1
                    rtt = (System.nanoTime() - ini) / 1_000_000; recibida = true
                }
            }
            return rtt
        } catch (_: Exception) {
            return -1
        } finally {
            terminado.set(true); vigilante.interrupt()
        }
    }

    /** Una linea de informe, sin datos personales: IP enmascarada, id corto del contacto y tipo de red. */
    fun linea(hora: String, red: String, contactoId: String, r: Resultado): String {
        val base = "$hora | $red | contacto ${contactoId.take(4)}"
        return if (r.exito) {
            "$base | OK con ${Informe.enmascarar(r.remoto)} | encuentro ${r.msEncuentro} ms | UDP ${r.msTransporte} ms | " +
                "TLS ${r.msTls} ms (${r.protocolo}) | eco ${r.msEco} ms"
        } else {
            "$base | FALLO: ${r.motivo} | encuentro ${r.msEncuentro} ms" +
                (if (r.remoto != null) " | con ${Informe.enmascarar(r.remoto)}" else "")
        }
    }
}
