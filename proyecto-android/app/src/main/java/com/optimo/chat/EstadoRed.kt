package com.optimo.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Estado de la red por defecto: tipo (Wi-Fi, datos, VPN), si hay IPv4 e IPv6 global, y aviso de cambios.
 * El aviso se usa para reconectar al pasar de Wi-Fi a datos o al reves (prueba P2 de la fase 0).
 */
class EstadoRed(contexto: Context) {

    data class Estado(
        val tipo: String,
        val vpn: Boolean,
        val ipv4: Boolean,
        val ipv6Global: Boolean
    ) {
        override fun toString(): String =
            "red=$tipo vpn=${if (vpn) "si" else "no"} ipv4=${if (ipv4) "si" else "no"} ipv6global=${if (ipv6Global) "si" else "no"}"
    }

    private val cm = contexto.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile var actual: Estado = leer(cm.activeNetwork)
        private set

    private fun leer(red: Network?): Estado {
        if (red == null) return Estado("sin red", false, false, false)
        val caps = cm.getNetworkCapabilities(red)
        val props: LinkProperties? = cm.getLinkProperties(red)
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val tipo = when {
            caps == null -> "desconocida"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "otra"
        }
        var v4 = false
        var v6 = false
        props?.linkAddresses?.forEach { la ->
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress) v4 = true
            if (a is Inet6Address && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isSiteLocalAddress) {
                // Se excluyen tambien las direcciones locales unicas fc00::/7.
                val primero = a.address[0].toInt() and 0xFE
                if (primero != 0xFC) v6 = true
            }
        }
        return Estado(tipo, vpn, v4, v6)
    }

    /** Empieza a avisar de cambios. [alCambiar] se llama en un hilo del sistema, no en el principal. */
    fun escuchar(alCambiar: (Estado) -> Unit) {
        parar()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notificar(network)
            override fun onLost(network: Network) { actual = leer(cm.activeNetwork); alCambiar(actual) }
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) = notificar(network)
            override fun onLinkPropertiesChanged(network: Network, p: LinkProperties) = notificar(network)
            private fun notificar(network: Network) {
                actual = leer(network)
                alCambiar(actual)
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    fun parar() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
    }
}
