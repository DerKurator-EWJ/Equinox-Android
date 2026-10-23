package de.equinox.v05

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
        private const val DREAM_STEP_MS = 520L
        private const val HYBRID_STEP_MS = 420L
    }

    private enum class AppPage { LIVE, HYBRID, MEMORY, LAB }

    @Volatile private var running = false
    @Volatile private var cameraRunning = false
    @Volatile private var memoryPlaybackRunning = false
    @Volatile private var dreamPlaybackRunning = false
    @Volatile private var hybridPlaybackRunning = false

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var visualFrameCount = 0L
    private var visualObservationCount = 0L
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    private lateinit var livePage: LinearLayout
    private lateinit var hybridPage: LinearLayout
    private lateinit var memoryPage: LinearLayout
    private lateinit var labPage: LinearLayout
    private var currentPage = AppPage.LIVE
    private var settingsOpen = false
    private var lastLabRenderMs = 0L
    private lateinit var surfaceVideo: GpuMemoryVideoView
    private lateinit var settingsOverlay: ScrollView
    private lateinit var settingsScrim: View
    private lateinit var settingsSheet: FrameLayout
    private lateinit var captureRecorder: UnifiedCaptureRecorder
    private lateinit var captureButton: TextView
    private lateinit var productAudioButton: TextView
    private lateinit var productModeButton: TextView
    private lateinit var cognitiveModeStatus: TextView
    private lateinit var captureStatus: TextView
    private val capturePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            CaptureForegroundService.start(this)
            // Give the foreground service a brief moment to enter foreground before MediaProjection.
            uiHandler.postDelayed({ startUnifiedCapture(result.resultCode, data) }, 140L)
        } else {
            if (::captureStatus.isInitialized) captureStatus.text = "Aufnahme: abgebrochen"
            if (::captureButton.isInitialized) captureButton.text = "REC"
            CaptureForegroundService.stop(this)
        }
    }
    private val profileExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null || !::profiles.isInitialized) return@registerForActivityResult
        val selected = selectedProfileInfo() ?: return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.let { profiles.exportProfile(selected.id, it) } ?: false
        }.getOrDefault(false)
        if (::profileStatus.isInitialized) profileStatus.text = if (ok) "Profil exportiert: ${selected.name}" else "Profil-Export fehlgeschlagen"
    }

    private val profileImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || !::profiles.isInitialized) return@registerForActivityResult
        val suggested = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Memory"
        val imported = runCatching {
            contentResolver.openInputStream(uri)?.let { profiles.importProfile(it, suggested) }
        }.getOrNull()
        if (imported != null) {
            refreshProfileControls(selectId = imported.id)
            if (::profileStatus.isInitialized) profileStatus.text = "Importiert: ${imported.name} · LOAD zum Aktivieren"
        } else if (::profileStatus.isInitialized) {
            profileStatus.text = "Profil-Import fehlgeschlagen"
        }
    }

    private val externalKnowledgeImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (::externalKnowledgeStatus.isInitialized) externalKnowledgeStatus.text = "External Knowledge: Dry-Run…"
        thread(name = "Equinox-ExternalKnowledge-DryRun") {
            val report = runCatching {
                contentResolver.openInputStream(uri)?.let { ExternalKnowledgeConverter().parseAndCompile(it) }
            }.getOrNull() ?: ExternalImportReport(
                valid = false, sourceId = "", sourceVersion = "", packId = "", packVersion = 0,
                nodesRead = 0, macrosCompiled = 0, explicitRelationsRead = 0, embeddingRelationsDerived = 0, finalRelations = 0,
                issues = listOf(ImportIssue(ImportIssue.Severity.ERROR, "Datei konnte nicht gelesen werden"))
            )
            runOnUiThread { showExternalKnowledgeDryRun(report) }
        }
    }

    private val livePipeline = LowLatencyLivePipeline()
    private val performanceGovernor = PerformanceGovernor()
    private var visualAnalyzer: VisualGridAnalyzer? = null
    private var lastGovernorUpdateMs = 0L
    private val liveRenderPosted = AtomicBoolean(false)
    private lateinit var liveDelaySpinner: Spinner
    private lateinit var liveLatencyStatus: TextView

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
    private lateinit var hybridCanvas: EquinoxOutputView
    private lateinit var liveVideoCanvas: MemoryVideoView
    private lateinit var hybridVideoCanvas: MemoryVideoView
    private lateinit var memoryCanvas: EquinoxOutputView
    private lateinit var graphView: RelationGraphView
    private lateinit var memoryModeStatus: TextView
    private lateinit var dreamModeStatus: TextView
    private lateinit var hybridModeStatus: TextView
    private lateinit var hybridSensorStatus: TextView
    private lateinit var memoryOrchestraStatus: TextView
    private lateinit var memoryAutoOrchestra: CheckBox
    private lateinit var hybridOrchestraStatus: TextView

    private lateinit var memory: MemoryModel
    private lateinit var outputBridge: EngineOutputBridge
    // Step 41: exactly one engine owns AudioTrack for every Equinox output mode.
    private val unifiedAudio = UnifiedAudioEngine()
    private val selfOutputSuppressor = AudioSelfOutputSuppressor(SAMPLE_RATE)
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var feedbackProtectionEnabled = true
    @Volatile private var lastSelfOutputRemovedRatio = 0.0
    @Volatile private var lastSelfOutputComponents = 0
    private var feedbackUiSyncing = false
    private lateinit var liveContinuousButton: Button
    private lateinit var hybridContinuousButton: Button
    private lateinit var liveFeedbackProtectionToggle: CheckBox
    private lateinit var hybridFeedbackProtectionToggle: CheckBox

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
    private lateinit var profiles: MemoryProfileManager
    private lateinit var profileSpinner: Spinner
    private lateinit var profileStatus: TextView
    private lateinit var externalKnowledgeStatus: TextView
    private var profileSpinnerSyncing = false
    private lateinit var diagnostics: DiagnosticsManager
    private lateinit var diagnosticsStatus: TextView
    private lateinit var hardTest: LongTermHardTestManager
    private lateinit var hardTestStatus: TextView
    private lateinit var audioEncoderModeStore: AudioEncoderModeStore
    private var audioEncoderMode: AudioEncoderMode = AudioEncoderMode.FFT_FREQUENCY
    private lateinit var audioEncoderStatus: TextView
    private lateinit var labAudioEncoderStatus: TextView
    private lateinit var encoderBenchmark: EncoderBenchmarkManager
    private lateinit var encoderBenchmarkStatus: TextView
    private lateinit var encoderBenchmarkToggle: CheckBox
    private lateinit var adaptiveQuantizer: AdaptiveAudioQuantizer
    private lateinit var adaptiveVisualQuantizer: AdaptiveVisualQuantizer
    private lateinit var adaptiveVisualQuantizerStatus: TextView
    private lateinit var adaptiveQuantizerStatus: TextView
    @Volatile private var parallelEncoderBenchmarkEnabled = false

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

            if (::memoryAutoOrchestra.isInitialized && memoryAutoOrchestra.isChecked && !unifiedAudio.isRunning()) {
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text ->
                    runOnUiThread { if (::memoryOrchestraStatus.isInitialized) memoryOrchestraStatus.text = text }
                }
            }

            uiHandler.postDelayed(this, MEMORY_STEP_MS)
        }
    }

    private val dreamLoop = object : Runnable {
        override fun run() {
            if (!dreamPlaybackRunning) return

            val ok = memory.autonomousDreamStep()
            if (!ok) {
                dreamPlaybackRunning = false
                if (::dreamModeStatus.isInitialized) dreamModeStatus.text = "DREAM: noch keine gelernten Makros"
                return
            }

            refreshMemoryPage()
            updateMemoryStats()

            if (::memoryAutoOrchestra.isInitialized && memoryAutoOrchestra.isChecked && !unifiedAudio.isRunning()) {
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text ->
                    runOnUiThread { if (::memoryOrchestraStatus.isInitialized) memoryOrchestraStatus.text = text }
                }
            }

            uiHandler.postDelayed(this, DREAM_STEP_MS)
        }
    }

    private val hybridLoop = object : Runnable {
        override fun run() {
            if (!hybridPlaybackRunning) return

            val ok = memory.hybridPlaybackStep()
            refreshHybridPage(ok)
            updateMemoryStats()
            if (unifiedAudio.isRunning()) pushContinuousOrchestraState()
            uiHandler.postDelayed(this, HYBRID_STEP_MS)
        }
    }

    private val hardTestLoop = object : Runnable {
        override fun run() {
            if (!::hardTest.isInitialized || !hardTest.isRunning()) return
            captureHardTestSample(saveMemory = true)
            uiHandler.postDelayed(this, LongTermHardTestManager.DEFAULT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        memory = MemoryModel(this)
        memory.setCognitiveMode(CognitiveMode.PLAY)
        outputBridge = EngineOutputBridge(memory)
        cameraExecutor = Executors.newSingleThreadExecutor()
        displayPrefsStore = DisplayPrefsStore(this)
        saveSlots = SaveSlotManager(this)
        profiles = MemoryProfileManager(this)
        diagnostics = DiagnosticsManager(this)
        hardTest = LongTermHardTestManager(this)
        audioEncoderModeStore = AudioEncoderModeStore(this)
        audioEncoderMode = audioEncoderModeStore.load()
        encoderBenchmark = EncoderBenchmarkManager(this)
        adaptiveQuantizer = AdaptiveAudioQuantizer(this)
        adaptiveVisualQuantizer = AdaptiveVisualQuantizer(this)
        captureRecorder = UnifiedCaptureRecorder(this)
        val latencyPrefs = getSharedPreferences("equinox_live_latency", MODE_PRIVATE)
        livePipeline.setPreset(
            LowLatencyLivePipeline.DelayPreset.fromName(latencyPrefs.getString("preset", null))
        )

        // Step 46.1: HYPNO is the only product renderer. Live sensors may still activate
        // the Unified State, but raw camera pigment is no longer exposed as a Reality mode.
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(6, 8, 12))
        }

        surfaceVideo = GpuMemoryVideoView(this).apply {
            setModeLabel("HYPNO")
            setPresentationMode(UnifiedPresentationMode.HYPNO)
            // Pure generative HYPNO surface: sensor input influences state, not visible camera pixels.
            setMemoryBlend(1.0f)
            visibility = View.VISIBLE
        }
        // Audio still reads the same Unified State. Its internal context weighting is not a product UI mode.
        unifiedAudio.setMemoryBlend(0.35f)
        rootFrame.addView(surfaceVideo, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Compact product dock. HYPNO itself has no mode switch and no Reality <-> Memory slider.
        // LIVE/DREAM chooses only the activation source; it never changes the renderer.
        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = roundedBackground(Color.argb(218, 12, 14, 20), 24f, Color.argb(42, 255, 255, 255), 1)
            elevation = dp(12).toFloat()
        }
        productModeButton = TextView(this).apply {
            text = "LIVE"
            textSize = 11f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(145, 230, 210))
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "HYPNO Aktivierungsquelle LIVE oder DREAM"
            attachPressMotion(this)
        }
        productAudioButton = TextView(this).apply {
            text = "♪"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "Unified Audio an oder aus"
            attachPressMotion(this)
        }
        captureButton = TextView(this).apply {
            text = "REC"
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 112, 112))
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            attachPressMotion(this)
        }
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            attachPressMotion(this)
        }
        controlBar.addView(productModeButton, LinearLayout.LayoutParams(dp(68), dp(46)))
        controlBar.addView(productAudioButton, LinearLayout.LayoutParams(dp(50), dp(46)).apply { marginStart = dp(6) })
        controlBar.addView(captureButton, LinearLayout.LayoutParams(dp(58), dp(46)).apply { marginStart = dp(6) })
        controlBar.addView(settingsButton, LinearLayout.LayoutParams(dp(50), dp(46)).apply { marginStart = dp(6) })
        val controlBarParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(10)
        }
        rootFrame.addView(controlBar, controlBarParams)

        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            val lp = controlBar.layoutParams as FrameLayout.LayoutParams
            val bottom = insets.systemWindowInsetBottom + dp(10)
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom
                controlBar.layoutParams = lp
            }
            if (::settingsSheet.isInitialized) {
                val sheetLp = settingsSheet.layoutParams as FrameLayout.LayoutParams
                val sheetBottom = insets.systemWindowInsetBottom
                if (sheetLp.bottomMargin != sheetBottom) {
                    sheetLp.bottomMargin = sheetBottom
                    settingsSheet.layoutParams = sheetLp
                }
            }
            insets
        }

        // Step 35: settings are a true animated bottom sheet. The unified canvas remains
        // visible beneath a dim scrim, so opening controls never feels like navigating away.
        settingsScrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            isClickable = true
        }
        rootFrame.addView(settingsScrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        settingsSheet = FrameLayout(this).apply {
            background = roundedBackground(Color.rgb(17, 19, 25), 30f, Color.argb(38, 255, 255, 255), 1)
            elevation = dp(24).toFloat()
            visibility = View.INVISIBLE
        }
        val sheetParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.82f).roundToInt(),
            Gravity.BOTTOM
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        }
        rootFrame.addView(settingsSheet, sheetParams)

        settingsOverlay = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val settingsRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(44))
            setBackgroundColor(Color.TRANSPARENT)
        }
        settingsOverlay.addView(settingsRoot, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        settingsSheet.addView(settingsOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val grabber = View(this).apply {
            background = roundedBackground(Color.argb(90, 235, 238, 245), 4f)
        }
        settingsSheet.addView(grabber, FrameLayout.LayoutParams(dp(42), dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8)
        })

        settingsRoot.addView(label("EQUINOX v0.6 · SETTINGS", 24f, 4))
        settingsRoot.addView(label("PRODUCT — HYPNO ONLY", 13f, 4).apply {
            setTextColor(Color.rgb(53, 214, 200))
        })
        captureStatus = label("Aufnahme: bereit · MP4 / H.264 + Unified Audio AAC", 12f, 4).apply {
            setTextColor(Color.rgb(214, 177, 116))
        }
        settingsRoot.addView(captureStatus)

        val settingsNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val inputSettings = productButton("INPUT")
        val memorySettings = productButton("MEMORY")
        val labSettings = productButton("LAB")
        val closeSettings = productButton("✕")
        settingsNav.addView(inputSettings, weightedButtonParams())
        settingsNav.addView(memorySettings, weightedButtonParams())
        settingsNav.addView(labSettings, weightedButtonParams())
        settingsNav.addView(closeSettings, weightedButtonParams())
        settingsRoot.addView(settingsNav, buttonParams(8))

        livePage = pageContainer()
        hybridPage = pageContainer()
        memoryPage = pageContainer()
        labPage = pageContainer()
        settingsRoot.addView(livePage)
        settingsRoot.addView(hybridPage)
        settingsRoot.addView(memoryPage)
        settingsRoot.addView(labPage)
        buildLivePage()
        buildHybridPage()
        buildMemoryPage()
        buildLabPage()
        polishProductTree(settingsRoot)

        if (::liveVideoCanvas.isInitialized) liveVideoCanvas.visibility = View.GONE
        if (::outputCanvas.isInitialized) outputCanvas.visibility = View.GONE
        if (::hybridVideoCanvas.isInitialized) hybridVideoCanvas.visibility = View.GONE
        if (::hybridCanvas.isInitialized) hybridCanvas.visibility = View.GONE
        if (::memoryCanvas.isInitialized) memoryCanvas.visibility = View.GONE

        fun showSettingsSection(page: AppPage) {
            currentPage = page
            livePage.visibility = if (page == AppPage.LIVE) View.VISIBLE else View.GONE
            hybridPage.visibility = if (page == AppPage.LIVE) View.VISIBLE else View.GONE
            memoryPage.visibility = if (page == AppPage.MEMORY) View.VISIBLE else View.GONE
            labPage.visibility = if (page == AppPage.LAB) View.VISIBLE else View.GONE
            if (page == AppPage.LIVE) refreshSensorUiState()
            updateMemoryStats()
            refreshGraph()
        }

        fun openSettingsOverlay() {
            if (settingsOpen) return
            settingsOpen = true
            showSettingsSection(currentPage)
            settingsScrim.visibility = View.VISIBLE
            settingsSheet.visibility = View.VISIBLE
            settingsScrim.animate().alpha(1f).setDuration(180L).start()
            settingsSheet.post {
                settingsSheet.translationY = settingsSheet.height.toFloat()
                settingsSheet.alpha = 0.96f
                settingsSheet.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(300L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }

        fun closeSettingsOverlay() {
            if (!settingsOpen) return
            // STRUCTURE is a LAB-only diagnostic preview. The product surface is always HYPNO.
            surfaceVideo.setPresentationMode(UnifiedPresentationMode.HYPNO)
            surfaceVideo.setModeLabel("HYPNO")
            settingsOpen = false
            settingsScrim.animate().alpha(0f).setDuration(170L).withEndAction {
                settingsScrim.visibility = View.GONE
            }.start()
            settingsSheet.animate()
                .translationY(settingsSheet.height.toFloat())
                .alpha(0.96f)
                .setDuration(240L)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    settingsSheet.visibility = View.INVISIBLE
                    settingsSheet.translationY = 0f
                    settingsSheet.alpha = 1f
                }.start()
            refreshGraph()
        }

        productModeButton.setOnClickListener {
            if (memory.cognitiveMode() == CognitiveMode.DREAM) enterPlayMode() else startDreamPlayback()
        }
        productAudioButton.setOnClickListener { toggleContinuousOrchestra() }
        captureButton.setOnClickListener {
            if (captureRecorder.isRecording()) {
                stopUnifiedCapture()
            } else {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                capturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
        settingsButton.setOnClickListener {
            if (settingsOpen) closeSettingsOverlay() else openSettingsOverlay()
        }
        settingsScrim.setOnClickListener { closeSettingsOverlay() }
        inputSettings.setOnClickListener { showSettingsSection(AppPage.LIVE) }
        memorySettings.setOnClickListener { showSettingsSection(AppPage.MEMORY) }
        labSettings.setOnClickListener { showSettingsSection(AppPage.LAB) }
        closeSettings.setOnClickListener { closeSettingsOverlay() }

        setContentView(rootFrame)
        showSettingsSection(AppPage.LIVE)
        syncCognitiveModeUi()
        closeSettingsOverlay()
    }

    private fun buildLivePage() {
        livePage.addView(label("INPUT / LEARN", 20f, 18).apply {
            setTextColor(Color.rgb(105, 225, 235))
        })
        livePage.addView(label(
            "Sensoren sind ein Werkzeug hinter den Settings. LEARN schreibt neue Erfahrung; PLAY erkennt nur bereits Bekanntes. DREAM nimmt keinen externen Input an.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        cognitiveModeStatus = label("Modus: PLAY · Sensoren read-only", 14f, 8).apply {
            setTextColor(Color.rgb(145, 230, 210))
        }
        livePage.addView(cognitiveModeStatus)
        val cognitiveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cognitiveRow.addView(Button(this).apply {
            text = "LEARN · SCHREIBEN"
            setOnClickListener { enterLearnMode() }
        }, weightedButtonParams())
        cognitiveRow.addView(Button(this).apply {
            text = "PLAY · READ-ONLY"
            setOnClickListener { enterPlayMode() }
        }, weightedButtonParams())
        livePage.addView(cognitiveRow, buttonParams(8))

        audioEncoderStatus = label("Audio-Encoder: ${audioEncoderMode.label}", 14f, 12).apply {
            setTextColor(Color.rgb(145, 210, 255))
        }
        livePage.addView(audioEncoderStatus)

        status = label("Audio: AUS", 19f, 10)
        frequency = label(
            when (audioEncoderMode) {
                AudioEncoderMode.FFT_FREQUENCY -> "Dominante Frequenz: — Hz"
                AudioEncoderMode.DIRECT_PATTERN -> "Direktmuster: —"
                AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive-ID: —"
            },
            15f,
            6
        )
        frequencyId = label(when (audioEncoderMode) {
            AudioEncoderMode.FFT_FREQUENCY -> "Frequenz-ID: —"
            AudioEncoderMode.DIRECT_PATTERN -> "Pattern-ID: —"
            AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive Pattern-ID: —"
        }, 15f, 4)
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
        adaptiveVisualQuantizerStatus = label(adaptiveVisualQuantizerText(), 12f, 4).apply {
            setTextColor(Color.rgb(195, 175, 235))
        }
        livePage.addView(cameraStatus)
        livePage.addView(cameraFrames)
        livePage.addView(adaptiveVisualQuantizerStatus)

        cameraButton = Button(this).apply {
            text = "KAMERA STARTEN"
            setOnClickListener { ensureCameraPermissionAndStart() }
        }
        cameraStopButton = Button(this).apply {
            text = "KAMERA STOPP"
            isEnabled = false
            setOnClickListener { stopCameraLearning() }
        }
        livePage.addView(cameraButton, buttonParams(8))
        livePage.addView(cameraStopButton, buttonParams(6))

        livePage.addView(label("LIVE-VERZÖGERUNG / FRAME-SYNC", 14f, 12).apply {
            setTextColor(Color.rgb(120, 220, 205))
        })
        liveDelaySpinner = Spinner(this).apply {
            val presets = LowLatencyLivePipeline.DelayPreset.entries
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presets.map { it.label }
            )
            setSelection(presets.indexOf(livePipeline.getPreset()).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = presets.getOrElse(position) { LowLatencyLivePipeline.DelayPreset.AUTO }
                    livePipeline.setPreset(selected)
                    getSharedPreferences("equinox_live_latency", MODE_PRIVATE)
                        .edit().putString("preset", selected.name).apply()
                    updateLiveLatencyStatus()
                    scheduleLowLatencyRender()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        livePage.addView(liveDelaySpinner, buttonParams(4))
        liveLatencyStatus = label("Live-Sync: —", 12f, 6).apply {
            setTextColor(Color.rgb(185, 205, 215))
        }
        livePage.addView(liveLatencyStatus)

        memoryStats = label(memoryText(), 13f, 16).apply { setTextColor(Color.rgb(180, 220, 215)) }
        livePage.addView(memoryStats)

        livePage.addView(label(
            "MEMORY VIDEO — Kamera liefert nur Pigment; der gelernte Working-State verformt das Bild read-only.",
            12f,
            12
        ).apply { setTextColor(Color.rgb(170, 205, 245)) })
        liveVideoCanvas = MemoryVideoView(this).apply { setModeLabel("LIVE") }
        livePage.addView(
            liveVideoCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(520)).apply { topMargin = dp(8) }
        )

        outputCanvas = EquinoxOutputView(this).apply { setModeLabel("LIVE STATE") }
        livePage.addView(
            outputCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(12) }
        )

        liveOrchestraStatus = label("Unified Audio: AUS", 13f, 10).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        livePage.addView(liveOrchestraStatus)
        liveFeedbackProtectionToggle = CheckBox(this).apply {
            text = "Feedback-Schutz: Android AEC + Eigenklang-Filter"
            isChecked = feedbackProtectionEnabled
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> setFeedbackProtection(checked) }
        }
        livePage.addView(liveFeedbackProtectionToggle)
        liveContinuousButton = Button(this).apply {
            text = "AUDIO START"
            setOnClickListener { toggleContinuousOrchestra() }
        }
        livePage.addView(liveContinuousButton, buttonParams(6))
        livePage.addView(label(
            "Mikrofon darf dabei weiterlaufen. Der Feedback-Schutz entfernt bekannte Syntheseanteile konservativ vor der Lernpipeline; mit Kopfhörern bleibt der Input am saubersten.",
            11f,
            4
        ).apply { setTextColor(Color.GRAY) })
    }

    private fun buildHybridPage() {
        hybridPage.addView(label("HYBRID — GEGENWART + GEDÄCHTNIS", 20f, 18).apply {
            setTextColor(Color.rgb(120, 235, 190))
        })
        hybridPage.addView(label(
            "Live-Input bleibt echtes A-Signal. Dazwischen darf das bereits Gelernte read-only über Prediction und lokale Relationen weiterlaufen. Interne Aktivierung wird nicht als neue Erfahrung zurückgeschrieben.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        hybridSensorStatus = label("Sensoren: Audio AUS · Kamera AUS", 14f, 12).apply {
            setTextColor(Color.rgb(155, 230, 205))
        }
        hybridPage.addView(hybridSensorStatus)

        val sensorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        sensorRow.addView(Button(this).apply {
            text = "AUDIO AN/AUS"
            setOnClickListener { if (running) stopAudio() else ensurePermissionAndStart() }
        }, weightedButtonParams())
        sensorRow.addView(Button(this).apply {
            text = "KAMERA AN/AUS"
            setOnClickListener { if (cameraRunning) stopCameraLearning() else ensureCameraPermissionAndStart() }
        }, weightedButtonParams())
        hybridPage.addView(sensorRow, buttonParams(8))

        hybridModeStatus = label("HYBRID: bereit · wartet auf Live-Makro", 15f, 12).apply {
            setTextColor(Color.rgb(185, 255, 220))
        }
        hybridPage.addView(hybridModeStatus)

        hybridPage.addView(label(
            "MEMORY VIDEO — derselbe Live-Frame wird zusätzlich durch Recall, Prediction und Überaustausch des Hybrid-State verformt.",
            12f,
            10
        ).apply { setTextColor(Color.rgb(170, 225, 205)) })
        hybridVideoCanvas = MemoryVideoView(this).apply { setModeLabel("HYBRID") }
        hybridPage.addView(
            hybridVideoCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(520)).apply { topMargin = dp(8) }
        )

        hybridCanvas = EquinoxOutputView(this).apply { setModeLabel("HYBRID STATE") }
        hybridPage.addView(
            hybridCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(12) }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(Button(this).apply {
            text = "RUN"
            setOnClickListener { startHybridPlayback() }
        }, weightedButtonParams())
        row.addView(Button(this).apply {
            text = "1 STEP"
            setOnClickListener {
                val ok = memory.hybridPlaybackStep()
                refreshHybridPage(ok)
                updateMemoryStats()
            }
        }, weightedButtonParams())
        row.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener { stopHybridPlayback(clear = false) }
        }, weightedButtonParams())
        hybridPage.addView(row, buttonParams(10))

        hybridOrchestraStatus = label("Unified Audio: AUS", 13f, 10).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        hybridPage.addView(hybridOrchestraStatus)
        hybridFeedbackProtectionToggle = CheckBox(this).apply {
            text = "Feedback-Schutz: Android AEC + Eigenklang-Filter"
            isChecked = feedbackProtectionEnabled
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> setFeedbackProtection(checked) }
        }
        hybridPage.addView(hybridFeedbackProtectionToggle)
        hybridContinuousButton = Button(this).apply {
            text = "AUDIO START"
            setOnClickListener { toggleContinuousOrchestra() }
        }
        hybridPage.addView(hybridContinuousButton, buttonParams(6))
    }

    private fun buildMemoryPage() {
        memoryPage.addView(label("MEMORY — INTERNES SPIEL", 20f, 18).apply {
            setTextColor(Color.rgb(226, 130, 255))
        })
        memoryPage.addView(label(
            "Kein Mikrofon, keine Kamera, kein neues Lernen. MEMORY traversiert gelernte Pfade; DREAM wechselt autonom zwischen Prediction, Relationen, Substitutionsklassen und Crossmodal-Brücken.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        memoryModeStatus = label("MEMORY: bereit", 15f, 14).apply {
            setTextColor(Color.rgb(225, 205, 255))
        }
        memoryPage.addView(memoryModeStatus)

        dreamModeStatus = label("DREAM: bereit", 14f, 6).apply {
            setTextColor(Color.rgb(255, 176, 112))
        }
        memoryPage.addView(dreamModeStatus)

        memoryCanvas = EquinoxOutputView(this).apply { setModeLabel("MEMORY / DREAM") }
        memoryPage.addView(
            memoryCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(540)).apply { topMargin = dp(12) }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply {
            text = "MEMORY RUN"
            setOnClickListener { startMemoryPlayback() }
        }
        val step = Button(this).apply {
            text = "1 STEP"
            setOnClickListener {
                if (running) stopAudio()
                stopCameraLearning()
                stopHybridPlayback(clear = false)
                memory.setCognitiveMode(CognitiveMode.PLAY)
                syncCognitiveModeUi()
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

        val dreamRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dreamRow.addView(Button(this).apply {
            text = "DREAM RUN"
            setOnClickListener { startDreamPlayback() }
        }, weightedButtonParams())
        dreamRow.addView(Button(this).apply {
            text = "DREAM STEP"
            setOnClickListener {
                stopMemoryPlayback(clear = false)
                stopAudio()
                stopCameraLearning()
                stopHybridPlayback(clear = false)
                memory.setCognitiveMode(CognitiveMode.DREAM)
                syncCognitiveModeUi()
                val ok = memory.autonomousDreamStep()
                dreamModeStatus.text = if (ok) "DREAM: Einzelschritt" else "DREAM: noch keine gelernten Makros"
                refreshMemoryPage()
                updateMemoryStats()
            }
        }, weightedButtonParams())
        dreamRow.addView(Button(this).apply {
            text = "DREAM STOP"
            setOnClickListener { enterPlayMode() }
        }, weightedButtonParams())
        memoryPage.addView(dreamRow, buttonParams(6))

        memoryAutoOrchestra = CheckBox(this).apply {
            text = "Generatives Orchester automatisch aus aktuellem Memory-State"
            setTextColor(Color.WHITE)
            isChecked = false
        }
        memoryPage.addView(memoryAutoOrchestra)

        memoryOrchestraStatus = label("Unified Audio: AUS", 13f, 8).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        memoryPage.addView(memoryOrchestraStatus)

        memoryPage.addView(Button(this).apply {
            text = "MEMORY AUDIO PREVIEW"
            setOnClickListener {
                unifiedAudio.stop()
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text -> runOnUiThread { memoryOrchestraStatus.text = text } }
            }
        }, buttonParams(6))

        memoryPage.addView(label("KNOWLEDGE BASE", 16f, 18).apply {
            setTextColor(Color.rgb(233, 211, 143))
        })
        memoryPage.addView(label(
            "BASE ist global und unveränderlich; PERSONAL/DERIVED liegen im Profil. BASE AUS + NEW ergibt einen echten Start von Null.",
            12f, 5
        ).apply { setTextColor(Color.LTGRAY) })
        memoryPage.addView(CheckBox(this).apply {
            text = "BASE KNOWLEDGE aktiv"
            setTextColor(Color.WHITE)
            isChecked = memory.baseKnowledgeEnabled()
            setOnCheckedChangeListener { _, enabled ->
                stopMemoryPlayback(clear = false)
                stopAudio()
                stopCameraLearning()
                stopHybridPlayback(clear = false)
                memory.setCognitiveMode(CognitiveMode.PLAY)
                val ok = memory.setBaseKnowledgeEnabled(enabled)
                if (!ok) {
                    setOnCheckedChangeListener(null)
                    isChecked = memory.baseKnowledgeEnabled()
                }
                syncCognitiveModeUi()
                refreshMemoryPage()
                updateMemoryStats()
            }
        })

        memoryPage.addView(label("EXTERNAL KNOWLEDGE IMPORT", 14f, 10).apply {
            setTextColor(Color.rgb(205, 182, 245))
        })
        memoryPage.addView(label(
            "Step 46 importiert nur das kanonische equinox-external-knowledge-v1 Schema. Erst Dry-Run/Validierung, dann optionaler atomarer BASE-Install. Embeddings dürfen nur sparse Relationen zwischen bereits geerdeten Makros ableiten.",
            12f, 4
        ).apply { setTextColor(Color.LTGRAY) })
        externalKnowledgeStatus = label("External Knowledge: bereit · noch kein Import", 12f, 5).apply {
            setTextColor(Color.rgb(190, 205, 235))
        }
        memoryPage.addView(externalKnowledgeStatus)
        memoryPage.addView(Button(this).apply {
            text = "EXTERNAL KNOWLEDGE DRY-RUN / IMPORT"
            setOnClickListener { externalKnowledgeImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*")) }
        }, buttonParams(6))

        memoryPage.addView(label("MEMORY PROFILES", 16f, 18).apply {
            setTextColor(Color.rgb(233, 211, 143))
        })
        memoryPage.addView(label(
            "Ein Profil enthält das indexierte Langzeitgedächtnis plus adaptive Audio- und Visual-Codebooks. UI-Einstellungen bleiben global.",
            12f, 5
        ).apply { setTextColor(Color.LTGRAY) })

        profileStatus = label("Aktiv: ${profiles.activeProfileName()}", 13f, 6).apply {
            setTextColor(Color.rgb(205, 220, 235))
        }
        memoryPage.addView(profileStatus)
        profileSpinner = Spinner(this)
        memoryPage.addView(profileSpinner, buttonParams(4))
        refreshProfileControls()

        val profileRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        profileRow1.addView(Button(this).apply {
            text = "NEW"
            setOnClickListener { askProfileName("Neues Gedächtnis", "Memory ${profiles.listProfiles().size + 1}") { name ->
                stopAllForProfileSwitch()
                saveLiveIdentity()
                val created = profiles.createFresh(name)
                if (created != null) {
                    reloadLearnedIdentity()
                    refreshProfileControls(selectId = created.id)
                    profileStatus.text = "Neu und aktiv: ${created.name}"
                } else profileStatus.text = "Neues Profil konnte nicht erstellt werden"
            } }
        }, weightedButtonParams())
        profileRow1.addView(Button(this).apply {
            text = "SAVE"
            setOnClickListener {
                saveLiveIdentity()
                val ok = profiles.saveActive()
                refreshProfileControls()
                profileStatus.text = if (ok) "Gespeichert: ${profiles.activeProfileName()}" else "Profil konnte nicht gespeichert werden"
            }
        }, weightedButtonParams())
        profileRow1.addView(Button(this).apply {
            text = "LOAD"
            setOnClickListener {
                val target = selectedProfileInfo() ?: return@setOnClickListener
                stopAllForProfileSwitch()
                saveLiveIdentity()
                val ok = profiles.switchTo(target.id)
                if (ok) {
                    reloadLearnedIdentity()
                    refreshProfileControls(selectId = target.id)
                    profileStatus.text = "Aktiv: ${target.name}"
                } else profileStatus.text = "Profil konnte nicht geladen werden"
            }
        }, weightedButtonParams())
        memoryPage.addView(profileRow1, buttonParams(4))

        val profileRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        profileRow2.addView(Button(this).apply {
            text = "DUP"
            setOnClickListener {
                val source = selectedProfileInfo() ?: return@setOnClickListener
                askProfileName("Profil duplizieren", "${source.name} Copy") { name ->
                    saveLiveIdentity()
                    val copy = profiles.duplicate(source.id, name)
                    refreshProfileControls(selectId = copy?.id)
                    profileStatus.text = if (copy != null) "Dupliziert: ${copy.name}" else "Duplizieren fehlgeschlagen"
                }
            }
        }, weightedButtonParams())
        profileRow2.addView(Button(this).apply {
            text = "EXPORT"
            setOnClickListener {
                val selected = selectedProfileInfo() ?: return@setOnClickListener
                saveLiveIdentity()
                profileExportLauncher.launch(selected.name.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".eqx")
            }
        }, weightedButtonParams())
        profileRow2.addView(Button(this).apply {
            text = "IMPORT"
            setOnClickListener { profileImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        }, weightedButtonParams())
        memoryPage.addView(profileRow2, buttonParams(4))

        memoryPage.addView(Button(this).apply {
            text = "PROFIL LÖSCHEN"
            setOnClickListener {
                val selected = selectedProfileInfo() ?: return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Profil löschen?")
                    .setMessage(selected.name)
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Löschen") { _, _ ->
                        stopAllForProfileSwitch()
                        val wasActive = selected.active
                        val ok = profiles.delete(selected.id)
                        if (ok && wasActive) reloadLearnedIdentity()
                        refreshProfileControls()
                        profileStatus.text = if (ok) "Profil gelöscht" else "Mindestens ein Profil muss bestehen bleiben"
                    }.show()
            }
        }, buttonParams(4))

        memoryPage.addView(Button(this).apply {
            text = "AKTIVES FELD LEEREN"
            setOnClickListener {
                stopMemoryPlayback(clear = true)
                refreshMemoryPage()
            }
        }, buttonParams(6))
    }

    private fun refreshProfileControls(selectId: String? = null) {
        if (!::profileSpinner.isInitialized || !::profiles.isInitialized) return
        val items = profiles.listProfiles()
        val labels = items.map { (if (it.active) "● " else "") + it.name + if (it.bytes > 0L) " · ${it.bytes / 1024} KB" else "" }
        profileSpinnerSyncing = true
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val targetId = selectId ?: profiles.activeProfileId()
        val index = items.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        if (items.isNotEmpty()) profileSpinner.setSelection(index, false)
        profileSpinnerSyncing = false
    }

    private fun selectedProfileInfo(): MemoryProfileManager.ProfileInfo? {
        if (!::profileSpinner.isInitialized || !::profiles.isInitialized) return null
        val items = profiles.listProfiles()
        return items.getOrNull(profileSpinner.selectedItemPosition)
    }

    private fun askProfileName(title: String, initial: String, onName: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            selectAll()
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("OK") { _, _ -> onName(input.text.toString()) }
            .show()
    }

    private fun saveLiveIdentity() {
        if (::memory.isInitialized) memory.saveNow()
        if (::adaptiveQuantizer.isInitialized) adaptiveQuantizer.saveNow()
        if (::adaptiveVisualQuantizer.isInitialized) adaptiveVisualQuantizer.saveNow()
    }

    private fun stopAllForProfileSwitch() {
        stopAudio()
        stopCameraLearning()
        stopMemoryPlayback(clear = true)
        stopDreamPlayback(clear = true)
        if (hybridPlaybackRunning) stopHybridPlayback(clear = true)
        if (unifiedAudio.isRunning()) unifiedAudio.stop()
        memory.setCognitiveMode(CognitiveMode.PLAY)
        syncCognitiveModeUi()
    }

    /** Reloads only learned identity; UI/display preferences remain untouched. */
    private fun reloadLearnedIdentity() {
        memory.reloadFromDisk()
        adaptiveQuantizer = AdaptiveAudioQuantizer(this)
        adaptiveVisualQuantizer = AdaptiveVisualQuantizer(this)
        visualAnalyzer = null
        visualFrameCount = 0L
        visualObservationCount = 0L
        refreshMemoryPage()
        updateMemoryStats()
        refreshGraph()
        if (::adaptiveQuantizerStatus.isInitialized) adaptiveQuantizerStatus.text = adaptiveQuantizerText()
        if (::adaptiveVisualQuantizerStatus.isInitialized) adaptiveVisualQuantizerStatus.text = adaptiveVisualQuantizerText()
    }

    private fun showExternalKnowledgeDryRun(report: ExternalImportReport) {
        if (!::externalKnowledgeStatus.isInitialized) return
        val issuePreview = report.issues.take(5).joinToString("\n") { issue ->
            "${if (issue.severity == ImportIssue.Severity.ERROR) "ERROR" else "WARN"}: ${issue.message}"
        }
        externalKnowledgeStatus.text = if (report.valid) {
            "Dry-Run OK: ${report.packId} v${report.packVersion} · ${report.macrosCompiled} Makros · ${report.finalRelations} Relationen"
        } else {
            "Dry-Run FEHLER: ${report.errors.size} Error(s) · ${report.warnings.size} Warnung(en)"
        }

        val summary = buildString {
            append("Quelle: ").append(report.sourceId.ifBlank { "—" })
            if (report.sourceVersion.isNotBlank()) append(" ").append(report.sourceVersion)
            append("\nBase: ").append(report.packId.ifBlank { "—" }).append(" v").append(report.packVersion)
            append("\nNodes: ").append(report.nodesRead)
            append("\nMakros: ").append(report.macrosCompiled)
            append("\nExplizite Relationen: ").append(report.explicitRelationsRead)
            append("\nEmbedding-Relationen: ").append(report.embeddingRelationsDerived)
            append("\nFinale Relationen: ").append(report.finalRelations)
            append("\nWarnings: ").append(report.warnings.size).append(" · Errors: ").append(report.errors.size)
            if (issuePreview.isNotBlank()) append("\n\n").append(issuePreview)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (report.valid) "External Knowledge — Dry-Run OK" else "External Knowledge — abgelehnt")
            .setMessage(summary)
            .setNegativeButton("Schließen", null)

        val compiledPack = report.compiledPack
        if (report.valid && compiledPack != null) {
            val requirement = memory.baseRequirement()
            val compatible = !requirement.required || (
                report.packId == requirement.packId && report.packVersion >= requirement.minimumVersion
            )
            if (compatible) {
                builder.setPositiveButton("BASE INSTALLIEREN") { _, _ -> installExternalBasePack(compiledPack) }
            } else {
                builder.setMessage(summary + "\n\nINSTALL BLOCKIERT: Aktives Profil benötigt ${requirement.packId} >= v${requirement.minimumVersion}. Ein anderer Base-Pack würde dessen DERIVED-Wissen quarantinen.")
            }
        }
        builder.show()
    }

    private fun installExternalBasePack(pack: CompiledBasePack) {
        stopAllForProfileSwitch()
        saveLiveIdentity()
        externalKnowledgeStatus.text = "External Knowledge: installiere ${pack.packId} v${pack.version}…"
        thread(name = "Equinox-BasePack-Install") {
            val result = BaseKnowledgeRepository(this).install(pack)
            runOnUiThread {
                if (result.success) {
                    reloadLearnedIdentity()
                    externalKnowledgeStatus.text = "BASE installiert: ${result.manifest.packId} v${result.manifest.version} · ${result.manifest.macroCount} Makros · ${result.manifest.relationCount} Relationen"
                } else {
                    externalKnowledgeStatus.text = "BASE-Install fehlgeschlagen: ${result.message}"
                }
                updateMemoryStats()
            }
        }
    }

    private fun buildLabPage() {
        labPage.addView(label("LAB — DEBUG / SPEICHER / PARAMETER", 20f, 18).apply {
            setTextColor(Color.rgb(150, 175, 210))
        })

        labPage.addView(label("PRODUCT RENDERER", 16f, 12).apply {
            setTextColor(Color.rgb(178, 140, 255))
        })
        val productRendererStatus = label("Primär: HYPNO · organische Unified-State-Projektion", 13f, 5).apply {
            setTextColor(Color.rgb(210, 190, 255))
        }
        labPage.addView(productRendererStatus)
        labPage.addView(label(
            "HYPNO ist die einzige Consumer-Darstellung. STRUCTURE ist nur eine temporäre LAB-Vorschau und wird beim Schließen der Einstellungen automatisch auf HYPNO zurückgesetzt. HYPNO und Unified Audio lesen denselben normalisierten Präsentationszustand; bei Live-Kamera sogar denselben verzögerten WorkingSetSnapshot. Nichts davon schreibt in MemoryModel zurück.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })
        val rendererRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val hypnoRendererButton = Button(this).apply { text = "HYPNO"; isEnabled = false }
        val structureRendererButton = Button(this).apply { text = "STRUCTURE" }
        fun refreshRendererButtons() {
            val hypno = surfaceVideo.presentationMode() == UnifiedPresentationMode.HYPNO
            hypnoRendererButton.isEnabled = !hypno
            structureRendererButton.isEnabled = hypno
            productRendererStatus.text = if (hypno)
                "Primär: HYPNO · organische Unified-State-Projektion"
            else
                "Lab: STRUCTURE · technische Tier-/Gewebe-Projektion"
        }
        hypnoRendererButton.setOnClickListener {
            surfaceVideo.setPresentationMode(UnifiedPresentationMode.HYPNO)
            surfaceVideo.setModeLabel("HYPNO")
            refreshRendererButtons()
        }
        structureRendererButton.setOnClickListener {
            surfaceVideo.setPresentationMode(UnifiedPresentationMode.STRUCTURE)
            surfaceVideo.setModeLabel("STRUCTURE")
            refreshRendererButtons()
        }
        rendererRow.addView(hypnoRendererButton, weightedButtonParams())
        rendererRow.addView(structureRendererButton, weightedButtonParams())
        labPage.addView(rendererRow, buttonParams(6))
        refreshRendererButtons()

        labPage.addView(label("AUDIO-ENCODER HARD TEST", 16f, 12).apply {
            setTextColor(Color.rgb(145, 210, 255))
        })
        labPage.addView(label(
            "Drei getrennte Alphabete: FFT-Frequenz, festes DIRECT-16-bit und ADAPTIVE. ADAPTIVE nutzt keine FFT und keine festen Bins: ein persistentes Codebook lernt stabile Prototypen aus Zeitbereichsmerkmalen.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })
        labAudioEncoderStatus = label("Aktiv: ${audioEncoderMode.label}", 13f, 8).apply {
            setTextColor(Color.rgb(180, 220, 255))
        }
        labPage.addView(labAudioEncoderStatus)
        val encoderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val fftEncoderButton = Button(this).apply { text = "FFT" }
        val directEncoderButton = Button(this).apply { text = "DIRECT" }
        val adaptiveEncoderButton = Button(this).apply { text = "ADAPTIVE" }
        fun refreshEncoderButtons() {
            fftEncoderButton.isEnabled = audioEncoderMode != AudioEncoderMode.FFT_FREQUENCY
            directEncoderButton.isEnabled = audioEncoderMode != AudioEncoderMode.DIRECT_PATTERN
            adaptiveEncoderButton.isEnabled = audioEncoderMode != AudioEncoderMode.ADAPTIVE_PATTERN
            labAudioEncoderStatus.text = "Aktiv: ${audioEncoderMode.label}"
            if (::audioEncoderStatus.isInitialized) audioEncoderStatus.text = "Audio-Encoder: ${audioEncoderMode.label}"
        }
        fftEncoderButton.setOnClickListener {
            setAudioEncoderMode(AudioEncoderMode.FFT_FREQUENCY)
            refreshEncoderButtons()
        }
        directEncoderButton.setOnClickListener {
            setAudioEncoderMode(AudioEncoderMode.DIRECT_PATTERN)
            refreshEncoderButtons()
        }
        adaptiveEncoderButton.setOnClickListener {
            setAudioEncoderMode(AudioEncoderMode.ADAPTIVE_PATTERN)
            refreshEncoderButtons()
        }
        encoderRow.addView(fftEncoderButton, weightedButtonParams())
        encoderRow.addView(directEncoderButton, weightedButtonParams())
        encoderRow.addView(adaptiveEncoderButton, weightedButtonParams())
        labPage.addView(encoderRow, buttonParams(5))
        refreshEncoderButtons()

        adaptiveQuantizerStatus = label(adaptiveQuantizerText(), 12f, 5).apply {
            setTextColor(Color.rgb(220, 190, 255))
        }
        labPage.addView(adaptiveQuantizerStatus)

        labPage.addView(label("PARALLELER KONVERGENZTEST", 16f, 16).apply {
            setTextColor(Color.rgb(120, 235, 190))
        })
        labPage.addView(label(
            "Im Test sehen FFT, DIRECT und ADAPTIVE exakt dieselben PCM-Frames und lernen gleichzeitig in getrennten Modalitäten. Gemessen werden ID-Neuheit, Wiederholung, Übergangsstabilität, Entropie und Makroaufbau. ADAPTIVE lernt dabei zusätzlich sein eigenes Codebook.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })

        encoderBenchmarkToggle = CheckBox(this).apply {
            text = "Alle 3 Encoder parallel lernen + messen"
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                parallelEncoderBenchmarkEnabled = checked
                if (checked) {
                    encoderBenchmark.resetAndStart(memory.stats())
                } else {
                    encoderBenchmark.stop()
                }
                refreshEncoderBenchmarkStatus()
            }
        }
        labPage.addView(encoderBenchmarkToggle)

        encoderBenchmarkStatus = label("Konvergenztest: AUS", 12f, 6).apply {
            setTextColor(Color.rgb(180, 235, 205))
        }
        labPage.addView(encoderBenchmarkStatus)

        val benchmarkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        benchmarkRow.addView(Button(this).apply {
            text = "TEST NEU"
            setOnClickListener {
                encoderBenchmark.resetAndStart(memory.stats())
                parallelEncoderBenchmarkEnabled = true
                encoderBenchmarkToggle.isChecked = true
                refreshEncoderBenchmarkStatus()
            }
        }, weightedButtonParams())
        benchmarkRow.addView(Button(this).apply {
            text = "CSV"
            setOnClickListener {
                encoderBenchmark.updateMemory(memory.stats())
                val f = encoderBenchmark.writeCsvExport()
                refreshEncoderBenchmarkStatus()
                encoderBenchmarkStatus.text = encoderBenchmarkStatus.text.toString() + "\nCSV intern: ${f.name} (${f.length()} Bytes)"
            }
        }, weightedButtonParams())
        benchmarkRow.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                parallelEncoderBenchmarkEnabled = false
                encoderBenchmark.stop()
                encoderBenchmarkToggle.isChecked = false
                refreshEncoderBenchmarkStatus()
            }
        }, weightedButtonParams())
        labPage.addView(benchmarkRow, buttonParams(5))
        refreshEncoderBenchmarkStatus()

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
        val l1Input = colorField("F HEX", currentPrefs.macroLevel1Color)
        val l2Input = colorField("E HEX", currentPrefs.macroLevel2Color)
        val l3Input = colorField("D HEX", currentPrefs.macroLevel3Color)
        val l4Input = colorField("C+ Basis HEX", currentPrefs.macroLevel4Color)
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

        labPage.addView(label("LONG-TERM HARD TEST", 16f, 18).apply { setTextColor(Color.rgb(245, 190, 95)) })
        labPage.addView(label(
            "Misst nur. Der Test verändert keine Lernregeln. Automatisches Sample alle 5 Minuten; für verwertbare Trends sollte die App über längere Zeit mit ähnlichen Alltagsbedingungen laufen.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })
        hardTestStatus = label("Hard Test: noch keine Daten", 12f, 6).apply { setTextColor(Color.rgb(225, 215, 175)) }
        labPage.addView(hardTestStatus)
        val hardTestRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        hardTestRow1.addView(Button(this).apply {
            text = "TEST START 5MIN"
            setOnClickListener {
                hardTest.startNewSession()
                captureHardTestSample(saveMemory = true)
                uiHandler.removeCallbacks(hardTestLoop)
                uiHandler.postDelayed(hardTestLoop, LongTermHardTestManager.DEFAULT_INTERVAL_MS)
                refreshHardTestStatus()
            }
        }, weightedButtonParams())
        hardTestRow1.addView(Button(this).apply {
            text = "SAMPLE"
            setOnClickListener { captureHardTestSample(saveMemory = true) }
        }, weightedButtonParams())
        hardTestRow1.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                hardTest.stop()
                uiHandler.removeCallbacks(hardTestLoop)
                refreshHardTestStatus()
            }
        }, weightedButtonParams())
        labPage.addView(hardTestRow1)
        val hardTestRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        hardTestRow2.addView(Button(this).apply {
            text = "CSV"
            setOnClickListener {
                val f = hardTest.writeCsvExport()
                refreshHardTestStatus()
                hardTestStatus.text = hardTestStatus.text.toString() + "\nCSV intern: ${f.name} (${f.length()} Bytes)"
            }
        }, weightedButtonParams())
        hardTestRow2.addView(Button(this).apply {
            text = "RESET"
            setOnClickListener {
                hardTest.reset()
                uiHandler.removeCallbacks(hardTestLoop)
                refreshHardTestStatus()
            }
        }, weightedButtonParams())
        labPage.addView(hardTestRow2)
        refreshHardTestStatus()
        if (hardTest.isRunning()) {
            uiHandler.removeCallbacks(hardTestLoop)
            uiHandler.postDelayed(hardTestLoop, LongTermHardTestManager.DEFAULT_INTERVAL_MS)
        }

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

        labPage.addView(Button(this).apply {
            text = "MEMORY COMPETITION JETZT"
            setOnClickListener {
                val r = memory.runMemoryCompetition(force = true)
                lastConsolidationSummary = "Memory Competition: ${r.macrosExamined} geprüft · ${r.macrosPruned} Makros · ${r.relationsPruned} Relationen · ${r.candidatesPruned} Kandidaten entfernt · ${r.protectedMacros} geschützt"
                updateConsolidationStatus()
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
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text -> runOnUiThread { sonificationStatus.text = text } }
            }
        }, buttonParams(6))
        labPage.addView(Button(this).apply {
            text = "KLANG STOPP"
            setOnClickListener { unifiedAudio.stop(); sonificationStatus.text = "Sonifikation: AUS" }
        }, buttonParams(6))

        labOrchestraStatus = label("Orchester: AUS", 13f, 10).apply { setTextColor(Color.rgb(125, 220, 255)) }
        labPage.addView(labOrchestraStatus)
        labPage.addView(Button(this).apply {
            text = "STATE AUDIO PREVIEW"
            setOnClickListener {
                if (running) stopAudio()
                unifiedAudio.stop()
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text -> runOnUiThread { labOrchestraStatus.text = text } }
            }
        }, buttonParams(6))
        labPage.addView(Button(this).apply {
            text = "AUDIO PREVIEW STOPP"
            setOnClickListener {
                unifiedAudio.stop()
                labOrchestraStatus.text = "Orchester: AUS"
                if (::memoryOrchestraStatus.isInitialized) memoryOrchestraStatus.text = "Unified Audio: AUS"
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
            "Step 24 ergänzt den indexierten Langzeitspeicher um einen kontrollierten Hard Test. Er protokolliert über Stunden/Tage Neuheitsraten, Makrowachstum, Kompression, Prediction, Vergessen sowie Storage- und Heap-Wachstum. Die Messung ist read-only gegenüber der Lernlogik und kann als CSV exportiert werden.",
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
                stopHybridPlayback(clear = false)
                unifiedAudio.stop()
                hybridPage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                labPage.visibility = View.GONE
                livePage.visibility = View.VISIBLE
            }
            AppPage.HYBRID -> {
                stopMemoryPlayback(clear = false)
                unifiedAudio.stop()
                livePage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                labPage.visibility = View.GONE
                hybridPage.visibility = View.VISIBLE
                refreshSensorUiState()
                refreshHybridPage(ok = true)
            }
            AppPage.MEMORY -> {
                stopHybridPlayback(clear = true)
                stopAudio()
                stopCameraLearning()
                unifiedAudio.stop()
                stopContinuousOrchestra()
                memory.clearMomentaryState()
                livePage.visibility = View.GONE
                hybridPage.visibility = View.GONE
                labPage.visibility = View.GONE
                memoryPage.visibility = View.VISIBLE
                memoryModeStatus.text = if (memory.hasLearnedMacros()) "MEMORY: bereit" else "MEMORY: noch keine gelernten Makros"
                if (::dreamModeStatus.isInitialized) dreamModeStatus.text = if (memory.hasLearnedMacros()) "DREAM: bereit" else "DREAM: noch keine gelernten Makros"
            }
            AppPage.LAB -> {
                stopMemoryPlayback(clear = false)
                stopHybridPlayback(clear = false)
                livePage.visibility = View.GONE
                hybridPage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                labPage.visibility = View.VISIBLE
            }
        }
        currentPage = page
        refreshGraph()
    }

    private fun enterLearnMode() {
        stopDreamPlayback(clear = false)
        stopMemoryPlayback(clear = false)
        stopHybridPlayback(clear = false)
        memory.setCognitiveMode(CognitiveMode.LEARN)
        syncCognitiveModeUi()
        refreshGraph()
    }

    private fun enterPlayMode(stopInternalPlayback: Boolean = true) {
        if (stopInternalPlayback) {
            stopDreamPlayback(clear = false)
            stopMemoryPlayback(clear = false)
            stopHybridPlayback(clear = false)
        }
        memory.setCognitiveMode(CognitiveMode.PLAY)
        syncCognitiveModeUi()
        refreshGraph()
    }

    private fun syncCognitiveModeUi() {
        val mode = memory.cognitiveMode()
        if (::productModeButton.isInitialized) {
            productModeButton.text = when (mode) {
                CognitiveMode.LEARN -> "LEARN"
                CognitiveMode.PLAY -> "LIVE"
                CognitiveMode.DREAM -> "DREAM"
            }
            productModeButton.setTextColor(when (mode) {
                CognitiveMode.LEARN -> Color.rgb(105, 225, 235)
                CognitiveMode.PLAY -> Color.rgb(145, 230, 210)
                CognitiveMode.DREAM -> Color.rgb(255, 176, 112)
            })
        }
        if (::cognitiveModeStatus.isInitialized) {
            cognitiveModeStatus.text = when (mode) {
                CognitiveMode.LEARN -> "Modus: LEARN · Sensoren schreiben neue Erfahrung"
                CognitiveMode.PLAY -> "Modus: PLAY · Sensoren aktivieren nur Bekanntes"
                CognitiveMode.DREAM -> "Modus: DREAM · nur interne read-only Aktivierung"
            }
        }
        refreshSensorUiState()
    }

    private fun startHybridPlayback() {
        enterPlayMode(stopInternalPlayback = false)
        if (!memory.hasLearnedMacros()) {
            hybridModeStatus.text = "HYBRID: noch keine gelernten Makros"
            return
        }
        stopMemoryPlayback(clear = false)
        if (!hybridPlaybackRunning) {
            hybridPlaybackRunning = true
            hybridModeStatus.text = "HYBRID: RUN · Live ankert, Memory propagiert read-only"
            uiHandler.removeCallbacks(hybridLoop)
            uiHandler.post(hybridLoop)
        }
    }

    private fun stopHybridPlayback(clear: Boolean) {
        hybridPlaybackRunning = false
        uiHandler.removeCallbacks(hybridLoop)
        if (clear) memory.clearMomentaryState()
        if (::hybridModeStatus.isInitialized) {
            hybridModeStatus.text = if (memory.hasLearnedMacros()) "HYBRID: gestoppt" else "HYBRID: noch keine gelernten Makros"
        }
    }

    private fun refreshHybridPage(ok: Boolean) {
        val w = memory.workingSetSnapshot()
        livePipeline.offerState(w)
        if (::surfaceVideo.isInitialized) {
            surfaceVideo.setWorkingState(w)
            scheduleLowLatencyRender()
        }
        if (unifiedAudio.isRunning() && !cameraRunning) {
            unifiedAudio.updateState(memory.sonificationSnapshot(maxMicro = 0, maxMacros = 12), w)
        }
        if (settingsOpen && ::hybridModeStatus.isInitialized) {
            hybridModeStatus.text = if (!ok && w.nodes.none { it.activation > 0.12 }) {
                "HYBRID: wartet auf Live-Makro"
            } else if (hybridPlaybackRunning) {
                "HYBRID: RUN · ${w.nodes.count { it.activation > 0.12 }} live/aktiv · ${w.nodes.count { it.retrieved }} erinnert · ${w.predictions.size} Prediction · ${w.exchanges.size} Austausch"
            } else {
                "HYBRID: ${w.nodes.size} Working-Set-Knoten · ${w.predictions.size} Prediction"
            }
        }
        if (settingsOpen) refreshSensorUiState()
    }

    private fun refreshSensorUiState() {
        if (!settingsOpen) return
        if (::hybridSensorStatus.isInitialized) {
            hybridSensorStatus.text = "Sensoren: Audio ${if (running) "AKTIV" else "AUS"} · Kamera ${if (cameraRunning) "AKTIV" else "AUS"}"
        }
        if (::status.isInitialized) status.text = if (running) "Audio: AKTIV" else "Audio: AUS"
        if (::startButton.isInitialized) startButton.isEnabled = !running
        if (::stopButton.isInitialized) stopButton.isEnabled = running
        if (::cameraStatus.isInitialized) cameraStatus.text = if (cameraRunning) "Kamera: AKTIV · ${memory.cognitiveMode().label} · coarse→fine" else "Kamera: AUS"
        if (::cameraButton.isInitialized) cameraButton.isEnabled = !cameraRunning
        if (::cameraStopButton.isInitialized) cameraStopButton.isEnabled = cameraRunning
    }

    private fun startMemoryPlayback() {
        enterPlayMode(stopInternalPlayback = false)
        if (!memory.hasLearnedMacros()) {
            memoryModeStatus.text = "MEMORY: noch keine gelernten Makros"
            return
        }
        stopAudio()
        stopCameraLearning()
        stopHybridPlayback(clear = false)
        unifiedAudio.stop()
        stopContinuousOrchestra()
        stopDreamPlayback(clear = false)
        if (!memoryPlaybackRunning) {
            memoryPlaybackRunning = true
            memoryModeStatus.text = "MEMORY: RUN · nur gelerntes Gedächtnis"
            uiHandler.removeCallbacks(memoryLoop)
            uiHandler.post(memoryLoop)
        }
    }

    private fun stopMemoryPlayback(clear: Boolean) {
        memoryPlaybackRunning = false
        dreamPlaybackRunning = false
        uiHandler.removeCallbacks(memoryLoop)
        uiHandler.removeCallbacks(dreamLoop)
        if (clear) memory.clearMomentaryState()
        if (::memoryModeStatus.isInitialized) {
            memoryModeStatus.text = if (memory.hasLearnedMacros()) "MEMORY: gestoppt" else "MEMORY: noch keine gelernten Makros"
        }
        if (::dreamModeStatus.isInitialized) {
            dreamModeStatus.text = if (memory.hasLearnedMacros()) "DREAM: gestoppt" else "DREAM: noch keine gelernten Makros"
        }
    }

    private fun startDreamPlayback() {
        if (!memory.hasLearnedMacros()) {
            if (::dreamModeStatus.isInitialized) dreamModeStatus.text = "DREAM: noch keine gelernten Makros"
            return
        }
        stopAudio()
        stopCameraLearning()
        stopHybridPlayback(clear = false)
        unifiedAudio.stop()
        stopContinuousOrchestra()
        stopMemoryPlayback(clear = false)
        memory.setCognitiveMode(CognitiveMode.DREAM)
        syncCognitiveModeUi()
        dreamPlaybackRunning = true
        dreamModeStatus.text = "DREAM: RUN · read-only autonome Exploration"
        uiHandler.removeCallbacks(dreamLoop)
        uiHandler.post(dreamLoop)
    }

    private fun stopDreamPlayback(clear: Boolean) {
        dreamPlaybackRunning = false
        uiHandler.removeCallbacks(dreamLoop)
        if (clear) memory.clearMomentaryState()
        if (::dreamModeStatus.isInitialized) {
            dreamModeStatus.text = if (memory.hasLearnedMacros()) "DREAM: gestoppt" else "DREAM: noch keine gelernten Makros"
        }
    }

    private fun refreshMemoryPage() {
        val state = outputBridge.snapshot()
        if (::surfaceVideo.isInitialized) surfaceVideo.setWorkingState(state.working)
        if (unifiedAudio.isRunning()) unifiedAudio.updateState(state.sound, state.working)
        val w = state.working
        if (settingsOpen && ::memoryModeStatus.isInitialized && memoryPlaybackRunning) {
            memoryModeStatus.text = "MEMORY: RUN · ${w.nodes.size} aktiv · ${w.exchanges.size} Austausch"
        }
        if (settingsOpen && ::dreamModeStatus.isInitialized && dreamPlaybackRunning) {
            val d = memory.stats()
            dreamModeStatus.text = "DREAM: RUN · ${d.dreamLastMode} · Schritt ${d.dreamSteps} · ${w.nodes.size} aktiv"
        }
    }

    private fun refreshGraph() {
        // Step 30: one surface always receives the compact WorkingSet. The camera frame, if
        // available, is synchronized separately by LowLatencyLivePipeline. Memory-only/Dream
        // states therefore render on the same GPU canvas without requiring a second View.
        if (::surfaceVideo.isInitialized) {
            val working = memory.workingSetSnapshot()
            surfaceVideo.setWorkingState(working)
            livePipeline.offerState(working)
            scheduleLowLatencyRender()
        }

        val labDue = settingsOpen && currentPage == AppPage.LAB &&
            ::graphView.isInitialized && labPage.visibility == View.VISIBLE &&
            System.currentTimeMillis() - lastLabRenderMs >= 250L
        if (!labDue) return

        val state = outputBridge.snapshot()
        lastLabRenderMs = System.currentTimeMillis()
        graphView.setSnapshot(state.graph)
    }

    private fun scheduleLowLatencyRender() {
        if (!::surfaceVideo.isInitialized) return
        if (!liveRenderPosted.compareAndSet(false, true)) return

        uiHandler.post {
            liveRenderPosted.set(false)
            if (!::surfaceVideo.isInitialized || surfaceVideo.visibility != View.VISIBLE) return@post

            // Feed the controller the cost of the previous draw before selecting the next frame.
            livePipeline.reportRenderCost(surfaceVideo.lastRenderCostMs())
            applyPerformanceGovernor()
            val packet = livePipeline.poll() ?: return@post
            val preset = packet.configuredPreset.label
            val info = if (packet.configuredPreset == LowLatencyLivePipeline.DelayPreset.AUTO) {
                "Sync AUTO ${packet.effectiveDelayMs}ms · Frame ${packet.actualFrameAgeMs}ms · drop ${packet.droppedFramesTotal}"
            } else {
                "Sync $preset · Frame ${packet.actualFrameAgeMs}ms · drop ${packet.droppedFramesTotal}"
            }
            surfaceVideo.setSynchronizedFrame(packet.frame, packet.working, info)
            // Step 44: when camera presentation is active, audio receives the exact same
            // delayed WorkingSetSnapshot that HYPNO receives. This prevents semantic AV skew
            // from independently sampling the live memory frontier.
            if (unifiedAudio.isRunning() && packet.working != null) {
                unifiedAudio.updateState(
                    memory.sonificationSnapshot(maxMicro = 0, maxMacros = 12),
                    packet.working
                )
            }
            if (settingsOpen) updateLiveLatencyStatus()
        }
    }

    private fun applyPerformanceGovernor(force: Boolean = false) {
        if (!::surfaceVideo.isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastGovernorUpdateMs < 500L) return
        lastGovernorUpdateMs = now

        val st = livePipeline.stats()
        val dropRatio = if (st.offeredFrames <= 0L) 0.0 else
            (st.droppedFrames.toDouble() / st.offeredFrames.toDouble()).coerceIn(0.0, 1.0)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        } else 0
        val gov = performanceGovernor.update(
            renderMs = surfaceVideo.lastRenderCostMs(),
            analysisMs = st.analysisCostEwmaMs,
            dropRatio = dropRatio,
            thermalStatus = thermal,
            nowMs = now
        )
        surfaceVideo.setPerformanceProfile(gov.profile)
        visualAnalyzer?.setPerformanceProfile(gov.profile)
    }

    private fun updateLiveLatencyStatus() {
        if (!::liveLatencyStatus.isInitialized) return
        val st = livePipeline.stats()
        val g = performanceGovernor.snapshot()
        liveLatencyStatus.text =
            "Live-Sync: ${st.preset.label} · effektiv ${st.effectiveDelayMs} ms · Analyse Ø ${"%.1f".format(st.analysisCostEwmaMs)} ms · Render Ø ${"%.1f".format(st.renderCostEwmaMs)} ms · Frames ${st.renderedFrames}/${st.offeredFrames} · verworfen ${st.droppedFrames} · Buffer ${st.bufferedFrames} · Governor ${g.profile.label} (${g.profile.fieldBudget}F/${g.profile.linkBudget}L)"
    }

    private fun startUnifiedCapture(resultCode: Int, data: android.content.Intent) {
        if (captureRecorder.isRecording()) return
        val metrics = resources.displayMetrics
        unifiedAudio.setPcmTap { pcm -> captureRecorder.offerPcm(pcm) }
        captureButton.text = "■"
        captureButton.setTextColor(Color.rgb(255, 92, 92))
        captureRecorder.start(
            resultCode = resultCode,
            resultData = data,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            onState = { text -> runOnUiThread {
                if (::captureStatus.isInitialized) captureStatus.text = "Aufnahme: $text"
            } },
            onFinished = { result -> runOnUiThread {
                unifiedAudio.setPcmTap(null)
                CaptureForegroundService.stop(this)
                captureButton.text = "REC"
                captureButton.setTextColor(Color.rgb(255, 112, 112))
                if (::captureStatus.isInitialized) {
                    captureStatus.text = if (result.error == null) {
                        val where = result.uri?.toString() ?: result.path.orEmpty()
                        "Aufnahme gespeichert · ${"%.1f".format(result.durationMs / 1000.0)} s · $where"
                    } else "Aufnahmefehler: ${result.error}"
                }
            } }
        )
    }

    private fun stopUnifiedCapture() {
        if (!captureRecorder.isRecording()) return
        captureButton.text = "…"
        captureRecorder.stop(
            onState = { text -> runOnUiThread { if (::captureStatus.isInitialized) captureStatus.text = "Aufnahme: $text" } },
            onFinished = { result -> runOnUiThread {
                unifiedAudio.setPcmTap(null)
                CaptureForegroundService.stop(this)
                captureButton.text = "REC"
                captureButton.setTextColor(Color.rgb(255, 112, 112))
                if (::captureStatus.isInitialized) {
                    captureStatus.text = if (result.error == null)
                        "Aufnahme gespeichert · ${"%.1f".format(result.durationMs / 1000.0)} s"
                    else "Aufnahmefehler: ${result.error}"
                }
            } }
        )
    }

    private fun toggleContinuousOrchestra() {
        if (unifiedAudio.isRunning()) {
            stopContinuousOrchestra()
        } else {
            unifiedAudio.stop()
            unifiedAudio.start { text ->
                runOnUiThread {
                    updateContinuousOrchestraUi(text)
                    updateEchoCancelerState()
                }
            }
            pushContinuousOrchestraState()
            updateEchoCancelerState()
            updateContinuousOrchestraUi("Unified Audio: STARTET …")
        }
    }

    private fun stopContinuousOrchestra() {
        if (unifiedAudio.isRunning()) unifiedAudio.stop()
        updateEchoCancelerState()
        updateContinuousOrchestraUi("Unified Audio: AUS")
    }

    private fun pushContinuousOrchestraState() {
        if (!unifiedAudio.isRunning()) return
        val sound = memory.sonificationSnapshot(maxMicro = 0, maxMacros = 12)
        val working = memory.workingSetSnapshot()
        unifiedAudio.updateState(sound, working)
    }

    private fun updateContinuousOrchestraUi(text: String) {
        val guard = if (feedbackProtectionEnabled) {
            val aec = if (echoCanceler?.enabled == true) "AEC AN" else if (AcousticEchoCanceler.isAvailable()) "AEC bereit" else "AEC n/a"
            " · $aec · SelfGuard ${lastSelfOutputComponents}/${"%.1f".format(lastSelfOutputRemovedRatio * 100.0)}%"
        } else " · Feedback-Schutz AUS"
        val full = text + guard
        if (::liveOrchestraStatus.isInitialized) liveOrchestraStatus.text = full
        if (::hybridOrchestraStatus.isInitialized) hybridOrchestraStatus.text = full
        if (::productAudioButton.isInitialized) {
            productAudioButton.text = if (unifiedAudio.isRunning()) "■" else "♪"
            productAudioButton.setTextColor(if (unifiedAudio.isRunning()) Color.rgb(125, 220, 255) else Color.WHITE)
        }
        if (::liveContinuousButton.isInitialized) liveContinuousButton.text = if (unifiedAudio.isRunning()) "AUDIO STOP" else "AUDIO START"
        if (::hybridContinuousButton.isInitialized) hybridContinuousButton.text = if (unifiedAudio.isRunning()) "AUDIO STOP" else "AUDIO START"
    }

    private fun setFeedbackProtection(enabled: Boolean) {
        if (feedbackUiSyncing) return
        feedbackProtectionEnabled = enabled
        feedbackUiSyncing = true
        try {
            if (::liveFeedbackProtectionToggle.isInitialized && liveFeedbackProtectionToggle.isChecked != enabled) liveFeedbackProtectionToggle.isChecked = enabled
            if (::hybridFeedbackProtectionToggle.isInitialized && hybridFeedbackProtectionToggle.isChecked != enabled) hybridFeedbackProtectionToggle.isChecked = enabled
        } finally {
            feedbackUiSyncing = false
        }
        updateEchoCancelerState()
        updateContinuousOrchestraUi(if (unifiedAudio.isRunning()) "Unified Audio: ${unifiedAudio.sceneSummary()}" else "Unified Audio: AUS")
    }

    private fun setupEchoCanceler(recorder: AudioRecord) {
        releaseEchoCanceler()
        if (!AcousticEchoCanceler.isAvailable()) return
        echoCanceler = try { AcousticEchoCanceler.create(recorder.audioSessionId) } catch (_: Exception) { null }
        updateEchoCancelerState()
    }

    private fun updateEchoCancelerState() {
        val shouldEnable = feedbackProtectionEnabled && unifiedAudio.isRunning()
        try { echoCanceler?.enabled = shouldEnable } catch (_: Exception) { }
    }

    private fun releaseEchoCanceler() {
        try { echoCanceler?.enabled = false } catch (_: Exception) { }
        try { echoCanceler?.release() } catch (_: Exception) { }
        echoCanceler = null
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
        if (memory.cognitiveMode() == CognitiveMode.DREAM) enterPlayMode()
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
        setupEchoCanceler(recorder)
        running = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "Audio: AKTIV"
        refreshSensorUiState()
        lastConsolidationMs = System.currentTimeMillis()

        recorder.startRecording()
        val selectedEncoder = audioEncoderMode
        worker = thread(name = when (selectedEncoder) {
            AudioEncoderMode.FFT_FREQUENCY -> "EquinoxAudioFFT"
            AudioEncoderMode.DIRECT_PATTERN -> "EquinoxAudioDirectID"
            AudioEncoderMode.ADAPTIVE_PATTERN -> "EquinoxAudioAdaptiveID"
        }) {
            val frame = ShortArray(FFT_SIZE)
            var frameCount = 0L

            while (running) {
                var offset = 0
                while (running && offset < FFT_SIZE) {
                    val read = recorder.read(frame, offset, FFT_SIZE - offset)
                    if (read > 0) offset += read else break
                }
                if (!running || offset < FFT_SIZE) continue

                frameCount++
                val selfGuard = if (feedbackProtectionEnabled && unifiedAudio.isRunning()) {
                    selfOutputSuppressor.suppress(frame, unifiedAudio.suppressionFrequencies())
                } else null
                val analysisFrame = selfGuard?.samples ?: frame
                if (selfGuard != null) {
                    lastSelfOutputRemovedRatio = selfGuard.removedRmsRatio
                    lastSelfOutputComponents = selfGuard.removedComponents
                } else {
                    lastSelfOutputRemovedRatio = 0.0
                    lastSelfOutputComponents = 0
                }

                var primaryText: String
                var idText: String
                var strengthText: String

                val parallelTest = parallelEncoderBenchmarkEnabled && encoderBenchmark.isActive()
                if (parallelTest) {
                    // Fair hard test: all three encoders see the exact same PCM frame.
                    val mags = Fft.magnitudes(analysisFrame)
                    val minBin = (40.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtLeast(1)
                    val maxBin = (18_000.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtMost(mags.lastIndex)
                    var bestBin = minBin
                    var bestMag = 0.0
                    for (bin in minBin..maxBin) {
                        if (mags[bin] > bestMag) { bestMag = mags[bin]; bestBin = bin }
                    }
                    val fftHz = bestBin.toDouble() * SAMPLE_RATE / FFT_SIZE
                    val fftId = AudioQuantizer.idForHz(fftHz)
                    val direct = DirectAudioQuantizer.quantize(analysisFrame)
                    val adaptive = adaptiveQuantizer.quantize(analysisFrame, learn = memory.cognitiveMode() == CognitiveMode.LEARN)

                    fun feed(mode: AudioEncoderMode) {
                        when (mode) {
                            AudioEncoderMode.FFT_FREQUENCY -> memory.observe(fftId, fftHz)
                            AudioEncoderMode.DIRECT_PATTERN -> if (!direct.silent) memory.observeDirectAudio(direct.id)
                            AudioEncoderMode.ADAPTIVE_PATTERN -> if (!adaptive.silent && adaptive.recognized) memory.observeAdaptiveAudio(adaptive.id)
                        }
                    }
                    // Feed the selected stream last so the momentary LIVE working set remains
                    // centered on what the user chose while all long-term streams learn.
                    AudioEncoderMode.values().filter { it != selectedEncoder }.forEach(::feed)
                    feed(selectedEncoder)
                    encoderBenchmark.recordTriple(
                        fftId,
                        if (direct.silent) null else direct.id,
                        if (adaptive.silent || !adaptive.recognized) null else adaptive.id
                    )

                    when (selectedEncoder) {
                        AudioEncoderMode.FFT_FREQUENCY -> {
                            primaryText = "Dominante Frequenz: %.1f Hz · Paralleltest".format(fftHz)
                            idText = "Frequenz-ID: $fftId"
                            strengthText = "FFT-Stärke: %.5f".format(bestMag)
                        }
                        AudioEncoderMode.DIRECT_PATTERN -> {
                            primaryText = if (direct.silent) "Direktmuster: Stille — nicht gelernt · Paralleltest" else "Direktmuster: ${direct.debugText} · Paralleltest"
                            idText = if (direct.silent) "Pattern-ID: —" else "Pattern-ID: ${direct.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f".format(direct.activity)
                        }
                        AudioEncoderMode.ADAPTIVE_PATTERN -> {
                            primaryText = if (adaptive.silent) "Adaptive-ID: Stille — nicht gelernt · Paralleltest" else "Adaptive-ID: ${adaptive.debugText} · Paralleltest"
                            idText = if (adaptive.silent) "Adaptive Pattern-ID: —" else "Adaptive Pattern-ID: ${adaptive.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f · Codebook ${adaptive.codebookSize}".format(adaptive.activity)
                        }
                    }
                } else {
                    when (selectedEncoder) {
                        AudioEncoderMode.FFT_FREQUENCY -> {
                            val mags = Fft.magnitudes(analysisFrame)
                            val minBin = (40.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtLeast(1)
                            val maxBin = (18_000.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtMost(mags.lastIndex)
                            var bestBin = minBin
                            var bestMag = 0.0
                            for (bin in minBin..maxBin) {
                                if (mags[bin] > bestMag) { bestMag = mags[bin]; bestBin = bin }
                            }
                            val hz = bestBin.toDouble() * SAMPLE_RATE / FFT_SIZE
                            val id = AudioQuantizer.idForHz(hz)
                            memory.observe(id, hz)
                            primaryText = "Dominante Frequenz: %.1f Hz".format(hz)
                            idText = "Frequenz-ID: $id"
                            strengthText = "FFT-Stärke: %.5f".format(bestMag)
                        }
                        AudioEncoderMode.DIRECT_PATTERN -> {
                            val direct = DirectAudioQuantizer.quantize(analysisFrame)
                            if (!direct.silent) memory.observeDirectAudio(direct.id)
                            primaryText = if (direct.silent) "Direktmuster: Stille — nicht gelernt" else "Direktmuster: ${direct.debugText}"
                            idText = if (direct.silent) "Pattern-ID: —" else "Pattern-ID: ${direct.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f".format(direct.activity)
                        }
                        AudioEncoderMode.ADAPTIVE_PATTERN -> {
                            val adaptive = adaptiveQuantizer.quantize(analysisFrame, learn = memory.cognitiveMode() == CognitiveMode.LEARN)
                            if (!adaptive.silent && adaptive.recognized) memory.observeAdaptiveAudio(adaptive.id)
                            primaryText = if (adaptive.silent) "Adaptive-ID: Stille — nicht gelernt" else "Adaptive-ID: ${adaptive.debugText}"
                            idText = if (adaptive.silent) "Adaptive Pattern-ID: —" else "Adaptive Pattern-ID: ${adaptive.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f · Codebook ${adaptive.codebookSize}".format(adaptive.activity)
                        }
                    }
                }

                if (selfGuard != null && selfGuard.removedComponents > 0) {
                    strengthText += " · SelfGuard ${selfGuard.removedComponents} Komp. / ${"%.1f".format(selfGuard.removedRmsRatio * 100.0)}% RMS"
                }

                if (unifiedAudio.isRunning() && !cameraRunning && frameCount % 2L == 0L) {
                    // Audio-only operation: no video packet exists to provide the synchronized state.
                    pushContinuousOrchestraState()
                }

                var didConsolidate = false
                val now = System.currentTimeMillis()
                val interval = selectedIntervalMs()
                if (memory.cognitiveMode() == CognitiveMode.LEARN && now - lastConsolidationMs >= interval) {
                    val result = memory.consolidate()
                    lastConsolidationMs = now
                    lastConsolidationSummary = consolidationSummary(result)
                    didConsolidate = true
                }

                runOnUiThread {
                    if (running) {
                        if (settingsOpen) {
                            frequency.text = primaryText
                            frequencyId.text = idText
                            strength.text = strengthText
                            frames.text = "Frames verarbeitet: $frameCount"
                            if (frameCount % 5L == 0L) {
                                updateMemoryStats()
                                updateConsolidationStatus()
                                if (::adaptiveQuantizerStatus.isInitialized) adaptiveQuantizerStatus.text = adaptiveQuantizerText()
                            }
                            if (parallelTest && frameCount % 24L == 0L) refreshEncoderBenchmarkStatus()
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
        if (memory.cognitiveMode() == CognitiveMode.DREAM) enterPlayMode()
        stopMemoryPlayback(clear = true)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val visualAnalyzerInstance = VisualGridAnalyzer(
                        adaptiveVisualQuantizer,
                        onFrame = { result ->
                            if (!cameraRunning) return@VisualGridAnalyzer
                            // Frame-local reset prevents one spatial macro candidate from crossing
                            // the image boundary; learned identities/macros themselves remain intact.
                            memory.beginAdaptiveVisualFrame()
                            // Step 38: learning consumes contiguous coupled regions, not every
                            // redundant adaptive camera patch. Raw samples remain diagnostics only.
                            result.coupledObservations.forEach { region ->
                                memory.observeAdaptiveVisual(region.tokenId, region.packedRgb)
                            }
                            visualFrameCount++
                            visualObservationCount += result.coupledObservations.size
                            livePipeline.reportAnalysisCost(result.analysisCostMs.toDouble() + result.videoSampleCostMs.toDouble())

                            runOnUiThread {
                                if (settingsOpen) {
                                    cameraFrames.text =
                                        "Visuelle Frames: $visualFrameCount · Core $visualObservationCount · ${memory.cognitiveMode().label} · Samples ${result.sampledRegions} · Leaves ${result.spatialLeafRegions} → gekoppelt ${result.spatialCoupledRegions} (-${"%.0f".format(result.spatialCouplingRatio * 100.0)}%) · Tiefe ${result.maxResolutionDepth} · Bekannt ${"%.0f".format(result.meanFamiliarity * 100.0)}% · +${result.createdThisFrame} IDs · Ød ${"%.3f".format(result.meanMatchDistance)} · Analyse ${result.analysisCostMs}ms · Video ${result.videoSampleCostMs}ms/${result.videoIntervalMs}ms"
                                    updateLiveLatencyStatus()
                                    adaptiveVisualQuantizerStatus.text = adaptiveVisualQuantizerText()
                                    if (visualFrameCount % 2L == 0L) updateMemoryStats()
                                }
                                if (visualFrameCount % 2L == 0L) refreshGraph()
                            }
                        },
                        onVideoFrame = { video ->
                            if (!cameraRunning) return@VisualGridAnalyzer
                            livePipeline.offerFrame(video)
                            scheduleLowLatencyRender()
                        },
                        learningEnabled = { memory.cognitiveMode() == CognitiveMode.LEARN }
                    )
                visualAnalyzer = visualAnalyzerInstance
                visualAnalyzerInstance.setPerformanceProfile(performanceGovernor.snapshot().profile)
                analysis.setAnalyzer(cameraExecutor, visualAnalyzerInstance)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                cameraRunning = true
                cameraStatus.text = "Kamera: AKTIV · ${memory.cognitiveMode().label} · coarse→fine"
                cameraButton.isEnabled = false
                cameraStopButton.isEnabled = true
                refreshSensorUiState()
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
        visualAnalyzer = null
        livePipeline.clearFrames()
        adaptiveVisualQuantizer.saveNow()
        cameraStatus.text = "Kamera: AUS"
        if (::adaptiveVisualQuantizerStatus.isInitialized) adaptiveVisualQuantizerStatus.text = adaptiveVisualQuantizerText()
        cameraButton.isEnabled = true
        cameraStopButton.isEnabled = false
        refreshSensorUiState()
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

    private fun refreshEncoderBenchmarkStatus() {
        if (!::encoderBenchmarkStatus.isInitialized) return
        encoderBenchmark.updateMemory(memory.stats())
        val b = encoderBenchmark.summary()
        if (b.pairedFrames <= 0L) {
            encoderBenchmarkStatus.text = if (b.active) "Konvergenztest: bereit · noch keine PCM-Frames" else "Konvergenztest: AUS"
            return
        }
        fun line(name: String, t: EncoderTrackSummary): String =
            "$name: akzeptiert ${t.acceptedFrames}/${t.inputFrames} · IDs ${t.uniqueIds} · neu/1000 ${"%.1f".format(t.newIdsPer1000Accepted)} · neu letzte256 ${"%.1f".format(t.recentNoveltyRate * 100.0)}% · Übergang-neu ${"%.1f".format(t.recentTransitionNoveltyRate * 100.0)}% · H ${"%.2f".format(t.entropyBits)} bit · +Makro ${t.macroDelta}"
        encoderBenchmarkStatus.text =
            "Konvergenztest: ${if (b.active) "AKTIV" else "GESTOPPT"} · gemeinsame PCM-Frames ${b.pairedFrames}\n" +
                line("FFT", b.fft) + "\n" +
                line("DIRECT", b.direct) + "\n" +
                line("ADAPTIVE", b.adaptive) + "\n" +
                b.interpretation
    }

    private fun captureHardTestSample(saveMemory: Boolean) {
        if (!::hardTest.isInitialized || !::adaptiveQuantizer.isInitialized || !::adaptiveVisualQuantizer.isInitialized) return
        if (saveMemory) memory.saveNow()
        hardTest.capture(memory.stats(), adaptiveQuantizer.stats(), adaptiveVisualQuantizer.stats())
        refreshHardTestStatus()
    }

    private fun refreshHardTestStatus() {
        if (!::hardTestStatus.isInitialized || !::hardTest.isInitialized) return
        val h = hardTest.summary()
        if (h.samples == 0) {
            hardTestStatus.text = "Hard Test: ${if (h.running) "LÄUFT" else "AUS"} · noch keine Samples"
            return
        }
        val hours = h.elapsedMs / 3_600_000.0
        val heapMb = h.latestHeapBytes / (1024.0 * 1024.0)
        val heapDeltaMb = h.heapDeltaBytes / (1024.0 * 1024.0)
        val storageMb = h.latestStorageBytes / (1024.0 * 1024.0)
        val storageDeltaMb = h.storageDeltaBytes / (1024.0 * 1024.0)
        val early = h.earlyMacroRate?.let { "%.2f".format(it) } ?: "—"
        val recent = h.recentMacroRate?.let { "%.2f".format(it) } ?: "—"
        hardTestStatus.text =
            "Hard Test: ${if (h.running) "LÄUFT" else "GESTOPPT"} · ${h.samples} Samples · ${"%.2f".format(hours)} h · ΔObs ${h.observationsDelta}\n" +
            "Makros Δ ${h.macroDelta} · Relationen Δ ${h.relationDelta} · Makros/10k Obs ${"%.2f".format(h.macrosPer10kObservations)} · Trend $early→$recent (${h.macroGrowthTrend})\n" +
            "Neuheit: Audio ${"%.1f".format(h.latestAudioNovelty * 100.0)}% · Vision ${"%.1f".format(h.latestVisualNovelty * 100.0)}% · Kompression ${"%.1f".format(h.latestCompressionGain * 100.0)}%\n" +
            "Prediction Intervall: ${h.intervalPredictionHits}/${h.intervalPredictionChecks} (${"%.1f".format(h.intervalPredictionHitRate * 100.0)}%) · vergessen Δ ${h.prunedDelta}\n" +
            "Store ${"%.2f".format(storageMb)} MB (Δ ${"%+.2f".format(storageDeltaMb)} MB) · Heap ${"%.1f".format(heapMb)} MB (Δ ${"%+.1f".format(heapDeltaMb)} MB)\n" +
            "disk-backed ${h.latestDiskBackedMacros} Makros · Token-Reads ${h.latestTokenReads}"
    }

    private fun adaptiveQuantizerText(): String {
        val s = adaptiveQuantizer.stats()
        return "Adaptive-Codebook: ${s.prototypes} Prototypen · offen · Frames ${s.acceptedFrames} · neu ${s.createdIds} · reuse ${s.reusedIds} · Neuheit letzte256 ${"%.1f".format(s.recentNoveltyRate * 100.0)}% · ØDist ${"%.3f".format(s.meanMatchDistance)}"
    }

    private fun adaptiveVisualQuantizerText(): String {
        val s = adaptiveVisualQuantizer.stats()
        return "Visual-Codebook: ${s.prototypes} Prototypen · offen · Patches ${s.acceptedPatches} · neu ${s.createdIds} · reuse ${s.reusedIds} · Neuheit letzte256 ${"%.1f".format(s.recentNoveltyRate * 100.0)}% · ØDist ${"%.3f".format(s.meanMatchDistance)}"
    }

    private fun updateMemoryStats() {
        if (!settingsOpen) return
        val text = memoryText()
        if (::memoryStats.isInitialized && livePage.visibility == View.VISIBLE) memoryStats.text = text
        if (::labMemoryStats.isInitialized && labPage.visibility == View.VISIBLE) labMemoryStats.text = text
    }

    private fun memoryText(): String {
        val s = memory.stats()
        adaptiveQuantizer.updateStructureContext(s.adaptiveAudioMacros, s.macroRelations)
        adaptiveVisualQuantizer.updateStructureContext(s.adaptiveVisualMacros, s.crossModalMacros)
        val levelText = if (s.levelCounts.isEmpty()) "noch keine" else s.levelCounts.entries.joinToString(" · ") { "${AbstractionTier.labelForMacroDepth(it.key)}:${it.value}" }
        return "Cognitive Mode: ${memory.cognitiveMode().label} · ${if (memory.cognitiveMode() == CognitiveMode.LEARN) "PERSISTENT WRITES AN" else "READ-ONLY"}\n" +
            "Quanten: ${s.persistentCodes} | Kandidaten: ${s.temporaryCodes} | Beobachtungen: ${s.observations}\n" +
            "Audio-Alphabete: FFT ${s.fftAudioCodes} Q/${s.fftAudioMacros} M · Direct ${s.directAudioCodes} Q/${s.directAudioMacros} M · Adaptive ${s.adaptiveAudioCodes} Q/${s.adaptiveAudioMacros} M\n" +
            "Vision: adaptive ${s.adaptiveVisualCodes} Q/${s.adaptiveVisualMacros} M · visuell gesamt ${s.visualPersistentCodes} Q\n" +
            "Crossmodal: ${s.crossModalMacros} Makros · ${s.crossModalCandidates} Kandidaten · Aktivierungen ${s.crossModalActivations} · stärkster Kandidat ${s.strongestCrossModalSupport}/4\n" +
            "Makros: ${s.macroCodes} | $levelText | Relationen: ${s.macroRelations}\n" +
            "Knowledge Layers: BASE ${s.baseKnowledgeMacros} · PERSONAL ${s.personalKnowledgeMacros} · DERIVED ${s.derivedKnowledgeMacros} | Base ${if (s.basePackId.isBlank()) "—" else s.basePackId + " v" + s.basePackVersion} · ${if (s.baseKnowledgeEnabled) "AN" else "AUS/BLANK"} | kompatibel ${if (s.baseCompatibilityOk) "ja" else "NEIN"}\n" +
            "Working Set: ${s.activeMacros} | erinnert: ${s.retrievedMacros} | Austausch: ${s.overexchangePairs} | Ø|R| ${"%.3f".format(s.meanAbsOverexchange)}\n" +
            "Recall Hardening: ${s.recallRetrieved}/${s.recallCandidates} materialisiert · verworfen ${s.recallRejected} · InfoGain Ø ${"%.3f".format(s.recallMeanInformationGain)} · Recall-Faktor ${"%.2f".format(s.recallFactor)} · schwach verfallen ${s.weakCandidatesExpired}\n" +
            "Predictive Recall: ${s.predictionRawCandidates}→${s.predictionCandidates} Kandidaten | Top ${"%.3f".format(s.predictionTopConfidence)} | Coverage ${s.predictionHits}/${s.predictionChecks} (${"%.1f".format(s.predictionHitRate * 100.0)}%) | Top-1 ${s.predictionTop1Hits}/${s.predictionTop1Checks} (${"%.1f".format(s.predictionTop1HitRate * 100.0)}%) vs Baseline ${"%.1f".format(s.predictionBaselineHitRate * 100.0)}% | Lift ${if (s.predictionLift >= 0.0) "+" else ""}${"%.1f".format(s.predictionLift * 100.0)} pp\n" +
            "Hybrid: ${s.hybridSteps} interne Schritte | letzter Seed ${if (s.hybridSeedId == 0) "—" else s.hybridSeedId}\n" +
            "Dream: ${s.dreamSteps} Schritte | P ${s.dreamPredictionHops} · R ${s.dreamRelationHops} · S ${s.dreamSubstitutionHops} · X ${s.dreamCrossModalHops} · Restart ${s.dreamRestarts} | letzter Modus ${s.dreamLastMode}\n" +
            "Höchster aktiver Tier: ${s.highestActiveTier} · höchste Reife ${"%.2f".format(s.highestActiveMaturityScore)}\n" +
            "Templates: ${s.templateCodes} | aktiv: ${s.activeTemplates} | Varianten: ${s.templateVariantLinks} | max/Slot: ${s.widestTemplateVariants}\n" +
            "Substitutionsklassen: ${s.substitutionClasses} | aktiv: ${s.activeSubstitutionClasses} | Mitglieder: ${s.substitutionMembers} | stärkste Rollenstütze: ${s.strongestSubstitutionSupport}\n" +
            "Relevanz aktiv: Ø ${"%.3f".format(s.meanActiveRelevance)} | max ${"%.3f".format(s.highestActiveRelevance)} | Kontextentropie Ø ${"%.3f".format(s.meanActiveContextEntropy)} | Generalität max ${"%.3f".format(s.highestActiveGenerality)}\n" +
            "Adaptive Hierarchie: Q ${s.hierarchyBaseInputTokens} Token | Makro-Emissionen ${s.hierarchyBaseMacroEmissions} | gespart ${s.hierarchyBaseSavedTokens} (${"%.1f".format(s.hierarchyBaseCompressionGain * 100.0)}%) | ØSpan ${"%.2f".format(s.hierarchyMeanAcceptedSpan)} | Auto-Makros ${s.hierarchyAutoPromotions}\n" +
            "Hierarchical Routing: Treffer ${s.routingHits}/${s.routingQueries} (${"%.1f".format(s.routingHitRate * 100.0)}%) | ØSpan ${"%.2f".format(s.routingMeanMatchedSpan)} | Signaturchecks ${s.routingSignatureChecks} | vermieden ${s.routingChecksAvoided} | Index ${s.routingIndexStreams} Streams/${s.routingIndexAnchors} Anker/${s.routingIndexSignatures} Makros\n" +
            "Memory Competition: Läufe ${s.competitionRuns} | entfernt gesamt ${s.competitionMacrosPruned} M/${s.competitionRelationsPruned} R/${s.competitionCandidatesPruned} K | letzter Lauf ${s.competitionLastPruned} entfernt · ${s.competitionLastProtected} geschützt · Druckgruppen ${s.competitionLastPressuredGroups}\n" +
            "Long-Term Store: ${s.storageBackend} · ${s.storageBytes} Bytes · disk-backed Makros ${s.storageDiskBackedMacros}/${s.macroCodes} · Token-Lesezugriffe ${s.storageTokenReads}\n" +
            "Sequenz-/Kandidatenraum: offen · aktuell ${s.temporaryCodes} temporäre Kandidaten"
    }

    private fun consolidationSummary(result: ConsolidationResult): String {
        val levelPart = if (result.highestNewLevel > 0) " · höchste neue Stufe ${AbstractionTier.labelForMacroDepth(result.highestNewLevel)}" else ""
        val templatePart = if (result.templatesDiscovered > 0) " · +${result.templatesDiscovered} Templates" else ""
        val classPart = if (result.substitutionClassesDiscovered > 0) " · +${result.substitutionClassesDiscovered} Substitutionsklassen" else ""
        val competitionPart = if (result.competitionPruned > 0) " · -${result.competitionPruned} schwache Blatt-Makros" else ""
        return "Letzte Änderung: +${result.microPromoted} Mikro · +${result.macroPromoted} Abstraktionsknoten · davon ${result.sequencePromoted} Sequenzen$levelPart$templatePart$classPart$competitionPart"
    }

    private fun setAudioEncoderMode(mode: AudioEncoderMode) {
        if (audioEncoderMode == mode) return
        if (running) stopAudio()
        audioEncoderMode = mode
        audioEncoderModeStore.save(mode)
        if (::audioEncoderStatus.isInitialized) audioEncoderStatus.text = "Audio-Encoder: ${mode.label}"
        if (::labAudioEncoderStatus.isInitialized) labAudioEncoderStatus.text = "Aktiv: ${mode.label}"
        if (::frequency.isInitialized) {
            frequency.text = when (mode) {
                AudioEncoderMode.FFT_FREQUENCY -> "Dominante Frequenz: — Hz"
                AudioEncoderMode.DIRECT_PATTERN -> "Direktmuster: —"
                AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive-ID: —"
            }
        }
        if (::frequencyId.isInitialized) {
            frequencyId.text = when (mode) {
                AudioEncoderMode.FFT_FREQUENCY -> "Frequenz-ID: —"
                AudioEncoderMode.DIRECT_PATTERN -> "Pattern-ID: —"
                AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive Pattern-ID: —"
            }
        }
        if (::strength.isInitialized) strength.text = "Signalstärke: —"
    }

    private fun stopAudio() {
        if (!running) return
        running = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { worker?.join(500) } catch (_: InterruptedException) { }
        releaseEchoCanceler()
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        worker = null
        adaptiveQuantizer.saveNow()
        status.text = "Audio: AUS"
        startButton.isEnabled = true
        stopButton.isEnabled = false
        refreshSensorUiState()
        val result = memory.consolidate()
        lastConsolidationSummary = consolidationSummary(result)
        updateConsolidationStatus()
        updateMemoryStats()
        if (::encoderBenchmark.isInitialized && encoderBenchmark.isActive()) refreshEncoderBenchmarkStatus()
        refreshGraph()
    }

    private fun polishProductTree(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            when (child) {
                is Button -> {
                    child.isAllCaps = false
                    child.textSize = 13f
                    child.setTextColor(Color.rgb(235, 237, 244))
                    child.background = roundedBackground(
                        Color.argb(46, 255, 255, 255), 16f, Color.argb(28, 255, 255, 255), 1
                    )
                    child.minHeight = dp(48)
                    attachPressMotion(child)
                }
                is CheckBox -> {
                    child.textSize = 13f
                    child.setTextColor(Color.rgb(218, 222, 232))
                    child.setPadding(child.paddingLeft, dp(8), child.paddingRight, dp(8))
                    attachPressMotion(child)
                }
                is EditText -> {
                    child.setTextColor(Color.WHITE)
                    child.setHintTextColor(Color.rgb(130, 136, 150))
                    child.background = roundedBackground(
                        Color.argb(34, 255, 255, 255), 14f, Color.argb(34, 255, 255, 255), 1
                    )
                    child.setPadding(dp(12), dp(10), dp(12), dp(10))
                }
            }
            if (child is ViewGroup) polishProductTree(child)
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private fun attachPressMotion(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.82f).setDuration(70L).start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.15f)).start()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
                }
            }
            false
        }
    }

    private fun productButton(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 12f
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(232, 235, 242))
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = roundedBackground(Color.argb(38, 255, 255, 255), 16f, Color.argb(28, 255, 255, 255), 1)
        isClickable = true
        isFocusable = true
        attachPressMotion(this)
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

    override fun onResume() {
        super.onResume()
        if (::surfaceVideo.isInitialized) surfaceVideo.onResume()
    }

    override fun onPause() {
        if (::surfaceVideo.isInitialized) surfaceVideo.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hardTestLoop)
        if (::adaptiveQuantizer.isInitialized) adaptiveQuantizer.saveNow()
        if (::adaptiveVisualQuantizer.isInitialized) adaptiveVisualQuantizer.saveNow()
        stopMemoryPlayback(clear = false)
        stopHybridPlayback(clear = false)
        stopCameraLearning()
        try { cameraExecutor.shutdownNow() } catch (_: Exception) { }
        stopAudio()
        unifiedAudio.stop()
        if (::captureRecorder.isInitialized && captureRecorder.isRecording()) {
            unifiedAudio.setPcmTap(null)
            captureRecorder.stop({}, {})
        }
        CaptureForegroundService.stop(this)
        stopContinuousOrchestra()
        releaseEchoCanceler()
        super.onDestroy()
    }
}
