package com.drone.tellopilot.tracking

import android.graphics.RectF
import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.drone.tello.control.TrackingTarget

/** One detected target, box normalised to 0..1 (origin top-left). */
data class Detection(val box: RectF, val trackingId: Int?)

/**
 * ML Kit detector for the chosen [TrackingTarget], models bundled in the APK:
 * - FACE: face detector in fast mode, with tracking ids (several faces);
 * - PERSON: pose detector in stream mode (the most prominent person, box from its landmarks).
 */
class TargetDetector(private val target: TrackingTarget) : AutoCloseable {

    private val faces = if (target == TrackingTarget.FACE) {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(MIN_FACE_SIZE)
                .enableTracking()
                .build(),
        )
    } else {
        null
    }

    private val poses = if (target == TrackingTarget.PERSON) {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
        )
    } else {
        null
    }

    /**
     * Analyses [image] (rotation 0: the drone image is upright). [onDone] always runs exactly
     * once, on ML Kit's callback thread, with the detections (empty on failure).
     */
    fun process(image: Image, onDone: (List<Detection>) -> Unit) {
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val input = try {
            InputImage.fromMediaImage(image, 0)
        } catch (_: Exception) {
            onDone(emptyList()); return
        }
        faces?.let { detector ->
            detector.process(input)
                .addOnSuccessListener { list ->
                    onDone(list.map { f -> Detection(normalised(f.boundingBox.toRectF(), w, h), f.trackingId) })
                }
                .addOnFailureListener { onDone(emptyList()) }
            return
        }
        poses?.let { detector ->
            detector.process(input)
                .addOnSuccessListener { pose ->
                    val visible = pose.allPoseLandmarks.filter { it.inFrameLikelihood >= MIN_LIKELIHOOD }
                    if (visible.size < MIN_LANDMARKS) {
                        onDone(emptyList())
                    } else {
                        val box = RectF(
                            visible.minOf { it.position.x }, visible.minOf { it.position.y },
                            visible.maxOf { it.position.x }, visible.maxOf { it.position.y },
                        )
                        onDone(listOf(Detection(normalised(box, w, h), null)))
                    }
                }
                .addOnFailureListener { onDone(emptyList()) }
            return
        }
        onDone(emptyList())
    }

    override fun close() {
        faces?.close()
        poses?.close()
    }

    private fun android.graphics.Rect.toRectF() = RectF(this)

    private fun normalised(r: RectF, w: Float, h: Float) = RectF(
        (r.left / w).coerceIn(0f, 1f), (r.top / h).coerceIn(0f, 1f),
        (r.right / w).coerceIn(0f, 1f), (r.bottom / h).coerceIn(0f, 1f),
    )

    private companion object {
        const val MIN_FACE_SIZE = 0.05f
        const val MIN_LIKELIHOOD = 0.5f
        const val MIN_LANDMARKS = 6
    }
}
