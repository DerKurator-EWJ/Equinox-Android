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
 * Step 33 — Fluid Hierarchy Transitions on the Unified Canvas.
 *
 * There is only one physical OpenGL surface. Camera pigment and the read-only Equinox
 * working set are two ingredients of the same renderer. Since Step 46.1 the product surface
 * is HYPNO-only and uses the generative state projection at full strength; raw camera pigment is
 * an input source, not a user-visible Reality mode. The legacy blend remains internal for renderer
 * compatibility/LAB work and is not exposed by the product UI.
 *
 * The renderer never writes pixels, positions or presentation state back into MemoryModel.
 */
enum class UnifiedPresentationMode { HYPNO, STRUCTURE }

class GpuMemoryVideoView(context: Context) : GLSurfaceView(context) {
    private val gpuRenderer = GpuRenderer {
        postOnAnimation { requestRender() }
    }

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

    /** Update memory without requiring a new camera frame (Memory / Dream). */
    fun setWorkingState(working: WorkingSetSnapshot?) {
        gpuRenderer.submit(null, working, "")
        requestRender()
    }

    fun setMemoryBlend(value: Float) {
        gpuRenderer.memoryBlend = value.coerceIn(0f, 1f)
        requestRender()
    }

    fun memoryBlend(): Float = gpuRenderer.memoryBlend

    fun setPresentationMode(mode: UnifiedPresentationMode) {
        gpuRenderer.presentationMode = mode
        requestRender()
    }

    fun presentationMode(): UnifiedPresentationMode = gpuRenderer.presentationMode

    fun setModeLabel(label: String) {
        gpuRenderer.modeLabel = label.trim().uppercase().ifEmpty { "UNIFIED" }
    }

    fun lastRenderCostMs(): Double = gpuRenderer.lastRenderCostMs

    fun setPerformanceProfile(profile: PerformanceGovernor.Profile) {
        gpuRenderer.fieldBudget = profile.fieldBudget.coerceIn(1, 7)
        gpuRenderer.linkBudget = profile.linkBudget.coerceIn(0, 5)
        requestRender()
    }

    private class GpuRenderer(
        private val scheduleNextFrame: () -> Unit
    ) : GLSurfaceView.Renderer {
        private data class Packet(
            val frame: VideoFrameSnapshot?,
            val memoryEnergy: Float,
            val signedExchange: Float,
            val expectation: Float,
            val prediction: Float,
            val noveltyProxy: Float,
            val crossmodal: Float,
            val tierDepth: Float,
            val retrievalShare: Float,
            val fields: FloatArray,
            val fieldIds: IntArray,
            val fieldCount: Int,
            val links: FloatArray,
            val linkKeys: LongArray,
            val linkCount: Int,
            val info: String
        )

        @Volatile var lastRenderCostMs: Double = 0.0
        @Volatile var modeLabel: String = "HYPNO"
        @Volatile var memoryBlend: Float = 1.0f
        @Volatile var presentationMode: UnifiedPresentationMode = UnifiedPresentationMode.HYPNO
        @Volatile var fieldBudget: Int = MAX_FIELDS
        @Volatile var linkBudget: Int = MAX_LINKS

        private val pending = AtomicReference<Packet?>(null)
        private var current: Packet? = null
        private var currentFrame: VideoFrameSnapshot? = null

        private data class SmoothField(
            val id: Int,
            var x: Float,
            var y: Float,
            var strength: Float,
            var tier: Float,
            var targetX: Float,
            var targetY: Float,
            var targetStrength: Float,
            var targetTier: Float,
            var seenThisPacket: Boolean = false
        )

        private data class SmoothLink(
            val key: Long,
            var ax: Float,
            var ay: Float,
            var bx: Float,
            var by: Float,
            var alpha: Float,
            var targetAx: Float,
            var targetAy: Float,
            var targetBx: Float,
            var targetBy: Float,
            var targetAlpha: Float,
            var seenThisPacket: Boolean = false
        )

        private val smoothFields = LinkedHashMap<Int, SmoothField>()
        private val smoothLinks = LinkedHashMap<Long, SmoothLink>()
        private var displayMemoryEnergy = 0f
        private var displaySignedExchange = 0f
        private var displayExpectation = 0f
        private var displayPrediction = 0f
        private var displayNoveltyProxy = 0f
        private var displayCrossmodal = 0f
        private var displayTierDepth = 0f
        private var displayRetrievalShare = 0f
        private var displayBlend = 1.0f
        private var displayHypno = 1f
        private var lastAnimNanos = 0L
        private var animationActive = false

        private var program = 0
        private var textureId = 0
        private var textureW = 0
        private var textureH = 0
        private var pixelBuffer: ByteBuffer? = null

        private var aPosition = -1
        private var aTexCoord = -1
        private var uTexture = -1
        private var uSummary = -1
        private var uHypnoSummary = -1
        private var uBlend = -1
        private var uPresentation = -1
        private var uTexel = -1
        private var uFieldCount = -1
        private var uFields = -1
        private var uLinkCount = -1
        private var uLinks = -1
        private var uLinkAlpha = -1

        private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(floatArrayOf(
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f
                ))
                position(0)
            }

        fun submit(frame: VideoFrameSnapshot?, working: WorkingSetSnapshot?, info: String) {
            val nodes = working?.nodes.orEmpty()
            val exchanges = working?.exchanges.orEmpty()
            val predictions = working?.predictions.orEmpty()
            // Step 44: HYPNO and Unified Audio consume the exact same normalized state vector.
            val unified = UnifiedPresentationState.from(working)

            // Renderer selection deliberately reserves visual room for abstraction depth.
            // Deep structures must not disappear merely because many F/E nodes are active.
            // This does NOT change learning, relevance or the working set itself.
            fun renderScore(node: ActiveMacroNode): Double =
                max(node.activation, node.expectation) * (0.45 + 0.55 * node.structuralRelevance)

            val strongest = buildList {
                val chosen = HashSet<Int>()
                fun addBest(candidates: Sequence<ActiveMacroNode>, count: Int) {
                    candidates
                        .filter { chosen.add(it.id) }
                        .sortedByDescending(::renderScore)
                        .take(count)
                        .forEach { add(it) }
                }

                // Fine structures: F/E/D
                addBest(nodes.asSequence().filter { it.abstractionTierIndex <= 2 }, 2)
                // Tissue structures: C/B/A/S/SS
                addBest(nodes.asSequence().filter { it.abstractionTierIndex in 3..7 }, 2)
                // Large organizations: SSS/Ω/Ω+n
                addBest(nodes.asSequence().filter { it.abstractionTierIndex >= 8 }, 2)
                // One final strongest state regardless of depth.
                addBest(nodes.asSequence(), fieldBudget - size)
            }.take(fieldBudget)

            val positionById = HashMap<Int, Pair<Float, Float>>(strongest.size)
            val fieldData = FloatArray(MAX_FIELDS * 4)
            val fieldIds = IntArray(MAX_FIELDS) { Int.MIN_VALUE }
            strongest.forEachIndexed { index, node ->
                fieldIds[index] = node.id
                val p = fieldPosition(node.id)
                positionById[node.id] = p
                val activity = max(node.activation, node.expectation).coerceIn(0.0, 1.0).toFloat()
                val relevance = node.structuralRelevance.coerceIn(0.0, 1.0).toFloat()
                val base = index * 4
                fieldData[base] = p.first
                fieldData[base + 1] = p.second
                fieldData[base + 2] = activity * (0.30f + 0.70f * relevance)
                // Raw zero-based abstraction tier: F=0 ... SSS=8, Ω=9, Ω+n>9.
                // Tier changes visual organization only; it is not an importance multiplier.
                fieldData[base + 3] = node.abstractionTierIndex.coerceAtLeast(0).toFloat()
            }

            // A very small, GPU-friendly tissue skeleton. Links are chosen only from the
            // strongest currently materialized nodes, never from the whole long-term graph.
            val linkPairs = ArrayList<Pair<Int, Int>>(MAX_LINKS)
            fun addLink(a: Int, b: Int) {
                if (linkPairs.size >= linkBudget || a == b) return
                if (!positionById.containsKey(a) || !positionById.containsKey(b)) return
                val key = if (a < b) Pair(a, b) else Pair(b, a)
                if (linkPairs.none { (if (it.first < it.second) it else Pair(it.second, it.first)) == key }) {
                    linkPairs.add(Pair(a, b))
                }
            }

            exchanges.asSequence()
                .sortedByDescending { abs(it.value) }
                .forEach { addLink(it.fromId, it.toId) }
            predictions.asSequence()
                .sortedByDescending { it.confidence }
                .forEach { addLink(it.sourceId, it.targetId) }
            working?.crossModalLinks.orEmpty()
                .asSequence()
                .sortedByDescending { it.confidence }
                .forEach {
                    addLink(it.jointId, it.audioId)
                    addLink(it.jointId, it.visualId)
                }

            val linkData = FloatArray(MAX_LINKS * 4)
            val linkKeys = LongArray(MAX_LINKS)
            linkPairs.take(linkBudget).forEachIndexed { index, pair ->
                val a = positionById[pair.first] ?: return@forEachIndexed
                val b = positionById[pair.second] ?: return@forEachIndexed
                val lo = min(pair.first, pair.second)
                val hi = max(pair.first, pair.second)
                linkKeys[index] = (lo.toLong() shl 32) xor (hi.toLong() and 0xffffffffL)
                val base = index * 4
                linkData[base] = a.first
                linkData[base + 1] = a.second
                linkData[base + 2] = b.first
                linkData[base + 3] = b.second
            }

            pending.set(
                Packet(
                    frame = frame,
                    memoryEnergy = unified.memoryEnergy.toFloat(),
                    signedExchange = unified.signedExchange.toFloat(),
                    expectation = unified.expectation.toFloat(),
                    prediction = unified.prediction.toFloat(),
                    noveltyProxy = unified.novelty.toFloat(),
                    crossmodal = unified.crossmodal.toFloat(),
                    tierDepth = unified.tierDepth.toFloat(),
                    retrievalShare = unified.retrievalShare.toFloat(),
                    fields = fieldData,
                    fieldIds = fieldIds,
                    fieldCount = strongest.size,
                    links = linkData,
                    linkKeys = linkKeys,
                    linkCount = linkPairs.size.coerceAtMost(linkBudget),
                    info = info
                )
            )
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.002f, 0.004f, 0.008f, 1f)
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            uSummary = GLES20.glGetUniformLocation(program, "uSummary")
            uHypnoSummary = GLES20.glGetUniformLocation(program, "uHypnoSummary")
            uBlend = GLES20.glGetUniformLocation(program, "uBlend")
            uPresentation = GLES20.glGetUniformLocation(program, "uPresentation")
            uTexel = GLES20.glGetUniformLocation(program, "uTexel")
            uFieldCount = GLES20.glGetUniformLocation(program, "uFieldCount")
            uFields = GLES20.glGetUniformLocation(program, "uFields[0]")
            uLinkCount = GLES20.glGetUniformLocation(program, "uLinkCount")
            uLinks = GLES20.glGetUniformLocation(program, "uLinks[0]")
            uLinkAlpha = GLES20.glGetUniformLocation(program, "uLinkAlpha[0]")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            uploadFallbackTexture()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val start = System.nanoTime()
            pending.getAndSet(null)?.let { packet ->
                current = packet
                if (packet.frame != null) currentFrame = packet.frame
                acceptTargets(packet)
            }
            val packet = current
            val now = System.nanoTime()
            val dt = if (lastAnimNanos == 0L) 1.0 / 60.0 else ((now - lastAnimNanos) / 1_000_000_000.0).coerceIn(0.0, 0.10)
            lastAnimNanos = now
            animationActive = advanceVisualState(dt, packet)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (packet == null || program == 0 || textureId == 0) {
                lastRenderCostMs = (System.nanoTime() - start) / 1_000_000.0
                return
            }

            currentFrame?.let { uploadTexture(it) }

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniform4f(
                uSummary,
                displayMemoryEnergy,
                displaySignedExchange,
                displayExpectation,
                displayPrediction
            )
            GLES20.glUniform4f(
                uHypnoSummary,
                displayNoveltyProxy,
                displayCrossmodal,
                displayTierDepth,
                displayRetrievalShare
            )
            GLES20.glUniform1f(uBlend, displayBlend)
            GLES20.glUniform1f(uPresentation, displayHypno)
            GLES20.glUniform2f(
                uTexel,
                1f / textureW.coerceAtLeast(1).toFloat(),
                1f / textureH.coerceAtLeast(1).toFloat()
            )
            val renderFields = buildSmoothedFieldArray()
            val renderLinks = buildSmoothedLinkArray()
            GLES20.glUniform1i(uFieldCount, renderFields.second)
            GLES20.glUniform4fv(uFields, MAX_FIELDS, renderFields.first, 0)
            GLES20.glUniform1i(uLinkCount, renderLinks.second)
            GLES20.glUniform4fv(uLinks, MAX_LINKS, renderLinks.first, 0)
            GLES20.glUniform1fv(uLinkAlpha, MAX_LINKS, renderLinks.third, 0)

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
            if (animationActive) scheduleNextFrame()
        }

        private fun acceptTargets(packet: Packet) {
            smoothFields.values.forEach { it.seenThisPacket = false }
            for (i in 0 until packet.fieldCount.coerceAtMost(MAX_FIELDS)) {
                val id = packet.fieldIds[i]
                if (id == Int.MIN_VALUE) continue
                val base = i * 4
                val tx = packet.fields[base]
                val ty = packet.fields[base + 1]
                val ts = packet.fields[base + 2]
                val tt = packet.fields[base + 3]
                val state = smoothFields[id]
                if (state == null) {
                    smoothFields[id] = SmoothField(id, tx, ty, 0f, tt, tx, ty, ts, tt, true)
                } else {
                    state.targetX = tx
                    state.targetY = ty
                    state.targetStrength = ts
                    state.targetTier = tt
                    state.seenThisPacket = true
                }
            }
            smoothFields.values.filter { !it.seenThisPacket }.forEach { it.targetStrength = 0f }

            smoothLinks.values.forEach { it.seenThisPacket = false }
            for (i in 0 until packet.linkCount.coerceAtMost(MAX_LINKS)) {
                val key = packet.linkKeys[i]
                if (key == 0L) continue
                val base = i * 4
                val ax = packet.links[base]
                val ay = packet.links[base + 1]
                val bx = packet.links[base + 2]
                val by = packet.links[base + 3]
                val state = smoothLinks[key]
                if (state == null) {
                    smoothLinks[key] = SmoothLink(key, ax, ay, bx, by, 0f, ax, ay, bx, by, 1f, true)
                } else {
                    state.targetAx = ax
                    state.targetAy = ay
                    state.targetBx = bx
                    state.targetBy = by
                    state.targetAlpha = 1f
                    state.seenThisPacket = true
                }
            }
            smoothLinks.values.filter { !it.seenThisPacket }.forEach { it.targetAlpha = 0f }
            animationActive = true
        }

        private fun advanceVisualState(dt: Double, packet: Packet?): Boolean {
            fun alpha(rate: Double): Float = (1.0 - kotlin.math.exp(-rate * dt)).toFloat().coerceIn(0f, 1f)
            val fast = alpha(12.0)
            val medium = alpha(8.0)
            val slow = alpha(5.0)

            val targetMemory = packet?.memoryEnergy ?: 0f
            val targetExchange = packet?.signedExchange ?: 0f
            val targetExpectation = packet?.expectation ?: 0f
            val targetPrediction = packet?.prediction ?: 0f
            val targetNovelty = packet?.noveltyProxy ?: 0f
            val targetCrossmodal = packet?.crossmodal ?: 0f
            val targetTierDepth = packet?.tierDepth ?: 0f
            val targetRetrievalShare = packet?.retrievalShare ?: 0f
            val targetHypno = if (presentationMode == UnifiedPresentationMode.HYPNO) 1f else 0f
            displayMemoryEnergy += (targetMemory - displayMemoryEnergy) * medium
            displaySignedExchange += (targetExchange - displaySignedExchange) * medium
            displayExpectation += (targetExpectation - displayExpectation) * medium
            displayPrediction += (targetPrediction - displayPrediction) * medium
            displayNoveltyProxy += (targetNovelty - displayNoveltyProxy) * medium
            displayCrossmodal += (targetCrossmodal - displayCrossmodal) * medium
            displayTierDepth += (targetTierDepth - displayTierDepth) * slow
            displayRetrievalShare += (targetRetrievalShare - displayRetrievalShare) * medium
            displayBlend += (memoryBlend - displayBlend) * fast
            displayHypno += (targetHypno - displayHypno) * fast

            smoothFields.values.forEach { f ->
                f.x += (f.targetX - f.x) * slow
                f.y += (f.targetY - f.y) * slow
                f.strength += (f.targetStrength - f.strength) * medium
                f.tier += (f.targetTier - f.tier) * slow
            }
            smoothLinks.values.forEach { l ->
                l.ax += (l.targetAx - l.ax) * slow
                l.ay += (l.targetAy - l.ay) * slow
                l.bx += (l.targetBx - l.bx) * slow
                l.by += (l.targetBy - l.by) * slow
                l.alpha += (l.targetAlpha - l.alpha) * medium
            }

            smoothFields.entries.removeAll { (_, f) -> f.targetStrength <= 0f && f.strength < 0.004f }
            smoothLinks.entries.removeAll { (_, l) -> l.targetAlpha <= 0f && l.alpha < 0.004f }

            val fieldMotion = smoothFields.values.any {
                kotlin.math.abs(it.targetStrength - it.strength) > 0.003f ||
                    kotlin.math.abs(it.targetTier - it.tier) > 0.01f ||
                    kotlin.math.abs(it.targetX - it.x) > 0.001f ||
                    kotlin.math.abs(it.targetY - it.y) > 0.001f
            }
            val linkMotion = smoothLinks.values.any { kotlin.math.abs(it.targetAlpha - it.alpha) > 0.003f }
            val summaryMotion = kotlin.math.abs(targetMemory - displayMemoryEnergy) > 0.003f ||
                kotlin.math.abs(targetExchange - displaySignedExchange) > 0.003f ||
                kotlin.math.abs(targetExpectation - displayExpectation) > 0.003f ||
                kotlin.math.abs(targetPrediction - displayPrediction) > 0.003f ||
                kotlin.math.abs(targetNovelty - displayNoveltyProxy) > 0.003f ||
                kotlin.math.abs(targetCrossmodal - displayCrossmodal) > 0.003f ||
                kotlin.math.abs(targetTierDepth - displayTierDepth) > 0.003f ||
                kotlin.math.abs(targetRetrievalShare - displayRetrievalShare) > 0.003f ||
                kotlin.math.abs(memoryBlend - displayBlend) > 0.003f ||
                kotlin.math.abs(targetHypno - displayHypno) > 0.003f
            return fieldMotion || linkMotion || summaryMotion
        }

        private fun buildSmoothedFieldArray(): Pair<FloatArray, Int> {
            val selected = smoothFields.values
                .filter { it.strength > 0.003f }
                .sortedByDescending { it.strength }
                .take(MAX_FIELDS)
            val data = FloatArray(MAX_FIELDS * 4)
            selected.forEachIndexed { i, f ->
                val base = i * 4
                data[base] = f.x
                data[base + 1] = f.y
                data[base + 2] = f.strength.coerceIn(0f, 1f)
                data[base + 3] = f.tier.coerceAtLeast(0f)
            }
            return Pair(data, selected.size)
        }

        private fun buildSmoothedLinkArray(): Triple<FloatArray, Int, FloatArray> {
            val selected = smoothLinks.values
                .filter { it.alpha > 0.003f }
                .sortedByDescending { it.alpha }
                .take(MAX_LINKS)
            val data = FloatArray(MAX_LINKS * 4)
            val alpha = FloatArray(MAX_LINKS)
            selected.forEachIndexed { i, l ->
                val base = i * 4
                data[base] = l.ax
                data[base + 1] = l.ay
                data[base + 2] = l.bx
                data[base + 3] = l.by
                alpha[i] = l.alpha.coerceIn(0f, 1f)
            }
            return Triple(data, selected.size, alpha)
        }

        private fun uploadFallbackTexture() {
            val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
            repeat(4) {
                buffer.put(0)
                buffer.put(0)
                buffer.put(0)
                buffer.put(0xff.toByte())
            }
            buffer.flip()
            textureW = 2
            textureH = 2
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
            )
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
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    frame.width, frame.height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
                )
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    frame.width, frame.height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
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
            // Keep fragment-uniform pressure inside conservative OpenGL ES 2.0 limits.
            private const val MAX_FIELDS = 7
            private const val MAX_LINKS = 5

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
                // x=memory energy, y=signed exchange, z=expectation, w=prediction
                uniform vec4 uSummary;
                // x=novelty proxy, y=crossmodal coherence, z=active tier depth, w=retrieval share
                uniform vec4 uHypnoSummary;
                uniform float uBlend;
                // 0=technical structure/tissue, 1=primary organic Hypno projection
                uniform float uPresentation;
                uniform vec2 uTexel;
                uniform int uFieldCount;
                // x/y=renderer-only position, z=strength, w=zero-based abstraction tier
                uniform vec4 uFields[7];
                uniform int uLinkCount;
                // x/y=start, z/w=end
                uniform vec4 uLinks[5];
                uniform float uLinkAlpha[5];
                varying vec2 vTex;

                float sdSegment(vec2 p, vec2 a, vec2 b) {
                    vec2 pa = p - a;
                    vec2 ba = b - a;
                    float h = clamp(dot(pa, ba) / max(0.0001, dot(ba, ba)), 0.0, 1.0);
                    return length(pa - ba * h);
                }

                void main() {
                    float blend = clamp(uBlend, 0.0, 1.0);
                    float reality = 1.0 - blend;
                    float memory = clamp(uSummary.x, 0.0, 1.0);
                    float signedEx = clamp(uSummary.y, -1.0, 1.0);
                    float expectation = clamp(uSummary.z, 0.0, 1.0);
                    float prediction = clamp(uSummary.w, 0.0, 1.0);
                    float novelty = clamp(uHypnoSummary.x, 0.0, 1.0);
                    float crossmodal = clamp(uHypnoSummary.y, 0.0, 1.0);
                    float tierDepth = clamp(uHypnoSummary.z, 0.0, 1.0);
                    float retrievalShare = clamp(uHypnoSummary.w, 0.0, 1.0);
                    float hypno = clamp(uPresentation, 0.0, 1.0);

                    // Tier-aware organization:
                    // F/E/D are fine local fibers, C..SS become membranes/tissue,
                    // SSS becomes a broad density field, Ω+n becomes a domain-scale organizer.
                    // No tier is treated as automatically more important: strength still comes from
                    // current activity/relevance, while tier only changes spatial expression.
                    vec2 uv = vTex;
                    float fineFiber = 0.0;
                    float localTissue = 0.0;
                    float densityField = 0.0;
                    float domainField = 0.0;
                    float coreSpark = 0.0;
                    float hypnoAura = 0.0;
                    float hypnoInterference = 0.0;
                    vec2 hierarchyWarp = vec2(0.0);
                    vec2 hypnoWarp = vec2(0.0);

                    for (int i = 0; i < 7; ++i) {
                        if (i < uFieldCount) {
                            vec4 f = uFields[i];
                            vec2 delta = vTex - f.xy;
                            float d = length(delta);
                            float d2 = 0.024 + dot(delta, delta);
                            float tier = max(0.0, f.w);
                            float strength = clamp(f.z, 0.0, 1.0);

                            // Continuous category weights around the RPG abstraction scale.
                            float low = 1.0 - smoothstep(2.0, 4.0, tier);
                            float midIn = smoothstep(2.0, 4.0, tier);
                            float midOut = 1.0 - smoothstep(6.5, 8.2, tier);
                            float mid = midIn * midOut;
                            float high = smoothstep(6.5, 9.2, tier);
                            float omega = smoothstep(8.8, 10.5, tier);

                            // Low tiers remain filamentary and local instead of appearing as big dots.
                            float fiberRadius = 0.010 + 0.010 * strength;
                            fineFiber += (1.0 - smoothstep(fiberRadius, fiberRadius + 0.022, d)) * strength * low;
                            coreSpark += (1.0 - smoothstep(0.0035, 0.010, d)) * strength * low * 0.55;

                            // Mid tiers are soft membranes/densifications rather than point markers.
                            float tissueRadius = 0.060 + 0.035 * strength;
                            localTissue += (1.0 - smoothstep(tissueRadius, tissueRadius + 0.070, d)) * strength * mid;

                            // SSS and nearby tiers organize larger regional density.
                            float fieldRadius = 0.14 + 0.06 * strength;
                            densityField += (1.0 - smoothstep(fieldRadius, fieldRadius + 0.16, d)) * strength * high;

                            // Ω and Ω+n act as broad organizing fields, not giant circles.
                            // Their influence is deliberately low-frequency and distributed.
                            float domain = (1.0 - smoothstep(0.22, 0.62, d)) * strength * omega;
                            domainField += domain;

                            // Hypno does not draw a node graph. The same fields become overlapping
                            // continuous auras and state-locked interference bands. There is no
                            // clock/time term here: motion can only come from changing/interpolated state.
                            float auraRadius = 0.12 + 0.035 * tier + 0.08 * strength;
                            float aura = (1.0 - smoothstep(auraRadius, auraRadius + 0.22, d)) * strength;
                            float band = 0.5 + 0.5 * sin(d * (34.0 + 24.0 * novelty) - tier * 0.72 + signedEx * 2.4);
                            hypnoAura += aura;
                            hypnoInterference += aura * band * (0.35 + 0.65 * (prediction + crossmodal) * 0.5);

                            vec2 radial = delta / max(0.08, d);
                            vec2 tangent = vec2(-radial.y, radial.x);
                            hierarchyWarp -= radial * (0.00010 * fineFiber / d2);
                            hierarchyWarp += tangent * (0.00018 * localTissue / d2) * sign(signedEx + 0.0001);
                            hierarchyWarp -= radial * domain * (0.0010 + 0.0012 * memory);
                            hypnoWarp += tangent * aura * (0.0010 + 0.0018 * crossmodal) * sign(signedEx + 0.0001);
                            hypnoWarp -= radial * aura * (0.00045 + 0.0012 * prediction);
                        }
                    }

                    fineFiber = min(1.25, fineFiber);
                    localTissue = min(1.25, localTissue);
                    densityField = min(1.25, densityField);
                    domainField = min(1.0, domainField);
                    coreSpark = min(1.0, coreSpark);
                    hypnoAura = min(1.35, hypnoAura);
                    hypnoInterference = min(1.25, hypnoInterference);

                    // Memory depth shapes the same camera pigment. Hypno adds only state-driven
                    // field flow; there is still no artificial time wobble.
                    uv += mix(hierarchyWarp * (0.30 + 0.70 * reality),
                              (hierarchyWarp * 0.18 + hypnoWarp) * (0.42 + 0.58 * memory), hypno);
                    uv = clamp(uv, vec2(0.002), vec2(0.998));

                    vec2 chroma = vec2(0.0017 * signedEx * memory * reality, -0.0012 * signedEx * memory * reality);
                    vec3 center = texture2D(uTexture, uv).rgb;
                    float rr = texture2D(uTexture, clamp(uv + chroma, vec2(0.002), vec2(0.998))).r;
                    float bb = texture2D(uTexture, clamp(uv - chroma, vec2(0.002), vec2(0.998))).b;
                    vec3 camera = vec3(rr, center.g, bb);
                    camera = (camera - 0.5) * (1.0 + 0.20 * memory * reality) + 0.5;
                    camera *= 1.0 + 0.07 * expectation * reality;

                    // Camera edges become a bridge during the Reality -> Memory transition.
                    float l = dot(texture2D(uTexture, clamp(uv - vec2(uTexel.x, 0.0), vec2(0.002), vec2(0.998))).rgb, vec3(0.299, 0.587, 0.114));
                    float r = dot(texture2D(uTexture, clamp(uv + vec2(uTexel.x, 0.0), vec2(0.002), vec2(0.998))).rgb, vec3(0.299, 0.587, 0.114));
                    float t = dot(texture2D(uTexture, clamp(uv - vec2(0.0, uTexel.y), vec2(0.002), vec2(0.998))).rgb, vec3(0.299, 0.587, 0.114));
                    float b = dot(texture2D(uTexture, clamp(uv + vec2(0.0, uTexel.y), vec2(0.002), vec2(0.998))).rgb, vec3(0.299, 0.587, 0.114));
                    float cameraEdge = clamp(abs(r - l) + abs(b - t), 0.0, 1.0);

                    float linkGlow = 0.0;
                    for (int i = 0; i < 5; ++i) {
                        if (i < uLinkCount) {
                            vec4 ln = uLinks[i];
                            float d = sdSegment(vTex, ln.xy, ln.zw);
                            linkGlow += (1.0 - smoothstep(0.0025, 0.017, d)) * clamp(uLinkAlpha[i], 0.0, 1.0);
                        }
                    }
                    linkGlow = min(1.4, linkGlow);

                    vec3 memoryBg = vec3(0.002, 0.004, 0.008);
                    vec3 tissueCyan = vec3(0.10, 0.78, 0.92);
                    vec3 tissueGold = vec3(0.95, 0.66, 0.22);
                    vec3 tissueMagenta = vec3(0.84, 0.18, 0.72);
                    vec3 exchangeTint = signedEx >= 0.0 ? tissueCyan : tissueMagenta;

                    vec3 tissue = memoryBg;
                    // Fine tiers: thread-like cyan/gold filaments.
                    tissue += mix(tissueCyan, tissueGold, 0.28 + 0.35 * prediction) * fineFiber * (0.12 + 0.55 * memory);
                    tissue += vec3(1.0, 0.78, 0.36) * coreSpark * (0.08 + 0.35 * expectation);
                    // Mid tiers: translucent living membrane / local density.
                    tissue += mix(exchangeTint, tissueGold, 0.35) * localTissue * (0.10 + 0.42 * memory);
                    // High tiers: broad fields that organize multiple local structures.
                    tissue += mix(tissueMagenta, tissueCyan, prediction) * densityField * (0.07 + 0.28 * memory);
                    // Ω+n: very broad, quiet domain tint instead of a huge visible node.
                    tissue += mix(vec3(0.06, 0.05, 0.16), tissueGold, 0.18 + 0.30 * expectation) * domainField * (0.08 + 0.22 * memory);
                    // Persistent relation skeleton still ties the hierarchy together.
                    tissue += mix(tissueCyan, tissueGold, prediction) * linkGlow * (0.10 + 0.50 * memory);

                    // At mid blend, real camera geometry is allowed to grow into the same tissue.
                    float bridge = 4.0 * blend * (1.0 - blend);
                    float hierarchyBridge = max(fineFiber, max(localTissue, densityField));
                    tissue += mix(tissueCyan, tissueGold, 0.42) * cameraEdge * bridge * (0.08 + 0.25 * memory);
                    tissue += exchangeTint * cameraEdge * hierarchyBridge * bridge * 0.08;

                    // One continuous surface: Reality and Memory are states of the same renderer.
                    vec3 finalColor = mix(camera, tissue, blend);
                    // Keep subtle hierarchy traces in Reality without turning it back into a node graph.
                    finalColor += exchangeTint * localTissue * memory * reality * 0.018;
                    finalColor += mix(tissueCyan, tissueGold, prediction) * fineFiber * memory * reality * 0.022;
                    finalColor += mix(tissueMagenta, tissueCyan, 0.5) * domainField * memory * reality * 0.012;

                    // HYPNO: an organic, continuous projection of the exact same Unified State.
                    // Activity controls presence; prediction controls coherence; signed exchange
                    // controls flow polarity; novelty sharpens interference; tier depth broadens
                    // the field; crossmodal confidence fuses the cyan/gold families. Explicit
                    // relation lines are intentionally absent from this consumer presentation.
                    vec3 hypnoDeep = vec3(0.004, 0.006, 0.014);
                    vec3 hypnoCool = vec3(0.06, 0.58, 0.92);
                    vec3 hypnoWarm = vec3(0.98, 0.55, 0.16);
                    vec3 hypnoViolet = vec3(0.58, 0.12, 0.86);
                    vec3 coherent = mix(hypnoViolet, hypnoCool, 0.34 + 0.58 * crossmodal);
                    coherent = mix(coherent, hypnoWarm, 0.12 + 0.42 * prediction);
                    float organic = clamp(0.56 * hypnoAura + 0.62 * hypnoInterference, 0.0, 1.4);
                    float noveltyRim = pow(clamp(hypnoInterference, 0.0, 1.0), 1.6) * novelty;
                    float calmDepth = tierDepth * (0.18 + 0.38 * retrievalShare);
                    vec3 hypnoMemory = hypnoDeep;
                    hypnoMemory += coherent * organic * (0.14 + 0.56 * memory);
                    hypnoMemory += mix(hypnoCool, hypnoWarm, 0.5 + 0.5 * signedEx) * noveltyRim * 0.30;
                    hypnoMemory += mix(hypnoViolet, hypnoCool, crossmodal) * calmDepth * hypnoAura * 0.16;
                    // Camera is pigment, not geometry authority. Toward Reality it remains legible;
                    // toward Memory the organic state projection takes over.
                    vec3 hypnoCamera = camera * (0.90 + 0.08 * prediction);
                    hypnoCamera += coherent * cameraEdge * (0.025 + 0.09 * crossmodal) * memory;
                    vec3 hypnoColor = mix(hypnoCamera, hypnoMemory, blend);
                    // A small state trace remains at the Reality side without exposing graph lines.
                    hypnoColor += coherent * organic * memory * reality * 0.025;

                    finalColor = mix(finalColor, hypnoColor, hypno);
                    gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
                }
            """
        }
    }
}
