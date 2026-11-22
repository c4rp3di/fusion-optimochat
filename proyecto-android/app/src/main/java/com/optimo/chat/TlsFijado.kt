package com.optimo.chat

import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Prueba P1 de la fase 0: TLS mutuo con la clave del Keystore y confianza fijada por huella.
 *
 * - Cada extremo presenta el certificado autofirmado de su identidad.
 * - El TrustManager no valida cadena ni nombre de host: acepta SOLO el certificado cuya clave publica tiene el
 *   SHA-256 fijado. Si no hay huella fijada (prueba en el servidor), acepta y registra la del otro.
 * - En la fase 2 esto pasa de SSLSocket a SSLEngine para correr sobre el canal UDP fiable.
 */
object TlsFijado {

    const val PUERTO_PRUEBA = 47474

    private fun contexto(fijada: ByteArray?, alVerPar: (ByteArray) -> Unit): SSLContext {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        Identidad.asegurar()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)

        val tm = object : X509TrustManager {
            private fun comprobar(cadena: Array<X509Certificate>) {
                if (cadena.isEmpty()) throw CertificateException("Sin certificado")
                val h = Identidad.huellaDe(cadena[0])
                alVerPar(h)
                if (fijada != null && !MessageDigest.isEqual(h, fijada)) {
                    throw CertificateException("Huella distinta de la fijada")
                }
            }

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = comprobar(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = comprobar(chain)
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(tm), SecureRandom())
        return ctx
    }

    /** Espera una conexion (60 s) y responde "pong". [fijada] null = aceptar a cualquiera y registrar su huella. */
    fun servidor(fijada: ByteArray?, log: (String) -> Unit): String {
        val ctx = contexto(fijada) { log("  huella del cliente: ${Identidad.huellaTexto(it)}") }
        val ss = ctx.serverSocketFactory.createServerSocket(PUERTO_PRUEBA) as SSLServerSocket
        return ss.use {
            it.needClientAuth = true
            it.soTimeout = 60_000
            log("Servidor TLS a la espera en el puerto $PUERTO_PRUEBA (60 s)")
            try {
                val s = it.accept() as SSLSocket
                s.use { sock ->
                    sock.startHandshake()
                    val linea = sock.inputStream.bufferedReader().readLine()
                    sock.outputStream.write("pong\n".toByteArray())
                    sock.outputStream.flush()
                    "TLS servidor: OK (${sock.session.protocol}, ${sock.session.cipherSuite}), recibido '$linea'"
                }
            } catch (e: SSLException) {
                "TLS servidor: RECHAZADO (${e.javaClass.simpleName}: ${e.message})"
            } catch (e: java.net.SocketTimeoutException) {
                "TLS servidor: nadie se conecto en 60 s"
            }
        }
    }

    /** Conecta al servidor de prueba y exige que su huella sea [fijada]. */
    fun cliente(ip: String, fijada: ByteArray, log: (String) -> Unit): String {
        val ctx = contexto(fijada) { log("  huella del servidor: ${Identidad.huellaTexto(it)}") }
        return try {
            val s = ctx.socketFactory.createSocket() as SSLSocket
            s.use { sock ->
                sock.connect(InetSocketAddress(ip, PUERTO_PRUEBA), 8_000)
                sock.soTimeout = 8_000
                sock.startHandshake()
                sock.outputStream.write("ping\n".toByteArray())
                sock.outputStream.flush()
                val linea = sock.inputStream.bufferedReader().readLine()
                "TLS cliente: OK (${sock.session.protocol}, ${sock.session.cipherSuite}), recibido '$linea'"
            }
        } catch (e: SSLException) {
            "TLS cliente: RECHAZADO (${e.javaClass.simpleName}: ${e.message}). Esperado si la huella no coincide"
        } catch (e: Exception) {
            "TLS cliente: ERROR de red (${e.javaClass.simpleName}: ${e.message})"
        }
    }
}
