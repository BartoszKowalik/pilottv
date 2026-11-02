package pl.pilottv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {

    /** Zwraca MAC w formacie "AA:BB:CC:DD:EE:FF" albo null, gdy format jest zły. */
    fun normalizeMac(mac: String): String? = runCatching {
        parseMac(mac).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    suspend fun send(mac: String, broadcast: InetAddress?) = withContext(Dispatchers.IO) {
        val macBytes = parseMac(mac)
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) macBytes.copyInto(packet, 6 + i * 6)

        val targets = listOfNotNull(broadcast, InetAddress.getByName("255.255.255.255")).distinct()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            // UDP może zgubić pakiet, więc wysyłamy kilka razy.
            repeat(3) {
                for (target in targets) {
                    for (port in intArrayOf(9, 7)) {
                        runCatching { socket.send(DatagramPacket(packet, packet.size, target, port)) }
                    }
                }
                delay(100)
            }
        }
    }

    private fun parseMac(mac: String): ByteArray {
        val parts = mac.trim().split(':', '-').filter { it.isNotEmpty() }
        require(parts.size == 6) { "Niepoprawny MAC" }
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }
}
