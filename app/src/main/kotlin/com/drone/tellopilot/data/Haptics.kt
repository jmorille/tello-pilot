package com.drone.tellopilot.data

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vibrations, so that confirmations and alerts are felt without looking at the screen.
 * Alerts use the alarm usage: they are not muted with the touch feedback setting.
 */
@Singleton
class Haptics @Inject constructor(@ApplicationContext context: Context) {

    private val vibrator: Vibrator? = defaultVibrator(context)?.takeIf { it.hasVibrator() }

    /** Takeoff / landing accepted. */
    fun confirm() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK), alarm = false)

    /** Non-critical alert (low battery, phone getting warm…): two short pulses. */
    fun warning() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 110, 90), -1), alarm = true)

    /** Critical alert or link lost: three long pulses. */
    fun critical() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 350), -1), alarm = true)

    private fun vibrate(effect: VibrationEffect, alarm: Boolean) {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val usage = if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_TOUCH
                v.vibrate(effect, VibrationAttributes.createForUsage(usage))
            } else {
                v.vibrate(effect)
            }
        }
    }

    private companion object {
        fun defaultVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
    }
}
