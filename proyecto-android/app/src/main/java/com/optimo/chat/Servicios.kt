package com.optimo.chat

import android.content.Context
import android.os.Build
import java.io.File

/** Acceso comun a los datos de la app (Android, sin compilar). */
object Servicios {
    @Volatile private var contactos: Contactos? = null

    /** Lista de contactos cifrada con una clave AES de Android Keystore. */
    fun contactos(ctx: Context): Contactos {
        contactos?.let { return it }
        synchronized(this) {
            contactos?.let { return it }
            val c = ContactosArchivo(File(ctx.applicationContext.filesDir, "contactos.bin"), SelladorAesGcm(ClaveAlmacen.obtener()))
            contactos = c
            return c
        }
    }

    /** El archivo existe pero no se puede abrir (clave perdida o alterado). */
    fun contactosIlegibles(ctx: Context): Boolean {
        val c = contactos(ctx)
        c.listar()
        return (c as? ContactosArchivo)?.corrupto == true
    }

    fun nombre(ctx: Context): String =
        ctx.getSharedPreferences("perfil", Context.MODE_PRIVATE).getString("nombre", null)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "Mi movil" }

    fun guardarNombre(ctx: Context, nombre: String) {
        ctx.getSharedPreferences("perfil", Context.MODE_PRIVATE).edit().putString("nombre", nombre.trim().take(Emparejamiento.MAX_NOMBRE)).apply()
    }
}
