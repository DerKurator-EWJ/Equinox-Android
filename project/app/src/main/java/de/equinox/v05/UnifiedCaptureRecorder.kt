package de.equinox.v05

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Step 36 — one capture path for the same Unified Canvas and the same generated orchestra.
 *
 * Video is captured from Android's MediaProjection into H.264. Unified Audio PCM is tapped
 * before AudioTrack, encoded to AAC, and muxed into the same MP4. No camera/raw-memory
 * data is exported separately and recording never writes back into MemoryModel.
 */
class UnifiedCaptureRecorder(private val context: Context) {
    data class Result(val uri: Uri?, val path: String?, val durationMs: Long, val error: String? = null)

    private data class EncodedSample(val kind: Int, val bytes: ByteArray, val info: MediaCodec.BufferInfo)

    companion object {
        private const val VIDEO = 1
        private const val AUDIO = 2
        private const val AUDIO_RATE = 48_000
        private const val AUDIO_CHUNK = 960 // 20 ms
    }

    private val pcmQueue = ArrayBlockingQueue<ShortArray>(80)
    private val active = AtomicBoolean(false)
    @Volatile private var stopping = false
    @Volatile private var startedAtMs = 0L
    @Volatile private var statusText = "Bereit"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var outputUri: Uri? = null
    private var outputPath: String? = null
    private var worker: Thread? = null

    fun isRecording(): Boolean = active.get()
    fun status(): String = statusText

    /** Called from the orchestra audio thread. Never blocks that thread. */
    fun offerPcm(pcm: ShortArray) {
        if (!active.get() || stopping) return
        val copy = pcm.copyOf()
        if (!pcmQueue.offer(copy)) {
            pcmQueue.poll()
            pcmQueue.offer(copy)
        }
    }

    fun start(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        densityDpi: Int,
        onState: (String) -> Unit,
        onFinished: (Result) -> Unit
    ) {
        if (!active.compareAndSet(false, true)) return
        stopping = false
        pcmQueue.clear()
        startedAtMs = System.currentTimeMillis()
        statusText = "Recorder wird vorbereitet …"
        onState(statusText)

        try {
            val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (active.get()) stop(onState, onFinished)
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))

            val w = max(2, width and -2)
            val h = max(2, height and -2)
            val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 7).coerceIn(4_000_000, 24_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = video.createInputSurface()

            val audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK * 2 * 4)
            }
            audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val output = createOutput()
            outputUri = output.first
            outputPath = output.second
            val fileDescriptor = output.first?.let { context.contentResolver.openFileDescriptor(it, "rw") }
            val m = when {
                fileDescriptor != null -> MediaMuxer(fileDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                output.second != null -> MediaMuxer(output.second!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                else -> error("Kein Ausgabepfad")
            }
            // MediaMuxer duplicates/uses the descriptor during its lifetime. Keep the PFD alive
            // by closing it only after muxer stop through the worker's local reference.

            videoCodec = video
            audioCodec = audio
            muxer = m
            video.start()
            audio.start()

            virtualDisplay = projection.createVirtualDisplay(
                "EquinoxUnifiedCapture",
                w,
                h,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            statusText = "REC · ${w}×${h} · H.264 + AAC"
            onState(statusText)
            worker = thread(name = "EquinoxCaptureMuxer") {
                runMuxLoop(video, audio, m, fileDescriptor, onState, onFinished)
            }
        } catch (e: Exception) {
            active.set(false)
            releaseAll(deleteIncomplete = true)
            statusText = "Recorder-Fehler: ${e.javaClass.simpleName}"
            onState(statusText)
            onFinished(Result(outputUri, outputPath, 0L, e.message ?: e.javaClass.simpleName))
        }
    }

    fun stop(onState: (String) -> Unit, onFinished: (Result) -> Unit) {
        if (!active.get() || stopping) return
        stopping = true
        statusText = "Aufnahme wird abgeschlossen …"
        onState(statusText)
        // Worker observes stopping, queues EOS and finalizes the muxer.
    }

    private fun runMuxLoop(
        video: MediaCodec,
        audio: MediaCodec,
        m: MediaMuxer,
        outputPfd: android.os.ParcelFileDescriptor?,
        onState: (String) -> Unit,
        onFinished: (Result) -> Unit
    ) {
        var videoTrack = -1
        var audioTrack = -1
        var muxStarted = false
        var audioSamples = 0L
        var videoBasePts = -1L
        var audioBasePts = -1L
        var videoEos = false
        var audioEos = false
        var audioInputEos = false
        var videoInputEos = false
        val pending = ArrayList<EncodedSample>(32)
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var nextAudioFeedNs = System.nanoTime()

        fun normalized(info: MediaCodec.BufferInfo, kind: Int): MediaCodec.BufferInfo {
            val base = if (kind == VIDEO) {
                if (videoBasePts < 0L) videoBasePts = info.presentationTimeUs
                videoBasePts
            } else {
                if (audioBasePts < 0L) audioBasePts = info.presentationTimeUs
                audioBasePts
            }
            return MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, (info.presentationTimeUs - base).coerceAtLeast(0L), info.flags)
            }
        }

        fun writeOrHold(kind: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
            val normalized = normalized(info, kind)
            val dup = buffer.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            }
            if (muxStarted) {
                m.writeSampleData(if (kind == VIDEO) videoTrack else audioTrack, dup, normalized)
            } else {
                val bytes = ByteArray(info.size)
                dup.get(bytes)
                pending += EncodedSample(kind, bytes, normalized)
            }
        }

        fun tryStartMux() {
            if (!muxStarted && videoTrack >= 0 && audioTrack >= 0) {
                m.start()
                muxStarted = true
                pending.sortedBy { it.info.presentationTimeUs }.forEach { sample ->
                    val track = if (sample.kind == VIDEO) videoTrack else audioTrack
                    m.writeSampleData(track, ByteBuffer.wrap(sample.bytes), sample.info)
                }
                pending.clear()
            }
        }

        try {
            while (!(videoEos && audioEos)) {
                val nowNs = System.nanoTime()
                if (!audioInputEos && nowNs >= nextAudioFeedNs) {
                    val chunk = pcmQueue.poll() ?: ShortArray(AUDIO_CHUNK)
                    val idx = audio.dequeueInputBuffer(0)
                    if (idx >= 0) {
                        val input = audio.getInputBuffer(idx)
                        input?.clear()
                        val count = minOf(chunk.size, (input?.remaining() ?: 0) / 2)
                        if (input != null) {
                            for (i in 0 until count) input.putShort(chunk[i])
                        }
                        val pts = audioSamples * 1_000_000L / AUDIO_RATE
                        audioSamples += count
                        audio.queueInputBuffer(idx, 0, count * 2, pts, 0)
                    }
                    nextAudioFeedNs += 20_000_000L
                }

                if (stopping && !videoInputEos) {
                    try { video.signalEndOfInputStream() } catch (_: Exception) { }
                    videoInputEos = true
                }
                if (stopping && !audioInputEos) {
                    val idx = audio.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val pts = audioSamples * 1_000_000L / AUDIO_RATE
                        audio.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioInputEos = true
                    }
                }

                while (true) {
                    val out = video.dequeueOutputBuffer(videoInfo, 0)
                    when {
                        out == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoTrack < 0) videoTrack = m.addTrack(video.outputFormat)
                            tryStartMux()
                        }
                        out >= 0 -> {
                            video.getOutputBuffer(out)?.let { writeOrHold(VIDEO, it, videoInfo) }
                            if ((videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoEos = true
                            video.releaseOutputBuffer(out, false)
                        }
                    }
                }

                while (true) {
                    val out = audio.dequeueOutputBuffer(audioInfo, 0)
                    when {
                        out == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (audioTrack < 0) audioTrack = m.addTrack(audio.outputFormat)
                            tryStartMux()
                        }
                        out >= 0 -> {
                            audio.getOutputBuffer(out)?.let { writeOrHold(AUDIO, it, audioInfo) }
                            if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioEos = true
                            audio.releaseOutputBuffer(out, false)
                        }
                    }
                }

                if (!stopping) Thread.sleep(3L) else Thread.sleep(1L)
            }

            val duration = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            if (muxStarted) m.stop()
            finalizeMediaStore(success = muxStarted)
            statusText = if (muxStarted) "Gespeichert · ${duration / 1000.0}s" else "Aufnahme ohne verwertbare Frames"
            onState(statusText)
            onFinished(Result(outputUri, outputPath, duration, if (muxStarted) null else "Muxer wurde nicht gestartet"))
        } catch (e: Exception) {
            finalizeMediaStore(success = false)
            statusText = "Recorder-Fehler: ${e.javaClass.simpleName}"
            onState(statusText)
            onFinished(Result(outputUri, outputPath, System.currentTimeMillis() - startedAtMs, e.message ?: e.javaClass.simpleName))
        } finally {
            try { if (muxStarted) m.release() else m.release() } catch (_: Exception) { }
            try { outputPfd?.close() } catch (_: Exception) { }
            active.set(false)
            stopping = false
            releaseAll(deleteIncomplete = false, releaseMuxer = false)
        }
    }

    private fun createOutput(): Pair<Uri?, String?> {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "Equinox_$stamp.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Equinox")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore konnte keine Datei anlegen")
            Pair(uri, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Equinox").apply { mkdirs() }
            Pair(null, File(dir, name).absolutePath)
        }
    }

    private fun finalizeMediaStore(success: Boolean) {
        val uri = outputUri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (success) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            } else {
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
        }
    }

    private fun releaseAll(deleteIncomplete: Boolean, releaseMuxer: Boolean = true) {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null
        try { videoCodec?.stop() } catch (_: Exception) { }
        try { videoCodec?.release() } catch (_: Exception) { }
        videoCodec = null
        try { audioCodec?.stop() } catch (_: Exception) { }
        try { audioCodec?.release() } catch (_: Exception) { }
        audioCodec = null
        if (releaseMuxer) {
            try { muxer?.release() } catch (_: Exception) { }
        }
        muxer = null
        if (deleteIncomplete) finalizeMediaStore(false)
    }
}
