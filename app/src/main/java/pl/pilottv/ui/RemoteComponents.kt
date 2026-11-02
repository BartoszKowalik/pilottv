package pl.pilottv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.pilottv.samsung.SamsungKeys

/** Wibracja przy naciśnięciu. Dostarczana przez ekran, bo zależy od ustawień. */
val LocalHaptic = staticCompositionLocalOf<() -> Unit> { {} }

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 120L

/**
 * Przycisk pilota. Komenda idzie w chwili dotknięcia (nie po puszczeniu), co daje szybszą reakcję.
 * Gdy [repeat] = true, przytrzymanie powtarza komendę.
 */
@Composable
fun RemoteButton(
    onPress: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    repeat: Boolean = false,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHaptic.current
    val currentOnPress by rememberUpdatedState(onPress)
    val currentHaptic by rememberUpdatedState(haptic)
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val background = if (pressed) contentColor.copy(alpha = 0.22f).compositeOver(color) else color

    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label) {
                    currentOnPress()
                    true
                }
            }
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val job = scope.launch {
                        currentHaptic()
                        currentOnPress()
                        if (repeat) {
                            delay(REPEAT_DELAY_MS)
                            while (isActive) {
                                currentOnPress()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        job.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
fun IconKey(
    icon: ImageVector,
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    repeat: Boolean = false,
) {
    RemoteButton(
        onPress = onPress,
        label = label,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = color,
        contentColor = contentColor,
        repeat = repeat,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun TextKey(
    text: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = text,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    RemoteButton(
        onPress = onPress,
        label = label,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = color,
        contentColor = contentColor,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Okrągły krzyżak z przyciskiem OK. Rozmiar ustala wywołujący, krzyżak jest kwadratowy. */
@Composable
fun DPad(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val arrow = Modifier.fillMaxSize(1f / 3f)
    val iconSize = 44.dp
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        RemoteButton({ onKey(SamsungKeys.UP) }, "W górę", Modifier.align(Alignment.TopCenter).then(arrow), color = Color.Transparent, repeat = true) {
            Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(iconSize))
        }
        RemoteButton({ onKey(SamsungKeys.DOWN) }, "W dół", Modifier.align(Alignment.BottomCenter).then(arrow), color = Color.Transparent, repeat = true) {
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(iconSize))
        }
        RemoteButton({ onKey(SamsungKeys.LEFT) }, "W lewo", Modifier.align(Alignment.CenterStart).then(arrow), color = Color.Transparent, repeat = true) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(iconSize))
        }
        RemoteButton({ onKey(SamsungKeys.RIGHT) }, "W prawo", Modifier.align(Alignment.CenterEnd).then(arrow), color = Color.Transparent, repeat = true) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(iconSize))
        }
        RemoteButton(
            onPress = { onKey(SamsungKeys.ENTER) },
            label = "OK",
            modifier = Modifier.align(Alignment.Center).fillMaxSize(0.36f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text("OK", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
    }
}

/**
 * Touchpad zamiast krzyżaka. Przesunięcie palca o [stepDp] wysyła strzałkę w dominującym kierunku.
 * Dalsze przesuwanie bez odrywania palca wysyła kolejne strzałki. Stuknięcie = OK.
 */
@Composable
fun TouchPad(onKey: (String) -> Unit, modifier: Modifier = Modifier, stepDp: Dp = 48.dp) {
    val currentOnKey by rememberUpdatedState(onKey)
    val currentHaptic by rememberUpdatedState(LocalHaptic.current)
    val stepPx = with(LocalDensity.current) { stepDp.toPx() }

    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "Touchpad. Przesuń, aby nawigować. Stuknij, aby zatwierdzić." }
            .pointerInput(stepPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    var accX = 0f
                    var accY = 0f
                    var travelled = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        change.consume()
                        accX += delta.x
                        accY += delta.y
                        travelled += abs(delta.x) + abs(delta.y)
                        if (abs(accX) >= stepPx || abs(accY) >= stepPx) {
                            val key = if (abs(accX) > abs(accY)) {
                                if (accX > 0) SamsungKeys.RIGHT else SamsungKeys.LEFT
                            } else {
                                if (accY > 0) SamsungKeys.DOWN else SamsungKeys.UP
                            }
                            currentHaptic()
                            currentOnKey(key)
                            accX = 0f
                            accY = 0f
                        }
                    }
                    if (travelled < slop) {
                        currentHaptic()
                        currentOnKey(SamsungKeys.ENTER)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Przesuń palcem, aby nawigować\nStuknij = OK",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Pionowy przełącznik +/- (głośność, kanały). */
@Composable
fun Rocker(
    title: String,
    upIcon: ImageVector,
    upLabel: String,
    onUp: () -> Unit,
    downIcon: ImageVector,
    downLabel: String,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(76.dp)
            .clip(RoundedCornerShape(38.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RemoteButton(onUp, upLabel, Modifier.fillMaxWidth().height(70.dp), color = Color.Transparent, repeat = true) {
            Icon(upIcon, null, Modifier.size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 2.dp))
        RemoteButton(onDown, downLabel, Modifier.fillMaxWidth().height(70.dp), color = Color.Transparent, repeat = true) {
            Icon(downIcon, null, Modifier.size(32.dp))
        }
    }
}
