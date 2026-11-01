package com.drone.tellopilot.ui.flight

import android.util.Log
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.drone.tellopilot.video.FrameSink
import com.drone.tellopilot.video.H264Decoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live video: a dedicated SurfaceView-backed surface fed directly by the hardware decoder
 * (no copy through Compose, no texture upload). The decoder is created off the main thread
 * so that touch handling — and therefore the sticks — never freezes.
 */
@Composable
fun VideoView(
    attach: (FrameSink) -> Unit,
    detach: (FrameSink) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidExternalSurface(modifier.aspectRatio(4f / 3f)) {
        onSurface { surface, _, _ ->
            var decoder: H264Decoder? = null
            surface.onDestroyed {
                decoder?.let {
                    detach(it)
                    it.release()
                }
                decoder = null
            }
            val created = try {
                withContext(Dispatchers.Default) { H264Decoder(surface) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Surface released meanwhile (screen off): a new onSurface call will follow.
                if (surface.isValid) Log.e("VideoView", "decoder unavailable", e)
                else Log.w("VideoView", "surface released while creating the decoder")
                return@onSurface
            }
            if (surface.isValid) {
                decoder = created
                attach(created)
            } else {
                created.release()
            }
        }
    }
}
