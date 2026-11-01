package com.drone.tellopilot.video

import com.drone.tello.protocol.H264

/**
 * Pure part of [VideoRecorder] (no Android): turns the drone's H.264 frames into MP4 samples.
 *
 * - the file starts on a key frame, once SPS and PPS are known (they become the track's csd);
 * - the Tello sends SPS, PPS and the picture in separate packets: the picture right after the
 *   parameter sets is the key frame;
 * - after a gap (frames dropped by a late writer), P-frames are skipped until the next key frame;
 * - parameter sets changed during the recording are written in-band before the next key frame.
 *
 * Single-threaded (the recorder's writer thread).
 */
internal class RecordingAssembler {

    /**
     * One sample to write. [start]: SPS/PPS of the track to create first (first sample only);
     * [configInBand]: [sample] starts with new parameter sets.
     */
    class Output(
        val sample: ByteArray,
        val ptsUs: Long,
        val key: Boolean,
        val start: Pair<ByteArray, ByteArray>?,
        val configInBand: Boolean,
    )

    /** The track exists (a key frame with SPS/PPS was accepted). */
    var started = false
        private set

    private var firstPtsNs = 0L
    private var waitingKey = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var afterConfig = false
    private var csd: Pair<ByteArray, ByteArray>? = null
    private var pendingConfig: ByteArray? = null

    /** [gapBefore]: frames were dropped just before this one. `null`: nothing to write. */
    fun onFrame(bytes: ByteArray, arrivalNs: Long, gapBefore: Boolean): Output? {
        val types = H264.nalTypes(bytes)
        if (gapBefore) {
            // Frames were lost (writer late): skip to the next key frame, and a picture
            // following parameter sets seen before the gap is not a key frame.
            waitingKey = true
            afterConfig = false
        }
        // Remember the parameter sets as they come, the picture right after them is the key frame.
        if (H264.NAL_SPS in types) {
            H264.extractNal(bytes, H264.NAL_SPS)?.let { sps = it }
            afterConfig = true
        }
        if (H264.NAL_PPS in types) H264.extractNal(bytes, H264.NAL_PPS)?.let { pps = it }
        val hasPicture = types.any { it == H264.NAL_IDR || it == H264.NAL_SLICE }
        // Parameter sets alone are not a sample: they go to the track format (csd).
        if (!hasPicture) return null
        val key = H264.NAL_IDR in types || afterConfig
        afterConfig = false
        // Parameter sets changed during the recording (bitrate change, drone encoder restart):
        // the track keeps its first csd, the new ones go in-band before the next key frame.
        val current = csd
        val s0 = sps
        val p0 = pps
        if (current != null && s0 != null && p0 != null &&
            !(s0.contentEquals(current.first) && p0.contentEquals(current.second))
        ) {
            pendingConfig = s0 + p0
            csd = s0 to p0
        }
        var start: Pair<ByteArray, ByteArray>? = null
        if (!started) {
            val s = sps
            val p = pps
            if (s == null || p == null || !key) return null // wait for SPS + PPS, then a key frame
            start = s to p
            csd = s to p
            started = true
            firstPtsNs = arrivalNs
        }
        if (waitingKey && !key) return null
        waitingKey = false
        val config = if (key) pendingConfig else null
        if (config != null) pendingConfig = null
        return Output(
            sample = if (config != null) config + bytes else bytes,
            ptsUs = (arrivalNs - firstPtsNs) / 1_000,
            key = key,
            start = start,
            configInBand = config != null,
        )
    }
}
