package pl.pilottv.remote

/** Zdarzenia pola tekstowego (klawiatury ekranowej) na TV lub dekoderze. */
sealed interface ImeEvent {
    /** Na urządzeniu otworzyło się pole tekstowe. [text] = obecna zawartość, jeśli urządzenie ją podało. */
    data class Opened(val text: String?) : ImeEvent

    /** Zawartość pola na urządzeniu się zmieniła. */
    data class Updated(val text: String) : ImeEvent

    data object Closed : ImeEvent
}

/** Skrót do aplikacji na dekoderze: nazwa pakietu Android albo link. */
data class AppShortcut(val name: String, val app: String) {
    companion object {
        val DEFAULTS = listOf(
            AppShortcut("Netflix", "com.netflix.ninja"),
            AppShortcut("YouTube", "com.google.android.youtube.tv"),
            AppShortcut("Disney+", "com.disney.disneyplus"),
            AppShortcut("Prime Video", "com.amazon.amazonvideo.livingroom"),
            AppShortcut("Max", "com.wbd.stream"),
            AppShortcut("Spotify", "com.spotify.tv.android"),
        )
    }
}
