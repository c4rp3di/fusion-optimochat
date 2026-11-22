package com.optimo.chat

import java.net.InetSocketAddress

/** Utilidades para que el informe de la fase 0 no contenga IPs completas ni datos personales. */
object Informe {

    /** 83.45.12.34:5000 pasa a 83.45.*.*:5000 (IPv6: se conservan solo los dos primeros grupos). */
    fun enmascarar(a: InetSocketAddress?): String {
        if (a == null) return "-"
        val ip = a.address?.hostAddress ?: return "?"
        val corto = if (ip.contains(':')) {
            ip.split(':').take(2).joinToString(":") + ":*"
        } else {
            val p = ip.split('.')
            if (p.size == 4) "${p[0]}.${p[1]}.*.*" else "?"
        }
        return "$corto:${a.port}"
    }
}
