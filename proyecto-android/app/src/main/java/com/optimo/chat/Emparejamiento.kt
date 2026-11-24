package com.optimo.chat

import java.net.InetSocketAddress
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

/**
 * Emparejamiento de dos moviles a partir de una invitacion (logica pura, probada en la JVM).
 *
 * El anfitrion crea la invitacion ([Invitacion]) y la muestra como enlace o QR. El invitado la abre. Se encuentran por
 * la DHT con un identificador derivado del secreto de la invitacion, abren TLS (el invitado fija la huella del enlace; el
 * anfitrion acepta a cualquiera, porque aun no conoce al invitado) y dentro de TLS ejecutan [sesion]:
 *
 *   1. intercambian nombre y nonce;
 *   2. cada uno calcula la prueba HMAC(secreto de la invitacion, huella A, huella B, nonce A, nonce B) con las huellas
 *      REALES vistas en TLS e intercambian las pruebas: si alguien hizo de intermediario o no conoce el secreto, no
 *      coinciden;
 *   3. cada pantalla muestra el codigo de 6 digitos derivado de la prueba y la persona confirma que es el mismo;
 *   4. solo si los dos confirman se crea el contacto, con un secreto nuevo derivado por HKDF.
 *
 * Que ocurre si alguien conoce el enlace: puede conectar y superar la prueba, pero la persona del anfitrion compara el
 * codigo con quien tiene delante; el del atacante no coincidira con el de nadie y el anfitrion lo rechaza.
 */
object Emparejamiento {

    const val T_HELLO = 0x30
    const val T_PRUEBA = 0x31
    const val T_DECISION = 0x32
    const val MAX_NOMBRE = 40
    private const val MAX_BYTES_NOMBRE = MAX_NOMBRE * 4

    sealed class Resultado {
        class Exito(val contacto: Contacto, val codigo: String) : Resultado()
        class Fallo(val motivo: String) : Resultado()
    }

    /** Limpia un nombre recibido: sin caracteres de control, recortado, nunca vacio. */
    fun limpiarNombre(n: String): String {
        val s = n.filter { !it.isISOControl() && it != ' ' && it != ' ' }.trim().take(MAX_NOMBRE).trim()
        return s.ifEmpty { "Contacto" }
    }

    /**
     * Protocolo dentro de TLS. Bloquea hasta terminar. [confirmar] recibe el codigo y el nombre del otro y devuelve si
     * la persona confirma que el codigo coincide. [plazoMs] cubre TODO, incluida la espera a la persona.
     */
    fun sesion(
        canal: Canal,
        anfitrion: Boolean,
        secreto: ByteArray,
        miHuella: ByteArray,
        huellaPar: ByteArray,
        miNombre: String,
        confirmar: (codigo: String, nombrePar: String) -> Boolean,
        plazoMs: Long = 180_000,
        ahoraMs: () -> Long = { System.currentTimeMillis() }
    ): Resultado {
        val terminado = AtomicBoolean(false)
        val vencido = AtomicBoolean(false)
        val vigilante = Thread({
            try {
                Thread.sleep(plazoMs)
                if (!terminado.get()) { vencido.set(true); try { canal.cerrar() } catch (_: Exception) {} }
            } catch (_: InterruptedException) {}
        }, "emparejamiento-vigilante").apply { isDaemon = true; start() }
        try {
            val r = interior(canal, anfitrion, secreto, miHuella, huellaPar, miNombre, confirmar, ahoraMs)
            // Si el plazo vencio, el cierre desde el vigilante puede aparecer como "el otro cerro": se dice lo que paso.
            return if (r is Resultado.Fallo && vencido.get()) Resultado.Fallo("Tiempo agotado") else r
        } catch (e: Exception) {
            return Resultado.Fallo(if (vencido.get()) "Tiempo agotado" else "Error en el emparejamiento: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            terminado.set(true)
            vigilante.interrupt()
        }
    }

    private fun interior(
        canal: Canal, anfitrion: Boolean, secreto: ByteArray, miHuella: ByteArray, huellaPar: ByteArray,
        miNombre: String, confirmar: (String, String) -> Boolean, ahoraMs: () -> Long
    ): Resultado {
        val mio = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nombre = limpiarNombre(miNombre).toByteArray(Charsets.UTF_8)
        Protocolo.escribirTrama(canal, T_HELLO, mio + byteArrayOf(nombre.size.toByte()) + nombre)

        val h = Protocolo.leerTrama(canal) ?: return Resultado.Fallo("El otro lado cerro la conexion")
        if (h.tipo != T_HELLO || h.carga.size < 17) return Resultado.Fallo("Mensaje de emparejamiento no valido")
        val n = h.carga[16].toInt() and 0xFF
        if (n > MAX_BYTES_NOMBRE || h.carga.size != 17 + n) return Resultado.Fallo("Nombre no valido")
        val nombrePar = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(h.carga, 17, n)).toString()
        } catch (_: Exception) { return Resultado.Fallo("Nombre no valido") }
        val nonceP = h.carga.copyOfRange(0, 16)

        val prueba = if (anfitrion) Invitacion.prueba(secreto, miHuella, huellaPar, mio, nonceP)
        else Invitacion.prueba(secreto, huellaPar, miHuella, nonceP, mio)
        Protocolo.escribirTrama(canal, T_PRUEBA, prueba)
        val pp = Protocolo.leerTrama(canal) ?: return Resultado.Fallo("El otro lado cerro la conexion")
        if (pp.tipo != T_PRUEBA || pp.carga.size != 32) return Resultado.Fallo("Prueba no valida")
        if (!Invitacion.iguales(prueba, pp.carga)) {
            return Resultado.Fallo("La prueba no coincide: el enlace no es el mismo o alguien se ha interpuesto")
        }

        val codigo = Invitacion.codigoCorto(prueba)
        val acepto = confirmar(codigo, limpiarNombre(nombrePar))
        Protocolo.escribirTrama(canal, T_DECISION, byteArrayOf(if (acepto) 1 else 0))
        val d = Protocolo.leerTrama(canal) ?: return Resultado.Fallo("El otro lado cerro la conexion")
        if (d.tipo != T_DECISION || d.carga.size != 1) return Resultado.Fallo("Decision no valida")
        if (!acepto) return Resultado.Fallo("No has confirmado el codigo")
        if (d.carga[0].toInt() != 1) return Resultado.Fallo("El otro lado no ha confirmado el codigo")

        val a = if (anfitrion) miHuella else huellaPar
        val b = if (anfitrion) huellaPar else miHuella
        return Resultado.Exito(
            Contacto(limpiarNombre(nombrePar), huellaPar, Invitacion.secretoDeContacto(secreto, a, b), ahoraMs()),
            codigo
        )
    }

    /** Identificador de encuentro del emparejamiento, distinto del de los contactos ya creados. */
    fun secretoEncuentro(secretoInvitacion: ByteArray): ByteArray =
        Invitacion.hkdf(secretoInvitacion, "optimochat-par-rdv-v1".toByteArray(Charsets.UTF_8), ByteArray(0), 32)

    /** Parametros de tiempo, ajustables para pruebas. */
    class Tiempos(
        val rafagaMs: Long = 15_000,
        val pausaMs: Long = 6_000,
        val plazoTlsMs: Long = 20_000,
        val plazoSesionMs: Long = 180_000
    )

    /** Abre transporte fiable y TLS contra [remoto]. Devuelve el TLS ya negociado o lanza. Cierra todo si falla. */
    private fun abrir(dht: Dht, remoto: InetSocketAddress, contexto: SSLContext, servidor: Boolean, plazoTlsMs: Long): TlsCanal {
        val udp = UdpFiable(dht.puerto.canal(remoto))
        try {
            if (!udp.conectar(10_000)) throw java.io.IOException("El transporte no conecto")
            val tls = TlsCanal(udp, contexto, servidor)
            val terminado = AtomicBoolean(false)
            val vigilante = Thread({
                try { Thread.sleep(plazoTlsMs); if (!terminado.get()) tls.cerrar() } catch (_: InterruptedException) {}
            }, "emparejamiento-tls").apply { isDaemon = true; start() }
            try { tls.apretonDeManos() } finally { terminado.set(true); vigilante.interrupt() }
            return tls
        } catch (e: Exception) {
            try { udp.cerrar() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Lado del anfitrion: espera al invitado hasta que caduque la invitacion. Acepta hasta [MAX_INTENTOS] conexiones
     * fallidas (alguien que conoce el enlace pero no supera la prueba, o la persona rechaza el codigo) antes de rendirse.
     */
    class Anfitrion(
        private val dht: Dht,
        private val contexto: SSLContext,     // con las claves propias y SIN fijar huella
        private val miHuella: ByteArray,
        private val invitacion: Invitacion.Datos,
        private val miNombre: String,
        private val confirmar: (String, String) -> Boolean,
        private val alEstado: (String) -> Unit = {},
        private val semillas: List<InetSocketAddress>? = null,
        private val tiempos: Tiempos = Tiempos(),
        private val reloj: () -> Long = { System.currentTimeMillis() }
    ) {
        companion object { const val MAX_INTENTOS = 3 }

        @Volatile private var cancelado = false
        @Volatile private var actual: Canal? = null

        fun cancelar() { cancelado = true; try { actual?.cerrar() } catch (_: Exception) {} }

        fun ejecutar(): Resultado {
            var intentos = 0
            val sec = secretoEncuentro(invitacion.secreto)
            while (!cancelado) {
                val restante = invitacion.caducaS * 1000 - reloj()
                if (restante <= 0) return Resultado.Fallo("La invitacion ha caducado")
                alEstado("Esperando al invitado")
                val r = SondaNat.encuentro(dht, sec, restante, {}, semillas, tiempos.rafagaMs, tiempos.pausaMs) { cancelado }
                if (cancelado) break
                if (!r.exito || r.remoto == null) return Resultado.Fallo("Nadie ha usado la invitacion a tiempo. ${r.texto}")
                alEstado("Alguien se ha conectado; comprobando")
                val tls = try { abrir(dht, r.remoto, contexto, true, tiempos.plazoTlsMs) } catch (e: Exception) {
                    if (cancelado) break
                    if (++intentos >= MAX_INTENTOS) return Resultado.Fallo("Demasiados intentos fallidos")
                    continue
                }
                actual = tls
                try {
                    val hp = tls.huellaPar ?: return Resultado.Fallo("El otro lado no presento clave")
                    when (val s = sesion(tls, true, invitacion.secreto, miHuella, hp, miNombre, confirmar, tiempos.plazoSesionMs)) {
                        is Resultado.Exito -> return s
                        is Resultado.Fallo -> {
                            if (cancelado) break
                            if (++intentos >= MAX_INTENTOS) return s
                            alEstado("Intento fallido: ${s.motivo}")
                        }
                    }
                } finally {
                    try { tls.cerrar() } catch (_: Exception) {}
                    actual = null
                }
            }
            return Resultado.Fallo("Cancelado")
        }
    }

    /** Lado del invitado: una sola oportunidad. [contexto] debe tener fijada la huella del enlace. */
    class Invitado(
        private val dht: Dht,
        private val contexto: SSLContext,     // con las claves propias y la huella de la invitacion fijada
        private val miHuella: ByteArray,
        private val invitacion: Invitacion.Datos,
        private val miNombre: String,
        private val confirmar: (String, String) -> Boolean,
        private val alEstado: (String) -> Unit = {},
        private val semillas: List<InetSocketAddress>? = null,
        private val tiempos: Tiempos = Tiempos(),
        private val reloj: () -> Long = { System.currentTimeMillis() }
    ) {
        @Volatile private var cancelado = false
        @Volatile private var actual: Canal? = null

        fun cancelar() { cancelado = true; try { actual?.cerrar() } catch (_: Exception) {} }

        fun ejecutar(): Resultado {
            val restante = invitacion.caducaS * 1000 - reloj()
            if (restante <= 0) return Resultado.Fallo("La invitacion ha caducado")
            alEstado("Buscando al anfitrion")
            val r = SondaNat.encuentro(dht, secretoEncuentro(invitacion.secreto), restante, {}, semillas,
                tiempos.rafagaMs, tiempos.pausaMs) { cancelado }
            if (cancelado) return Resultado.Fallo("Cancelado")
            if (!r.exito || r.remoto == null) return Resultado.Fallo("No se ha encontrado al anfitrion. ${r.texto}")
            alEstado("Anfitrion encontrado; comprobando su identidad")
            val tls = try { abrir(dht, r.remoto, contexto, false, tiempos.plazoTlsMs) } catch (e: Exception) {
                return Resultado.Fallo(
                    if (cancelado) "Cancelado"
                    else "La conexion no es con quien creo la invitacion (clave distinta o sin respuesta)"
                )
            }
            actual = tls
            try {
                val hp = tls.huellaPar ?: return Resultado.Fallo("El otro lado no presento clave")
                if (!Invitacion.iguales(hp, invitacion.huella)) return Resultado.Fallo("La clave del anfitrion no coincide con el enlace")
                return sesion(tls, false, invitacion.secreto, miHuella, hp, miNombre, confirmar, tiempos.plazoSesionMs)
            } finally {
                try { tls.cerrar() } catch (_: Exception) {}
                actual = null
            }
        }
    }
}
