package de.equinox.v05

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQ_MIC = 100
        private const val REQ_CAMERA = 101
        private const val SAMPLE_RATE = 48_000
        private const val FFT_SIZE = 2_048
        private const val MEMORY_STEP_MS = 650L
    }

    private enum class AppPage { LIVE, MEMORY, LAB }

    @Volatile private var running = false
    @Volatile private var cameraRunning = false
    @Volatile private var memoryPlaybackRunning = false

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var visualFrameCount = 0L
    private var visualObservationCount = 0L
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    private lateinit var livePage: LinearLayout
    private lateinit var memoryPage: LinearLayout
    private lateinit var labPage: LinearLayout
    private var currentPage = AppPage.LIVE

    private lateinit var status: TextView
    private lateinit var frequency: TextView
    private lateinit var frequencyId: TextView
    private lateinit var strength: TextView
    private lateinit var frames: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var memoryStats: TextView
    private lateinit var labMemoryStats: TextView
    private lateinit var intervalSpinner: Spinner
    private lateinit var outputCanvas: EquinoxOutputView
    private lateinit var memoryCanvas: EquinoxOutputView
    private lateinit var graphView: RelationGraphView
    private lateinit var memoryModeStatus: TextView
    private lateinit var memoryOrchestraStatus: TextView
    private lateinit var memoryAutoOrchestra: CheckBox

    private lateinit var memory: MemoryModel
    private lateinit var outputBridge: EngineOutputBridge
    private val sonifier = Sonifier()
    private val orchestra = OrchestraSonifier()

    private lateinit var sonificationStatus: TextView
    private lateinit var labOrchestraStatus: TextView
    private lateinit var liveOrchestraStatus: TextView
    private lateinit var consolidationStatus: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var cameraFrames: TextView
    private lateinit var cameraButton: Button
    private lateinit var cameraStopButton: Button
    private lateinit var displayPrefsStore: DisplayPrefsStore
    private lateinit var saveSlots: SaveSlotManager
    private lateinit var diagnostics: DiagnosticsManager
    private lateinit var diagnosticsStatus: TextView

    private var lastConsolidationSummary = "Noch keine Konsolidierung in dieser Sitzung"
    @Volatile private var lastConsolidationMs = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val memoryLoop = object : Runnable {
        override fun run() {
            if (!memoryPlaybackRunning) return

            val ok = memory.memoryPlaybackStep()
            if (!ok) {
                memoryPlaybackRunning = false
                memoryModeStatus.text = "MEMORY: noch keine gelernten Makros"
                return
            }

            refreshMemoryPage()
            updateMemoryStats()

            if (::memoryAutoOrchestra.isInitialized && memoryAutoOrchestra.isChecked && !orchestra.isPlaying()) {
                val state = outputBridge.snapshot(maxSoundMacros = 12)
                orchestra.play(state) { text ->
                    runOnUiThread { if (::memoryOrchestraStatus.isInitialized) memoryOrchestraStatus.text = text }
                }
            }

            uiHandler.postDelayed(this, MEMORY_STEP_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        memory = MemoryModel(this)
        outputBridge = EngineOutputBridge(memory)
        cameraExecutor = Executors.newSingleThreadExecutor()
        displayPrefsStore = DisplayPrefsStore(this)
        saveSlots = SaveSlotManager(this)
        diagnostics = DiagnosticsManager(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 18, 22))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(24), dp(18), dp(36))
            setBackgroundColor(Color.rgb(16, 18, 22))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        root.addView(label("EQUINOX v0.6", 28f, 0))
        root.addView(label("STEP 08 — SUBSTITUTION CLASSES", 17f, 8).apply {
            setTextColor(Color.rgb(53, 214, 200))
        })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val liveTab = Button(this).apply { text = "LIVE" }
        val memoryTab = Button(this).apply { text = "MEMORY" }
        val labTab = Button(this).apply { text = "LAB" }
        modeRow.addView(liveTab, weightedButtonParams())
        modeRow.addView(memoryTab, weightedButtonParams())
        modeRow.addView(labTab, weightedButtonParams())
        root.addView(modeRow, buttonParams(14))

        livePage = pageContainer()
        memoryPage = pageContainer()
        labPage = pageContainer()
        root.addView(livePage)
        root.addView(memoryPage)
        root.addView(labPage)

        buildLivePage()
        buildMemoryPage()
        buildLabPage()

        fun selectPage(page: AppPage) {
            showPage(page)
            liveTab.isEnabled = page != AppPage.LIVE
            memoryTab.isEnabled = page != AppPage.MEMORY
            labTab.isEnabled = page != AppPage.LAB
        }

        liveTab.setOnClickListener { selectPage(AppPage.LIVE) }
        memoryTab.setOnClickListener { selectPage(AppPage.MEMORY) }
        labTab.setOnClickListener { selectPage(AppPage.LAB) }

        setContentView(scroll)
        selectPage(AppPage.LIVE)
        updateMemoryStats()
        refreshGraph()
    }

    private fun buildLivePage() {
        livePage.addView(label("LIVE — EXTERNE WELT", 20f, 18).apply {
            setTextColor(Color.rgb(105, 225, 235))
        })
        livePage.addView(label(
            "Mikrofon und Kamera erzeugen Input. Der Output ist nur eine Projektion des aktuellen Core-Zustands und schreibt selbst nichts zurück.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        status = label("Audio: AUS", 19f, 18)
        frequency = label("Dominante Frequenz: — Hz", 15f, 6)
        frequencyId = label("Frequenz-ID: —", 15f, 4)
        strength = label("Signalstärke: —", 15f, 4)
        frames = label("Frames verarbeitet: 0", 15f, 4)
        livePage.addView(status)
        livePage.addView(frequency)
        livePage.addView(frequencyId)
        livePage.addView(strength)
        livePage.addView(frames)

        startButton = Button(this).apply {
            text = "AUDIO STARTEN"
            setOnClickListener { ensurePermissionAndStart() }
        }
        stopButton = Button(this).apply {
            text = "AUDIO STOPP"
            isEnabled = false
            setOnClickListener { stopAudio() }
        }
        livePage.addView(startButton, buttonParams(12))
        livePage.addView(stopButton, buttonParams(6))

        cameraStatus = label("Kamera: AUS", 17f, 16).apply { setTextColor(Color.rgb(235, 175, 95)) }
        cameraFrames = label("Visuelle Frames: 0 · visuelle Beobachtungen: 0", 13f, 5).apply {
            setTextColor(Color.rgb(205, 205, 205))
        }
        livePage.addView(cameraStatus)
        livePage.addView(cameraFrames)

        cameraButton = Button(this).apply {
            text = "KAMERA-LERNEN STARTEN"
            setOnClickListener { ensureCameraPermissionAndStart() }
        }
        cameraStopButton = Button(this).apply {
            text = "KAMERA STOPP"
            isEnabled = false
            setOnClickListener { stopCameraLearning() }
        }
        livePage.addView(cameraButton, buttonParams(8))
        livePage.addView(cameraStopButton, buttonParams(6))

        memoryStats = label(memoryText(), 13f, 16).apply { setTextColor(Color.rgb(180, 220, 215)) }
        livePage.addView(memoryStats)

        outputCanvas = EquinoxOutputView(this).apply { setModeLabel("LIVE") }
        livePage.addView(
            outputCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(500)).apply { topMargin = dp(12) }
        )

        liveOrchestraStatus = label("Live-Orchester: AUS", 13f, 10).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        livePage.addView(liveOrchestraStatus)
        livePage.addView(Button(this).apply {
            text = "AKTUELLEN STATE SPIELEN"
            setOnClickListener {
                if (running) stopAudio()
                sonifier.stop()
                val state = outputBridge.snapshot(maxSoundMacros = 12)
                orchestra.play(state) { text -> runOnUiThread { liveOrchestraStatus.text = text } }
            }
        }, buttonParams(6))
    }

    private fun buildMemoryPage() {
        memoryPage.addView(label("MEMORY — INTERNES SPIEL", 20f, 18).apply {
            setTextColor(Color.rgb(226, 130, 255))
        })
        memoryPage.addView(label(
            "Kein Mikrofon, keine Kamera, kein neues Lernen. Equinox traversiert nur bereits gelernte Makros und Relationen und materialisiert daraus ein temporäres Working Set.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        memoryModeStatus = label("MEMORY: bereit", 15f, 14).apply {
            setTextColor(Color.rgb(225, 205, 255))
        }
        memoryPage.addView(memoryModeStatus)

        memoryCanvas = EquinoxOutputView(this).apply { setModeLabel("MEMORY") }
        memoryPage.addView(
            memoryCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(540)).apply { topMargin = dp(12) }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply {
            text = "RUN"
            setOnClickListener { startMemoryPlayback() }
        }
        val step = Button(this).apply {
            text = "1 STEP"
            setOnClickListener {
                if (running) stopAudio()
                stopCameraLearning()
                val ok = memory.memoryPlaybackStep()
                memoryModeStatus.text = if (ok) "MEMORY: Einzelschritt" else "MEMORY: noch keine gelernten Makros"
                refreshMemoryPage()
                updateMemoryStats()
            }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener { stopMemoryPlayback(clear = false) }
        }
        row.addView(start, weightedButtonParams())
        row.addView(step, weightedButtonParams())
        row.addView(stop, weightedButtonParams())
        memoryPage.addView(row, buttonParams(10))

        memoryAutoOrchestra = CheckBox(this).apply {
            text = "Orchester automatisch aus aktuellem Memory-State"
            setTextColor(Color.WHITE)
            isChecked = false
        }
        memoryPage.addView(memoryAutoOrchestra)

        memoryOrchestraStatus = label("Memory-Orchester: AUS", 13f, 8).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        memoryPage.addView(memoryOrchestraStatus)

        memoryPage.addView(Button(this).apply {
            text = "MEMORY-STATE JETZT SPIELEN"
            setOnClickListener {
                sonifier.stop()
                val state = outputBridge.snapshot(maxSoundMacros = 12)
                orchestra.play(state) { text -> runOnUiThread { memoryOrchestraStatus.text = text } }
            }
        }, buttonParams(6))

        memoryPage.addView(Button(this).apply {
            text = "AKTIVES FELD LEEREN"
            setOnClickListener {
                stopMemoryPlayback(clear = true)
                refreshMemoryPage()
            }
        }, buttonParams(6))
    }

    private fun buildLabPage() {
        labPage.addView(label("LAB — DEBUG / SPEICHER / PARAMETER", 20f, 18).apply {
            setTextColor(Color.rgb(150, 175, 210))
        })

        labMemoryStats = label(memoryText(), 13f, 8).apply { setTextColor(Color.rgb(180, 220, 215)) }
        labPage.addView(labMemoryStats)

        graphView = RelationGraphView(this)
        graphView.setDisplayPrefs(displayPrefsStore.load())
        graphView.setSnapshot(outputBridge.snapshot().graph)
        labPage.addView(
            graphView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(480)).apply { topMargin = dp(14) }
        )

        val geometryMemory = GeometryGroundMemory.standard()
        val displayModeLabel = label(
            "Darstellung: 2D · Geometrie-Grundgedächtnis: ${geometryMemory.totalTemplates} Formregeln",
            13f,
            8
        ).apply { setTextColor(Color.rgb(170, 205, 255)) }
        labPage.addView(displayModeLabel)

        val displayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val button2D = Button(this).apply { text = "2D"; isEnabled = false }
        val button3D = Button(this).apply { text = "3D" }
        fun refreshDisplayButtons() {
            val is2D = graphView.getDisplayMode() == GraphDisplayMode.TWO_D
            button2D.isEnabled = !is2D
            button3D.isEnabled = is2D
            displayModeLabel.text =
                "Darstellung: ${if (is2D) "2D" else "3D"} · Geometrie-Grundgedächtnis: ${geometryMemory.totalTemplates} Formregeln"
        }
        button2D.setOnClickListener { graphView.setDisplayMode(GraphDisplayMode.TWO_D); refreshDisplayButtons() }
        button3D.setOnClickListener { graphView.setDisplayMode(GraphDisplayMode.THREE_D); refreshDisplayButtons() }
        displayRow.addView(button2D, weightedButtonParams())
        displayRow.addView(button3D, weightedButtonParams())
        labPage.addView(displayRow, buttonParams(6))

        labPage.addView(label("DARSTELLUNG", 16f, 16).apply { setTextColor(Color.rgb(130, 220, 210)) })
        val currentPrefs = displayPrefsStore.load()
        fun colorField(labelText: String, initial: Int): EditText {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label(labelText, 13f, 0).apply { setTextColor(Color.LTGRAY) }, weightedLabelParams())
            val input = EditText(this).apply {
                setSingleLine(true)
                setText(displayPrefsStore.toHex(initial))
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            row.addView(input, weightedButtonParams())
            labPage.addView(row)
            return input
        }
        val bgInput = colorField("Hintergrund HEX", currentPrefs.backgroundColor)
        val edgeInput = colorField("Mikro-Kanten HEX", currentPrefs.audioEdgeColor)
        val l1Input = colorField("L1 HEX", currentPrefs.macroLevel1Color)
        val l2Input = colorField("L2 HEX", currentPrefs.macroLevel2Color)
        val l3Input = colorField("L3 HEX", currentPrefs.macroLevel3Color)
        val l4Input = colorField("L4 HEX", currentPrefs.macroLevel4Color)
        val glowCheck = CheckBox(this).apply {
            text = "Glow aktiv"
            isChecked = currentPrefs.glowEnabled
            setTextColor(Color.WHITE)
        }
        labPage.addView(glowCheck)
        val settingsStatus = label("Darstellung: geladen", 13f, 4).apply { setTextColor(Color.rgb(185, 195, 210)) }
        labPage.addView(settingsStatus)
        labPage.addView(Button(this).apply {
            text = "DARSTELLUNG SPEICHERN"
            setOnClickListener {
                val colors = listOf(bgInput, edgeInput, l1Input, l2Input, l3Input, l4Input)
                    .map { displayPrefsStore.parseHexOrNull(it.text.toString()) }
                if (colors.any { it == null }) {
                    settingsStatus.text = "Fehler: HEX als #RRGGBB eingeben"
                } else {
                    val saved = DisplayPrefs(
                        backgroundColor = colors[0]!!,
                        audioEdgeColor = colors[1]!!,
                        macroLevel1Color = colors[2]!!,
                        macroLevel2Color = colors[3]!!,
                        macroLevel3Color = colors[4]!!,
                        macroLevel4Color = colors[5]!!,
                        glowEnabled = glowCheck.isChecked,
                        glowStrength = 90
                    )
                    displayPrefsStore.save(saved)
                    graphView.setDisplayPrefs(saved)
                    settingsStatus.text = "Darstellung gespeichert und angewendet"
                }
            }
        }, buttonParams(6))

        labPage.addView(label("SAVE SLOTS — v0.6", 16f, 16).apply { setTextColor(Color.rgb(130, 220, 210)) })
        val slotStatus = label("3 lokale v0.6-Speicherstände verfügbar", 13f, 5).apply {
            setTextColor(Color.rgb(185, 195, 210))
        }
        labPage.addView(slotStatus)
        for (slot in 1..3) {
            val slotLabel = label(saveSlots.exportSummary(slot), 12f, 6).apply { setTextColor(Color.LTGRAY) }
            labPage.addView(slotLabel)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val saveButton = Button(this).apply {
                text = "S$slot SAVE"
                setOnClickListener {
                    memory.saveNow()
                    val ok = saveSlots.saveToSlot(slot)
                    slotLabel.text = saveSlots.exportSummary(slot)
                    slotStatus.text = if (ok) "Slot $slot gespeichert" else "Slot $slot konnte nicht gespeichert werden"
                }
            }
            val loadButton = Button(this).apply {
                text = "S$slot LOAD"
                setOnClickListener {
                    stopAudio()
                    stopCameraLearning()
                    stopMemoryPlayback(clear = true)
                    val ok = saveSlots.loadFromSlot(slot)
                    if (ok) {
                        memory.reloadFromDisk()
                        updateMemoryStats()
                        refreshGraph()
                        slotStatus.text = "Slot $slot geladen"
                    } else slotStatus.text = "Slot $slot ist leer"
                }
            }
            val deleteButton = Button(this).apply {
                text = "S$slot DEL"
                setOnClickListener {
                    val ok = saveSlots.deleteSlot(slot)
                    slotLabel.text = saveSlots.exportSummary(slot)
                    slotStatus.text = if (ok) "Slot $slot gelöscht" else "Slot $slot konnte nicht gelöscht werden"
                }
            }
            row.addView(saveButton, weightedButtonParams())
            row.addView(loadButton, weightedButtonParams())
            row.addView(deleteButton, weightedButtonParams())
            labPage.addView(row)
        }

        labPage.addView(label("LANGZEITDIAGNOSE", 16f, 16).apply { setTextColor(Color.rgb(130, 220, 210)) })
        diagnosticsStatus = label("Noch keine Diagnose-Samples", 12f, 5).apply { setTextColor(Color.rgb(190, 205, 220)) }
        labPage.addView(diagnosticsStatus)
        fun refreshDiagnosticsStatus() {
            val d = diagnostics.summary()
            diagnosticsStatus.text = if (d.samples == 0) {
                "Noch keine Diagnose-Samples"
            } else {
                "Samples: ${d.samples} · ΔObs ${d.observationsSinceFirst}\n" +
                    "ΔMikro ${d.newMicroSinceFirst} · ΔMakro ${d.newMacroSinceFirst} · ΔSeq ${d.newSequencesSinceFirst}\n" +
                    "Neue Mikro/1000 Obs: ${"%.3f".format(d.microPer1000Observations)} · neue Seq/1000 Obs: ${"%.3f".format(d.sequencePer1000Observations)}\n" +
                    "Memory-Datei: ${d.latestMemoryBytes / 1024} KB · Java-Heap: ${d.latestHeapBytes / (1024 * 1024)} MB"
            }
        }
        val diagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        diagRow.addView(Button(this).apply {
            text = "SAMPLE"
            setOnClickListener { memory.saveNow(); diagnostics.capture(memory.stats()); refreshDiagnosticsStatus() }
        }, weightedButtonParams())
        diagRow.addView(Button(this).apply {
            text = "CSV"
            setOnClickListener {
                val f = diagnostics.writeCsvExport()
                refreshDiagnosticsStatus()
                diagnosticsStatus.text = diagnosticsStatus.text.toString() + "\nCSV intern: ${f.name} (${f.length()} Bytes)"
            }
        }, weightedButtonParams())
        diagRow.addView(Button(this).apply {
            text = "RESET"
            setOnClickListener { diagnostics.reset(); refreshDiagnosticsStatus() }
        }, weightedButtonParams())
        labPage.addView(diagRow)
        refreshDiagnosticsStatus()

        intervalSpinner = Spinner(this)
        val intervals = arrayOf("5 Sekunden", "15 Sekunden", "30 Sekunden", "1 Minute", "5 Minuten", "15 Minuten", "30 Minuten")
        intervalSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals)
        consolidationStatus = label("Konsolidierung: 5 s · nächste: —\n$lastConsolidationSummary", 13f, 16).apply {
            setTextColor(Color.rgb(255, 205, 105))
        }
        labPage.addView(consolidationStatus)
        labPage.addView(intervalSpinner, buttonParams(6))

        labPage.addView(Button(this).apply {
            text = "JETZT KONSOLIDIEREN"
            setOnClickListener {
                val result = memory.consolidate()
                lastConsolidationMs = System.currentTimeMillis()
                lastConsolidationSummary = consolidationSummary(result)
                updateConsolidationStatus()
                updateMemoryStats()
                refreshGraph()
            }
        }, buttonParams(6))

        labPage.addView(Button(this).apply {
            fun refreshText() { text = if (memory.isMacroFrozen()) "MAKRO-ERZEUGUNG: GEFROREN" else "MAKRO-ERZEUGUNG: AKTIV" }
            refreshText()
            setOnClickListener {
                memory.setMacroFrozen(!memory.isMacroFrozen())
                refreshText()
                updateMemoryStats()
                refreshGraph()
            }
        }, buttonParams(6))

        sonificationStatus = label("Sonifikation: AUS", 13f, 12).apply { setTextColor(Color.rgb(255, 190, 90)) }
        labPage.addView(sonificationStatus)
        labPage.addView(Button(this).apply {
            text = "GEDÄCHTNIS HÖRBAR MACHEN"
            setOnClickListener {
                if (running) stopAudio()
                val snap = outputBridge.snapshot().sound
                sonifier.play(snap) { text -> runOnUiThread { sonificationStatus.text = text } }
            }
        }, buttonParams(6))
        labPage.addView(Button(this).apply {
            text = "KLANG STOPP"
            setOnClickListener { sonifier.stop(); sonificationStatus.text = "Sonifikation: AUS" }
        }, buttonParams(6))

        labOrchestraStatus = label("Orchester: AUS", 13f, 10).apply { setTextColor(Color.rgb(125, 220, 255)) }
        labPage.addView(labOrchestraStatus)
        labPage.addView(Button(this).apply {
            text = "AKTIVEN STATE ALS ORCHESTER"
            setOnClickListener {
                if (running) stopAudio()
                sonifier.stop()
                val state = outputBridge.snapshot(maxSoundMacros = 12)
                orchestra.play(state) { text -> runOnUiThread { labOrchestraStatus.text = text } }
            }
        }, buttonParams(6))
        labPage.addView(Button(this).apply {
            text = "ORCHESTER STOPP"
            setOnClickListener {
                orchestra.stop()
                labOrchestraStatus.text = "Orchester: AUS"
                if (::memoryOrchestraStatus.isInitialized) memoryOrchestraStatus.text = "Memory-Orchester: AUS"
                if (::liveOrchestraStatus.isInitialized) liveOrchestraStatus.text = "Live-Orchester: AUS"
            }
        }, buttonParams(6))

        labPage.addView(Button(this).apply {
            text = "GEDÄCHTNIS LÖSCHEN"
            setOnClickListener {
                stopMemoryPlayback(clear = true)
                memory.reset()
                updateMemoryStats()
                refreshGraph()
            }
        }, buttonParams(16))

        labPage.addView(label(
            "Step 09 trennt strukturelle Relevanz in mehrere Messachsen: Häufigkeit, Wiederverwendung, Relationsstütze, Kontextentropie, Spezifität und zeitliche Persistenz. Häufig heißt dadurch nicht automatisch wichtig. Die Werte sind weiterhin keine eingebaute Wortbedeutung und verändern das Lernen nicht direkt.",
            12f,
            14
        ).apply { setTextColor(Color.LTGRAY) })
    }

    private fun pageContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }

    private fun showPage(page: AppPage) {
        if (page == currentPage && livePage.visibility == View.VISIBLE) return

        when (page) {
            AppPage.LIVE -> {
                stopMemoryPlayback(clear = true)
                orchestra.stop()
                memoryPage.visibility = View.GONE
                labPage.visibility = View.GONE
                livePage.visibility = View.VISIBLE
            }
            AppPage.MEMORY -> {
                stopAudio()
                stopCameraLearning()
                sonifier.stop()
                orchestra.stop()
                memory.clearMomentaryState()
                livePage.visibility = View.GONE
                labPage.visibility = View.GONE
                memoryPage.visibility = View.VISIBLE
                memoryModeStatus.text = if (memory.hasLearnedMacros()) "MEMORY: bereit" else "MEMORY: noch keine gelernten Makros"
            }
            AppPage.LAB -> {
                stopMemoryPlayback(clear = false)
                livePage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                labPage.visibility = View.VISIBLE
            }
        }
        currentPage = page
        refreshGraph()
    }

    private fun startMemoryPlayback() {
        if (!memory.hasLearnedMacros()) {
            memoryModeStatus.text = "MEMORY: noch keine gelernten Makros"
            return
        }
        stopAudio()
        stopCameraLearning()
        sonifier.stop()
        if (!memoryPlaybackRunning) {
            memoryPlaybackRunning = true
            memoryModeStatus.text = "MEMORY: RUN · nur gelerntes Gedächtnis"
            uiHandler.removeCallbacks(memoryLoop)
            uiHandler.post(memoryLoop)
        }
    }

    private fun stopMemoryPlayback(clear: Boolean) {
        memoryPlaybackRunning = false
        uiHandler.removeCallbacks(memoryLoop)
        if (clear) memory.clearMomentaryState()
        if (::memoryModeStatus.isInitialized) {
            memoryModeStatus.text = if (memory.hasLearnedMacros()) "MEMORY: gestoppt" else "MEMORY: noch keine gelernten Makros"
        }
    }

    private fun refreshMemoryPage() {
        if (!::memoryCanvas.isInitialized) return
        val state = outputBridge.snapshot(maxSoundMacros = 12)
        memoryCanvas.setState(state)
        val w = state.working
        if (::memoryModeStatus.isInitialized && memoryPlaybackRunning) {
            memoryModeStatus.text = "MEMORY: RUN · ${w.nodes.size} aktiv · ${w.exchanges.size} Austausch"
        }
    }

    private fun refreshGraph() {
        val state = outputBridge.snapshot()
        if (::outputCanvas.isInitialized && livePage.visibility == View.VISIBLE) outputCanvas.setState(state)
        if (::memoryCanvas.isInitialized && memoryPage.visibility == View.VISIBLE) memoryCanvas.setState(state)
        if (::graphView.isInitialized && labPage.visibility == View.VISIBLE) graphView.setSnapshot(state.graph)
    }

    private fun ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startAudio()
            else status.text = "Audio: Mikrofon-Berechtigung fehlt"
        } else if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCameraLearning()
            else cameraStatus.text = "Kamera: Berechtigung fehlt"
        }
    }

    private fun startAudio() {
        if (running) return
        stopMemoryPlayback(clear = true)

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
        } catch (_: SecurityException) {
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
                val id = AudioQuantizer.idForHz(hz)
                frameCount++
                memory.observe(id, hz)

                var didConsolidate = false
                val now = System.currentTimeMillis()
                val interval = selectedIntervalMs()
                if (now - lastConsolidationMs >= interval) {
                    val result = memory.consolidate()
                    lastConsolidationMs = now
                    lastConsolidationSummary = consolidationSummary(result)
                    didConsolidate = true
                }

                runOnUiThread {
                    if (running) {
                        frequency.text = "Dominante Frequenz: %.1f Hz".format(hz)
                        frequencyId.text = "Frequenz-ID: $id"
                        strength.text = "Signalstärke: %.5f".format(bestMag)
                        frames.text = "Frames verarbeitet: $frameCount"
                        if (frameCount % 5L == 0L) {
                            updateMemoryStats()
                            updateConsolidationStatus()
                        }
                        if (didConsolidate || frameCount % 12L == 0L) refreshGraph()
                    }
                }
            }
        }
    }

    private fun ensureCameraPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraLearning()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun startCameraLearning() {
        if (cameraRunning) return
        stopMemoryPlayback(clear = true)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(
                    cameraExecutor,
                    VisualGridAnalyzer { result ->
                        if (!cameraRunning) return@VisualGridAnalyzer
                        result.observations.forEach { obs -> memory.observeVisual(obs.tokenId, obs.packedRgb) }
                        visualFrameCount++
                        visualObservationCount += result.observations.size

                        runOnUiThread {
                            cameraFrames.text = "Visuelle Frames: $visualFrameCount · visuelle Beobachtungen: $visualObservationCount"
                            if (visualFrameCount % 2L == 0L) {
                                updateMemoryStats()
                                refreshGraph()
                            }
                        }
                    }
                )

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                cameraRunning = true
                cameraStatus.text = "Kamera: AKTIV · 8×6 Raster"
                cameraButton.isEnabled = false
                cameraStopButton.isEnabled = true
            } catch (e: Exception) {
                cameraRunning = false
                cameraStatus.text = "Kamera: Startfehler ${e.javaClass.simpleName}"
                cameraButton.isEnabled = true
                cameraStopButton.isEnabled = false
            }
        }, mainExecutor)
    }

    private fun stopCameraLearning() {
        if (!::cameraButton.isInitialized) return
        if (!cameraRunning && cameraProvider == null) return
        cameraRunning = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        cameraStatus.text = "Kamera: AUS"
        cameraButton.isEnabled = true
        cameraStopButton.isEnabled = false
        updateMemoryStats()
        refreshGraph()
    }

    private fun selectedIntervalMs(): Long = when (intervalSpinner.selectedItemPosition) {
        0 -> 5_000L
        1 -> 15_000L
        2 -> 30_000L
        3 -> 60_000L
        4 -> 300_000L
        5 -> 900_000L
        else -> 1_800_000L
    }

    private fun updateConsolidationStatus() {
        if (!::consolidationStatus.isInitialized) return
        val interval = selectedIntervalMs()
        val remaining = if (running) {
            (interval - (System.currentTimeMillis() - lastConsolidationMs)).coerceAtLeast(0L)
        } else interval
        val seconds = (remaining + 999L) / 1000L
        val label = intervalSpinner.selectedItem?.toString() ?: "—"
        consolidationStatus.text = "Konsolidierung: $label · nächste in ${seconds}s\n$lastConsolidationSummary"
    }

    private fun updateMemoryStats() {
        val text = memoryText()
        if (::memoryStats.isInitialized) memoryStats.text = text
        if (::labMemoryStats.isInitialized) labMemoryStats.text = text
    }

    private fun memoryText(): String {
        val s = memory.stats()
        val levelText = if (s.levelCounts.isEmpty()) "noch keine" else s.levelCounts.entries.joinToString(" · ") { "L${it.key}:${it.value}" }
        return "Quanten: ${s.persistentCodes} | Kandidaten: ${s.temporaryCodes} | Beobachtungen: ${s.observations}\n" +
            "Makros: ${s.macroCodes} | $levelText | Relationen: ${s.macroRelations}\n" +
            "Working Set: ${s.activeMacros} | erinnert: ${s.retrievedMacros} | Austausch: ${s.overexchangePairs} | Ø|R| ${"%.3f".format(s.meanAbsOverexchange)}\n" +
            "Höchster aktiver Rang: ${s.highestActiveRank} · Score ${"%.2f".format(s.highestActiveRankScore)}\n" +
            "Templates: ${s.templateCodes} | aktiv: ${s.activeTemplates} | Varianten: ${s.templateVariantLinks} | max/Slot: ${s.widestTemplateVariants}\n" +
            "Substitutionsklassen: ${s.substitutionClasses} | aktiv: ${s.activeSubstitutionClasses} | Mitglieder: ${s.substitutionMembers} | stärkste Rollenstütze: ${s.strongestSubstitutionSupport}\n" +
            "Relevanz aktiv: Ø ${"%.3f".format(s.meanActiveRelevance)} | max ${"%.3f".format(s.highestActiveRelevance)} | Kontextentropie Ø ${"%.3f".format(s.meanActiveContextEntropy)} | Generalität max ${"%.3f".format(s.highestActiveGenerality)}\n" +
            "Sequenzspeicher: ${"%.1f".format(s.sequenceCapacityUsedPercent)}% von ${s.sequenceCapacity}"
    }

    private fun consolidationSummary(result: ConsolidationResult): String {
        val levelPart = if (result.highestNewLevel > 0) " · höchste neue Ebene L${result.highestNewLevel}" else ""
        val templatePart = if (result.templatesDiscovered > 0) " · +${result.templatesDiscovered} Templates" else ""
        val classPart = if (result.substitutionClassesDiscovered > 0) " · +${result.substitutionClassesDiscovered} Substitutionsklassen" else ""
        return "Letzte Änderung: +${result.microPromoted} Mikro · +${result.macroPromoted} Abstraktionsknoten · davon ${result.sequencePromoted} Sequenzen$levelPart$templatePart$classPart"
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
        val result = memory.consolidate()
        lastConsolidationSummary = consolidationSummary(result)
        updateConsolidationStatus()
        updateMemoryStats()
        refreshGraph()
    }

    private fun label(textValue: String, size: Float = 18f, top: Int = 16): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(0, dp(top), 0, 0)
    }

    private fun buttonParams(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { marginStart = dp(2); marginEnd = dp(2) }

    private fun weightedLabelParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1.2f
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        stopMemoryPlayback(clear = false)
        stopCameraLearning()
        try { cameraExecutor.shutdownNow() } catch (_: Exception) { }
        stopAudio()
        sonifier.stop()
        orchestra.stop()
        super.onDestroy()
    }
}
