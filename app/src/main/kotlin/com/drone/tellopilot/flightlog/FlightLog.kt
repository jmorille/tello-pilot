package com.drone.tellopilot.flightlog

import com.drone.tello.control.Telemetry
import com.drone.tellopilot.ui.flight.Warning
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pure parts of the flight recorder (no Android): sample model, sampling state machine,
 * CSV format, per-flight summary, statistics and storage cap. Unit tested on the JVM.
 */

/**
 * One telemetry sample (~10 Hz). Log-stream fields are `null` until the drone sends them, and
 * the fields added after v1 of the file format are `null` when read from an older file.
 */
data class FlightSample(
    /** Offset from the takeoff, ms. */
    val tMs: Long,
    val heightM: Float,
    val northMps: Float,
    val eastMps: Float,
    /** Climb rate, positive upwards. */
    val climbMps: Float,
    val batteryPct: Int,
    val flying: Boolean,
    val rollDeg: Float? = null,
    val pitchDeg: Float? = null,
    val yawDeg: Float? = null,
    val odoX: Float? = null,
    val odoY: Float? = null,
    val odoZ: Float? = null,
    val odoValid: Boolean? = null,
    val imuTempC: Float? = null,
    val wifiPct: Int? = null,
    val batteryLow: Boolean = false,
    val batteryCritical: Boolean = false,
    val overheating: Boolean = false,
    /** Flight time counted by the drone, s (flight screen timer). */
    val flyTimeS: Float? = null,
    /**
     * Alerts raised by the drone's own state ([Warning.from]), as [Warning] names; the motor
     * error carries its code (`MOTOR_ERROR:231`). Empty: none; `null`: not recorded (old file).
     */
    val alerts: List<String>? = null,
) {
    val horizontalMps: Float get() = hypot(northMps, eastMps)

    companion object {
        /** `null` when the telemetry has no flight data yet. */
        fun from(tMs: Long, t: Telemetry): FlightSample? {
            val f = t.flight ?: return null
            val odo = t.odometry
            return FlightSample(
                tMs = tMs,
                heightM = f.heightMeters,
                northMps = f.northSpeed,
                eastMps = f.eastSpeed,
                climbMps = f.climbRate,
                batteryPct = f.batteryPercent,
                flying = f.flying,
                rollDeg = t.attitude?.rollDeg,
                pitchDeg = t.attitude?.pitchDeg,
                yawDeg = t.attitude?.yawDeg,
                odoX = odo?.x,
                odoY = odo?.y,
                odoZ = odo?.z,
                odoValid = odo?.positionValid,
                imuTempC = t.imuTemperatureC,
                wifiPct = t.wifi?.strength,
                batteryLow = f.batteryLow,
                batteryCritical = f.batteryCritical,
                overheating = f.overheating,
                flyTimeS = f.flyTimeSeconds.takeIf { it.isFinite() },
                alerts = Warning.from(f, t).map {
                    if (it == Warning.MOTOR_ERROR) "${it.name}$CODE_SEPARATOR${f.motorErrorCode}" else it.name
                },
            )
        }

        /** Between an alert name and its code in [alerts]. */
        const val CODE_SEPARATOR = ':'
    }
}

/**
 * Decides when a flight starts, when to sample and when it ends. Fed by a periodic tick with
 * the latest telemetry (`null` = no live link). Monotonic clock in ms.
 *
 * - starts when the drone reports `flying`;
 * - ends [tailMs] after it reports landed (a new takeoff within the tail continues the flight),
 *   or after [noDataTimeoutMs] without telemetry (link lost, never reconnected).
 */
class FlightSampler(
    private val intervalMs: Long = INTERVAL_MS,
    private val tailMs: Long = TAIL_MS,
    private val noDataTimeoutMs: Long = NO_DATA_TIMEOUT_MS,
) {
    data class Tick(val start: Boolean = false, val sample: FlightSample? = null, val stop: Boolean = false)

    private var startedAt: Long? = null
    private var lastSampleAt = Long.MIN_VALUE / 2
    private var lastDataAt = 0L
    private var landedSince: Long? = null

    val recording: Boolean get() = startedAt != null

    fun onTick(now: Long, telemetry: Telemetry?): Tick {
        val started = startedAt
        val flight = telemetry?.flight
        if (started == null) {
            if (flight?.flying != true) return NOTHING
            startedAt = now
            lastDataAt = now
            lastSampleAt = now
            landedSince = null
            return Tick(start = true, sample = FlightSample.from(0, telemetry))
        }
        if (flight == null) {
            if (now - lastDataAt >= noDataTimeoutMs) {
                reset()
                return Tick(stop = true)
            }
            return NOTHING
        }
        lastDataAt = now
        // Tolerates a late tick: never two samples closer than 3/4 of the interval.
        val due = now - lastSampleAt >= intervalMs * 3 / 4
        val sample = if (due) FlightSample.from(now - started, telemetry).also { lastSampleAt = now } else null
        if (flight.flying) {
            landedSince = null
        } else {
            val since = landedSince ?: now.also { landedSince = it }
            if (now - since >= tailMs) {
                reset()
                return Tick(sample = sample, stop = true)
            }
        }
        return Tick(sample = sample)
    }

    private fun reset() {
        startedAt = null
        landedSince = null
    }

    companion object {
        /**
         * 10 Hz, about the rate of the drone's flight data and log stream (the tick is not
         * synchronised with them, so a row may repeat a reading): the attitude replayed in the
         * video overlay follows quick rolls and flips much better than at 5 Hz, where linear
         * interpolation cuts their corners. About 85 bytes a row, i.e. ~50 KB a
         * minute: ~0.65 MB for a full 13-minute battery. [FlightRetention.MAX_BYTES] still
         * holds 75 such flights, or the [FlightRetention.MAX_FLIGHTS] of 5 minutes.
         */
        const val INTERVAL_MS = 100L
        const val TAIL_MS = 5_000L
        const val NO_DATA_TIMEOUT_MS = 60_000L
        private val NOTHING = Tick()
    }
}

/** Per-flight figures, shown in the list and cached in the file's footer. */
data class FlightSummary(
    /** Time with the drone flying, ms. */
    val durationMs: Long,
    val maxHeightM: Float,
    /** Horizontal speed, m/s. */
    val maxSpeedMps: Float,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val samples: Int,
    /** At least one sample with a valid visual odometry position. */
    val hasTrack: Boolean,
) {
    /** Battery % used during the flight (never negative: the reading may go up a bit at rest). */
    val batteryUsed: Int? get() = if (batteryStart != null && batteryEnd != null) max(0, batteryStart - batteryEnd) else null
}

/** Builds a [FlightSummary] incrementally (recorder) or from a parsed file. */
class SummaryAccumulator {
    private var firstFlying: Long? = null
    private var lastFlying: Long? = null
    private var maxHeight = 0f
    private var maxSpeed = 0f
    private var batteryStart: Int? = null
    private var batteryEnd: Int? = null
    private var samples = 0
    private var hasTrack = false

    fun add(s: FlightSample) {
        samples++
        if (s.flying) {
            if (firstFlying == null) firstFlying = s.tMs
            lastFlying = s.tMs
        }
        maxHeight = max(maxHeight, s.heightM)
        maxSpeed = max(maxSpeed, s.horizontalMps)
        if (batteryStart == null) batteryStart = s.batteryPct
        batteryEnd = s.batteryPct
        if (s.odoValid == true && s.odoX != null && s.odoY != null) hasTrack = true
    }

    fun build() = FlightSummary(
        durationMs = (lastFlying ?: 0) - (firstFlying ?: 0),
        maxHeightM = maxHeight,
        maxSpeedMps = maxSpeed,
        batteryStart = batteryStart,
        batteryEnd = batteryEnd,
        samples = samples,
        hasTrack = hasTrack,
    )

    companion object {
        fun of(samples: List<FlightSample>) = SummaryAccumulator().apply { samples.forEach(::add) }.build()
    }
}

/** A flight file read back. [summary] comes from the footer, or is computed if it is missing. */
data class FlightLog(
    val startEpochMs: Long,
    val samples: List<FlightSample>,
    val summary: FlightSummary,
    /** No footer: the app was killed / crashed during the flight. */
    val interrupted: Boolean,
)

/**
 * File format (UTF-8, LF, one file per flight):
 * ```
 * # tellopilot-flight v2 start=<epoch ms>
 * t_ms,height_m,north_mps,east_mps,climb_mps,battery_pct,flying,roll_deg,…
 * 0,0.30,0.00,0.00,0.10,87,1,,,,,,,,,90,0,0,0,1.2,BATTERY_LOW
 * …
 * # summary duration_ms=… max_height_m=… …
 * ```
 * Comment lines start with `#`; empty cells are unknown values; booleans are 1 / 0. Rows are
 * appended and flushed one by one, so a file cut by a crash is still readable (the truncated
 * last row is skipped, the summary is recomputed).
 *
 * Columns are read by name, so files of any version read back: a column missing from an older
 * file gives `null` values. v2 added `fly_time_s` and `alerts` (`;`-separated names, see
 * [FlightSample.alerts]) at the end of the row.
 */
object FlightLogCsv {
    const val VERSION = 2
    private const val MAGIC = "# tellopilot-flight"
    private const val SUMMARY = "# summary"

    val COLUMNS = listOf(
        "t_ms", "height_m", "north_mps", "east_mps", "climb_mps", "battery_pct", "flying",
        "roll_deg", "pitch_deg", "yaw_deg", "odo_x_m", "odo_y_m", "odo_z_m", "odo_valid",
        "imu_temp_c", "wifi_pct", "battery_low", "battery_critical", "overheating",
        "fly_time_s", "alerts",
    )

    private const val ALERT_SEPARATOR = ';'

    fun header(startEpochMs: Long): String = "$MAGIC v$VERSION start=$startEpochMs\n${COLUMNS.joinToString(",")}\n"

    fun row(s: FlightSample): String = buildString(96) {
        append(s.tMs).append(',')
        f(s.heightM).append(',')
        f(s.northMps).append(',')
        f(s.eastMps).append(',')
        f(s.climbMps).append(',')
        append(s.batteryPct).append(',')
        b(s.flying).append(',')
        f(s.rollDeg, 1).append(',')
        f(s.pitchDeg, 1).append(',')
        f(s.yawDeg, 1).append(',')
        f(s.odoX).append(',')
        f(s.odoY).append(',')
        f(s.odoZ).append(',')
        s.odoValid?.let { b(it) }
        append(',')
        f(s.imuTempC, 1).append(',')
        s.wifiPct?.let { append(it) }
        append(',')
        b(s.batteryLow).append(',')
        b(s.batteryCritical).append(',')
        b(s.overheating).append(',')
        f(s.flyTimeS, 1).append(',')
        s.alerts?.forEachIndexed { i, name ->
            if (i > 0) append(ALERT_SEPARATOR)
            append(name)
        }
        append('\n')
    }

    fun footer(summary: FlightSummary, interrupted: Boolean = false): String = buildString {
        append(SUMMARY)
        append(" duration_ms=").append(summary.durationMs)
        append(" max_height_m=").append(fmt(summary.maxHeightM, 2))
        append(" max_speed_mps=").append(fmt(summary.maxSpeedMps, 2))
        summary.batteryStart?.let { append(" battery_start=").append(it) }
        summary.batteryEnd?.let { append(" battery_end=").append(it) }
        append(" samples=").append(summary.samples)
        append(" track=").append(if (summary.hasTrack) 1 else 0)
        if (interrupted) append(" interrupted=1")
        append('\n')
    }

    /** Start time from the first line, `null` if it is not a flight file. */
    fun parseStart(firstLine: String?): Long? =
        firstLine?.takeIf { it.startsWith(MAGIC) }?.let { keyValues(it.removePrefix(MAGIC))["start"]?.toLongOrNull() }

    /** Summary footer line, `null` if [line] is not one (or is damaged). */
    fun parseFooter(line: String): Pair<FlightSummary, Boolean>? {
        if (!line.startsWith(SUMMARY)) return null
        val kv = keyValues(line.removePrefix(SUMMARY))
        return FlightSummary(
            durationMs = kv["duration_ms"]?.toLongOrNull() ?: return null,
            maxHeightM = kv["max_height_m"]?.toFloatOrNull() ?: return null,
            maxSpeedMps = kv["max_speed_mps"]?.toFloatOrNull() ?: return null,
            batteryStart = kv["battery_start"]?.toIntOrNull(),
            batteryEnd = kv["battery_end"]?.toIntOrNull(),
            samples = kv["samples"]?.toIntOrNull() ?: return null,
            hasTrack = kv["track"] == "1",
        ) to (kv["interrupted"] == "1")
    }

    /** Whole file; `null` if it is not a flight file. Malformed rows are skipped. */
    fun parse(lines: Sequence<String>): FlightLog? {
        var start: Long? = null
        var columns: Map<String, Int>? = null
        var footer: Pair<FlightSummary, Boolean>? = null
        val samples = ArrayList<FlightSample>()
        for ((n, line) in lines.withIndex()) {
            when {
                n == 0 -> start = parseStart(line) ?: return null
                line.startsWith("#") -> parseFooter(line)?.let { footer = it }
                line.isBlank() -> Unit
                columns == null -> columns = line.split(',').withIndex().associate { (i, c) -> c.trim() to i }
                else -> parseRow(line, columns)?.let(samples::add)
            }
        }
        val s = start ?: return null
        val f = footer
        return FlightLog(
            startEpochMs = s,
            samples = samples,
            summary = f?.first ?: SummaryAccumulator.of(samples),
            interrupted = f?.second ?: true,
        )
    }

    private fun parseRow(line: String, columns: Map<String, Int>): FlightSample? {
        val cells = line.split(',')
        if (cells.size < columns.size) return null // truncated by a crash
        fun cell(name: String): String? = columns[name]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
        fun float(name: String) = cell(name)?.toFloatOrNull()
        fun bool(name: String) = cell(name)?.let { it == "1" }
        return FlightSample(
            tMs = cell("t_ms")?.toLongOrNull() ?: return null,
            heightM = float("height_m") ?: return null,
            northMps = float("north_mps") ?: return null,
            eastMps = float("east_mps") ?: return null,
            climbMps = float("climb_mps") ?: return null,
            batteryPct = cell("battery_pct")?.toIntOrNull() ?: return null,
            flying = bool("flying") ?: return null,
            rollDeg = float("roll_deg"),
            pitchDeg = float("pitch_deg"),
            yawDeg = float("yaw_deg"),
            odoX = float("odo_x_m"),
            odoY = float("odo_y_m"),
            odoZ = float("odo_z_m"),
            odoValid = bool("odo_valid"),
            imuTempC = float("imu_temp_c"),
            wifiPct = cell("wifi_pct")?.toIntOrNull(),
            batteryLow = bool("battery_low") ?: false,
            batteryCritical = bool("battery_critical") ?: false,
            overheating = bool("overheating") ?: false,
            flyTimeS = float("fly_time_s"),
            // Present column, empty cell: no alert (not "unknown").
            alerts = if ("alerts" in columns) cell("alerts")?.split(ALERT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty() else null,
        )
    }

    private fun keyValues(s: String): Map<String, String> = s.trim().split(' ').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()

    private fun StringBuilder.f(v: Float?, decimals: Int = 2): StringBuilder {
        if (v != null && v.isFinite()) append(fmt(v, decimals))
        return this
    }

    private fun StringBuilder.b(v: Boolean): StringBuilder = append(if (v) '1' else '0')

    private fun fmt(v: Float, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", v)
}

/** Totals over all the recorded flights. */
data class FlightStats(
    val flights: Int,
    val totalMs: Long,
    val longestMs: Long,
    val maxHeightM: Float,
    val maxSpeedMps: Float,
    /**
     * Average battery % used per minute of flight, over the flights with a battery reading;
     * `null` with less than one minute of such flights (not meaningful).
     */
    val batteryPerMinute: Float?,
) {
    val averageMs: Long get() = if (flights == 0) 0 else totalMs / flights

    companion object {
        fun of(summaries: List<FlightSummary>): FlightStats {
            var usedPct = 0L
            var usedMs = 0L
            summaries.forEach { s ->
                val used = s.batteryUsed ?: return@forEach
                usedPct += used
                usedMs += s.durationMs
            }
            return FlightStats(
                flights = summaries.size,
                totalMs = summaries.sumOf { it.durationMs },
                longestMs = summaries.maxOfOrNull { it.durationMs } ?: 0,
                maxHeightM = summaries.maxOfOrNull { it.maxHeightM } ?: 0f,
                maxSpeedMps = summaries.maxOfOrNull { it.maxSpeedMps } ?: 0f,
                batteryPerMinute = if (usedMs >= 60_000) usedPct * 60_000f / usedMs else null,
            )
        }
    }
}

/** Storage cap: keeps the newest flights within [maxFlights] and [maxBytes]. */
object FlightRetention {
    const val MAX_FLIGHTS = 200
    const val MAX_BYTES = 50L * 1024 * 1024

    /** [modifiedMs]: file time, independent of the time zone written in the name. */
    data class Stored(val name: String, val sizeBytes: Long, val modifiedMs: Long = 0)

    /**
     * Names to delete. [files] in any order; names sort chronologically (`flight-yyyyMMdd-…`).
     * [keep] (the flight being recorded) is never deleted and counts towards the cap.
     */
    fun toDelete(files: List<Stored>, keep: String? = null, maxFlights: Int = MAX_FLIGHTS, maxBytes: Long = MAX_BYTES): List<String> {
        val ordered = files.sortedWith(
            compareByDescending<Stored> { it.name == keep }.thenByDescending { it.modifiedMs }.thenByDescending { it.name },
        )
        var count = 0
        var bytes = 0L
        return ordered.mapNotNull { f ->
            count++
            bytes += f.sizeBytes
            if (f.name != keep && (count > maxFlights || bytes > maxBytes)) f.name else null
        }
    }
}
