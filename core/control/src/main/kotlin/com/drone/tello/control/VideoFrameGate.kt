package com.drone.tello.control

import com.drone.tello.protocol.H264
import com.drone.tello.protocol.VideoFrameAssembler

/**
 * Decides which frames reach the decoder so that the picture is never late:
 * after any loss or decoder backlog, P-frames are dropped until the next
 * key frame (SPS) instead of being queued, and a key frame is requested.
 */
class VideoFrameGate {

    /** @property frame to decode (or `null` to drop), @property requestKeyFrame ask the drone for an I-frame. */
    data class Decision(val frame: ByteArray?, val requestKeyFrame: Boolean)

    var waitingForKeyFrame = true
        private set

    fun onResult(result: VideoFrameAssembler.Result): Decision = when (result) {
        VideoFrameAssembler.Result.Pending -> Decision(null, requestKeyFrame = false)
        VideoFrameAssembler.Result.Lost -> resync()
        is VideoFrameAssembler.Result.Frame -> {
            // A frame completed right after a loss references missing data unless it is a key frame.
            if (result.afterLoss) waitingForKeyFrame = true
            onFrame(result.data)
        }
    }

    /** Decoder cannot keep up: drop until the next key frame. */
    fun onDecoderBacklog(): Decision = resync()

    /** Decoder (re)created: it needs SPS/PPS first. */
    fun reset() {
        waitingForKeyFrame = true
    }

    private fun onFrame(frame: ByteArray): Decision {
        if (!waitingForKeyFrame) return Decision(frame, requestKeyFrame = false)
        return if (H264.containsConfig(frame)) {
            waitingForKeyFrame = false
            Decision(frame, requestKeyFrame = false)
        } else {
            Decision(null, requestKeyFrame = true)
        }
    }

    private fun resync(): Decision {
        waitingForKeyFrame = true
        return Decision(null, requestKeyFrame = true)
    }
}
