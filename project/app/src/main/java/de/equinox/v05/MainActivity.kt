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
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
    private lateinit var memoryStats: TextView
    private lateinit var intervalSpinner: Spinner
    private lateinit var graphView: RelationGraphView
    private lateinit var memory: MemoryModel
    @Volatile private var lastConsolidationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = MemoryModel(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 18, 22))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 48)
            setBackgroundColor(Color.rgb(16, 18, 22))
        }
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        fun label(textValue: String, size: Float = 18f, top: Int = 16): TextView = TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, top, 0, 0)
        }

        root.addView(label("EQUINOX v0.5", 28f, 0))
        root.addView(label("STEP 05 — MACRO NODES", 18f, 16).apply {
            setTextColor(Color.rgb(53, 214, 200))
        })

        status = label("Audio: AUS", 20f, 28)
        frequency = label("Dominante Frequenz: — Hz")
        frequencyId = label("Frequenz-ID: —")
        strength = label("Signalstärke: —")
        frames = label("Frames verarbeitet: 0")
        root.addView(status)
        root.addView(frequency)
        root.addView(frequencyId)
        root.addView(strength)
        root.addView(frames)

        memoryStats = label(memoryText(), 15f, 18).apply { setTextColor(Color.rgb(180, 220, 215)) }
        root.addView(memoryStats)

        graphView = RelationGraphView(this)
        root.addView(graphView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(420)
        ).apply { topMargin = dp(18) })
        refreshGraph()

        root.addView(label(
            "Kreise = Mikrocodes · orange Felder = Makrocodes · orange Kurven = Makro-/Mitgliedsrelationen",
            12f,
            12
        ).apply { setTextColor(Color.LTGRAY) })

        intervalSpinner = Spinner(this)
        val intervals = arrayOf("3 Sekunden", "30 Sekunden", "1 Minute", "5 Minuten", "15 Minuten", "30 Minuten")
        intervalSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals)
        root.addView(intervalSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        val refreshButton = Button(this).apply {
            text = "GRAPH AKTUALISIEREN"
            setOnClickListener { refreshGraph() }
        }
        root.addView(refreshButton, buttonParams(10))

        val consolidateButton = Button(this).apply {
            text = "JETZT KONSOLIDIEREN"
            setOnClickListener {
                val result = memory.consolidate()
                memoryStats.text = memoryText() + "\nNeu: ${result.microPromoted} Mikro · ${result.macroPromoted} Makro"
                refreshGraph()
            }
        }
        root.addView(consolidateButton, buttonParams(8))

        val macroFreezeButton = Button(this).apply {
            fun refreshText() { text = if (memory.isMacroFrozen()) "MAKRO-ERZEUGUNG: GEFROREN" else "MAKRO-ERZEUGUNG: AKTIV" }
            refreshText()
            setOnClickListener {
                memory.setMacroFrozen(!memory.isMacroFrozen())
                refreshText()
                memoryStats.text = memoryText()
                refreshGraph()
            }
        }
        root.addView(macroFreezeButton, buttonParams(8))

        val resetButton = Button(this).apply {
            text = "GEDÄCHTNIS LÖSCHEN"
            setOnClickListener {
                memory.reset()
                memoryStats.text = memoryText()
                refreshGraph()
            }
        }
        root.addView(resetButton, buttonParams(8))

        startButton = Button(this).apply {
            text = "AUDIO STARTEN"
            setOnClickListener { ensurePermissionAndStart() }
        }
        stopButton = Button(this).apply {
            text = "STOPP"
            isEnabled = false
            setOnClickListener { stopAudio() }
        }

        root.addView(startButton, buttonParams(28))
        root.addView(stopButton, buttonParams(8))

        root.addView(label(
            "Der Kern speichert keine x/y-Positionen. Die Ansicht erzeugt ihre Koordinaten nur aus dem Relationsgraphen.",
            12f,
            18
        ).apply { setTextColor(Color.LTGRAY) })

        setContentView(scroll)
    }

    private fun buttonParams(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun refreshGraph() {
        graphView.setSnapshot(memory.graphSnapshot())
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
        lastConsolidationMs = System.currentTimeMillis()

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
                memory.observe(id, hz)

                var didConsolidate = false
                val now = System.currentTimeMillis()
                val interval = selectedIntervalMs()
                if (now - lastConsolidationMs >= interval) {
                    memory.consolidate()
                    lastConsolidationMs = now
                    didConsolidate = true
                }

                runOnUiThread {
                    if (running) {
                        frequency.text = "Dominante Frequenz: %.1f Hz".format(hz)
                        frequencyId.text = "Frequenz-ID: $id"
                        strength.text = "Signalstärke: %.5f".format(bestMag)
                        frames.text = "Frames verarbeitet: $frameCount"
                        if (frameCount % 5L == 0L) memoryStats.text = memoryText()
                        if (didConsolidate || frameCount % 12L == 0L) refreshGraph()
                    }
                }
            }
        }
    }

    private fun selectedIntervalMs(): Long {
        return when (intervalSpinner.selectedItemPosition) {
            0 -> 3_000L
            1 -> 30_000L
            2 -> 60_000L
            3 -> 300_000L
            4 -> 900_000L
            else -> 1_800_000L
        }
    }

    private fun memoryText(): String {
        val s = memory.stats()
        return "Mikro: ${s.persistentCodes} Codes | ${s.temporaryCodes} temporär | ${s.relations} Relationen\n" +
            "Makro: ${s.macroCodes} Codes | ${s.macroRelations} Relationen | Erzeugung: ${if (s.macroFrozen) "GEFROREN" else "AKTIV"}\n" +
            "Beobachtungen: ${s.observations} | stärkstes Mikrogewicht: ${s.strongestWeight}"
    }

    private fun stopAudio() {
        if (!running) return
        running = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { worker?.join(500) } catch (_: InterruptedException) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        worker = null
        status.text = "Audio: AUS"
        startButton.isEnabled = true
        stopButton.isEnabled = false
        memory.consolidate()
        memoryStats.text = memoryText()
        refreshGraph()
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }
}
