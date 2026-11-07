package pl.pilottv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.pilottv.RemoteViewModel
import pl.pilottv.data.LayoutMode
import kotlinx.coroutines.delay
import pl.pilottv.remote.AppShortcut
import pl.pilottv.remote.ImeEvent
import pl.pilottv.remote.Key
import pl.pilottv.remote.Target
import pl.pilottv.resolveTarget
import pl.pilottv.samsung.SessionEnd
import pl.pilottv.samsung.TvState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: RemoteViewModel, onOpenSettings: () -> Unit) {
    val tvState by vm.tvState.collectAsStateWithLifecycle()
    val atvState by vm.atvState.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val target = resolveTarget(prefs.target, config)
    val state = if (target == Target.DECODER) atvState else tvState
    val title = when (target) {
        Target.TV -> config.tv?.let { it.name ?: it.ip } ?: "Telewizor"
        Target.DECODER -> config.atv?.let { it.name ?: it.ip } ?: "Dekoder"
    }
    val context = LocalContext.current
    val haptics = remember(context) { Haptics(context) }
    val hapticLevel = prefs.haptics
    val haptic: () -> Unit = remember(haptics, hapticLevel) { { haptics.click(hapticLevel) } }
    val send: (Key) -> Unit = vm::send
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val atvApp by vm.atvCurrentApp.collectAsStateWithLifecycle()
    val currentApp = if (target == Target.DECODER) atvApp else null
    var showMore by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf(EditedText("", fromUser = false)) }
    var lastUserEdit by remember { mutableStateOf(0L) }

    fun editText(value: String) {
        keyboardText = EditedText(value, fromUser = true)
        lastUserEdit = SystemClock.elapsedRealtime()
    }

    // Pole tekstowe na urządzeniu: otwarcie pokazuje klawiaturę w telefonie, zmiany aktualizują tekst.
    LaunchedEffect(vm) {
        vm.ime.collect { event ->
            when (event) {
                is ImeEvent.Opened -> {
                    showKeyboard = true
                    event.text?.let { keyboardText = EditedText(it, fromUser = false) }
                }
                is ImeEvent.Updated -> {
                    // Nie nadpisujemy tekstu, który użytkownik właśnie wpisuje.
                    if (SystemClock.elapsedRealtime() - lastUserEdit > ECHO_GUARD_MS) {
                        keyboardText = EditedText(event.text, fromUser = false)
                    }
                }
                ImeEvent.Closed -> showKeyboard = false
            }
        }
    }

    // Zmiany od użytkownika idą na urządzenie z krótkim opóźnieniem (cały tekst, więc kasowanie też działa).
    LaunchedEffect(keyboardText) {
        if (keyboardText.fromUser) {
            delay(SEND_DEBOUNCE_MS)
            vm.sendText(keyboardText.value)
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) {
            editText(text)
            showKeyboard = true
        }
    }
    val startSpeech: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Powiedz, co wpisać")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Brak usługi rozpoznawania mowy w telefonie.", Toast.LENGTH_SHORT).show()
        }
    }

    val actions = RemoteActions(
        send = send,
        power = vm::power,
        openMore = { showMore = true },
        openKeyboard = { showKeyboard = true },
        openMic = startSpeech,
        touchpad = prefs.touchpad,
        shortcuts = if (target == Target.DECODER) shortcuts else emptyList(),
        launch = vm::launchShortcut,
        currentApp = currentApp,
    )

    CompositionLocalProvider(LocalHaptic provides haptic) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Header(
                title = title,
                state = state,
                appName = currentApp?.let { pkg -> shortcuts.firstOrNull { it.packageName == pkg }?.name },
                touchpad = prefs.touchpad,
                onToggleTouchpad = { vm.setTouchpad(!prefs.touchpad) },
                onOpenSettings = onOpenSettings,
                onRetry = vm::restartConnection,
                onRepair = if (target == Target.TV) vm::resetPairing else onOpenSettings,
            )
            if (config.tv != null && config.atv != null) {
                TargetSwitch(
                    target = target,
                    tvState = tvState,
                    atvState = atvState,
                    volumeToTv = prefs.volumeToTv,
                    onSelect = vm::setTarget,
                )
            }
            Spacer(Modifier.height(12.dp))
            when (prefs.layout) {
                LayoutMode.MINIMAL -> MinimalLayout(actions)
                LayoutMode.FULL -> FullLayout(actions)
            }
        }

        if (showMore) {
            ModalBottomSheet(
                onDismissRequest = { showMore = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DigitKeys(send)
                    FunctionKeys(send)
                }
            }
        }

        if (showKeyboard) {
            ModalBottomSheet(
                onDismissRequest = { showKeyboard = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                KeyboardSheet(
                    text = keyboardText.value,
                    onTextChange = ::editText,
                    onMic = startSpeech,
                    onSubmit = {
                        if (keyboardText.fromUser) vm.sendText(keyboardText.value)
                        vm.endInput()
                        showKeyboard = false
                    },
                )
            }
        }
    }
}

/** Przełącznik TV / dekoder, widoczny tylko, gdy oba urządzenia są skonfigurowane. */
@Composable
private fun TargetSwitch(
    target: Target,
    tvState: TvState,
    atvState: TvState,
    volumeToTv: Boolean,
    onSelect: (Target) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TargetChip("TV", tvState, target == Target.TV) { onSelect(Target.TV) }
        TargetChip("Dekoder", atvState, target == Target.DECODER) { onSelect(Target.DECODER) }
        if (target == Target.DECODER && volumeToTv) {
            Text(
                "Głośność: TV",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetChip(label: String, state: TvState, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(state)),
            )
        },
    )
}

/** Tekst w polu klawiatury. [fromUser] = wpisany w telefonie (trzeba go wysłać), a nie odebrany z urządzenia. */
private data class EditedText(val value: String, val fromUser: Boolean)

private const val SEND_DEBOUNCE_MS = 250L
private const val ECHO_GUARD_MS = 1_500L

private class RemoteActions(
    val send: (Key) -> Unit,
    val power: () -> Unit,
    val openMore: () -> Unit,
    val openKeyboard: () -> Unit,
    val openMic: () -> Unit,
    val touchpad: Boolean,
    val shortcuts: List<AppShortcut>,
    val launch: (AppShortcut) -> Unit,
    /** Pakiet aplikacji otwartej na dekoderze: jej skrót jest zaznaczony. */
    val currentApp: String?,
)

/** Skróty do aplikacji VOD (tylko dekoder). Przewijane poziomo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutRow(a: RemoteActions) {
    if (a.shortcuts.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        a.shortcuts.forEach { shortcut ->
            FilterChip(
                selected = a.currentApp != null && shortcut.packageName == a.currentApp,
                onClick = { a.launch(shortcut) },
                label = { Text(shortcut.name) },
                leadingIcon = { ShortcutIcon(shortcut) },
            )
        }
    }
}

/** Domyślny układ: wszystko na jednym ekranie, bez przewijania. */
@Composable
private fun ColumnScope.MinimalLayout(a: RemoteActions) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopKeys(a)
        // Nawigacja zajmuje całe wolne miejsce, więc układ mieści się także na małych ekranach.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (a.touchpad) {
                TouchPad(a.send, Modifier.fillMaxSize())
            } else {
                DPad(a.send, Modifier.widthIn(max = 320.dp).aspectRatio(1f))
            }
        }
        NavKeys(a)
        RockerRow(a) {
            IconKey(Icons.Filled.Keyboard, "Wpisz tekst", a.openKeyboard, size = 52.dp)
            IconKey(Icons.Filled.Mic, "Mikrofon", a.openMic, size = 52.dp)
            IconKey(Icons.Filled.MoreHoriz, "Więcej przycisków", a.openMore, size = 52.dp)
        }
        ShortcutRow(a)
        MediaKeys(a)
    }
}

/** Pełny pilot: wszystkie przyciski na przewijanym ekranie, jak na fizycznym pilocie. */
@Composable
private fun ColumnScope.FullLayout(a: RemoteActions) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopKeys(a)
        DigitKeys(a.send)
        RockerRow(a) {
            TextKey("CH LIST", { a.send(Key.CH_LIST) }, Modifier.width(96.dp), label = "Lista kanałów")
            IconKey(Icons.Filled.Keyboard, "Wpisz tekst", a.openKeyboard, size = 52.dp)
            IconKey(Icons.Filled.Mic, "Mikrofon", a.openMic, size = 52.dp)
        }
        ShortcutRow(a)
        if (a.touchpad) {
            TouchPad(a.send, Modifier.fillMaxWidth().height(260.dp))
        } else {
            DPad(a.send, Modifier.widthIn(max = 280.dp).fillMaxWidth())
        }
        NavKeys(a)
        FunctionKeys(a.send)
        MediaKeys(a)
    }
}

@Composable
private fun TopKeys(a: RemoteActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconKey(Icons.Filled.PowerSettingsNew, "Zasilanie", a.power, color = PowerRed, contentColor = Color.White)
        IconKey(Icons.Filled.Input, "Źródło", { a.send(Key.SOURCE) })
        IconKey(Icons.AutoMirrored.Filled.VolumeOff, "Wycisz", { a.send(Key.MUTE) })
    }
}

@Composable
private fun NavKeys(a: RemoteActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IconKey(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz", { a.send(Key.RETURN) })
        IconKey(Icons.Filled.Home, "Home", { a.send(Key.HOME) })
        IconKey(Icons.Filled.Menu, "Menu", { a.send(Key.MENU) })
    }
}

@Composable
private fun RockerRow(a: RemoteActions, center: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rocker(
            title = "VOL",
            upIcon = Icons.Filled.Add, upLabel = "Głośniej", onUp = { a.send(Key.VOL_UP) },
            downIcon = Icons.Filled.Remove, downLabel = "Ciszej", onDown = { a.send(Key.VOL_DOWN) },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { center() }
        Rocker(
            title = "CH",
            upIcon = Icons.Filled.KeyboardArrowUp, upLabel = "Następny kanał", onUp = { a.send(Key.CH_UP) },
            downIcon = Icons.Filled.KeyboardArrowDown, downLabel = "Poprzedni kanał", onDown = { a.send(Key.CH_DOWN) },
        )
    }
}

@Composable
private fun MediaKeys(a: RemoteActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconKey(Icons.Filled.FastRewind, "Przewiń do tyłu", { a.send(Key.REWIND) }, size = 52.dp)
        IconKey(Icons.Filled.PlayArrow, "Odtwórz", { a.send(Key.PLAY) }, size = 52.dp)
        IconKey(Icons.Filled.Pause, "Pauza", { a.send(Key.PAUSE) }, size = 52.dp)
        IconKey(Icons.Filled.Stop, "Stop", { a.send(Key.STOP) }, size = 52.dp)
        IconKey(Icons.Filled.FastForward, "Przewiń do przodu", { a.send(Key.FAST_FORWARD) }, size = 52.dp)
    }
}

@Composable
private fun Header(
    title: String,
    state: TvState,
    appName: String?,
    touchpad: Boolean,
    onToggleTouchpad: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    // Komunikat to druga linia pod nazwą. Wiersz ma stałą wysokość (jak ikony),
    // więc komunikat nie zmienia rozmiaru przycisków pod spodem.
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor(state)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (state == TvState.Connected && appName != null) "Połączono · $appName" else statusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            state is TvState.Failed && state.reason == SessionEnd.CERT_CHANGED ->
                TextButton(onClick = onRepair) { Text("Sparuj") }
            state is TvState.Failed && state.reason == SessionEnd.NOT_PAIRED ->
                TextButton(onClick = onRepair) { Text("Sparuj") }
            state is TvState.Failed && state.reason == SessionEnd.DENIED ->
                TextButton(onClick = onRetry) { Text("Ponów") }
        }
        IconButton(onClick = onToggleTouchpad) {
            Icon(
                if (touchpad) Icons.Filled.Gamepad else Icons.Filled.TouchApp,
                contentDescription = if (touchpad) "Przełącz na krzyżak" else "Przełącz na touchpad",
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Ustawienia")
        }
    }
}

private val HEADER_HEIGHT = 48.dp

private fun statusColor(state: TvState): Color = when (state) {
    TvState.Connected -> Color(0xFF4CAF50)
    TvState.Connecting, TvState.AwaitingApproval -> Color(0xFFFFC107)
    TvState.Idle, is TvState.Failed -> Color(0xFF9E9E9E)
}

/** Krótkie teksty: mieszczą się w jednej linii pod nazwą urządzenia. */
private fun statusText(state: TvState): String = when (state) {
    TvState.Idle -> "Rozłączono"
    TvState.Connecting -> "Łączenie… Zezwól na TV, jeśli pyta."
    TvState.AwaitingApproval -> "Na ekranie TV wybierz „Zezwól”."
    TvState.Connected -> "Połączono"
    is TvState.Failed -> when (state.reason) {
        SessionEnd.CLOSED -> "Rozłączono. Łączę ponownie…"
        SessionEnd.UNREACHABLE -> "Nie odpowiada. Włącz zasilaniem."
        SessionEnd.TIMEOUT -> "Brak zgody na TV. Ponawiam…"
        SessionEnd.DENIED -> "TV odrzucił połączenie."
        SessionEnd.CERT_CHANGED -> "Certyfikat się zmienił."
        SessionEnd.NOT_PAIRED -> "Dekoder nie zna telefonu."
    }
}

@Composable
private fun DigitKeys(send: (Key) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val digitRows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        digitRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d -> TextKey(d, { send(Key.digit(d.toInt())) }, Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextKey("TTX", { send(Key.TTX) }, Modifier.weight(1f), label = "Teletekst")
            TextKey("0", { send(Key.digit(0)) }, Modifier.weight(1f))
            TextKey("PRE-CH", { send(Key.PRE_CH) }, Modifier.weight(1f), label = "Poprzedni kanał")
        }
    }
}

@Composable
private fun FunctionKeys(send: (Key) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextKey("A", { send(Key.RED) }, Modifier.weight(1f), label = "Czerwony", color = Color(0xFFC62828), contentColor = Color.White)
            TextKey("B", { send(Key.GREEN) }, Modifier.weight(1f), label = "Zielony", color = Color(0xFF2E7D32), contentColor = Color.White)
            TextKey("C", { send(Key.YELLOW) }, Modifier.weight(1f), label = "Żółty", color = Color(0xFFF9A825), contentColor = Color.Black)
            TextKey("D", { send(Key.BLUE) }, Modifier.weight(1f), label = "Niebieski", color = Color(0xFF1565C0), contentColor = Color.White)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextKey("INFO", { send(Key.INFO) }, Modifier.weight(1f))
            TextKey("GUIDE", { send(Key.GUIDE) }, Modifier.weight(1f), label = "Przewodnik")
            TextKey("TOOLS", { send(Key.TOOLS) }, Modifier.weight(1f), label = "Narzędzia")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextKey("CH LIST", { send(Key.CH_LIST) }, Modifier.weight(1f), label = "Lista kanałów")
            TextKey("EXIT", { send(Key.EXIT) }, Modifier.weight(1f))
        }
    }
}

/**
 * Edycja pola tekstowego na urządzeniu na żywo. [text] pokazuje zawartość pola z urządzenia,
 * a każda zmiana w telefonie zastępuje cały tekst na urządzeniu.
 */
@Composable
private fun KeyboardSheet(
    text: String,
    onTextChange: (String) -> Unit,
    onMic: () -> Unit,
    onSubmit: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tekst na urządzeniu", style = MaterialTheme.typography.titleMedium)
        Text(
            "Działa, gdy na urządzeniu jest otwarte pole tekstowe, np. wyszukiwarka. Zmiany idą na bieżąco.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            trailingIcon = {
                IconButton(onClick = onMic) { Icon(Icons.Filled.Mic, contentDescription = "Mikrofon") }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let(onTextChange) }) { Text("Wklej") }
            OutlinedButton(onClick = { onTextChange("") }, enabled = text.isNotEmpty()) { Text("Wyczyść") }
            Button(onClick = onSubmit) { Text("Zatwierdź") }
        }
    }
}
