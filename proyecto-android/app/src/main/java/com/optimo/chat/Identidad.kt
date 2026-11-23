package com.optimo.chat

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Identidad criptografica del dispositivo: un par EC P-256 creado en el Android Keystore.
 * La clave privada no sale nunca del Keystore. El Keystore genera tambien el certificado autofirmado
 * que usa TLS. La huella es el SHA-256 de la clave publica (SubjectPublicKeyInfo).
 *
 * Fase 0: comprobar que esta clave firma en TLS (ver TlsFijado) en Android 8-9 (TLS 1.2) y 10+ (TLS 1.3).
 * Sin dependencias: solo APIs del sistema.
 */
object Identidad {

    const val ALIAS = "optimochat_id_v1"
    private const val ALMACEN = "AndroidKeyStore"

    private fun almacen(): KeyStore = KeyStore.getInstance(ALMACEN).apply { load(null) }

    /** Crea la identidad si no existe. Devuelve true si se ha creado ahora. */
    fun asegurar(): Boolean {
        if (almacen().containsAlias(ALIAS)) return false
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                crear(strongBox = true)
                return true
            } catch (e: Exception) {
                // StrongBox no disponible en este movil: se crea en el Keystore normal.
                try { almacen().deleteEntry(ALIAS) } catch (_: Exception) {}
            }
        }
        crear(strongBox = false)
        return true
    }

    private fun crear(strongBox: Boolean) {
        val ahora = System.currentTimeMillis()
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS necesita firmar con distintos resumenes segun la version del protocolo.
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .setCertificateSubject(X500Principal("CN=OptimoChat"))
            .setCertificateSerialNumber(BigInteger.valueOf(ahora))
            .setCertificateNotBefore(Date(ahora - 24L * 3600 * 1000))
            .setCertificateNotAfter(Date(ahora + 20L * 365 * 24 * 3600 * 1000))
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ALMACEN)
        gen.initialize(b.build())
        gen.generateKeyPair()
    }

    fun certificado(): X509Certificate {
        asegurar()
        return almacen().getCertificate(ALIAS) as X509Certificate
    }

    fun clavePrivada(): PrivateKey {
        asegurar()
        return almacen().getKey(ALIAS, null) as PrivateKey
    }

    /** SHA-256 de la clave publica del certificado dado. */
    fun huellaDe(cert: X509Certificate): ByteArray = Confianza.huellaDe(cert)

    fun huella(): ByteArray = huellaDe(certificado())

    /** Hexadecimal agrupado en bloques de 4 caracteres, para mostrar y comparar a ojo. */
    fun huellaTexto(h: ByteArray): String =
        h.joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")

    /** Inversa de huellaTexto: ignora espacios y mayusculas. Devuelve null si no son 32 bytes hex. */
    fun huellaDesdeTexto(t: String): ByteArray? {
        val limpio = t.filter { !it.isWhitespace() }.lowercase()
        if (limpio.length != 64 || !limpio.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(32) { i -> limpio.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun borrar() {
        try { almacen().deleteEntry(ALIAS) } catch (_: Exception) {}
    }
}
