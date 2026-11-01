package com.drone.tellopilot.data

/** The monotonic clocks of [android.os.SystemClock], replaced by a virtual clock in the JVM tests. */
internal interface MonotonicClock {
    /** Milliseconds since boot, deep sleep included. */
    fun elapsedRealtime(): Long

    /** Milliseconds since boot, deep sleep excluded. */
    fun uptimeMillis(): Long

    object System : MonotonicClock {
        override fun elapsedRealtime() = android.os.SystemClock.elapsedRealtime()
        override fun uptimeMillis() = android.os.SystemClock.uptimeMillis()
    }
}
