package pl.pilottv.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Krótkie „kliknięcie” wibracją przy każdym przycisku. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    private val effect: VibrationEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    } else {
        VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    fun click() {
        val v = vibrator ?: return
        if (v.hasVibrator()) v.vibrate(effect)
    }
}
