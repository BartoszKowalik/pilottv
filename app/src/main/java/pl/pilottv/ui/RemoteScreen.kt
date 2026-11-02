package pl.pilottv.ui

import androidx.compose.foundation.background
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
import pl.pilottv.data.TvConfig
import pl.pilottv.samsung.SamsungKeys
import pl.pilottv.samsung.SessionEnd
import pl.pilottv.samsung.TvState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: RemoteViewModel, config: TvConfig, onOpenSettings: () -> Unit) {
    val state by vm.tvState.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = remember(context) { Haptics(context) }
    val hapticLevel = prefs.haptics
    val haptic: () -> Unit = remember(haptics, hapticLevel) { { haptics.click(hapticLevel) } }
    val send: (String) -> Unit = vm::send
    var showMore by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }

    // Gdy na TV otworzy się pole tekstowe, od razu pokazujemy klawiaturę w telefonie.
    LaunchedEffect(vm) {
        vm.keyboardOnTv.collect { open -> showKeyboard = open }
    }

    val actions = RemoteActions(
        send = send,
        power = vm::power,
        openMore = { showMore = true },
        openKeyboard = { showKeyboard = true },
        touchpad = prefs.touchpad,
    )

    CompositionLocalProvider(LocalHaptic provides haptic) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Header(
                title = config.name ?: config.ip,
                state = state,
                touchpad = prefs.touchpad,
                onToggleTouchpad = { vm.setTouchpad(!prefs.touchpad) },
                onOpenSettings = onOpenSettings,
                onRetry = vm::restartConnection,
                onRepair = vm::resetPairing,
            )
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
                    onSend = vm::sendText,
                    onSubmit = {
                        vm.endInput()
                        showKeyboard = false
                    },
                )
            }
        }
    }
}

private class RemoteActions(
    val send: (String) -> Unit,
    val power: () -> Unit,
    val openMore: () -> Unit,
    val openKeyboard: () -> Unit,
    val touchpad: Boolean,
)

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
            TextKey("EXIT", { a.send(SamsungKeys.EXIT) }, Modifier.width(96.dp))
            IconKey(Icons.Filled.Keyboard, "Wpisz tekst", a.openKeyboard, size = 52.dp)
            IconKey(Icons.Filled.MoreHoriz, "Więcej przycisków", a.openMore, size = 52.dp)
        }
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
            TextKey("CH LIST", { a.send(SamsungKeys.CH_LIST) }, Modifier.width(96.dp), label = "Lista kanałów")
            IconKey(Icons.Filled.Keyboard, "Wpisz tekst", a.openKeyboard, size = 52.dp)
            TextKey("EXIT", { a.send(SamsungKeys.EXIT) }, Modifier.width(96.dp))
        }
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
        IconKey(Icons.Filled.Input, "Źródło", { a.send(SamsungKeys.SOURCE) })
        IconKey(Icons.AutoMirrored.Filled.VolumeOff, "Wycisz", { a.send(SamsungKeys.MUTE) })
    }
}

@Composable
private fun NavKeys(a: RemoteActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IconKey(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz", { a.send(SamsungKeys.RETURN) })
        IconKey(Icons.Filled.Home, "Home", { a.send(SamsungKeys.HOME) })
        IconKey(Icons.Filled.Menu, "Menu", { a.send(SamsungKeys.MENU) })
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
            upIcon = Icons.Filled.Add, upLabel = "Głośniej", onUp = { a.send(SamsungKeys.VOL_UP) },
            downIcon = Icons.Filled.Remove, downLabel = "Ciszej", onDown = { a.send(SamsungKeys.VOL_DOWN) },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { center() }
        Rocker(
            title = "CH",
            upIcon = Icons.Filled.KeyboardArrowUp, upLabel = "Następny kanał", onUp = { a.send(SamsungKeys.CH_UP) },
            downIcon = Icons.Filled.KeyboardArrowDown, downLabel = "Poprzedni kanał", onDown = { a.send(SamsungKeys.CH_DOWN) },
        )
    }
}

@Composable
private fun MediaKeys(a: RemoteActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconKey(Icons.Filled.FastRewind, "Przewiń do tyłu", { a.send(SamsungKeys.REWIND) }, size = 52.dp)
        IconKey(Icons.Filled.PlayArrow, "Odtwórz", { a.send(SamsungKeys.PLAY) }, size = 52.dp)
        IconKey(Icons.Filled.Pause, "Pauza", { a.send(SamsungKeys.PAUSE) }, size = 52.dp)
        IconKey(Icons.Filled.Stop, "Stop", { a.send(SamsungKeys.STOP) }, size = 52.dp)
        IconKey(Icons.Filled.FastForward, "Przewiń do przodu", { a.send(SamsungKeys.FAST_FORWARD) }, size = 52.dp)
    }
}

@Composable
private fun Header(
    title: String,
    state: TvState,
    touchpad: Boolean,
    onToggleTouchpad: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor(state)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
        if (state != TvState.Connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when {
                    state is TvState.Failed && state.reason == SessionEnd.CERT_CHANGED ->
                        TextButton(onClick = onRepair) { Text("Sparuj ponownie") }
                    state is TvState.Failed && state.reason == SessionEnd.DENIED ->
                        TextButton(onClick = onRetry) { Text("Ponów") }
                }
            }
        }
    }
}

private fun statusColor(state: TvState): Color = when (state) {
    TvState.Connected -> Color(0xFF4CAF50)
    TvState.Connecting, TvState.AwaitingApproval -> Color(0xFFFFC107)
    TvState.Idle, is TvState.Failed -> Color(0xFF9E9E9E)
}

private fun statusText(state: TvState): String = when (state) {
    TvState.Idle -> "Rozłączono"
    TvState.Connecting -> "Łączenie… Jeśli TV pyta o zgodę, wybierz „Zezwól”."
    TvState.AwaitingApproval -> "Na ekranie TV wybierz „Zezwól”."
    TvState.Connected -> "Połączono"
    is TvState.Failed -> when (state.reason) {
        SessionEnd.CLOSED -> "Rozłączono. Łączę ponownie…"
        SessionEnd.UNREACHABLE -> "TV nie odpowiada. Jeśli jest wyłączony, naciśnij zasilanie."
        SessionEnd.TIMEOUT -> "Brak zgody na TV. Ponawiam…"
        SessionEnd.DENIED -> "TV odrzucił połączenie."
        SessionEnd.CERT_CHANGED -> "Certyfikat TV się zmienił. Sparuj TV ponownie."
    }
}

@Composable
private fun DigitKeys(send: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val digitRows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        digitRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d -> TextKey(d, { send(SamsungKeys.digit(d.toInt())) }, Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextKey("TTX", { send(SamsungKeys.TTX) }, Modifier.weight(1f), label = "Teletekst")
            TextKey("0", { send(SamsungKeys.digit(0)) }, Modifier.weight(1f))
            TextKey("PRE-CH", { send(SamsungKeys.PRE_CH) }, Modifier.weight(1f), label = "Poprzedni kanał")
        }
    }
}

@Composable
private fun FunctionKeys(send: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextKey("A", { send(SamsungKeys.RED) }, Modifier.weight(1f), label = "Czerwony", color = Color(0xFFC62828), contentColor = Color.White)
            TextKey("B", { send(SamsungKeys.GREEN) }, Modifier.weight(1f), label = "Zielony", color = Color(0xFF2E7D32), contentColor = Color.White)
            TextKey("C", { send(SamsungKeys.YELLOW) }, Modifier.weight(1f), label = "Żółty", color = Color(0xFFF9A825), contentColor = Color.Black)
            TextKey("D", { send(SamsungKeys.BLUE) }, Modifier.weight(1f), label = "Niebieski", color = Color(0xFF1565C0), contentColor = Color.White)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextKey("INFO", { send(SamsungKeys.INFO) }, Modifier.weight(1f))
            TextKey("GUIDE", { send(SamsungKeys.GUIDE) }, Modifier.weight(1f), label = "Przewodnik")
            TextKey("TOOLS", { send(SamsungKeys.TOOLS) }, Modifier.weight(1f), label = "Narzędzia")
            TextKey("CH LIST", { send(SamsungKeys.CH_LIST) }, Modifier.weight(1f), label = "Lista kanałów")
        }
    }
}

/** Tekst z telefonu do pola tekstowego na TV (np. wyszukiwarka YouTube). */
@Composable
private fun KeyboardSheet(onSend: (String) -> Unit, onSubmit: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
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
        Text("Tekst na TV", style = MaterialTheme.typography.titleMedium)
        Text(
            "Działa, gdy na TV jest otwarte pole tekstowe, np. wyszukiwarka.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                onSend(text)
                onSubmit()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it } }) { Text("Wklej") }
            OutlinedButton(onClick = { onSend(text) }, enabled = text.isNotEmpty()) { Text("Wyślij") }
            Button(onClick = {
                if (text.isNotEmpty()) onSend(text)
                onSubmit()
            }) { Text("Zatwierdź") }
        }
    }
}
