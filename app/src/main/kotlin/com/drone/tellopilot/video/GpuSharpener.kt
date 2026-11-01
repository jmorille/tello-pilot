package com.drone.tellopilot.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GPU upscaling of the 960x720 drone video to the screen: bilinear upscale plus contrast
 * adaptive sharpening (CAS / RCAS-like, 5 taps), drawn into [output].
 *
 * The decoder renders into [inputSurface] (a SurfaceTexture); every frame is drawn as soon as
 * it is available, with swap interval 0, so the extra delay is one GPU pass (about 1 ms).
 * All GL work runs on a dedicated display-priority thread; [release] is synchronous.
 */
class GpuSharpener(private val output: Surface, width: Int, height: Int) {

    private val thread = HandlerThread("tello-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val texMatrix = FloatArray(16)
    /** Full-screen strip (x, y, u, v), one per renderer (draw() moves its position). */
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    private var uTexMatrix = 0
    private var uTex = 0
    private var uTexel = 0
    private var uSharpness = 0
    private var aPos = 0
    private var aTex = 0
    private var viewportWidth = width
    private var viewportHeight = height
    @Volatile private var released = false

    /** Surface to give to the decoder. */
    lateinit var inputSurface: Surface
        private set

    /** Linux thread id of the GL thread (performance hints). */
    @Volatile var tid: Int = 0
        private set

    init {
        val error = runOnGl { setUp() }
        if (error != null) {
            release()
            throw IllegalStateException("GPU sharpener unavailable", error)
        }
    }

    /** The view changed size: next frames use the new viewport. */
    fun resize(width: Int, height: Int) {
        handler.post {
            viewportWidth = width
            viewportHeight = height
        }
    }

    fun release() {
        if (released) return
        released = true
        runOnGl {
            if (this::inputSurface.isInitialized) inputSurface.release()
            if (this::surfaceTexture.isInitialized) {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
            }
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
        thread.quitSafely()
    }

    /** Runs [block] on the GL thread and waits; returns the error it threw, if any. */
    private fun runOnGl(block: () -> Unit): Throwable? {
        var error: Throwable? = null
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        if (!posted || !done.await(2, TimeUnit.SECONDS)) return error ?: IllegalStateException("GL thread not responding")
        return error
    }

    private fun setUp() {
        tid = Process.myTid()
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        val config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        // Never wait for vsync in swap: the newest frame is shown at the next refresh.
        EGL14.eglSwapInterval(display, 0)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uSharpness = GLES20.glGetUniformLocation(program, "uSharpness")
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            setOnFrameAvailableListener({ draw() }, handler)
        }
        inputSurface = Surface(surfaceTexture)
    }

    private fun draw() {
        if (released) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform2f(uTexel, 1f / VIDEO_WIDTH, 1f / VIDEO_HEIGHT)
            GLES20.glUniform1f(uSharpness, SHARPNESS)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (e: RuntimeException) {
            // Output surface gone (screen off): the view recreates everything.
            Log.w(TAG, "draw failed", e)
        }
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val TAG = "GpuSharpener"
        const val VIDEO_WIDTH = 960
        const val VIDEO_HEIGHT = 720
        /** 0 = light, 1 = strong. */
        const val SHARPNESS = 0.6f

        const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTexMatrix * aTex).xy;
            }
        """

        // Contrast adaptive sharpening on the bilinear upscale: strong where the local contrast
        // is low, weak near edges (no halos), never amplifies noise beyond the local min/max.
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float; // mediump (fp16 on Mali) snaps coordinates to half a texel
            uniform samplerExternalOES uTex;
            uniform vec2 uTexel;
            uniform float uSharpness;
            varying vec2 vUv;
            void main() {
                vec3 c = texture2D(uTex, vUv).rgb;
                vec3 n = texture2D(uTex, vUv + vec2(0.0, -uTexel.y)).rgb;
                vec3 s = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;
                vec3 w = texture2D(uTex, vUv + vec2(-uTexel.x, 0.0)).rgb;
                vec3 e = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;
                vec3 mn = min(c, min(min(n, s), min(w, e)));
                vec3 mx = max(c, max(max(n, s), max(w, e)));
                vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
                vec3 wgt = amp * (-1.0 / mix(8.0, 5.0, uSharpness));
                vec3 color = (c + (n + s + w + e) * wgt) / (1.0 + 4.0 * wgt);
                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """
    }
}
