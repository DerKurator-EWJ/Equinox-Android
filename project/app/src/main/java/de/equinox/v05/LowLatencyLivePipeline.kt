package de.equinox.v05

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Step 27 low-latency presentation controller.
 *
 * The camera/analyzer and the Android UI are deliberately decoupled. Frames and compact
 * working-set snapshots enter bounded ring buffers. Rendering always consumes the newest
 * frame that satisfies the requested presentation delay; all older unrendered frames are
 * dropped. This prevents a slow renderer from building a latency queue.
 *
 * AUTO delay is based on an EWMA of analyzer + previous draw cost. The delay is a presentation
 * choice only: it does not alter learning, memory or the sensor timestamps.
 */
class LowLatencyLivePipeline {
    enum class DelayPreset(val label: String, val fixedMs: Long?) {
        AUTO("AUTO", null),
        MS_0("0 ms", 0L),
        MS_33("33 ms", 33L),
        MS_66("66 ms", 66L),
        MS_100("100 ms", 100L),
        MS_200("200 ms", 200L),
        MS_500("500 ms", 500L);

        companion object {
            fun fromName(name: String?): DelayPreset = entries.firstOrNull { it.name == name } ?: AUTO
        }
    }

    data class TimedWorkingState(
        val timestampMs: Long,
        val working: WorkingSetSnapshot
    )

    data class RenderPacket(
        val frame: VideoFrameSnapshot,
        val working: WorkingSetSnapshot?,
        val configuredPreset: DelayPreset,
        val effectiveDelayMs: Long,
        val actualFrameAgeMs: Long,
        val droppedFramesTotal: Long,
        val bufferedFrames: Int,
        val bufferedStates: Int
    )

    data class Stats(
        val preset: DelayPreset,
        val effectiveDelayMs: Long,
        val analysisCostEwmaMs: Double,
        val renderCostEwmaMs: Double,
        val offeredFrames: Long,
        val renderedFrames: Long,
        val droppedFrames: Long,
        val bufferedFrames: Int,
        val bufferedStates: Int
    )

    private val frames = ArrayDeque<VideoFrameSnapshot>()
    private val states = ArrayDeque<TimedWorkingState>()
    private var preset = DelayPreset.AUTO
    private var analysisCostEwmaMs = 45.0
    private var renderCostEwmaMs = 12.0
    private var offeredFrames = 0L
    private var renderedFrames = 0L
    private var droppedFrames = 0L

    private val maxFrames = 24
    private val maxStates = 48

    @Synchronized
    fun setPreset(newPreset: DelayPreset) {
        preset = newPreset
        trimLocked(System.currentTimeMillis())
    }

    @Synchronized
    fun getPreset(): DelayPreset = preset

    @Synchronized
    fun reportAnalysisCost(costMs: Double) {
        if (!costMs.isFinite() || costMs < 0.0) return
        analysisCostEwmaMs = ewma(analysisCostEwmaMs, costMs.coerceAtMost(500.0), 0.16)
    }

    @Synchronized
    fun reportRenderCost(costMs: Double) {
        if (!costMs.isFinite() || costMs < 0.0) return
        renderCostEwmaMs = ewma(renderCostEwmaMs, costMs.coerceAtMost(250.0), 0.18)
    }

    @Synchronized
    fun offerFrame(frame: VideoFrameSnapshot) {
        offeredFrames++
        frames.addLast(frame)
        while (frames.size > maxFrames) {
            frames.removeFirst()
            droppedFrames++
        }
        trimLocked(System.currentTimeMillis())
    }

    @Synchronized
    fun offerState(working: WorkingSetSnapshot, timestampMs: Long = System.currentTimeMillis()) {
        states.addLast(TimedWorkingState(timestampMs, working))
        while (states.size > maxStates) states.removeFirst()
        trimLocked(timestampMs)
    }

    /**
     * Returns at most one frame. Old frames before the chosen one are discarded intentionally.
     * If the requested delay has not yet elapsed, null is returned; the next camera/state event
     * will try again rather than posting another queued UI job.
     */
    @Synchronized
    fun poll(nowMs: Long = System.currentTimeMillis()): RenderPacket? {
        if (frames.isEmpty()) return null
        val delay = effectiveDelayLocked()
        val targetTimestamp = nowMs - delay

        var chosenIndex = -1
        var i = 0
        for (frame in frames) {
            if (frame.timestampMs <= targetTimestamp || delay == 0L) chosenIndex = i
            i++
        }
        if (chosenIndex < 0) return null

        var chosen: VideoFrameSnapshot? = null
        repeat(chosenIndex + 1) {
            val removed = frames.removeFirst()
            if (it < chosenIndex) droppedFrames++ else chosen = removed
        }
        val frame = chosen ?: return null
        renderedFrames++

        // State generation may trail the sensor frame because analysis happens first. Pair with
        // the state nearest to frame + half the presentation delay, never with future UI time.
        val desiredStateTs = frame.timestampMs + (delay / 2L).coerceAtMost(120L)
        val state = nearestStateLocked(desiredStateTs)

        trimLocked(nowMs)
        return RenderPacket(
            frame = frame,
            working = state?.working,
            configuredPreset = preset,
            effectiveDelayMs = delay,
            actualFrameAgeMs = (nowMs - frame.timestampMs).coerceAtLeast(0L),
            droppedFramesTotal = droppedFrames,
            bufferedFrames = frames.size,
            bufferedStates = states.size
        )
    }

    @Synchronized
    fun clearFrames() {
        frames.clear()
    }

    @Synchronized
    fun clearAll() {
        frames.clear()
        states.clear()
    }

    @Synchronized
    fun stats(): Stats = Stats(
        preset = preset,
        effectiveDelayMs = effectiveDelayLocked(),
        analysisCostEwmaMs = analysisCostEwmaMs,
        renderCostEwmaMs = renderCostEwmaMs,
        offeredFrames = offeredFrames,
        renderedFrames = renderedFrames,
        droppedFrames = droppedFrames,
        bufferedFrames = frames.size,
        bufferedStates = states.size
    )

    private fun nearestStateLocked(targetMs: Long): TimedWorkingState? {
        if (states.isEmpty()) return null
        var best: TimedWorkingState? = null
        var bestDistance = Long.MAX_VALUE
        for (state in states) {
            val d = abs(state.timestampMs - targetMs)
            if (d < bestDistance) {
                bestDistance = d
                best = state
            }
        }
        return best ?: states.peekLast()
    }

    private fun effectiveDelayLocked(): Long {
        preset.fixedMs?.let { return it }
        // Keep the smallest stable frame age. Quantizing to a display-frame interval avoids
        // constantly changing delay labels and leaves a little scheduling margin.
        val estimated = max(16.0, analysisCostEwmaMs + renderCostEwmaMs + 12.0)
        val quantized = ceil(estimated / 16.67) * 16.67
        return quantized.toLong().coerceIn(33L, 250L)
    }

    private fun trimLocked(nowMs: Long) {
        val horizon = max(1_500L, effectiveDelayLocked() + 900L)
        while (frames.isNotEmpty() && nowMs - frames.peekFirst().timestampMs > horizon) {
            frames.removeFirst()
            droppedFrames++
        }
        while (states.size > 1 && nowMs - states.peekFirst().timestampMs > horizon * 2L) {
            states.removeFirst()
        }
    }

    private fun ewma(old: Double, sample: Double, alpha: Double): Double = old + alpha * (sample - old)
}
