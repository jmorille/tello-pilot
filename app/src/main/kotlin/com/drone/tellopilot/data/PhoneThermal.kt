package com.drone.tellopilot.data

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** How hot the phone itself is (the Tensor SoC throttles in the sun). */
enum class PhoneHeat { NORMAL, WARM, HOT }

/**
 * Phone temperature from Android's thermal API: the status listener (current throttling) and
 * the headroom forecast (how close the SoC is to throttling in the next seconds).
 */
@Singleton
class PhoneThermal @Inject constructor(@ApplicationContext private val context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    private val status: Flow<Int> = callbackFlow {
        val listener = PowerManager.OnThermalStatusChangedListener { trySend(it) }
        trySend(power.currentThermalStatus)
        power.addThermalStatusListener(context.mainExecutor, listener)
        awaitClose { power.removeThermalStatusListener(listener) }
    }

    /** Headroom forecast 10 s ahead: 1.0 = throttling. Polled slowly (the API is rate-limited). */
    private val headroom: Flow<Float> = flow {
        while (true) {
            val value = runCatching { power.getThermalHeadroom(FORECAST_SECONDS) }.getOrDefault(Float.NaN)
            emit(value)
            delay(POLL_MS)
        }
    }

    val heat: Flow<PhoneHeat> = combine(status, headroom) { s, h ->
        when {
            s >= PowerManager.THERMAL_STATUS_SEVERE -> PhoneHeat.HOT
            s >= PowerManager.THERMAL_STATUS_MODERATE || (!h.isNaN() && h >= WARM_HEADROOM) -> PhoneHeat.WARM
            else -> PhoneHeat.NORMAL
        }
    }.distinctUntilChanged()

    private companion object {
        const val FORECAST_SECONDS = 10
        const val POLL_MS = 10_000L
        /** Close to the throttling point (1.0). */
        const val WARM_HEADROOM = 0.9f
    }
}
