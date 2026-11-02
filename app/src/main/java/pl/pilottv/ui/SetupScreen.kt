package pl.pilottv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.pilottv.BuildConfig
import pl.pilottv.PairingUi
import pl.pilottv.RemoteViewModel
import pl.pilottv.data.HapticLevel
import pl.pilottv.data.LayoutMode
import pl.pilottv.data.ThemeMode

/** Ustawienia i konfiguracja urządzeń. [onClose] = null, gdy nie ma jeszcze żadnego urządzenia. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: RemoteViewModel, onClose: (() -> Unit)?) {
    val configState by vm.config.collectAsStateWithLifecycle()
    val config = configState.tv
    val atv = configState.atv
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val scanningAtv by vm.scanningAtv.collectAsStateWithLifecycle()
    val foundAtv by vm.foundAtv.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var ipInput by rememberSaveable(config?.ip) { mutableStateOf(config?.ip ?: "") }
    var macInput by rememberSaveable(config?.mac) { mutableStateOf(config?.mac ?: "") }
    var atvIpInput by rememberSaveable(atv?.ip) { mutableStateOf(atv?.ip ?: "") }
    val context = LocalContext.current
    val haptics = remember(context) { Haptics(context) }

    if (onClose != null) BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
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

        // --- Telewizor Samsung ---
        SectionTitle("Telewizor Samsung")
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
        Hint("Telefon i urządzenia muszą być w tej samej sieci Wi-Fi.")
        ScanButton(scanning, "Szukaj TV w sieci", vm::scan)
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
            Text("Włączanie TV (Wake-on-LAN)", style = MaterialTheme.typography.bodyLarge)
            Hint(
                "Na TV włącz: Ustawienia → Ogólne → Sieć → Ustawienia eksperckie → " +
                    "Włącz za pomocą urządzenia mobilnego. MAC zwykle uzupełnia się sam.",
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

        // --- Dekoder Android TV ---
        SectionTitle("Dekoder (Android TV)")
        if (atv != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(atv.name ?: "Dekoder", style = MaterialTheme.typography.titleMedium)
                    Text("IP: ${atv.ip}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.startAtvPairing(atv.ip) }) { Text("Sparuj ponownie") }
                        OutlinedButton(onClick = vm::forgetAtv) { Text("Zapomnij") }
                    }
                }
            }
        }
        Hint("Parowanie: dekoder pokaże na ekranie kod z 6 znaków. Wpisz go w telefonie.")
        ScanButton(scanningAtv, "Szukaj dekodera w sieci", vm::scanAtv)
        foundAtv.forEach { ip ->
            Card(onClick = { vm.startAtvPairing(ip) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Android TV", style = MaterialTheme.typography.titleSmall)
                    Text("$ip · stuknij, aby sparować", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(
            value = atvIpInput,
            onValueChange = { atvIpInput = it },
            label = { Text("IP dekodera") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.startAtvPairing(atvIpInput) },
            enabled = atvIpInput.isNotBlank() && pairing == PairingUi.Idle,
        ) { Text("Sparuj dekoder") }
        if (config != null && atv != null) {
            SwitchRow("Głośność zawsze steruje telewizorem", prefs.volumeToTv, vm::setVolumeToTv)
        }

        // --- Pilot ---
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
        SwitchRow("Przyciski głośności telefonu sterują urządzeniem", prefs.volumeKeys, vm::setVolumeKeys)

        Hint("Wersja ${BuildConfig.VERSION_NAME}")
    }

    PairingDialog(pairing, onSubmit = vm::submitPairingCode, onCancel = vm::cancelPairing)
}

@Composable
private fun PairingDialog(state: PairingUi, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    if (state == PairingUi.Idle) return
    var code by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Parowanie dekodera") },
        text = {
            when (state) {
                PairingUi.Connecting, PairingUi.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(if (state == PairingUi.Connecting) "Łączę z dekoderem…" else "Sprawdzam kod…")
                }
                is PairingUi.EnterCode -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Wpisz kod z ekranu: ${state.deviceName}")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                        label = { Text("Kod (6 znaków)") },
                        singleLine = true,
                        isError = state.error != null,
                        supportingText = if (state.error != null) {
                            { Text(state.error.orEmpty()) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                }
                PairingUi.Idle -> Unit
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code) },
                enabled = state is PairingUi.EnterCode && code.length == 6,
            ) { Text("Paruj") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Anuluj") }
        },
    )
}

@Composable
private fun ScanButton(scanning: Boolean, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !scanning) {
        if (scanning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Szukam…")
        } else {
            Text(label)
        }
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

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
