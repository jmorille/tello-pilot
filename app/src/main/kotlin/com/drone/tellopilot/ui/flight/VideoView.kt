package com.drone.tellopilot.ui.flight

import android.util.Log
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.drone.tellopilot.video.GpuSharpener
import androidx.compose.ui.Modifier
import com.drone.tellopilot.video.FrameSink
import com.drone.tellopilot.video.H264Decoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Live video: a dedicated SurfaceView-backed surface fed by the hardware decoder, either
 * directly (no copy, no texture upload) or through [GpuSharpener] when [sharpen] is on
 * (upscale + sharpening on the GPU, about 1 ms). The decoder is created off the main thread
 * so that touch handling — and therefore the sticks — never freezes.
 */
@Composable
fun VideoView(
    attach: (FrameSink) -> Unit,
    detach: (FrameSink) -> Unit,
    modifier: Modifier = Modifier,
    sharpen: Boolean = true,
) {
    // Switching the GPU path rebuilds the surface, the decoder and (if any) the GL renderer.
    key(sharpen) {
    AndroidExternalSurface(modifier.aspectRatio(4f / 3f)) {
        onSurface { surface, width, height ->
            var decoder: H264Decoder? = null
            var sharpener: GpuSharpener? = null
            var size = width to height
            surface.onChanged { w, h ->
                size = w to h
                sharpener?.resize(w, h)
            }
            surface.onDestroyed {
                decoder?.let {
                    detach(it)
                    it.release()
                }
                decoder = null
                // After the decoder: it renders into the sharpener's input surface.
                sharpener?.release()
                sharpener = null
            }
            // Whatever was built is released in `finally` unless attached: a cancellation while
            // building or while coming back to the main thread can never leak a codec or a GL thread.
            var built: Pair<H264Decoder, GpuSharpener?>? = null
            var attached = false
            try {
                withContext(Dispatchers.Default + NonCancellable) {
                    // GPU upscale + sharpening when enabled; the direct path if the GPU fails.
                    val gpu = if (sharpen) {
                        runCatching { GpuSharpener(surface, width, height) }
                            .onFailure { Log.w("VideoView", "GPU sharpening unavailable, direct rendering", it) }
                            .getOrNull()
                    } else {
                        null
                    }
                    try {
                        val target = gpu?.inputSurface ?: surface
                        built = H264Decoder(target, gpu?.let { intArrayOf(it.tid) } ?: IntArray(0)) to gpu
                    } catch (e: Exception) {
                        gpu?.release()
                        throw e
                    }
                }
                val created = built
                if (created != null && surface.isValid && currentCoroutineContext().isActive) {
                    decoder = created.first
                    sharpener = created.second
                    created.second?.resize(size.first, size.second)
                    attach(created.first)
                    attached = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Surface released meanwhile (screen off): a new onSurface call will follow.
                if (surface.isValid) Log.e("VideoView", "decoder unavailable", e)
                else Log.w("VideoView", "surface released while creating the decoder")
            } finally {
                if (!attached) built?.let {
                    it.first.release()
                    it.second?.release()
                }
            }
        }
    }
    }
}
