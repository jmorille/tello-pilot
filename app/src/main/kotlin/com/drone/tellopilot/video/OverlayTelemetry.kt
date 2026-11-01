package com.drone.tellopilot.video

import com.drone.tello.control.Units
import com.drone.tellopilot.flightlog.FlightLog
import com.drone.tellopilot.flightlog.FlightSample
import com.drone.tellopilot.hud.HudMath
import com.drone.tellopilot.hud.TrackMapMath
import com.drone.tellopilot.ui.flight.Warning
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

/**
 * Telemetry values shown at one instant. The optional ones are `null` when the flight log does
 * not have them (log stream not received yet, or a column missing from an older file): the
 * matching HUD item is then not drawn.
 */
data class OverlayValues(
    val heightM: Float,
    /** Horizontal speed, m/s. */
    val speedMps: Float,
    /** Climb rate, positive upwards, m/s. */
    val climbMps: Float,
    val batteryPct: Int,
    val flying: Boolean = true,
    /** Flight time counted by the drone, s. */
    val flyTimeS: Float? = null,
    val imuTempC: Float? = null,
    val wifiPct: Int? = null,
    val rollDeg: Float? = null,
    val pitchDeg: Float? = null,
    /** Heading, degrees in `[0, 360)`. */
    val yawDeg: Float? = null,
    /** Drone alerts, most severe first. */
    val warnings: List<Warning> = emptyList(),
    val motorErrorCode: Int = 0,
)

/** Alerts of a flight log sample, as the flight screen showed them. */
object OverlayWarnings {
    /**
     * From the recorded alerts; files without them (v1) fall back to the battery and heat
     * flags, the only alerts they can rebuild. Unknown names (newer app) are ignored.
     */
    fun of(sample: FlightSample): Pair<List<Warning>, Int> {
        var motorCode = 0
        val warnings = sample.alerts?.mapNotNull { token ->
            val name = token.substringBefore(FlightSample.CODE_SEPARATOR)
            val warning = Warning.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            if (warning == Warning.MOTOR_ERROR) motorCode = token.substringAfter(FlightSample.CODE_SEPARATOR, "").toIntOrNull() ?: 0
            warning
        } ?: buildList {
            if (sample.batteryCritical) add(Warning.BATTERY_CRITICAL)
            if (sample.overheating) add(Warning.OVERHEATING)
            if (sample.batteryLow && !sample.batteryCritical) add(Warning.BATTERY_LOW)
        }
        // Same order as the flight screen: by severity, critical ones first.
        return warnings.distinct().sortedBy { it.ordinal }.sortedBy { !it.critical } to motorCode
    }
}

/** One flight log sample on the wall clock. */
data class TimedSample(val epochMs: Long, val values: OverlayValues)

object VideoStartTime {
    /** Maximum delay between pressing "record" (file name) and the first key frame (file start). */
    const val MAX_FIRST_FRAME_DELAY_MS = 3_000L
    /** The first frame may have been queued just before the file (and its name) was created. */
    const val MAX_FIRST_FRAME_ADVANCE_MS = 1_000L
    /** Time zone offsets are whole multiples of 15 minutes. */
    const val ZONE_STEP_MS = 15 * 60_000L
    const val MAX_ZONE_OFFSET_MS = 14 * 3_600_000L
    /** DATE_TAKEN this close to the end of the recording is the MP4 creation time, not the start. */
    const val END_TIME_MARGIN_MS = 5_000L
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
     * Without a recorder name, DATE_TAKEN is the only (rough) source. The name is local time:
     * if the time zone changed since the recording, DATE_TAKEN (UTC epoch, exact) is still
     * recognised when it matches the name up to a whole zone offset.
     */
    fun resolve(nameMs: Long?, dateTakenMs: Long?, durationMs: Long = 0): Long? = resolveDetailed(nameMs, dateTakenMs, durationMs)?.epochMs

    /** Where the retained start time comes from (diagnostic logs). */
    enum class Source {
        /** Exact first-frame time written by the recorder, consistent with the name. */
        DATE_TAKEN,
        /** File name (DATE_TAKEN missing or replaced by the media scanner). */
        NAME,
        /** No recorder name: DATE_TAKEN as is (rough). */
        DATE_TAKEN_ONLY,
    }

    data class Start(val epochMs: Long, val source: Source)

    /** [resolve] with the source of the retained time. */
    fun resolveDetailed(nameMs: Long?, dateTakenMs: Long?, durationMs: Long = 0): Start? {
        if (nameMs == null) return dateTakenMs?.takeIf { it > 0 }?.let { Start(it, Source.DATE_TAKEN_ONLY) }
        val byName = Start(nameMs + NAME_ROUNDING_MS, Source.NAME)
        if (dateTakenMs == null || dateTakenMs <= 0) return byName
        val diff = dateTakenMs - nameMs
        val zoneShift = Math.round(diff.toDouble() / ZONE_STEP_MS) * ZONE_STEP_MS
        val residual = diff - zoneShift
        val consistent = residual in -MAX_FIRST_FRAME_ADVANCE_MS..MAX_FIRST_FRAME_DELAY_MS &&
            kotlin.math.abs(zoneShift) <= MAX_ZONE_OFFSET_MS &&
            // Shifted match that is really the end of the recording (scanner value): reject.
            (zoneShift == 0L || durationMs <= 0 || kotlin.math.abs(dateTakenMs - (nameMs + durationMs)) > END_TIME_MARGIN_MS)
        return if (consistent) Start(dateTakenMs, Source.DATE_TAKEN) else byName
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
    val size: Int get() = samples.size

    /** Samples within `[fromMs, toMs]` (how much of a video the telemetry covers). */
    fun countBetween(fromMs: Long, toMs: Long): Int = samples.count { it.epochMs in fromMs..toMs }

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
        /** Samples come at ~10 Hz: a wider gap is missing data, not to be bridged. */
        const val MAX_GAP_MS = 2_000L
        const val TOLERANCE_MS = 1_000L

        fun of(logs: List<FlightLog>): OverlayTrack = OverlayTrack(
            logs.flatMap { log -> log.samples.map { TimedSample(log.startEpochMs + it.tMs, values(it)) } },
        )

        /** Overlay values of one flight log sample (non-finite readings are dropped). */
        fun values(s: FlightSample): OverlayValues {
            val (warnings, motorCode) = OverlayWarnings.of(s)
            return OverlayValues(
                heightM = s.heightM,
                speedMps = s.horizontalMps,
                climbMps = s.climbMps,
                batteryPct = s.batteryPct,
                flying = s.flying,
                flyTimeS = s.flyTimeS?.finite(),
                imuTempC = s.imuTempC?.finite(),
                wifiPct = s.wifiPct,
                rollDeg = s.rollDeg?.finite(),
                pitchDeg = s.pitchDeg?.finite(),
                yawDeg = s.yawDeg?.finite()?.let(HudMath::heading),
                warnings = warnings,
                motorErrorCode = motorCode,
            )
        }

        private fun Float.finite(): Float? = takeIf { it.isFinite() }

        private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        /** Both known: interpolated; otherwise the value of the nearest sample (may be `null`). */
        private fun lerp(a: Float?, b: Float?, f: Float): Float? =
            if (a != null && b != null) lerp(a, b, f) else if (f < 0.5f) a else b

        private fun <T> nearest(a: T, b: T, f: Float): T = if (f < 0.5f) a else b

        internal fun interpolate(a: OverlayValues, b: OverlayValues, f: Float) = OverlayValues(
            heightM = lerp(a.heightM, b.heightM, f),
            speedMps = lerp(a.speedMps, b.speedMps, f),
            climbMps = lerp(a.climbMps, b.climbMps, f),
            // A battery reading is an integer: the nearest one, never an in-between value.
            batteryPct = nearest(a.batteryPct, b.batteryPct, f),
            flying = nearest(a.flying, b.flying, f),
            flyTimeS = lerp(a.flyTimeS, b.flyTimeS, f),
            imuTempC = lerp(a.imuTempC, b.imuTempC, f),
            wifiPct = nearest(a.wifiPct, b.wifiPct, f),
            // A flip crosses ±180°: the short way round too, never back through level flight.
            rollDeg = if (a.rollDeg != null && b.rollDeg != null) HudMath.lerpSignedAngle(a.rollDeg, b.rollDeg, f) else nearest(a.rollDeg, b.rollDeg, f),
            pitchDeg = lerp(a.pitchDeg, b.pitchDeg, f),
            // The short way round: 359° -> 1° goes through 0°.
            yawDeg = if (a.yawDeg != null && b.yawDeg != null) HudMath.lerpAngle(a.yawDeg, b.yawDeg, f) else nearest(a.yawDeg, b.yawDeg, f),
            warnings = nearest(a.warnings, b.warnings, f),
            motorErrorCode = nearest(a.motorErrorCode, b.motorErrorCode, f),
        )
    }
}

/**
 * Visual-odometry track of the flights for the mini-map, built like the flight screen's: valid
 * positions in flight only, a point every [TrackMapMath.STEP_M], restarted at each takeoff, at
 * most [TrackMapMath.MAX_POINTS]. [at] gives the part of it known at an instant of the video.
 */
class OverlayMapTrack private constructor(
    private val epochMs: LongArray,
    private val xs: FloatArray,
    private val ys: FloatArray,
    /** Index of the first point of the flight each point belongs to. */
    private val segmentStart: IntArray,
    /** Wall-clock instants of the takeoffs, sorted: the track restarts at each one. */
    private val takeoffs: LongArray,
) {
    /** Points `from..to` (inclusive) of the track, as shown at one instant. */
    data class Range(val from: Int, val to: Int) {
        val count: Int get() = to - from + 1
    }

    val size: Int get() = epochMs.size

    fun x(i: Int): Float = xs[i]

    fun y(i: Int): Float = ys[i]

    /**
     * Track of the current flight up to [atEpochMs], `null` before its first point (the track
     * of a previous flight is never shown after a new takeoff, as on screen).
     */
    fun at(atEpochMs: Long): Range? {
        val to = lastAtOrBefore(epochMs.size, atEpochMs) { epochMs[it] } - 1
        if (to < 0) return null
        val takeoff = lastAtOrBefore(takeoffs.size, atEpochMs) { takeoffs[it] } - 1
        if (takeoff >= 0 && epochMs[to] < takeoffs[takeoff]) return null
        return Range(maxOf(segmentStart[to], to - TrackMapMath.MAX_POINTS + 1), to)
    }

    /** Number of sorted values at or before [t] (binary search). */
    private inline fun lastAtOrBefore(size: Int, t: Long, value: (Int) -> Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (value(mid) <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = OverlayMapTrack(LongArray(0), FloatArray(0), FloatArray(0), IntArray(0), LongArray(0))

        fun of(logs: List<FlightLog>): OverlayMapTrack {
            val times = ArrayList<Long>()
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            val starts = ArrayList<Int>()
            val takeoffs = ArrayList<Long>()
            for (log in logs.sortedBy { it.startEpochMs }) {
                var start = times.size
                var wasFlying = false
                for (s in log.samples.sortedBy { it.tMs }) {
                    // A takeoff restarts the track (the flight screen clears it).
                    if (s.flying && !wasFlying) {
                        start = times.size
                        takeoffs += log.startEpochMs + s.tMs
                    }
                    wasFlying = s.flying
                    val x = s.odoX ?: continue
                    val y = s.odoY ?: continue
                    if (!s.flying || s.odoValid != true || !x.isFinite() || !y.isFinite()) continue
                    val epoch = log.startEpochMs + s.tMs
                    // Logs overlapping on the wall clock (clock change): the lookup stays sorted.
                    if (times.isNotEmpty() && epoch < times.last()) continue
                    val last = times.size - 1
                    if (last >= start && !TrackMapMath.isNewPoint(xs[last], ys[last], x, y)) continue
                    times += epoch
                    xs += x
                    ys += y
                    starts += start
                }
            }
            return OverlayMapTrack(
                times.toLongArray(), xs.toFloatArray(), ys.toFloatArray(), starts.toIntArray(), takeoffs.sorted().toLongArray(),
            )
        }
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
