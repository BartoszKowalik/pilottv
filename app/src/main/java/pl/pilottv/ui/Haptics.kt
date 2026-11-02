package pl.pilottv.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import pl.pilottv.data.HapticLevel

/** Wibracja przy przycisku. Siła zależy od [HapticLevel]. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true

    private fun oneShot(ms: Long, amplitude: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, if (amplitudeControl) amplitude else VibrationEffect.DEFAULT_AMPLITUDE)

    // Predefiniowany EFFECT_CLICK jest na wielu telefonach ledwo wyczuwalny,
    // dlatego tylko poziom LIGHT go używa. Pozostałe poziomy mają jawny czas i siłę.
    private val effects: Map<HapticLevel, VibrationEffect> = mapOf(
        HapticLevel.LIGHT to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            oneShot(15, 120)
        },
        HapticLevel.MEDIUM to oneShot(30, 190),
        HapticLevel.STRONG to oneShot(50, 255),
    )

    fun click(level: HapticLevel) {
        val v = vibrator ?: return
        val effect = effects[level] ?: return
        if (v.hasVibrator()) v.vibrate(effect)
    }
}
