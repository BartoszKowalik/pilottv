package pl.pilottv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

object NetUtils {

    /**
     * Adres IPv4 telefonu w sieci lokalnej (Wi-Fi albo Ethernet, bez VPN).
     * Null, gdy telefon nie ma takiej sieci. Aktywna sieć może być komórkowa, więc jej nie bierzemy.
     */
    @Suppress("DEPRECATION")
    fun localIpv4(context: Context): LinkAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val props = cm.getLinkProperties(network) ?: continue
            val address = props.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            if (address != null) return address
        }
        return null
    }

    /** Pierwsze trzy oktety adresu, np. "192.168.1". Skanujemy zawsze /24. */
    fun subnetPrefix(context: Context): String? {
        val host = localIpv4(context)?.address?.hostAddress ?: return null
        return host.split(".").take(3).joinToString(".")
    }

    /**
     * Sprawdza wszystkie adresy w podsieci /24 telefonu: otwarty [port], a potem [probe].
     * Wynik [probe] = null oznacza „to nie jest szukane urządzenie”.
     */
    suspend fun <T : Any> scanSubnet(context: Context, port: Int, probe: suspend (String) -> T?): List<T> =
        coroutineScope {
            val prefix = subnetPrefix(context) ?: return@coroutineScope emptyList()
            val semaphore = Semaphore(PARALLEL_PROBES)
            (1..254).map { host ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val ip = "$prefix.$host"
                        if (isPortOpen(ip, port)) probe(ip) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }

    private fun isPortOpen(ip: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    private const val PROBE_TIMEOUT_MS = 400
    private const val PARALLEL_PROBES = 48

    fun broadcastAddress(context: Context): InetAddress? {
        val link = localIpv4(context) ?: return null
        val ip = ByteBuffer.wrap(link.address.address).int
        val mask = if (link.prefixLength == 0) 0 else -1 shl (32 - link.prefixLength)
        val broadcast = ip or mask.inv()
        return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcast).array())
    }
}
