package pl.pilottv.lg

import pl.pilottv.remote.Key

/** Komenda LG: przycisk przez gniazdo „pointer” albo żądanie SSAP. */
sealed interface LgCommand {
    data class Button(val name: String) : LgCommand
    data class Ssap(val uri: String, val payload: Map<String, Any>? = null) : LgCommand
}

object LgKeys {

    fun of(key: Key): LgCommand = when (key) {
        Key.POWER -> LgCommand.Ssap("ssap://system/turnOff")
        Key.SOURCE -> LgCommand.Ssap("ssap://system.launcher/launch", mapOf("id" to "com.webos.app.inputpicker"))
        Key.UP -> LgCommand.Button("UP")
        Key.DOWN -> LgCommand.Button("DOWN")
        Key.LEFT -> LgCommand.Button("LEFT")
        Key.RIGHT -> LgCommand.Button("RIGHT")
        Key.ENTER -> LgCommand.Button("ENTER")
        Key.RETURN -> LgCommand.Button("BACK")
        Key.HOME -> LgCommand.Button("HOME")
        Key.MENU -> LgCommand.Button("MENU")
        Key.EXIT -> LgCommand.Button("EXIT")
        Key.VOL_UP -> LgCommand.Button("VOLUMEUP")
        Key.VOL_DOWN -> LgCommand.Button("VOLUMEDOWN")
        Key.MUTE -> LgCommand.Button("MUTE")
        Key.CH_UP -> LgCommand.Button("CHANNELUP")
        Key.CH_DOWN -> LgCommand.Button("CHANNELDOWN")
        Key.CH_LIST -> LgCommand.Button("LIST")
        Key.PRE_CH -> LgCommand.Button("FLASHBACK")
        Key.TTX -> LgCommand.Button("TELETEXT")
        Key.GUIDE -> LgCommand.Button("GUIDE")
        Key.INFO -> LgCommand.Button("INFO")
        Key.TOOLS -> LgCommand.Button("QMENU")
        Key.RED -> LgCommand.Button("RED")
        Key.GREEN -> LgCommand.Button("GREEN")
        Key.YELLOW -> LgCommand.Button("YELLOW")
        Key.BLUE -> LgCommand.Button("BLUE")
        Key.PLAY -> LgCommand.Button("PLAY")
        Key.PAUSE -> LgCommand.Button("PAUSE")
        Key.STOP -> LgCommand.Button("STOP")
        Key.REWIND -> LgCommand.Button("REWIND")
        Key.FAST_FORWARD -> LgCommand.Button("FASTFORWARD")
        Key.D0 -> LgCommand.Button("0")
        Key.D1 -> LgCommand.Button("1")
        Key.D2 -> LgCommand.Button("2")
        Key.D3 -> LgCommand.Button("3")
        Key.D4 -> LgCommand.Button("4")
        Key.D5 -> LgCommand.Button("5")
        Key.D6 -> LgCommand.Button("6")
        Key.D7 -> LgCommand.Button("7")
        Key.D8 -> LgCommand.Button("8")
        Key.D9 -> LgCommand.Button("9")
    }

    /** Zapasowe żądania SSAP, gdy gniazdo „pointer” jeszcze nie działa. */
    fun fallback(key: Key): LgCommand.Ssap? = when (key) {
        Key.VOL_UP -> LgCommand.Ssap("ssap://audio/volumeUp")
        Key.VOL_DOWN -> LgCommand.Ssap("ssap://audio/volumeDown")
        Key.CH_UP -> LgCommand.Ssap("ssap://tv/channelUp")
        Key.CH_DOWN -> LgCommand.Ssap("ssap://tv/channelDown")
        Key.PLAY -> LgCommand.Ssap("ssap://media.controls/play")
        Key.PAUSE -> LgCommand.Ssap("ssap://media.controls/pause")
        Key.STOP -> LgCommand.Ssap("ssap://media.controls/stop")
        Key.REWIND -> LgCommand.Ssap("ssap://media.controls/rewind")
        Key.FAST_FORWARD -> LgCommand.Ssap("ssap://media.controls/fastForward")
        else -> null
    }
}
