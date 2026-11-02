package pl.pilottv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

object NetUtils {

    /** Adres IPv4 telefonu w aktywnej sieci (zwykle Wi-Fi). */
    fun localIpv4(context: Context): LinkAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
    }

    /** Pierwsze trzy oktety adresu, np. "192.168.1". Skanujemy zawsze /24. */
    fun subnetPrefix(context: Context): String? {
        val host = localIpv4(context)?.address?.hostAddress ?: return null
        return host.split(".").take(3).joinToString(".")
    }

    fun broadcastAddress(context: Context): InetAddress? {
        val link = localIpv4(context) ?: return null
        val ip = ByteBuffer.wrap(link.address.address).int
        val mask = if (link.prefixLength == 0) 0 else -1 shl (32 - link.prefixLength)
        val broadcast = ip or mask.inv()
        return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcast).array())
    }
}
