package com.optimo.chat

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Confianza por huella fijada (logica sin APIs de Android, probable en la JVM).
 *
 * El TrustManager no valida cadena ni nombre de host: acepta SOLO el certificado cuya clave publica tiene el
 * SHA-256 fijado. Si [fijada] es null acepta cualquiera y avisa de su huella (solo para pruebas y para el
 * primer emparejamiento, que se verifica aparte con el codigo corto).
 */
object Confianza {

    /** SHA-256 de la clave publica (SubjectPublicKeyInfo) del certificado. */
    fun huellaDe(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    fun contexto(
        claves: Array<KeyManager>?,
        fijada: ByteArray?,
        alVerPar: (ByteArray) -> Unit = {}
    ): SSLContext {
        val tm = object : X509TrustManager {
            private fun comprobar(cadena: Array<X509Certificate>) {
                if (cadena.isEmpty()) throw CertificateException("Sin certificado")
                val h = huellaDe(cadena[0])
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
        ctx.init(claves, arrayOf(tm), SecureRandom())
        return ctx
    }
}
