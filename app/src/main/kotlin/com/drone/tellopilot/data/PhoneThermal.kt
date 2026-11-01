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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.Dispatchers
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
    }.flowOn(Dispatchers.IO) // binder call to the thermal HAL

    /** Hysteresis on the forecast: warm from 0.9, normal again only below 0.8 (no flapping alert). */
    private val warmForecast: Flow<Boolean> = headroom.scan(false) { warm, h ->
        when {
            h.isNaN() -> warm
            warm -> h >= COOL_HEADROOM
            else -> h >= WARM_HEADROOM
        }
    }

    val heat: Flow<PhoneHeat> = combine(status, warmForecast) { s, warm ->
        when {
            s >= PowerManager.THERMAL_STATUS_SEVERE -> PhoneHeat.HOT
            s >= PowerManager.THERMAL_STATUS_MODERATE || warm -> PhoneHeat.WARM
            else -> PhoneHeat.NORMAL
        }
    }.distinctUntilChanged()

    private companion object {
        const val FORECAST_SECONDS = 10
        const val POLL_MS = 10_000L
        /** Close to the throttling point (1.0). */
        const val WARM_HEADROOM = 0.9f
        const val COOL_HEADROOM = 0.8f
    }
}
