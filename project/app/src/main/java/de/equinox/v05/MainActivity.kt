package de.equinox.v05

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQ_MIC = 100
        private const val SAMPLE_RATE = 48_000
        private const val FFT_SIZE = 2_048
    }

    @Volatile private var running = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    private lateinit var status: TextView
    private lateinit var frequency: TextView
    private lateinit var frequencyId: TextView
    private lateinit var strength: TextView
    private lateinit var frames: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.rgb(16, 18, 22))
        }

        fun label(textValue: String, size: Float = 18f, top: Int = 16): TextView = TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, top, 0, 0)
        }

        root.addView(label("EQUINOX v0.5", 28f, 0))
        root.addView(label("STEP 02 — AUDIO / FFT", 18f, 16).apply {
            setTextColor(Color.rgb(53, 214, 200))
        })

        status = label("Audio: AUS", 20f, 36)
        frequency = label("Dominante Frequenz: — Hz")
        frequencyId = label("Frequenz-ID: —")
        strength = label("Signalstärke: —")
        frames = label("Frames verarbeitet: 0")
        root.addView(status)
        root.addView(frequency)
        root.addView(frequencyId)
        root.addView(strength)
        root.addView(frames)

        startButton = Button(this).apply {
            text = "AUDIO STARTEN"
            setOnClickListener { ensurePermissionAndStart() }
        }
        stopButton = Button(this).apply {
            text = "STOPP"
            isEnabled = false
            setOnClickListener { stopAudio() }
        }

        root.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 40 })
        root.addView(stopButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })

        root.addView(label(
            "Mikrofon → 48 kHz PCM → 2048 Samples → Hann → FFT → dominante Frequenz → logarithmische ID",
            14f,
            28
        ).apply { setTextColor(Color.LTGRAY) })

        setContentView(root)
    }

    private fun ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudio()
            } else {
                status.text = "Audio: Mikrofon-Berechtigung fehlt"
            }
        }
    }

    private fun startAudio() {
        if (running) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            status.text = "Audio: Gerät meldet ungültigen Puffer"
            return
        }

        val bufferBytes = max(minBuffer, FFT_SIZE * 2 * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (e: SecurityException) {
            status.text = "Audio: keine Mikrofon-Berechtigung"
            return
        } catch (e: Exception) {
            status.text = "Audio: Startfehler ${e.javaClass.simpleName}"
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            status.text = "Audio: AudioRecord konnte nicht initialisiert werden"
            return
        }

        audioRecord = recorder
        running = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "Audio: AKTIV"

        recorder.startRecording()
        worker = thread(name = "EquinoxAudioFFT") {
            val frame = ShortArray(FFT_SIZE)
            var frameCount = 0L

            while (running) {
                var offset = 0
                while (running && offset < FFT_SIZE) {
                    val read = recorder.read(frame, offset, FFT_SIZE - offset)
                    if (read > 0) offset += read else break
                }
                if (!running || offset < FFT_SIZE) continue

                val mags = Fft.magnitudes(frame)
                val minBin = (40.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtLeast(1)
                val maxBin = (18_000.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtMost(mags.lastIndex)

                var bestBin = minBin
                var bestMag = 0.0
                for (bin in minBin..maxBin) {
                    if (mags[bin] > bestMag) {
                        bestMag = mags[bin]
                        bestBin = bin
                    }
                }

                val hz = bestBin.toDouble() * SAMPLE_RATE / FFT_SIZE
                val id = if (hz > 0.0) (24.0 * log2(hz / 55.0)).roundToInt() else 0
                frameCount++

                runOnUiThread {
                    if (running) {
                        frequency.text = "Dominante Frequenz: %.1f Hz".format(hz)
                        frequencyId.text = "Frequenz-ID: $id"
                        strength.text = "Signalstärke: %.5f".format(bestMag)
                        frames.text = "Frames verarbeitet: $frameCount"
                    }
                }
            }
        }
    }

    private fun stopAudio() {
        if (!running) return
        running = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { worker?.join(500) } catch (_: InterruptedException) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        worker = null
        status.text = "Audio: AUS"
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }
}
