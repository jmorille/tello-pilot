package com.drone.tellopilot.video

import com.drone.tello.control.Units
import com.drone.tellopilot.flightlog.FlightLog
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure parts of the telemetry overlay burnt into re-encoded videos (no Android): video start
 * time, flight log selection, sample lookup / interpolation and text. Unit tested on the JVM.
 *
 * Time alignment: every video frame is placed on the wall clock as
 * `video start (epoch ms) + presentation time`, and every flight log sample as
 * `log start (epoch ms) + t_ms`; the overlay shows the telemetry at that instant.
 */

/** Telemetry values shown at one instant. */
data class OverlayValues(
    val heightM: Float,
    /** Horizontal speed, m/s. */
    val speedMps: Float,
    /** Climb rate, positive upwards, m/s. */
    val climbMps: Float,
    val batteryPct: Int,
)

/** One flight log sample on the wall clock. */
data class TimedSample(val epochMs: Long, val values: OverlayValues)

object VideoStartTime {
    /** Maximum delay between pressing "record" (file name) and the first key frame (file start). */
    const val MAX_FIRST_FRAME_DELAY_MS = 3_000L
    /** Without an exact start, the second of the file name is truncated: assume its middle. */
    const val NAME_ROUNDING_MS = 500L

    private val NAME = Regex("""tello_(\d{8}_\d{6})""")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    /**
     * Epoch ms written in a recorder file name (`tello_yyyyMMdd_HHmmss[-suffix].mp4`, phone
     * local time when recording was started), `null` if the name is not one.
     */
    fun fromName(name: String, zone: ZoneId): Long? {
        val stamp = NAME.find(name)?.groupValues?.get(1) ?: return null
        return try {
            LocalDateTime.parse(stamp, FORMAT).atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Wall-clock time of the first frame (presentation time 0). The recorder stores the exact
     * first-frame time in MediaStore DATE_TAKEN, but the media scanner may replace it with the
     * MP4 creation time (written when the file is closed, i.e. the END of the recording) or
     * the insertion time. So DATE_TAKEN is trusted only when it is consistent with the file
     * name (between the "record" press and a few seconds after); otherwise the name is used.
     * Without a recorder name, DATE_TAKEN is the only (rough) source.
     */
    fun resolve(nameMs: Long?, dateTakenMs: Long?): Long? = when {
        nameMs == null -> dateTakenMs?.takeIf { it > 0 }
        dateTakenMs != null && dateTakenMs >= nameMs && dateTakenMs - nameMs <= MAX_FIRST_FRAME_DELAY_MS -> dateTakenMs
        else -> nameMs + NAME_ROUNDING_MS
    }
}

/** Flight log ranges that may overlap a video. */
object FlightLogMatch {
    /** Slack around a flight's recorded range (landing tail, clock jitter). */
    const val SLACK_MS = 60_000L

    data class Range(val id: String, val startEpochMs: Long, val durationMs: Long)

    /** Ids of the flights whose range ([SLACK_MS] wider) overlaps `[videoStart, videoEnd]`. */
    fun overlapping(flights: List<Range>, videoStartMs: Long, videoEndMs: Long): List<String> = flights.filter {
        it.startEpochMs - SLACK_MS <= videoEndMs && it.startEpochMs + it.durationMs + SLACK_MS >= videoStartMs
    }.map { it.id }
}

/**
 * Telemetry samples of one or more flights, sorted on the wall clock; [at] looks a time up
 * (binary search, no allocation besides the result). Built once per video.
 */
class OverlayTrack(samples: List<TimedSample>) {
    private val samples = samples.sortedBy { it.epochMs }

    val isEmpty: Boolean get() = samples.isEmpty()

    /**
     * Values at [epochMs]: linear interpolation between the two surrounding samples when they
     * are at most [MAX_GAP_MS] apart; otherwise the nearest sample if within [TOLERANCE_MS];
     * `null` outside the flights (before the takeoff, after the log, link lost).
     */
    fun at(epochMs: Long): OverlayValues? {
        if (samples.isEmpty()) return null
        // First index with time > epochMs.
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].epochMs <= epochMs) lo = mid + 1 else hi = mid
        }
        val before = samples.getOrNull(lo - 1)
        val after = samples.getOrNull(lo)
        if (before != null && after != null && after.epochMs - before.epochMs <= MAX_GAP_MS) {
            val span = after.epochMs - before.epochMs
            val f = if (span <= 0) 0f else (epochMs - before.epochMs).toFloat() / span
            return interpolate(before.values, after.values, f)
        }
        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.epochMs - epochMs) } ?: return null
        return nearest.values.takeIf { abs(nearest.epochMs - epochMs) <= TOLERANCE_MS }
    }

    companion object {
        /** Samples come at ~5 Hz: a wider gap is missing data, not to be bridged. */
        const val MAX_GAP_MS = 2_000L
        const val TOLERANCE_MS = 1_000L

        fun of(logs: List<FlightLog>): OverlayTrack = OverlayTrack(
            logs.flatMap { log ->
                log.samples.map {
                    TimedSample(log.startEpochMs + it.tMs, OverlayValues(it.heightM, it.horizontalMps, it.climbMps, it.batteryPct))
                }
            },
        )

        private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        internal fun interpolate(a: OverlayValues, b: OverlayValues, f: Float) = OverlayValues(
            heightM = lerp(a.heightM, b.heightM, f),
            speedMps = lerp(a.speedMps, b.speedMps, f),
            climbMps = lerp(a.climbMps, b.climbMps, f),
            // A battery reading is an integer: the nearest one, never an in-between value.
            batteryPct = if (f < 0.5f) a.batteryPct else b.batteryPct,
        )
    }
}

/** Text lines of the overlay box. */
object OverlayText {
    const val FEET_PER_METER = 3.28084f
    const val MPH_PER_MS = 2.23694f
    /** Climb rates below this are noise: no arrow. */
    const val VARIO_DEADBAND = 0.1f

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    /** Time of day, then (when known) height and speed, climb rate and battery. */
    fun lines(epochMs: Long, values: OverlayValues?, units: Units, zone: ZoneId, locale: Locale): List<String> {
        val time = CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(zone))
        if (values == null) return listOf(time)
        val imperial = units == Units.IMPERIAL
        val height = if (imperial) {
            "H ${(values.heightM * FEET_PER_METER).roundToInt()} ft"
        } else {
            "H ${String.format(locale, "%.1f", values.heightM)} m"
        }
        val speed = if (imperial) {
            "V ${String.format(locale, "%.1f", values.speedMps * MPH_PER_MS)} mph"
        } else {
            "V ${String.format(locale, "%.1f", values.speedMps)} m/s"
        }
        val arrow = when {
            values.climbMps >= VARIO_DEADBAND -> "↑"
            values.climbMps <= -VARIO_DEADBAND -> "↓"
            else -> "↕"
        }
        val rate = abs(values.climbMps).let { if (imperial) it * MPH_PER_MS else it }
        val climb = "$arrow ${String.format(locale, "%.1f", rate)} ${if (imperial) "mph" else "m/s"}"
        return listOf(time, "$height   $speed", "$climb   Bat. ${values.batteryPct.coerceIn(0, 100)} %")
    }
}
