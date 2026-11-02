package pl.pilottv.samsung

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pl.pilottv.net.NetUtils
import java.util.concurrent.TimeUnit

data class FoundTv(val ip: String, val name: String, val model: String?, val mac: String?)

/** Szuka telewizorów Samsung w podsieci /24: otwarty port 8001 + poprawny JSON z /api/v2/. */
object SamsungDiscovery {

    private const val INFO_PORT = 8001

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun scan(context: Context): List<FoundTv> = NetUtils.scanSubnet(context, INFO_PORT) { fetchInfo(it) }

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
}
