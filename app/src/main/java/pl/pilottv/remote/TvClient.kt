package pl.pilottv.remote

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import pl.pilottv.data.TvConfig

/** Dane do zapisania po połączeniu z TV. Null = bez zmian. */
data class TvUpdate(
    val token: String? = null,
    val certPin: String? = null,
    val mac: String? = null,
)

/** Klient telewizora (Samsung Tizen albo LG webOS). Jedna sesja = jedno połączenie. */
interface TvClient {
    val state: StateFlow<TvState>
    val ime: SharedFlow<ImeEvent>

    /** Unieważnia bieżącą sesję. */
    fun markIdle()

    /** Otwiera połączenie i czeka, aż się zakończy. Anulowanie korutyny zamyka połączenie. */
    suspend fun session(config: TvConfig, onUpdate: (TvUpdate) -> Unit): SessionResult

    fun sendKey(key: Key): Boolean

    /** Zastępuje cały tekst w aktywnym polu tekstowym. */
    fun sendText(text: String): Boolean

    /** Zatwierdza tekst w polu tekstowym. */
    fun endInput(): Boolean
}
