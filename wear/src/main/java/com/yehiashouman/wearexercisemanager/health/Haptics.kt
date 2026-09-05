package com.yehiashouman.wearexercisemanager.health

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Wraps the watch vibrator so the "Vibration" setting has an effect on the workout: every stage
 * change is confirmed haptically, which is the only feedback available when the screen is off.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    /** Short confirmation used when a new set starts. */
    fun tick() = play(longArrayOf(0, 90))

    /** Two pulses so rest is distinguishable from work without looking at the watch. */
    fun rest() = play(longArrayOf(0, 70, 110, 70))

    /** Longer pattern marking the end of the workout. */
    fun finish() = play(longArrayOf(0, 120, 100, 120, 100, 220))

    private fun play(pattern: LongArray) {
        val target = vibrator ?: return
        runCatching { target.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
            .onFailure { Log.w(TAG, "Could not play vibration pattern", it) }
    }

    private companion object {
        const val TAG = "Haptics"
    }
}
