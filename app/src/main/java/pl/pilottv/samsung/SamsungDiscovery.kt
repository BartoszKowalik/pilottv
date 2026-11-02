package pl.pilottv.samsung

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pl.pilottv.net.NetUtils
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class FoundTv(val ip: String, val name: String, val model: String?, val mac: String?)

/** Szuka telewizorów Samsung w podsieci /24: otwarty port 8001 + poprawny JSON z /api/v2/. */
object SamsungDiscovery {

    private const val INFO_PORT = 8001
    private const val PROBE_TIMEOUT_MS = 400
    private const val PARALLEL_PROBES = 48

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun scan(context: Context): List<FoundTv> = coroutineScope {
        val prefix = NetUtils.subnetPrefix(context) ?: return@coroutineScope emptyList()
        val semaphore = Semaphore(PARALLEL_PROBES)
        (1..254).map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val ip = "$prefix.$host"
                    if (isPortOpen(ip)) fetchInfo(ip) else null
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun fetchInfo(ip: String): FoundTv? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("http://$ip:$INFO_PORT/api/v2/").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                val device = json.optJSONObject("device") ?: return@use null
                FoundTv(
                    ip = ip,
                    name = json.optString("name").ifEmpty { device.optString("name") }.ifEmpty { "Samsung TV" },
                    model = device.optString("modelName").ifEmpty { null },
                    mac = device.optString("wifiMac").ifEmpty { null },
                )
            }
        }.getOrNull()
    }

    private fun isPortOpen(ip: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, INFO_PORT), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)
}
