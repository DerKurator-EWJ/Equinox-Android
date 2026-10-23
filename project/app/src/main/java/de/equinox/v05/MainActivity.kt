package de.equinox.v05

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_MIC = 100
        private const val REQ_CAMERA = 101
    }

    private lateinit var memory: MemoryModel
    private lateinit var sonifier: Sonifier
    private lateinit var displayPrefsStore: DisplayPrefsStore
    private lateinit var saveSlots: SaveSlotManager
    private lateinit var diagnostics: DiagnosticsManager

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var running = false
    @Volatile private var cameraRunning = false

    private var selectedIntervalMs = 15_000L
    private var nextConsolidationAt = 0L
    private var visualFrameCount = 0L
    private var visualObservationCount = 0L

    private lateinit var rootFrame: FrameLayout
    private lateinit var userPanel: View
    private lateinit var labPanel: View
    private lateinit var graphView: RelationGraphView

    private lateinit var topModeTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var consolidationText: TextView
    private lateinit var cameraText: TextView
    private lateinit var diagnosticsText: TextView

    private var lastConsolidationSummary = "Noch keine Konsolidierung"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        memory = MemoryModel(this)
        sonifier = Sonifier()
        displayPrefsStore = DisplayPrefsStore(this)
        saveSlots = SaveSlotManager(this)
        diagnostics = DiagnosticsManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        enterFullscreen()

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 9, 13))
        }

        userPanel = buildUserPanel()
        labPanel = buildLabPanel().apply { visibility = View.GONE }

        rootFrame.addView(userPanel)
        rootFrame.addView(labPanel)
        setContentView(rootFrame)
    }

    private fun enterFullscreen() {
        window.setDecorFitsSystemWindows(false)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun buildHeader(title: String, subtitle: String, onSwitch: () -> Unit): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(Color.rgb(15, 18, 25))
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        topModeTitle = TextView(this).apply {
            text = title
            textSize = 25f
            setTextColor(Color.WHITE)
        }

        val sub = TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.rgb(130, 220, 210))
        }

        texts.addView(topModeTitle)
        texts.addView(sub)

        val switchButton = Button(this).apply {
            text = if (title.contains("NUTZER")) "LAB" else "NUTZER"
            setOnClickListener { onSwitch() }
        }

        header.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(switchButton)
        return header
    }

    private fun buildUserPanel(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 9, 13))
        }

        outer.addView(
            buildHeader(
                "EQUINOX",
                "NUTZERANSICHT · v0.5 STEP 14",
                onSwitch = {
                    userPanel.visibility = View.GONE
                    labPanel.visibility = View.VISIBLE
                    enterFullscreen()
                }
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        statusText = cardText("System bereit", 18f)
        statsText = cardText(memoryText(), 14f)
        consolidationText = cardText("Konsolidierung: 15 Sekunden\n$lastConsolidationSummary", 14f)
        cameraText = cardText("Kamera: AUS", 14f)

        body.addView(sectionTitle("LIVE · VISUAL ENGINE"))
        body.addView(statusText)
        body.addView(statsText)

        graphView = RelationGraphView(this).apply {
            setDisplayPrefs(displayPrefsStore.load())
            setSnapshot(memory.graphSnapshot())
        }

        body.addView(
            graphView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(500)
            ).apply { topMargin = dp(10) }
        )

        val viewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val twoD = Button(this).apply {
            text = "2D"
            isEnabled = false
        }
        val threeD = Button(this).apply {
            text = "3D"
        }

        fun updateModeButtons() {
            val is2D = graphView.getDisplayMode() == GraphDisplayMode.TWO_D
            twoD.isEnabled = !is2D
            threeD.isEnabled = is2D
        }

        twoD.setOnClickListener {
            graphView.setDisplayMode(GraphDisplayMode.TWO_D)
            updateModeButtons()
        }

        threeD.setOnClickListener {
            graphView.setDisplayMode(GraphDisplayMode.THREE_D)
            updateModeButtons()
        }

        viewRow.addView(twoD, weightParams(1f, 4))
        viewRow.addView(threeD, weightParams(1f, 4))
        body.addView(viewRow)

        body.addView(sectionTitle("STEUERUNG"))

        val audioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        audioRow.addView(Button(this).apply {
            text = "AUDIO START"
            setOnClickListener { ensurePermissionAndStart() }
        }, weightParams(1f, 4))
        audioRow.addView(Button(this).apply {
            text = "AUDIO STOP"
            setOnClickListener { stopAudio() }
        }, weightParams(1f, 4))
        body.addView(audioRow)

        val cameraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cameraRow.addView(Button(this).apply {
            text = "KAMERA START"
            setOnClickListener { ensureCameraPermissionAndStart() }
        }, weightParams(1f, 4))
        cameraRow.addView(Button(this).apply {
            text = "KAMERA STOP"
            setOnClickListener { stopCameraLearning() }
        }, weightParams(1f, 4))
        body.addView(cameraRow)

        body.addView(cameraText)

        body.addView(Button(this).apply {
            text = "GEDÄCHTNIS HÖRBAR MACHEN"
            setOnClickListener {
                stopAudio()
                sonifier.play(memory.sonificationSnapshot())
            }
        }, fullButtonParams(8))

        body.addView(Button(this).apply {
            text = "KLANG STOPP"
            setOnClickListener { sonifier.stop() }
        }, fullButtonParams(6))

        body.addView(sectionTitle("KONSOLIDIERUNG"))
        body.addView(consolidationText)

        val intervals = listOf(
            "5s" to 5_000L,
            "15s" to 15_000L,
            "30s" to 30_000L,
            "1m" to 60_000L,
            "5m" to 300_000L
        )
        val intervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        intervals.forEach { (label, ms) ->
            intervalRow.addView(Button(this).apply {
                text = label
                isEnabled = ms != selectedIntervalMs
                setOnClickListener {
                    selectedIntervalMs = ms
                    nextConsolidationAt = System.currentTimeMillis() + selectedIntervalMs
                    rebuildUserMode()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(intervalRow)

        body.addView(Button(this).apply {
            text = "JETZT KONSOLIDIEREN"
            setOnClickListener { doConsolidation() }
        }, fullButtonParams(8))

        body.addView(Button(this).apply {
            text = "GRAPH AKTUALISIEREN"
            setOnClickListener { refreshGraphAndStats() }
        }, fullButtonParams(6))

        scroll.addView(body)
        outer.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return outer
    }

    private fun buildLabPanel(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 9, 13))
        }

        outer.addView(
            buildHeader(
                "EQUINOX LAB",
                "DIAGNOSE · SAVE · DARSTELLUNG",
                onSwitch = {
                    labPanel.visibility = View.GONE
                    userPanel.visibility = View.VISIBLE
                    refreshGraphAndStats()
                    enterFullscreen()
                }
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(30))
        }

        body.addView(sectionTitle("SYSTEMDIAGNOSE"))
        diagnosticsText = cardText("Noch keine Diagnose-Samples", 13f)
        body.addView(diagnosticsText)

        val diagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        diagRow.addView(Button(this).apply {
            text = "SAMPLE"
            setOnClickListener {
                memory.saveNow()
                diagnostics.capture(memory.stats())
                refreshDiagnostics()
            }
        }, weightParams(1f, 3))
        diagRow.addView(Button(this).apply {
            text = "CSV"
            setOnClickListener {
                val f = diagnostics.writeCsvExport()
                refreshDiagnostics()
                diagnosticsText.text =
                    diagnosticsText.text.toString() + "\nCSV: ${f.name} · ${f.length()} Bytes"
            }
        }, weightParams(1f, 3))
        diagRow.addView(Button(this).apply {
            text = "RESET"
            setOnClickListener {
                diagnostics.reset()
                refreshDiagnostics()
            }
        }, weightParams(1f, 3))
        body.addView(diagRow)

        body.addView(sectionTitle("SAVE SLOTS"))
        for (slot in 1..3) {
            val slotText = cardText(saveSlots.exportSummary(slot), 12f)
            body.addView(slotText)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(Button(this).apply {
                text = "S$slot SAVE"
                setOnClickListener {
                    memory.saveNow()
                    saveSlots.saveToSlot(slot)
                    slotText.text = saveSlots.exportSummary(slot)
                }
            }, weightParams(1f, 3))

            row.addView(Button(this).apply {
                text = "S$slot LOAD"
                setOnClickListener {
                    stopAudio()
                    stopCameraLearning()
                    if (saveSlots.loadFromSlot(slot)) {
                        memory.reloadFromDisk()
                        slotText.text = saveSlots.exportSummary(slot)
                        refreshGraphAndStats()
                    }
                }
            }, weightParams(1f, 3))

            row.addView(Button(this).apply {
                text = "S$slot DEL"
                setOnClickListener {
                    saveSlots.deleteSlot(slot)
                    slotText.text = saveSlots.exportSummary(slot)
                }
            }, weightParams(1f, 3))

            body.addView(row)
        }

        body.addView(sectionTitle("DARSTELLUNG"))
        val prefs = displayPrefsStore.load()

        fun colorInput(name: String, color: Int): EditText {
            val label = TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(Color.LTGRAY)
            }
            body.addView(label)

            return EditText(this).apply {
                setSingleLine(true)
                setText(displayPrefsStore.toHex(color))
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(20, 24, 32))
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }.also { body.addView(it, fullButtonParams(4)) }
        }

        val bg = colorInput("Hintergrund", prefs.backgroundColor)
        val edge = colorInput("Mikro-Kanten", prefs.audioEdgeColor)
        val l1 = colorInput("L1", prefs.macroLevel1Color)
        val l2 = colorInput("L2", prefs.macroLevel2Color)
        val l3 = colorInput("L3", prefs.macroLevel3Color)
        val l4 = colorInput("L4", prefs.macroLevel4Color)

        val glow = CheckBox(this).apply {
            text = "Glow aktiv"
            isChecked = prefs.glowEnabled
            setTextColor(Color.WHITE)
        }
        body.addView(glow)

        val settingsInfo = cardText("Darstellung geladen", 12f)
        body.addView(settingsInfo)

        body.addView(Button(this).apply {
            text = "DARSTELLUNG ANWENDEN"
            setOnClickListener {
                val values = listOf(bg, edge, l1, l2, l3, l4)
                    .map { displayPrefsStore.parseHexOrNull(it.text.toString()) }

                if (values.any { it == null }) {
                    settingsInfo.text = "HEX-Fehler: bitte #RRGGBB"
                } else {
                    val next = DisplayPrefs(
                        backgroundColor = values[0]!!,
                        audioEdgeColor = values[1]!!,
                        macroLevel1Color = values[2]!!,
                        macroLevel2Color = values[3]!!,
                        macroLevel3Color = values[4]!!,
                        macroLevel4Color = values[5]!!,
                        glowEnabled = glow.isChecked,
                        glowStrength = 90
                    )
                    displayPrefsStore.save(next)
                    graphView.setDisplayPrefs(next)
                    settingsInfo.text = "Darstellung gespeichert"
                }
            }
        }, fullButtonParams(8))

        body.addView(sectionTitle("GEOMETRIE"))
        val geometry = GeometryGroundMemory.standard()
        body.addView(
            cardText(
                "${geometry.totalTemplates} Grundformen · 2D ${geometry.templates2D.size} · 3D ${geometry.templates3D.size}\n" +
                    "Renderer-Geometrie bleibt getrennt vom relationalen Kern.",
                12f
            )
        )

        scroll.addView(body)
        outer.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        refreshDiagnostics()
        return outer
    }

    private fun rebuildUserMode() {
        rootFrame.removeView(userPanel)
        userPanel = buildUserPanel()
        rootFrame.addView(userPanel, 0)
        if (labPanel.visibility == View.VISIBLE) {
            userPanel.visibility = View.GONE
        }
    }

    private fun refreshDiagnostics() {
        if (!::diagnosticsText.isInitialized) return
        val d = diagnostics.summary()

        diagnosticsText.text = if (d.samples == 0) {
            "Noch keine Diagnose-Samples"
        } else {
            "Samples ${d.samples} · ΔObs ${d.observationsSinceFirst}\n" +
                "ΔMikro ${d.newMicroSinceFirst} · ΔMakro ${d.newMacroSinceFirst} · ΔSeq ${d.newSequencesSinceFirst}\n" +
                "Mikro/1000 Obs ${"%.3f".format(d.microPer1000Observations)} · Seq/1000 Obs ${"%.3f".format(d.sequencePer1000Observations)}\n" +
                "Memory ${d.latestMemoryBytes / 1024} KB · Heap ${d.latestHeapBytes / (1024 * 1024)} MB"
        }
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
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudio()
            } else {
                statusText.text = "Mikrofon-Berechtigung fehlt"
            }
        } else if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraLearning()
            } else {
                cameraText.text = "Kamera-Berechtigung fehlt"
            }
        }
    }

    private fun startAudio() {
        if (running) return
        running = true
        nextConsolidationAt = System.currentTimeMillis() + selectedIntervalMs
        statusText.text = "Audio: AKTIV"

        Thread {
            try {
                val encoder = SoundEncoder()
                encoder.run(
                    shouldContinue = { running },
                    onObservation = { id, hz, strength, frames ->
                        memory.observe(id, hz)

                        if (System.currentTimeMillis() >= nextConsolidationAt) {
                            doConsolidationFromWorker()
                        }

                        runOnUiThread {
                            statusText.text =
                                "Audio AKTIV\n${"%.1f".format(hz)} Hz · ID $id · Stärke ${"%.2f".format(strength)} · Frames $frames"
                            statsText.text = memoryText()
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Audio-Fehler: ${e.javaClass.simpleName}"
                }
            } finally {
                running = false
            }
        }.start()
    }

    private fun stopAudio() {
        running = false
        if (::statusText.isInitialized) statusText.text = "Audio: AUS"
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

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(
                    cameraExecutor,
                    VisualGridAnalyzer { result ->
                        if (!cameraRunning) return@VisualGridAnalyzer

                        result.observations.forEach { obs ->
                            memory.observeVisual(obs.tokenId, obs.packedRgb)
                        }

                        visualFrameCount++
                        visualObservationCount += result.observations.size

                        runOnUiThread {
                            cameraText.text =
                                "Kamera AKTIV · Frames $visualFrameCount · Beobachtungen $visualObservationCount"
                            statsText.text = memoryText()
                        }
                    }
                )

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )

                cameraRunning = true
                cameraText.text = "Kamera: AKTIV · 8×6 Raster"
            } catch (e: Exception) {
                cameraRunning = false
                cameraText.text = "Kamera-Fehler: ${e.javaClass.simpleName}"
            }
        }, mainExecutor)
    }

    private fun stopCameraLearning() {
        cameraRunning = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        if (::cameraText.isInitialized) cameraText.text = "Kamera: AUS"
    }

    private fun doConsolidation() {
        val result = memory.consolidate()
        lastConsolidationSummary = consolidationSummary(result)
        nextConsolidationAt = System.currentTimeMillis() + selectedIntervalMs
        refreshGraphAndStats()
    }

    private fun doConsolidationFromWorker() {
        val result = memory.consolidate()
        lastConsolidationSummary = consolidationSummary(result)
        nextConsolidationAt = System.currentTimeMillis() + selectedIntervalMs

        runOnUiThread {
            consolidationText.text =
                "Konsolidierung: ${intervalText(selectedIntervalMs)}\n$lastConsolidationSummary"
            refreshGraphAndStats()
        }
    }

    private fun refreshGraphAndStats() {
        if (::statsText.isInitialized) statsText.text = memoryText()
        if (::graphView.isInitialized) graphView.setSnapshot(memory.graphSnapshot())
        if (::consolidationText.isInitialized) {
            consolidationText.text =
                "Konsolidierung: ${intervalText(selectedIntervalMs)}\n$lastConsolidationSummary"
        }
    }

    private fun memoryText(): String {
        val s = memory.stats()
        val levels = if (s.levelCounts.isEmpty()) {
            "keine"
        } else {
            s.levelCounts.entries.joinToString(" · ") { "L${it.key}:${it.value}" }
        }

        return "Mikro ${s.persistentCodes} · temporär ${s.temporaryCodes} · Rel ${s.relations}\n" +
            "Abstraktion $levels · höchste L${s.maxAbstractionLevel}\n" +
            "Makro ${s.macroCodes} · Makrorel ${s.macroRelations}\n" +
            "Sequenzen ${s.sequenceCodes}/${s.sequenceCapacity} · ${"%.1f".format(s.sequenceCapacityUsedPercent)}%\n" +
            "Vision ${s.visualPersistentCodes} · Crossmodal ${s.crossModalRelations}\n" +
            "Beobachtungen ${s.observations}"
    }

    private fun consolidationSummary(result: ConsolidationResult): String {
        val level = if (result.highestNewLevel > 0) {
            " · höchste neue Ebene L${result.highestNewLevel}"
        } else {
            ""
        }

        return "+${result.microPromoted} Mikro · +${result.macroPromoted} Abstraktion · " +
            "${result.sequencePromoted} Sequenzen$level"
    }

    private fun intervalText(ms: Long): String = when (ms) {
        5_000L -> "5 Sekunden"
        15_000L -> "15 Sekunden"
        30_000L -> "30 Sekunden"
        60_000L -> "1 Minute"
        300_000L -> "5 Minuten"
        else -> "${ms / 1000}s"
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(120, 225, 210))
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun cardText(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(220, 228, 238))
        setBackgroundColor(Color.rgb(16, 20, 28))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun fullButtonParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
        }

    private fun weightParams(weight: Float, margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            weight
        ).apply {
            marginStart = dp(margin)
            marginEnd = dp(margin)
            topMargin = dp(4)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        enterFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onDestroy() {
        stopCameraLearning()
        try { cameraExecutor.shutdownNow() } catch (_: Exception) { }
        stopAudio()
        sonifier.stop()
        super.onDestroy()
    }
}
