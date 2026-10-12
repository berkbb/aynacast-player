package com.babadogan.aynacast.tv.engine

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
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws the decoder's output with OpenGL into [output], fitted to the video's aspect ratio with
 * black bars.
 *
 * Decoding straight into a SurfaceView sends the video to the TV's hardware video plane, and some
 * TVs (MediaTek / Philips) apply their own aspect processing there that apps cannot turn off: a
 * 1920x888 landscape iPhone stream came out squashed to about 1920x680. A finished full-screen
 * frame, letterboxed here, leaves the TV nothing to adjust for any sender (iPhone, iPad, Mac).
 *
 * The decoder renders into [inputSurface]; everything else runs on the renderer's own thread.
 */
class GlVideoRenderer(private val output: Surface) {

    private val thread = HandlerThread("AirPlay-GlRenderer").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private var hasFrame = false

    // Renderer-thread state.
    private var outputWidth = 0
    private var outputHeight = 0
    private var videoWidth = 16
    private var videoHeight = 9

    /** The surface the decoder renders into; valid until [release]. */
    lateinit var inputSurface: Surface
        private set

    init {
        val ready = CountDownLatch(1)
        handler.post {
            try {
                setUp()
            } catch (e: Exception) {
                Log.e(TAG, "OpenGL video renderer setup failed", e)
            }
            ready.countDown()
        }
        ready.await()
        check(::inputSurface.isInitialized) { "OpenGL video renderer could not be set up" }
    }

    fun setOutputSize(width: Int, height: Int) = handler.post {
        outputWidth = width
        outputHeight = height
        draw()
    }

    /** The stream's display size; it changes when the sender rotates. */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        handler.post {
            if (width == videoWidth && height == videoHeight) return@post
            videoWidth = width
            videoHeight = height
            draw()
        }
    }

    /** Frees the GL resources. Call after the decoder stopped rendering into [inputSurface]. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            try {
                tearDown()
            } finally {
                done.countDown()
            }
        }
        // Must finish before the output surface is destroyed (SurfaceHolder callback contract).
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    private fun setUp() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No EGL config"
        }
        val config = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        program = createProgram()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val texture = SurfaceTexture(textureId)
        texture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = texture
        inputSurface = Surface(texture)
        Log.i(TAG, "OpenGL video renderer ready (EGL ${version[0]}.${version[1]})")
    }

    private fun onFrameAvailable() {
        val texture = surfaceTexture ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            hasFrame = true
            draw()
        } catch (e: Exception) {
            Log.w(TAG, "Could not draw a video frame", e)
        }
    }

    private fun draw() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || outputWidth <= 0 || outputHeight <= 0) return
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) {
            // Largest rectangle of the video's aspect ratio that fits, centred.
            val scale = minOf(outputWidth.toFloat() / videoWidth, outputHeight.toFloat() / videoHeight)
            val width = (videoWidth * scale).toInt()
            val height = (videoHeight * scale).toInt()
            GLES20.glViewport((outputWidth - width) / 2, (outputHeight - height) / 2, width, height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coord = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD_POSITIONS)
            GLES20.glEnableVertexAttribArray(coord)
            GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX_COORDS)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun tearDown() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (::inputSurface.isInitialized) inputSurface.release()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun createProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val TAG = "GlVideoRenderer"
        const val RELEASE_TIMEOUT_MS = 500L

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        val QUAD_POSITIONS: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_TEX_COORDS: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
