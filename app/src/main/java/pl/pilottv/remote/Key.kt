package pl.pilottv.remote

/** Przycisk pilota niezależny od urządzenia. Każde urządzenie tłumaczy go na własny kod. */
enum class Key {
    POWER, SOURCE,
    UP, DOWN, LEFT, RIGHT, ENTER,
    RETURN, HOME, MENU, EXIT,
    VOL_UP, VOL_DOWN, MUTE,
    CH_UP, CH_DOWN, CH_LIST, PRE_CH,
    TTX, GUIDE, INFO, TOOLS,
    RED, GREEN, YELLOW, BLUE,
    PLAY, PAUSE, STOP, REWIND, FAST_FORWARD,
    D0, D1, D2, D3, D4, D5, D6, D7, D8, D9;

    companion object {
        fun digit(n: Int): Key = valueOf("D$n")

        val VOLUME = setOf(VOL_UP, VOL_DOWN, MUTE)
    }
}

/** Urządzenie, do którego idą przyciski. */
enum class Target { TV, DECODER }
