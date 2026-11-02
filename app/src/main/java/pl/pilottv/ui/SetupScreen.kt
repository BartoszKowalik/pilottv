package pl.pilottv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.pilottv.BuildConfig
import pl.pilottv.RemoteViewModel
import pl.pilottv.data.HapticLevel
import pl.pilottv.data.LayoutMode
import pl.pilottv.data.ThemeMode
import pl.pilottv.data.TvConfig

/** Ustawienia i konfiguracja TV. [onClose] = null, gdy TV nie jest jeszcze skonfigurowany. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: RemoteViewModel, config: TvConfig?, onClose: (() -> Unit)?) {
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var ipInput by rememberSaveable(config?.ip) { mutableStateOf(config?.ip ?: "") }
    var macInput by rememberSaveable(config?.mac) { mutableStateOf(config?.mac ?: "") }
    val context = LocalContext.current
    val haptics = remember(context) { Haptics(context) }

    if (onClose != null) BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                }
            }
            Text("Ustawienia", style = MaterialTheme.typography.headlineSmall)
        }

        if (config != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(config.name ?: "Samsung TV", style = MaterialTheme.typography.titleMedium)
                    Text("IP: ${config.ip}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (config.certPin != null) "Sparowano" else "Nie sparowano",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::resetPairing) { Text("Sparuj ponownie") }
                        OutlinedButton(onClick = vm::forgetTv) { Text("Zapomnij TV") }
                    }
                }
            }
        }

        SectionTitle("Znajdź telewizor")
        Text(
            "Telefon i TV muszą być w tej samej sieci Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = vm::scan, enabled = !scanning) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Szukam…")
            } else {
                Text("Szukaj w sieci")
            }
        }
        found.forEach { tv ->
            Card(
                onClick = {
                    vm.selectTv(tv)
                    onClose?.invoke()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(tv.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(tv.model, tv.ip).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        SectionTitle("Adres IP ręcznie")
        OutlinedTextField(
            value = ipInput,
            onValueChange = { ipInput = it },
            label = { Text("IP telewizora, np. 192.168.1.50") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                vm.selectManualIp(ipInput)
                onClose?.invoke()
            },
            enabled = ipInput.isNotBlank(),
        ) { Text("Zapisz i połącz") }

        if (config != null) {
            SectionTitle("Włączanie TV (Wake-on-LAN)")
            Text(
                "Na TV włącz: Ustawienia → Ogólne → Sieć → Ustawienia eksperckie → " +
                    "Włącz za pomocą urządzenia mobilnego. MAC zwykle uzupełnia się sam.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = macInput,
                onValueChange = { macInput = it },
                label = { Text("MAC telewizora") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.saveMac(macInput) }) { Text("Zapisz MAC") }
        }

        SectionTitle("Pilot")
        Text("Wibracja przy naciśnięciu", style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(
            options = listOf(
                HapticLevel.OFF to "Wył.",
                HapticLevel.LIGHT to "Słaba",
                HapticLevel.MEDIUM to "Średnia",
                HapticLevel.STRONG to "Mocna",
            ),
            selected = prefs.haptics,
            onSelect = { level ->
                vm.setHaptics(level)
                haptics.click(level)
            },
        )
        Text("Układ pilota", style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(
            options = listOf(
                LayoutMode.MINIMAL to "Minimalny",
                LayoutMode.FULL to "Pełny",
            ),
            selected = prefs.layout,
            onSelect = vm::setLayout,
        )
        SwitchRow("Touchpad zamiast krzyżaka", prefs.touchpad, vm::setTouchpad)
        Text("Motyw", style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(
            options = listOf(
                ThemeMode.SYSTEM to "Systemowy",
                ThemeMode.LIGHT to "Jasny",
                ThemeMode.DARK to "Ciemny",
            ),
            selected = prefs.theme,
            onSelect = vm::setTheme,
        )
        SwitchRow("Przyciski głośności telefonu sterują TV", prefs.volumeKeys, vm::setVolumeKeys)

        Text(
            "Wersja ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
