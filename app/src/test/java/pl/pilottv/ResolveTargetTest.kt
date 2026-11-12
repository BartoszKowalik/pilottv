package pl.pilottv

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.pilottv.data.AtvConfig
import pl.pilottv.data.TvConfig
import pl.pilottv.remote.Target

class ResolveTargetTest {

    private val tv = TvConfig(ip = "192.168.1.10", name = null, mac = null, token = null, certPin = null)
    private val atv = AtvConfig(ip = "192.168.1.20", name = null, certPin = null)

    private fun config(tv: TvConfig?, atv: AtvConfig?) = ConfigState(loaded = true, tv = tv, atv = atv)

    @Test
    fun nothingConfigured() {
        assertEquals(Target.TV, resolveTarget(Target.TV, config(null, null)))
        assertEquals(Target.TV, resolveTarget(Target.DECODER, config(null, null)))
    }

    @Test
    fun onlyTv() {
        assertEquals(Target.TV, resolveTarget(Target.TV, config(tv, null)))
        assertEquals(Target.TV, resolveTarget(Target.DECODER, config(tv, null)))
    }

    @Test
    fun onlyDecoder() {
        assertEquals(Target.DECODER, resolveTarget(Target.TV, config(null, atv)))
        assertEquals(Target.DECODER, resolveTarget(Target.DECODER, config(null, atv)))
    }

    @Test
    fun both() {
        assertEquals(Target.TV, resolveTarget(Target.TV, config(tv, atv)))
        assertEquals(Target.DECODER, resolveTarget(Target.DECODER, config(tv, atv)))
    }
}
