package de.equinox.v05

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Step 28 GPU live-video renderer.
 *
 * The Equinox core remains coordinate-free. This view receives only a transient camera pigment
 * frame plus a read-only WorkingSetSnapshot. The renderer uploads the pigment as an OpenGL ES
 * texture and performs deformation/color projection in one fragment shader pass.
 *
 * No rendered pixel is written back into MemoryModel. There are no per-cell Android Canvas draw
 * calls on the live hot path anymore.
 */
class GpuMemoryVideoView(context: Context) : GLSurfaceView(context) {
    private val gpuRenderer = GpuRenderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(gpuRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setSynchronizedFrame(
        newFrame: VideoFrameSnapshot,
        working: WorkingSetSnapshot?,
        info: String = ""
    ) {
        gpuRenderer.submit(newFrame, working, info)
        requestRender()
    }

    fun setFrame(newFrame: VideoFrameSnapshot) {
        gpuRenderer.submit(newFrame, null, "")
        requestRender()
    }

    fun setModeLabel(label: String) {
        gpuRenderer.modeLabel = label.trim().uppercase().ifEmpty { "LIVE" }
    }

    fun lastRenderCostMs(): Double = gpuRenderer.lastRenderCostMs

    private class GpuRenderer : GLSurfaceView.Renderer {
        private data class Packet(
            val frame: VideoFrameSnapshot,
            val memoryEnergy: Float,
            val signedExchange: Float,
            val expectation: Float,
            val prediction: Float,
            val fields: FloatArray,
            val fieldCount: Int,
            val info: String
        )

        @Volatile var lastRenderCostMs: Double = 0.0
        @Volatile var modeLabel: String = "LIVE"
        private val pending = AtomicReference<Packet?>(null)
        private var current: Packet? = null
        private var phase = 0f

        private var program = 0
        private var textureId = 0
        private var textureW = 0
        private var textureH = 0
        private var pixelBuffer: ByteBuffer? = null

        private var aPosition = -1
        private var aTexCoord = -1
        private var uTexture = -1
        private var uPhase = -1
        private var uSummary = -1
        private var uFieldCount = -1
        private var uFields = -1

        private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                // x, y, u, v. V is flipped because VideoFrameSnapshot row 0 is the top row.
                put(floatArrayOf(
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f
                ))
                position(0)
            }

        fun submit(frame: VideoFrameSnapshot, working: WorkingSetSnapshot?, info: String) {
            val nodes = working?.nodes.orEmpty()
            val exchanges = working?.exchanges.orEmpty()
            val predictions = working?.predictions.orEmpty()

            val meanActivation = if (nodes.isEmpty()) 0.0 else nodes.sumOf { it.activation.coerceIn(0.0, 1.0) } / nodes.size
            val meanExpectation = if (nodes.isEmpty()) 0.0 else nodes.sumOf { it.expectation.coerceIn(0.0, 1.0) } / nodes.size
            val meanRelevance = if (nodes.isEmpty()) 0.0 else nodes.sumOf { it.structuralRelevance.coerceIn(0.0, 1.0) } / nodes.size
            val exchangeEnergy = if (exchanges.isEmpty()) 0.0 else exchanges.sumOf { abs(it.value).coerceIn(0.0, 2.0) } / exchanges.size
            val signedExchange = if (exchanges.isEmpty()) 0.0 else exchanges.sumOf { it.value.coerceIn(-2.0, 2.0) } / exchanges.size
            val predictionEnergy = if (predictions.isEmpty()) 0.0 else predictions.sumOf { it.confidence.coerceIn(0.0, 1.0) } / predictions.size

            val memoryEnergy = (
                0.26 * meanActivation +
                    0.20 * meanExpectation +
                    0.24 * meanRelevance +
                    0.20 * min(1.0, exchangeEnergy) +
                    0.10 * predictionEnergy
                ).coerceIn(0.0, 1.0)

            val strongest = nodes
                .asSequence()
                .sortedByDescending {
                    max(it.activation, it.expectation) * (0.45 + 0.55 * it.structuralRelevance)
                }
                .take(MAX_FIELDS)
                .toList()

            val fieldData = FloatArray(MAX_FIELDS * 4)
            strongest.forEachIndexed { index, node ->
                val p = fieldPosition(node.id)
                val activity = max(node.activation, node.expectation).coerceIn(0.0, 1.0).toFloat()
                val relevance = node.structuralRelevance.coerceIn(0.0, 1.0).toFloat()
                val exchange = node.exchangeBias.coerceIn(-1.5, 1.5).toFloat()
                val base = index * 4
                fieldData[base] = p.first
                fieldData[base + 1] = p.second
                fieldData[base + 2] = activity * (0.35f + 0.65f * relevance)
                fieldData[base + 3] = exchange
            }

            pending.set(
                Packet(
                    frame = frame,
                    memoryEnergy = memoryEnergy.toFloat(),
                    signedExchange = signedExchange.coerceIn(-1.0, 1.0).toFloat(),
                    expectation = meanExpectation.coerceIn(0.0, 1.0).toFloat(),
                    prediction = predictionEnergy.coerceIn(0.0, 1.0).toFloat(),
                    fields = fieldData,
                    fieldCount = strongest.size,
                    info = info
                )
            )
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.01f, 0.015f, 0.025f, 1f)
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            uPhase = GLES20.glGetUniformLocation(program, "uPhase")
            uSummary = GLES20.glGetUniformLocation(program, "uSummary")
            uFieldCount = GLES20.glGetUniformLocation(program, "uFieldCount")
            uFields = GLES20.glGetUniformLocation(program, "uFields[0]")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val start = System.nanoTime()
            pending.getAndSet(null)?.let { current = it }
            val packet = current
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (packet == null || program == 0 || textureId == 0) {
                lastRenderCostMs = (System.nanoTime() - start) / 1_000_000.0
                return
            }

            uploadTexture(packet.frame)
            phase = (phase + 0.013f) % 1f

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniform1f(uPhase, phase)
            GLES20.glUniform4f(
                uSummary,
                packet.memoryEnergy,
                packet.signedExchange,
                packet.expectation,
                packet.prediction
            )
            GLES20.glUniform1i(uFieldCount, packet.fieldCount)
            GLES20.glUniform4fv(uFields, MAX_FIELDS, packet.fields, 0)

            quad.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)

            lastRenderCostMs = (System.nanoTime() - start) / 1_000_000.0
        }

        private fun uploadTexture(frame: VideoFrameSnapshot) {
            if (frame.width <= 0 || frame.height <= 0 || frame.rgb.size < frame.width * frame.height) return
            val needed = frame.width * frame.height * 4
            val buffer = if (pixelBuffer == null || pixelBuffer!!.capacity() < needed) {
                ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).also { pixelBuffer = it }
            } else {
                pixelBuffer!!
            }
            buffer.clear()
            val count = frame.width * frame.height
            for (i in 0 until count) {
                val c = frame.rgb[i]
                buffer.put(((c ushr 16) and 0xff).toByte())
                buffer.put(((c ushr 8) and 0xff).toByte())
                buffer.put((c and 0xff).toByte())
                buffer.put(0xff.toByte())
            }
            buffer.flip()

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            if (textureW != frame.width || textureH != frame.height) {
                textureW = frame.width
                textureH = frame.height
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    frame.width,
                    frame.height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    buffer
                )
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    frame.width,
                    frame.height,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    buffer
                )
            }
        }

        private fun buildProgram(vertex: String, fragment: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            if (vs == 0 || fs == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            if (linked[0] == 0) {
                GLES20.glDeleteProgram(p)
                return 0
            }
            return p
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun fieldPosition(id: Int): Pair<Float, Float> {
            val a = mix(id.toLong() * 0x9E3779B97F4A7C15UL.toLong())
            val b = mix(a xor 0xC2B2AE3D27D4EB4FUL.toLong())
            val x = 0.08f + 0.84f * (((a ushr 11) and 0xffff).toFloat() / 65535f)
            val y = 0.08f + 0.84f * (((b ushr 13) and 0xffff).toFloat() / 65535f)
            return Pair(x, y)
        }

        private fun mix(x0: Long): Long {
            var x = x0
            x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
            x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
            return x xor (x ushr 31)
        }

        companion object {
            private const val MAX_FIELDS = 8

            private const val VERTEX_SHADER = """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTex;
                void main() {
                    vTex = aTexCoord;
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                }
            """

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTexture;
                uniform float uPhase;
                // x=memory energy, y=signed exchange, z=expectation, w=prediction
                uniform vec4 uSummary;
                uniform int uFieldCount;
                // x/y=renderer-only focus position, z=strength, w=exchange bias
                uniform vec4 uFields[8];
                varying vec2 vTex;

                void main() {
                    float memory = clamp(uSummary.x, 0.0, 1.0);
                    float signedEx = clamp(uSummary.y, -1.0, 1.0);
                    float expectation = clamp(uSummary.z, 0.0, 1.0);
                    float prediction = clamp(uSummary.w, 0.0, 1.0);

                    vec2 uv = vTex;
                    float warp = 0.004 + 0.040 * memory + 0.018 * abs(signedEx);
                    float tau = 6.28318530718;
                    uv.x += sin((vTex.y * 2.7 + uPhase * 1.3) * tau) * warp * (0.55 + 0.45 * prediction);
                    uv.y += cos((vTex.x * 2.2 - uPhase * 1.1) * tau) * warp * (0.55 + 0.45 * expectation);

                    float fieldGlow = 0.0;
                    for (int i = 0; i < 8; ++i) {
                        if (i < uFieldCount) {
                            vec4 f = uFields[i];
                            vec2 delta = vTex - f.xy;
                            float d2 = 0.018 + dot(delta, delta);
                            float twist = 0.0008 + 0.0046 * f.z;
                            float direction = f.w >= 0.0 ? 1.0 : -1.0;
                            float exchangeGain = 0.35 + 0.65 * min(1.0, abs(f.w));
                            vec2 tangent = vec2(-delta.y, delta.x);
                            uv += tangent * (twist * exchangeGain * direction / d2);

                            float d = sqrt(dot(delta, delta));
                            float radius = 0.035 + 0.040 * f.z;
                            fieldGlow += (1.0 - smoothstep(radius, radius + 0.012, abs(d - radius))) * f.z;
                        }
                    }
                    uv = clamp(uv, vec2(0.001), vec2(0.999));

                    vec2 chroma = vec2(0.0025 * signedEx * memory, -0.0018 * signedEx * memory);
                    vec3 center = texture2D(uTexture, uv).rgb;
                    float rr = texture2D(uTexture, clamp(uv + chroma, vec2(0.001), vec2(0.999))).r;
                    float bb = texture2D(uTexture, clamp(uv - chroma, vec2(0.001), vec2(0.999))).b;
                    vec3 c = vec3(rr, center.g, bb);

                    float contrast = 1.0 + 0.30 * memory;
                    c = (c - 0.5) * contrast + 0.5;
                    c *= 1.0 + 0.10 * expectation;

                    vec3 exchangeTint = signedEx >= 0.0
                        ? vec3(0.08, 0.30, 0.42)
                        : vec3(0.42, 0.08, 0.30);
                    c += exchangeTint * abs(signedEx) * memory * 0.16;
                    c += exchangeTint * min(1.0, fieldGlow) * 0.10 * memory;

                    // Small prediction shimmer without replacing the underlying camera pigment.
                    float shimmer = 0.5 + 0.5 * sin((vTex.x + vTex.y + uPhase * 2.0) * tau * 3.0);
                    c += vec3(0.03, 0.045, 0.055) * shimmer * prediction * memory;

                    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
                }
            """
        }
    }
}
