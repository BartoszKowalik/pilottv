package pl.pilottv

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.pilottv.samsung.SamsungKeys
import pl.pilottv.samsung.TvState
import pl.pilottv.ui.PilotTheme
import pl.pilottv.ui.isDark
import pl.pilottv.ui.RemoteScreen
import pl.pilottv.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val dark = prefs.theme.isDark()
            // Ikony paska stanu muszą pasować do motywu aplikacji, nie do motywu systemu.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            PilotTheme(dark) { PilotApp(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onStart()
    }

    override fun onStop() {
        vm.onStop()
        super.onStop()
    }

    /** Fizyczne przyciski głośności telefonu sterują głośnością TV, gdy TV jest połączony. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.prefs.value.volumeKeys && vm.tvState.value == TvState.Connected) {
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> SamsungKeys.VOL_UP
                KeyEvent.KEYCODE_VOLUME_DOWN -> SamsungKeys.VOL_DOWN
                else -> null
            }
            if (key != null) {
                if (event.action == KeyEvent.ACTION_DOWN) vm.send(key)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun PilotApp(vm: RemoteViewModel) {
    val configState by vm.config.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (!configState.loaded) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val config = configState.config
    // Po „Zapomnij TV” wybór nowego TV ma od razu wrócić do pilota.
    LaunchedEffect(config == null) {
        if (config == null) showSettings = false
    }
    if (config == null || showSettings) {
        SetupScreen(
            vm = vm,
            config = config,
            onClose = if (config != null) ({ showSettings = false }) else null,
        )
    } else {
        RemoteScreen(vm, config, onOpenSettings = { showSettings = true })
    }
}
