package pl.pilottv.samsung

import pl.pilottv.remote.Key

/** Kody klawiszy Samsung Tizen (pole DataOfCmd). */
object SamsungKeys {

    fun of(key: Key): String = when (key) {
        Key.POWER -> "KEY_POWER"
        Key.SOURCE -> "KEY_SOURCE"
        Key.UP -> "KEY_UP"
        Key.DOWN -> "KEY_DOWN"
        Key.LEFT -> "KEY_LEFT"
        Key.RIGHT -> "KEY_RIGHT"
        Key.ENTER -> "KEY_ENTER"
        Key.RETURN -> "KEY_RETURN"
        Key.HOME -> "KEY_HOME"
        Key.MENU -> "KEY_MENU"
        Key.EXIT -> "KEY_EXIT"
        Key.VOL_UP -> "KEY_VOLUP"
        Key.VOL_DOWN -> "KEY_VOLDOWN"
        Key.MUTE -> "KEY_MUTE"
        Key.CH_UP -> "KEY_CHUP"
        Key.CH_DOWN -> "KEY_CHDOWN"
        Key.CH_LIST -> "KEY_CH_LIST"
        Key.PRE_CH -> "KEY_PRECH"
        Key.TTX -> "KEY_TTX_MIX"
        Key.GUIDE -> "KEY_GUIDE"
        Key.INFO -> "KEY_INFO"
        Key.TOOLS -> "KEY_TOOLS"
        Key.RED -> "KEY_RED"
        Key.GREEN -> "KEY_GREEN"
        Key.YELLOW -> "KEY_YELLOW"
        Key.BLUE -> "KEY_CYAN"
        Key.PLAY -> "KEY_PLAY"
        Key.PAUSE -> "KEY_PAUSE"
        Key.STOP -> "KEY_STOP"
        Key.REWIND -> "KEY_REWIND"
        Key.FAST_FORWARD -> "KEY_FF"
        Key.D0 -> "KEY_0"
        Key.D1 -> "KEY_1"
        Key.D2 -> "KEY_2"
        Key.D3 -> "KEY_3"
        Key.D4 -> "KEY_4"
        Key.D5 -> "KEY_5"
        Key.D6 -> "KEY_6"
        Key.D7 -> "KEY_7"
        Key.D8 -> "KEY_8"
        Key.D9 -> "KEY_9"
    }
}
