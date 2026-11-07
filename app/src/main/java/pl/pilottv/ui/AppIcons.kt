package pl.pilottv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.pilottv.remote.AppShortcut
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Logo skrótów: ikona strony serwisu z usługi favicon Google, zapisana w pamięci podręcznej.
 * Skrót z samą nazwą pakietu nie ma domeny, więc dostaje literę zamiast logo.
 */
object AppIcons {

    private val memory = ConcurrentHashMap<String, Bitmap>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Niektóre subdomeny nie mają własnej ikony.
    private val DOMAIN_OVERRIDES = mapOf(
        "app.primevideo.com" to "primevideo.com",
        "play.hbomax.com" to "hbomax.com",
    )

    // Skróty z samą nazwą pakietu: domena serwisu do pobrania logo.
    private val PACKAGE_DOMAINS = mapOf(
        "com.skyshowtime.skyshowtime.google" to "skyshowtime.com",
        "com.amazon.spiderpork" to "luna.amazon.com",
    )

    fun domainOf(shortcut: AppShortcut): String? {
        PACKAGE_DOMAINS[shortcut.app]?.let { return it }
        val host = runCatching { Uri.parse(shortcut.app).host }.getOrNull()
            ?.takeIf { shortcut.app.startsWith("http") && it.isNotEmpty() }
            ?: return null
        return DOMAIN_OVERRIDES[host] ?: host
    }

    suspend fun load(context: Context, domain: String): Bitmap? {
        memory[domain]?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(File(context.cacheDir, "app_icons").apply { mkdirs() }, "$domain.png")
            val cached = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            val bitmap = cached ?: runCatching {
                val request = Request.Builder()
                    .url("https://www.google.com/s2/favicons?domain=$domain&sz=$SIZE_PX")
                    .build()
                client.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes()
                    if (!response.isSuccessful || bytes == null) return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { file.writeBytes(bytes) }
                }
            }.getOrNull()
            bitmap?.also { memory[domain] = it }
        }
    }

    private const val SIZE_PX = 128
}

/** Logo skrótu albo pierwsza litera nazwy, gdy logo nie ma (brak sieci, brak domeny). */
@Composable
fun ShortcutIcon(shortcut: AppShortcut, size: Dp = 20.dp) {
    val context = LocalContext.current
    val domain = AppIcons.domainOf(shortcut)
    val image by produceState<ImageBitmap?>(null, domain) {
        value = domain?.let { AppIcons.load(context.applicationContext, it) }?.asImageBitmap()
    }
    val shape = RoundedCornerShape(4.dp)
    val current = image
    if (current != null) {
        Image(current, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                shortcut.name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = (size.value * 0.6f).sp,
            )
        }
    }
}
