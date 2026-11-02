package pl.pilottv.atv

import android.view.KeyEvent
import pl.pilottv.remote.Key

/** Kody klawiszy Android TV. Protokół używa tych samych wartości co android.view.KeyEvent. */
object AtvKeys {

    fun of(key: Key): Int = when (key) {
        Key.POWER -> KeyEvent.KEYCODE_POWER
        Key.SOURCE -> KeyEvent.KEYCODE_TV_INPUT
        Key.UP -> KeyEvent.KEYCODE_DPAD_UP
        Key.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        Key.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        Key.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        Key.ENTER -> KeyEvent.KEYCODE_DPAD_CENTER
        Key.RETURN, Key.EXIT -> KeyEvent.KEYCODE_BACK
        Key.HOME -> KeyEvent.KEYCODE_HOME
        Key.MENU -> KeyEvent.KEYCODE_MENU
        Key.VOL_UP -> KeyEvent.KEYCODE_VOLUME_UP
        Key.VOL_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
        Key.MUTE -> KeyEvent.KEYCODE_VOLUME_MUTE
        Key.CH_UP -> KeyEvent.KEYCODE_CHANNEL_UP
        Key.CH_DOWN -> KeyEvent.KEYCODE_CHANNEL_DOWN
        Key.CH_LIST, Key.GUIDE -> KeyEvent.KEYCODE_GUIDE
        Key.PRE_CH -> KeyEvent.KEYCODE_LAST_CHANNEL
        Key.TTX -> KeyEvent.KEYCODE_TV_TELETEXT
        Key.INFO -> KeyEvent.KEYCODE_INFO
        Key.TOOLS -> KeyEvent.KEYCODE_SETTINGS
        Key.RED -> KeyEvent.KEYCODE_PROG_RED
        Key.GREEN -> KeyEvent.KEYCODE_PROG_GREEN
        Key.YELLOW -> KeyEvent.KEYCODE_PROG_YELLOW
        Key.BLUE -> KeyEvent.KEYCODE_PROG_BLUE
        Key.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        Key.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        Key.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        Key.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
        Key.FAST_FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        Key.D0 -> KeyEvent.KEYCODE_0
        Key.D1 -> KeyEvent.KEYCODE_1
        Key.D2 -> KeyEvent.KEYCODE_2
        Key.D3 -> KeyEvent.KEYCODE_3
        Key.D4 -> KeyEvent.KEYCODE_4
        Key.D5 -> KeyEvent.KEYCODE_5
        Key.D6 -> KeyEvent.KEYCODE_6
        Key.D7 -> KeyEvent.KEYCODE_7
        Key.D8 -> KeyEvent.KEYCODE_8
        Key.D9 -> KeyEvent.KEYCODE_9
    }
}
