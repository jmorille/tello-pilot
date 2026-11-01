package com.drone.tellopilot.video

import com.drone.tellopilot.data.PhoneHeat
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscodePolicyTest {

    @Test
    fun `thermal pause holds from HOT until the phone is back to NORMAL`() {
        val heats = listOf(
            PhoneHeat.NORMAL, PhoneHeat.WARM, PhoneHeat.HOT, PhoneHeat.WARM, PhoneHeat.WARM, PhoneHeat.NORMAL, PhoneHeat.WARM,
        )
        val held = heats.runningFold(false) { hold, heat -> TranscodeQueue.thermalHold(hold, heat) }.drop(1)
        // WARM alone never pauses; once HOT, WARM keeps the pause (hysteresis), NORMAL releases it.
        assertEquals(listOf(false, false, true, true, true, false, false), held)
    }

    private data class Video(val name: String, val h264: Boolean)

    private fun todo(vararg videos: Video) =
        TranscodeQueue.untranscoded(videos.toList(), Video::h264) { it.name.substringBeforeLast('.') }.map { it.name }

    @Test
    fun `only H264 videos without a re-encoded copy are queued`() {
        assertEquals(
            listOf("tello_2.mp4", "tello_4.mp4"),
            todo(
                Video("tello_1.mp4", true), Video("tello_1-hevc.mp4", false), // already converted (HEVC)
                Video("tello_2.mp4", true),
                Video("tello_3.mp4", true), Video("tello_3-av1.mp4", false), // already converted (AV1)
                Video("tello_4.mp4", true), Video("tello_4-x.mp4", false), // unrelated suffix
            ),
        )
    }

    @Test
    fun `re-encoded copies are never queued themselves`() {
        assertEquals(emptyList<String>(), todo(Video("tello_1-hevc.mp4", false), Video("other.mp4", false)))
        assertEquals(emptyList<String>(), todo())
    }
}
