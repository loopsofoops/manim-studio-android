package com.atelier.manim.ui

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelier.manim.AtelierApp
import com.atelier.manim.data.FileRevisionEntity
import com.atelier.manim.data.ProjectFileEntity
import com.atelier.manim.data.RenderHistoryEntity
import com.atelier.manim.data.StarterTemplates
import com.atelier.manim.data.detectManimScenes
import com.atelier.manim.runtime.ComputeProfile
import com.atelier.manim.runtime.ControllerMacro
import com.atelier.manim.runtime.ControllerStep
import com.atelier.manim.runtime.CrashReportEntry
import com.atelier.manim.runtime.StudioMode
import com.atelier.manim.runtime.StudioUiEvent
import com.atelier.manim.runtime.BatchJobItem
import com.atelier.manim.runtime.BrandKitConfig
import com.atelier.manim.runtime.HardwareClipSpec
import com.atelier.manim.runtime.HardwareTelemetry
import com.atelier.manim.runtime.VoiceDownloadState
import com.atelier.manim.runtime.VoiceModelSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import com.atelier.manim.runtime.ExecutionState
import com.atelier.manim.runtime.ManimPreset
import com.atelier.manim.runtime.ManimRenderMode
import com.atelier.manim.runtime.OutputArtifact
import com.atelier.manim.runtime.OutputFormat
import com.atelier.manim.runtime.OutputKind
import com.atelier.manim.runtime.ReplEntry
import com.atelier.manim.runtime.ReplVar
import com.atelier.manim.runtime.RuntimeState
import com.atelier.manim.runtime.VideoTelemetry
import com.atelier.manim.runtime.decodeExitSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
class AtelierViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AtelierApp
    private val settings = app.settingsStore

    val runtimeState: StateFlow<RuntimeState> = app.runtimeManager.state
    val executionState: StateFlow<ExecutionState> = app.coordinator.state
    val consoleLog: StateFlow<String> = app.coordinator.consoleLog
    val crashReports: StateFlow<List<CrashReportEntry>> = app.crashReporter.reports
    val pendingCrashAlert: StateFlow<CrashReportEntry?> = app.crashReporter.pendingCrashAlert
    val voiceCatalog: List<VoiceModelSpec> get() = app.voiceEngine.catalog
    val installedVoiceIds: StateFlow<Set<String>> = app.voiceEngine.installedIds
    val voiceDownloadState: StateFlow<VoiceDownloadState> = app.voiceEngine.downloadState
    val telemetry: StateFlow<HardwareTelemetry> = app.automation.telemetry
    val brandKit: StateFlow<BrandKitConfig> = app.automation.brand
    val batchQueue: StateFlow<List<BatchJobItem>> = app.automation.batchQueue
    val batchRunning: StateFlow<Boolean> = app.automation.batchRunning
    val thermalPaused: StateFlow<Boolean> = app.automation.thermalPaused

    val files: StateFlow<List<ProjectFileEntity>> = app.workspaceRepo.observeFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val renderHistory: StateFlow<List<RenderHistoryEntity>> = app.database.dao().observeRenderHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeFile = MutableStateFlow<ProjectFileEntity?>(null)
    val activeFile: StateFlow<ProjectFileEntity?> = _activeFile.asStateFlow()

    val revisions: StateFlow<List<FileRevisionEntity>> = _activeFile
        .flatMapLatest { f -> if (f == null) flowOf(emptyList()) else app.workspaceRepo.observeRevisions(f.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editorValue = MutableStateFlow(TextFieldValue(""))
    val editorValue: StateFlow<TextFieldValue> = _editorValue.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private val _detectedScenes = MutableStateFlow<List<String>>(emptyList())
    val detectedScenes: StateFlow<List<String>> = _detectedScenes.asStateFlow()

    val selectedScene: StateFlow<String> = settings.selectedScene
    val preset: StateFlow<ManimPreset> = settings.preset
    val outputFormat: StateFlow<OutputFormat> = settings.outputFormat
    val customWidth: StateFlow<Int> = settings.customWidth
    val customHeight: StateFlow<Int> = settings.customHeight
    val customFps: StateFlow<Int> = settings.customFps
    val renderMode: StateFlow<ManimRenderMode> = settings.renderMode
    val animRangeStart: StateFlow<Int> = settings.animRangeStart
    val animRangeEnd: StateFlow<Int> = settings.animRangeEnd
    val disableCache: StateFlow<Boolean> = settings.disableCache
    val autoSaveStorage: StateFlow<Boolean> = settings.autoSaveStorage
    val autoExportCrashReports: StateFlow<Boolean> = settings.autoExportCrashReports
    val isDarkTheme: StateFlow<Boolean> = settings.isDarkTheme
    val fontSizeSp: StateFlow<Int> = settings.fontSizeSp
    val computeProfile: StateFlow<ComputeProfile> = settings.computeProfile
    val studioMode: StateFlow<StudioMode> = settings.studioMode
    val macros: StateFlow<List<ControllerMacro>> = settings.macros
    val controllerSteps: StateFlow<List<ControllerStep>> = app.coordinator.controllerSteps
    val lastTransactionId: StateFlow<String> = app.coordinator.lastTransactionId

    private val _controllerValue = MutableStateFlow(
        TextFieldValue("import atelier\n\nsnap = atelier.inspect.full_snapshot()\nprint(snap[:1200])\n")
    )
    val controllerValue: StateFlow<TextFieldValue> = _controllerValue.asStateFlow()

    private val _audioScriptText = MutableStateFlow(
        "On the complex plane, multiplying by e^{i\\theta} rotates smoothly along the unit circle.\n" +
            "Watch the real cosine shadow and imaginary sine height sweep cleanly to \\pi radians.\n" +
            "At angle \\pi, we land on negative one, revealing Euler's identity: e^{i\\pi} + 1 = 0."
    )
    val audioScriptText: StateFlow<String> = _audioScriptText.asStateFlow()

    private val _voiceStudioSummary = MutableStateFlow("")
    val voiceStudioSummary: StateFlow<String> = _voiceStudioSummary.asStateFlow()

    private val _latestManifestPath = MutableStateFlow("")
    val latestManifestPath: StateFlow<String> = _latestManifestPath.asStateFlow()

    private val _musicFiles = MutableStateFlow<List<File>>(emptyList())
    val musicFiles: StateFlow<List<File>> = _musicFiles.asStateFlow()

    private val _sfxFiles = MutableStateFlow<List<File>>(emptyList())
    val sfxFiles: StateFlow<List<File>> = _sfxFiles.asStateFlow()

    private val _voiceBusy = MutableStateFlow(false)
    val voiceBusy: StateFlow<Boolean> = _voiceBusy.asStateFlow()

    private val _isRecordingRef = MutableStateFlow(false)
    val isRecordingRef: StateFlow<Boolean> = _isRecordingRef.asStateFlow()

    private val _cloneRefWavPath = MutableStateFlow(if (app.voiceEngine.cloneRefFile.exists()) app.voiceEngine.cloneRefFile.absolutePath else "")
    val cloneRefWavPath: StateFlow<String> = _cloneRefWavPath.asStateFlow()

    private val _timelineUi = MutableStateFlow(TimelineUiState())
    val timelineUi: StateFlow<TimelineUiState> = _timelineUi.asStateFlow()

    private val _availableTimelines = MutableStateFlow<List<String>>(listOf("episode_01"))
    val availableTimelines: StateFlow<List<String>> = _availableTimelines.asStateFlow()

    private val _voiceWaveformPeaks = MutableStateFlow<List<Float>>(emptyList())
    val voiceWaveformPeaks: StateFlow<List<Float>> = _voiceWaveformPeaks.asStateFlow()

    private val _pacingReport = MutableStateFlow<JSONObject?>(null)
    val pacingReport: StateFlow<JSONObject?> = _pacingReport.asStateFlow()

    private val _latestThumbnailHostPath = MutableStateFlow("")
    val latestThumbnailHostPath: StateFlow<String> = _latestThumbnailHostPath.asStateFlow()

    private val _formulaInitial = MutableStateFlow("")
    val formulaInitial: StateFlow<String> = _formulaInitial.asStateFlow()
    private val _formulaPreviewPath = MutableStateFlow("")
    val formulaPreviewPath: StateFlow<String> = _formulaPreviewPath.asStateFlow()
    private val _formulaPreviewError = MutableStateFlow("")
    val formulaPreviewError: StateFlow<String> = _formulaPreviewError.asStateFlow()
    private val _formulaBusy = MutableStateFlow(false)
    val formulaBusy: StateFlow<Boolean> = _formulaBusy.asStateFlow()

    private val _gitOutput = MutableStateFlow("")
    val gitOutput: StateFlow<String> = _gitOutput.asStateFlow()
    private val _gitBusy = MutableStateFlow(false)
    val gitBusy: StateFlow<Boolean> = _gitBusy.asStateFlow()

    private val _replEntries = MutableStateFlow<List<ReplEntry>>(emptyList())
    val replEntries: StateFlow<List<ReplEntry>> = _replEntries.asStateFlow()
    private val _replVars = MutableStateFlow<List<ReplVar>>(emptyList())
    val replVars: StateFlow<List<ReplVar>> = _replVars.asStateFlow()
    private val _replBusy = MutableStateFlow(false)
    val replBusy: StateFlow<Boolean> = _replBusy.asStateFlow()
    private val _toastMessage = MutableStateFlow("")
    val toastMessage: StateFlow<String> = _toastMessage.asStateFlow()

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var lastUndoPushMs = 0L
    private var autosaveJob: Job? = null
    private var replSeq = 1L

    init {
        app.runtimeManager.refreshState()
        refreshAudioAssets()
        refreshTimelines()
        viewModelScope.launch {
            app.workspaceRepo.syncWorkspaceFromDisk()
            val all = app.workspaceRepo.ensureSeeded()
            if (all.isNotEmpty()) openFile(all.first())
        }
        viewModelScope.launch {
            settings.uiEvents.collect { ev ->
                when (ev) {
                    is StudioUiEvent.OpenFile -> {
                        val f = app.workspaceRepo.findOrSyncFile(ev.relativePath)
                        if (f != null) openFile(f)
                    }
                    is StudioUiEvent.ReloadIfActive -> {
                        val cur = _activeFile.value
                        if (cur != null && cur.relativePath == ev.relativePath) {
                            val fresh = app.workspaceRepo.readFile(cur)
                            _editorValue.value = TextFieldValue(fresh, TextRange(min(_editorValue.value.selection.start, fresh.length)))
                            _isDirty.value = false
                            refreshDetectedScenes(fresh, cur.language)
                            syncInspectorLiveState()
                        }
                    }
                    is StudioUiEvent.ShowToast -> showToast(ev.message)
                    is StudioUiEvent.SyncWorkspace -> {
                        app.workspaceRepo.syncWorkspaceFromDisk()
                        val cur = _activeFile.value
                        if (cur != null) {
                            val freshEnt = app.workspaceRepo.findOrSyncFile(cur.relativePath)
                            if (freshEnt != null) {
                                val fresh = app.workspaceRepo.readFile(freshEnt)
                                _activeFile.value = freshEnt
                                _editorValue.value = TextFieldValue(fresh, TextRange(min(_editorValue.value.selection.start, fresh.length)))
                                _isDirty.value = false
                                refreshDetectedScenes(fresh, freshEnt.language)
                                syncInspectorLiveState()
                            }
                        }
                    }
                }
            }
        }
    }

    fun openFile(file: ProjectFileEntity) {
        viewModelScope.launch {
            autosaveJob?.cancel()
            flushSaveIfDirty()
            val text = app.workspaceRepo.readFile(file)
            _activeFile.value = file
            _editorValue.value = TextFieldValue(text, TextRange(0))
            _isDirty.value = false
            undoStack.clear()
            redoStack.clear()
            updateUndoFlags()
            refreshDetectedScenes(text, file.language)
            syncInspectorLiveState()

            if (file.lastOutputPath.isNotBlank() && File(file.lastOutputPath).exists()) {
                val kind = runCatching { OutputKind.valueOf(file.lastOutputType) }.getOrDefault(OutputKind.NONE)
                if (kind != OutputKind.NONE) {
                    val outFile = File(file.lastOutputPath)
                    val telemetry = if (kind == OutputKind.VIDEO) withContext(Dispatchers.IO) { probeVideoLight(outFile) }
                    else VideoTelemetry(sizeBytes = outFile.length())
                    app.coordinator.setPreviewArtifact(
                        OutputArtifact(path = file.lastOutputPath, kind = kind, title = file.name, telemetry = telemetry)
                    )
                }
            }
        }
    }

    fun onCodeEdited(newVal: TextFieldValue) {
        val oldVal = _editorValue.value
        if (newVal.text != oldVal.text) {
            val now = System.currentTimeMillis()
            if (now - lastUndoPushMs > 650L || undoStack.isEmpty()) {
                if (undoStack.size >= 120) undoStack.removeFirst()
                undoStack.addLast(oldVal)
                lastUndoPushMs = now
            }
            redoStack.clear()
            updateUndoFlags()
            _isDirty.value = true
            _activeFile.value?.let { refreshDetectedScenes(newVal.text, it.language) }
            scheduleAutosave()
        }
        _editorValue.value = newVal
        syncInspectorLiveState()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_editorValue.value)
        _editorValue.value = undoStack.removeLast()
        _isDirty.value = true
        _activeFile.value?.let { refreshDetectedScenes(_editorValue.value.text, it.language) }
        updateUndoFlags()
        syncInspectorLiveState()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_editorValue.value)
        _editorValue.value = redoStack.removeLast()
        _isDirty.value = true
        _activeFile.value?.let { refreshDetectedScenes(_editorValue.value.text, it.language) }
        updateUndoFlags()
        syncInspectorLiveState()
    }

    fun manualSave(label: String = "Manual Snapshot") {
        val file = _activeFile.value ?: return
        autosaveJob?.cancel()
        viewModelScope.launch {
            app.workspaceRepo.saveFile(file, _editorValue.value.text, label = label, triggerType = "SAVE")
            _isDirty.value = false
            syncInspectorLiveState()
            showToast("Saved snapshot")
        }
    }

    fun createNewFile(name: String, language: String) {
        viewModelScope.launch {
            val template = StarterTemplates.blankFor(language)
            val created = app.workspaceRepo.createFile(name, language, template)
            openFile(created)
            showToast("Created ${created.relativePath}")
        }
    }

    fun createFromShowcaseTemplate(key: String) {
        viewModelScope.launch {
            val (name, lang, code) = when (key) {
                "narrated_euler" -> Triple("narrated_euler_studio.py", "PYTHON_MANIM", StarterTemplates.narratedEulerShowcase)
                "fourier_hd" -> Triple("fourier_epicycles_hd.py", "PYTHON_MANIM", StarterTemplates.fourierManim)
                else -> Triple("surface_wave_3d.py", "PYTHON_MANIM", StarterTemplates.surface3dManim)
            }
            val created = app.workspaceRepo.createFile(name, lang, code)
            openFile(created)
            showToast("Created ${created.relativePath}")
        }
    }

    fun installVoiceModel(modelId: String) = app.voiceEngine.startInstallModel(modelId)
    fun cancelVoiceDownload() = app.voiceEngine.cancelInstall()
    fun deleteVoiceModel(modelId: String) {
        app.voiceEngine.deleteModel(modelId)
        showToast("Removed voice model")
    }
    fun onAudioScriptChange(text: String) { _audioScriptText.value = text }

    fun normalizeMathPreviewLine(): String {
        val first = _audioScriptText.value.lines().firstOrNull { it.isNotBlank() }.orEmpty()
        return if (first.isBlank()) "" else app.voiceEngine.normalizer.normalize(first)
    }

    fun refreshAudioAssets() {
        val audioRoot = File(app.runtimeManager.outputsDir, "audio")
        _musicFiles.value = File(audioRoot, "music").listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        _sfxFiles.value = File(audioRoot, "sfx").listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val man = File(audioRoot, "timing_manifest.json")
        if (man.exists()) _latestManifestPath.value = "/outputs/audio/timing_manifest.json"
    }

    fun playHostAudioFile(hostPath: String) {
        app.voiceEngine.playPreviewWav(hostPath)
    }

    fun synthesizeAudioStudioScript(modelId: String, sid: Int, speed: Float, lang: String = "en") {
        if (_voiceBusy.value) return
        val lines = _audioScriptText.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) { showToast("Enter at least one narration line"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _voiceBusy.value = true
            try {
                _voiceStudioSummary.value = "Synthesizing ${lines.size} cue(s)..."
                val res = app.voiceEngine.synthesizeScript(
                    lines = lines, modelId = modelId, sid = sid, speed = speed, normalizeMath = true,
                    lang = lang, refWavHostPath = _cloneRefWavPath.value
                )
                _latestManifestPath.value = res.optString("manifestPath", "")
                val total = res.optDouble("totalDurationSec", 0.0)
                _voiceStudioSummary.value = "Ready: ${res.optInt("cueCount")} cues · ${String.format(Locale.US, "%.1fs", total)} · SRT saved"
                val firstCueWav = res.optJSONArray("cues")?.optJSONObject(0)?.optString("wavPath", "").orEmpty()
                if (firstCueWav.isNotBlank()) app.voiceEngine.playPreviewWav(app.prootEngine.guestToHostPath(firstCueWav).absolutePath)
                showToast("Voice script & captions.srt ready (${String.format(Locale.US, "%.1fs", total)})")
            } catch (t: Throwable) {
                _voiceStudioSummary.value = "Error: ${t.message}"
                showToast(t.message ?: "Synthesis failed")
            } finally {
                _voiceBusy.value = false
            }
        }
    }

    fun previewAudioScriptLine1(modelId: String, sid: Int, speed: Float, lang: String = "en") {
        if (_voiceBusy.value) return
        val line = _audioScriptText.value.lines().firstOrNull { it.isNotBlank() }.orEmpty()
        if (line.isBlank()) { showToast("Script is empty"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _voiceBusy.value = true
            try {
                val res = app.voiceEngine.synthesize(
                    text = line, modelId = modelId, sid = sid, speed = speed, normalizeMath = true,
                    refWavHostPath = _cloneRefWavPath.value, lang = lang
                )
                app.voiceEngine.playPreviewWav(res.wavHostPath)
                showToast("Playing line 1 (${String.format(Locale.US, "%.1fs", res.durationSec)})")
            } catch (t: Throwable) {
                showToast(t.message ?: "Voice preview failed")
            } finally {
                _voiceBusy.value = false
            }
        }
    }

    fun toggleRecordCloneSample(hasPermission: Boolean) {
        if (_isRecordingRef.value) {
            app.voiceEngine.stopRecordingCloneReference()
            _isRecordingRef.value = false
            return
        }
        if (!hasPermission) { showToast("Microphone permission required for voice cloning"); return }
        _isRecordingRef.value = true
        showToast("Recording voice reference (speak 5s)...")
        app.voiceEngine.startRecordingCloneReference(
            onComplete = { f ->
                _isRecordingRef.value = false
                _cloneRefWavPath.value = f.absolutePath
                showToast("Saved 5s clone sample: ${f.name}")
            },
            onError = { err ->
                _isRecordingRef.value = false
                showToast(err)
            }
        )
    }

    fun denoiseCloneSample() {
        val host = _cloneRefWavPath.value
        if (host.isBlank() || !File(host).exists()) { showToast("Record a reference sample first"); return }
        val py = "import atelier\nres = atelier.voice.denoise('/outputs/audio/voice_clone_ref.wav', '/outputs/audio/voice_clone_ref_clean.wav')\nprint('__ATELIER_ARTIFACT__:AUDIO:' + res['path'], flush=True)\n"
        app.coordinator.runAudioOrTimelineJob("Denoise Mic", "Cleaning vocal recording...", py, autoSaveVideo = false) { code, cleanedHost ->
            if (code == 0 && cleanedHost.isNotBlank()) {
                _cloneRefWavPath.value = cleanedHost
                app.voiceEngine.playPreviewWav(cleanedHost)
            }
        }
    }

    fun runForcedAlignmentOnLatestVoice() {
        if (_voiceBusy.value) return
        val targetHost = _cloneRefWavPath.value.takeIf { it.isNotBlank() && File(it).exists() }
            ?: app.voiceEngine.cacheDir.listFiles()?.filter { it.name.endsWith(".wav") }?.maxByOrNull { it.lastModified() }?.absolutePath
            ?: ""
        if (targetHost.isBlank()) { showToast("Synthesize or record a WAV first"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _voiceBusy.value = true
            try {
                val res = app.voiceEngine.alignWavAudio(targetHost)
                _voiceStudioSummary.value = "Aligned: ${res.optString("summary")} — \"${res.optString("text").take(80)}\""
                showToast("ASR aligned ${res.optJSONArray("words")?.length() ?: 0} words")
            } catch (t: Throwable) {
                showToast(t.message ?: "Alignment failed")
            } finally {
                _voiceBusy.value = false
            }
        }
    }

    fun installStableAudioPack(variant: String, hfToken: String) {
        val free = app.runtimeManager.freeStorageBytes()
        if (free < 2_200_000_000L) {
            showToast("Need >= 2.2 GB free storage for Stable Audio 3.0 pack")
            return
        }
        val safeTok = hfToken.replace("'", "")
        val py = "import atelier\nres = atelier.neural_audio.install('$variant', hf_token='$safeTok')\nprint(res)\n"
        app.coordinator.runAudioOrTimelineJob("Install Stable Audio 3", "Installing LiteRT + $variant weights...", py, autoSaveVideo = false)
    }

    fun generateStableAudioTrack(prompt: String, seconds: Int, variant: String) {
        val safePrompt = prompt.replace("\n", " ").replace("\"", "'")
        val fn = if (variant.contains("sfx")) "generate_sfx" else "generate_music"
        val py = "import atelier\nres = atelier.neural_audio.$fn(\"$safePrompt\", seconds=$seconds)\nprint('__ATELIER_ARTIFACT__:AUDIO:' + res['path'], flush=True)\nprint(res)\n"
        app.coordinator.runAudioOrTimelineJob("Stable Audio 3", "Generating ${seconds}s stereo neural audio...", py, autoSaveVideo = false) { code, host ->
            refreshAudioAssets()
            if (code == 0 && host.isNotBlank()) app.voiceEngine.playPreviewWav(host)
        }
    }

    fun composeBackgroundMusic(style: String, key: String, scale: String, bpm: Int, durationSec: Int, arc: String) {
        val cuesPy = when (arc) {
            "calm_study" -> "[{\"time\": 0, \"energy\": 0.35}, {\"time\": ${durationSec}, \"energy\": 0.40}]"
            "dynamic_build" -> "[{\"time\": 0, \"energy\": 0.30}, {\"time\": ${durationSec * 0.6}, \"energy\": 0.75}, {\"time\": ${durationSec * 0.9}, \"energy\": 0.95, \"resolve\": True}]"
            else -> "[{\"time\": 0, \"energy\": 0.38}, {\"time\": ${durationSec * 0.55}, \"energy\": 0.80}, {\"time\": ${durationSec * 0.82}, \"energy\": 0.96, \"resolve\": True}, {\"time\": ${durationSec}, \"energy\": 0.35}]"
        }
        val py = "import atelier\nres = atelier.music.compose(duration_sec=$durationSec, style='$style', bpm=$bpm, key='$key', scale='$scale', cues=$cuesPy)\nprint('__ATELIER_ARTIFACT__:AUDIO:' + res['path'], flush=True)\nprint(res)\n"
        app.coordinator.runAudioOrTimelineJob("Compose · $style", "Synthesizing $key $scale (${durationSec}s)...", py, autoSaveVideo = false) { code, hostPath ->
            refreshAudioAssets()
            if (code == 0 && hostPath.isNotBlank()) app.voiceEngine.playPreviewWav(hostPath)
        }
    }

    fun generateMathSfx(preset: String) {
        val py = "import atelier\nres = atelier.sfx.generate('$preset')\nprint('__ATELIER_ARTIFACT__:AUDIO:' + res['path'], flush=True)\nprint(res)\n"
        app.coordinator.runAudioOrTimelineJob("SFX · $preset", "Synthesizing $preset...", py, autoSaveVideo = false) { code, hostPath ->
            refreshAudioAssets()
            if (code == 0 && hostPath.isNotBlank()) app.voiceEngine.playPreviewWav(hostPath)
        }
    }

    fun muxActiveVideoWithAudio(bgmGuestPath: String, exportShorts9x16: Boolean) {
        val art = executionState.value.activeArtifact
        if (art.kind != OutputKind.VIDEO || art.path.isBlank() || !File(art.path).exists()) {
            showToast("Render a Manim video first")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val srcCopy = File(File(app.runtimeManager.outputsDir, "exports").apply { mkdirs() }, "_src_active.mp4")
            val copied = runCatching { File(art.path).copyTo(srcCopy, overwrite = true); true }.getOrDefault(false)
            if (!copied) { showToast("Failed staging source video"); return@launch }
            runMuxAfterStage(bgmGuestPath, exportShorts9x16)
        }
    }

    private fun runMuxAfterStage(bgmGuestPath: String, exportShorts9x16: Boolean) {
        val manPath = _latestManifestPath.value
        val presetName = if (exportShorts9x16) "shorts_9x16_60" else "youtube_1080p60"
        val py = buildString {
            appendLine("import atelier, os")
            appendLine("tl = 'audio_studio_mix'")
            appendLine("atelier.timeline.create(tl, preset='$presetName')")
            appendLine("atelier.timeline.add_video(tl, '/outputs/exports/_src_active.mp4')")
            if (manPath.isNotBlank()) {
                appendLine("if os.path.exists('$manPath'): atelier.timeline.import_voice_manifest(tl, '$manPath')")
            }
            if (bgmGuestPath.isNotBlank()) {
                appendLine("if os.path.exists('$bgmGuestPath'): atelier.timeline.add_music(tl, '$bgmGuestPath', gain_db=-11.0)")
            }
            if (exportShorts9x16) {
                appendLine("res = atelier.export.render_timeline(tl, preset='shorts_9x16_60', publish_to_stage=False)")
            } else {
                appendLine("doc = atelier.timeline.load(tl)")
                appendLine("res = atelier.audio.mux_video('/outputs/exports/_src_active.mp4', voice_clips=doc['voice_clips'], music_clips=doc['music_clips'], keep_video_audio=(len(doc['voice_clips'])==0), publish_to_stage=False)")
            }
            appendLine("print('__ATELIER_ARTIFACT__:VIDEO:' + res['outputPath'], flush=True)")
            appendLine("print(res)")
        }
        val label = if (exportShorts9x16) "Master 9:16 Short" else "Master 16:9 Audio Mix"
        app.coordinator.runAudioOrTimelineJob(label, "Applying EQ, auto-ducking & -14 LUFS...", py, autoSaveVideo = settings.autoSaveStorage.value) { code, _ ->
            if (code == 0) settings.setStudioMode(StudioMode.CODE)
        }
    }

    fun testVoiceModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val res = app.voiceEngine.synthesize(
                    text = "Welcome to Atelier. Euler's identity e^{i\\pi} + 1 = 0 connects five fundamental constants.",
                    modelId = modelId, normalizeMath = true
                )
                app.voiceEngine.playPreviewWav(res.wavHostPath)
                showToast("Playing sample (${String.format("%.1fs", res.durationSec)})")
            }.onFailure { showToast(it.message ?: "Voice test failed") }
        }
    }

    fun deleteFile(file: ProjectFileEntity) {
        viewModelScope.launch {
            app.workspaceRepo.deleteFile(file)
            if (_activeFile.value?.id == file.id) {
                val remaining = app.workspaceRepo.ensureSeeded()
                if (remaining.isNotEmpty()) openFile(remaining.first())
            }
        }
    }

    fun syncWorkspaceFromDisk() {
        viewModelScope.launch {
            val added = app.workspaceRepo.syncWorkspaceFromDisk()
            showToast(if (added > 0) "Synced $added new file(s)" else "Workspace already in sync")
        }
    }

    fun importFilesFromUris(uris: List<Uri>) {
        viewModelScope.launch {
            val imported = app.workspaceRepo.importDocumentUris(uris)
            if (imported.isNotEmpty()) {
                val firstText = imported.firstOrNull { it.language != "ASSET" }
                if (firstText != null) openFile(firstText)
                showToast("Imported ${imported.size} file(s)")
            } else {
                showToast("No files could be imported")
            }
        }
    }

    fun exportWorkspaceZipToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = app.mediaExport.exportWorkspaceZip(app.workspaceRepo.workspaceDir, uri)
            showToast(if (res.success) "Exported workspace ZIP" else res.error)
        }
    }

    fun readRevisionContent(revision: FileRevisionEntity): String =
        app.blobStore.readBlob(revision.contentHash) ?: ""

    fun restoreRevision(revision: FileRevisionEntity) {
        val file = _activeFile.value ?: return
        autosaveJob?.cancel()
        viewModelScope.launch {
            val (restoredText, _) = app.workspaceRepo.restoreRevision(file, revision)
            _editorValue.value = TextFieldValue(restoredText, TextRange(0))
            _isDirty.value = false
            refreshDetectedScenes(restoredText, file.language)
            syncInspectorLiveState()
            previewRevisionOutput(revision)
            showToast("Restored revision #${revision.id}")
        }
    }

    fun previewRevisionOutput(revision: FileRevisionEntity) {
        if (revision.outputPath.isBlank() || !File(revision.outputPath).exists()) return
        val kind = runCatching { OutputKind.valueOf(revision.outputType) }.getOrDefault(OutputKind.NONE)
        if (kind == OutputKind.NONE) return
        viewModelScope.launch {
            val f = File(revision.outputPath)
            val tel = if (kind == OutputKind.VIDEO) withContext(Dispatchers.IO) { probeVideoLight(f) } else VideoTelemetry(sizeBytes = f.length())
            app.coordinator.setPreviewArtifact(
                OutputArtifact(path = revision.outputPath, kind = kind, title = revision.label, telemetry = tel, revisionId = revision.id, timestamp = System.currentTimeMillis())
            )
        }
    }

    fun runPrimaryAction() {
        val file = _activeFile.value ?: return
        autosaveJob?.cancel()
        _isDirty.value = false
        val code = _editorValue.value.text
        syncInspectorLiveState()
        when {
            file.language == "LATEX" || file.name.endsWith(".tex", ignoreCase = true) -> {
                app.coordinator.compileLatex(file, code, settings.autoSaveStorage.value)
            }
            _detectedScenes.value.isNotEmpty() -> {
                val scene = settings.selectedScene.value.ifBlank { _detectedScenes.value.first() }
                app.coordinator.renderManim(
                    file = file, code = code, sceneName = scene,
                    preset = settings.preset.value,
                    customWidth = settings.customWidth.value,
                    customHeight = settings.customHeight.value,
                    customFps = settings.customFps.value,
                    outputFormat = settings.outputFormat.value,
                    renderMode = settings.renderMode.value,
                    animRangeStart = settings.animRangeStart.value,
                    animRangeEnd = settings.animRangeEnd.value,
                    disableCaching = settings.disableCache.value,
                    autoSaveToStorage = settings.autoSaveStorage.value
                )
            }
            else -> app.coordinator.runPython(file, code)
        }
    }

    fun runAsPurePython() {
        val file = _activeFile.value ?: return
        autosaveJob?.cancel(); _isDirty.value = false
        app.coordinator.runPython(file, _editorValue.value.text)
    }

    fun compileCurrentFile(nativeCython: Boolean) {
        val file = _activeFile.value ?: return
        autosaveJob?.cancel(); _isDirty.value = false
        app.coordinator.compilePython(file, _editorValue.value.text, nativeCython)
    }

    fun cancelExecution() = app.coordinator.cancelActiveTask()
    fun sendStdin(line: String) = app.coordinator.sendStdin(line)
    fun runPip(action: String, pkg: String) = app.coordinator.runPip(action, pkg)
    fun runShell(cmd: String) = app.coordinator.runShellCommand(cmd)
    fun clearConsole() = app.coordinator.clearConsole()
    fun clearBanner() = app.coordinator.clearBanner()

    // ---- AI System Controller Actions ----
    fun setStudioMode(mode: StudioMode) = settings.setStudioMode(mode)
    fun onControllerCodeEdited(v: TextFieldValue) { _controllerValue.value = v }

    fun runControllerScript() {
        autosaveJob?.cancel()
        viewModelScope.launch {
            flushSaveIfDirty()
            syncInspectorLiveState()
            app.coordinator.runControllerScript(_controllerValue.value.text)
        }
    }

    fun pasteAndRunController() {
        val clip = app.mediaExport.readFromClipboard().trim()
        if (clip.isBlank()) {
            showToast("Clipboard is empty")
            return
        }
        _controllerValue.value = TextFieldValue(clip, TextRange(0))
        runControllerScript()
    }

    fun copyApiAndContext() {
        viewModelScope.launch(Dispatchers.IO) {
            syncInspectorLiveState()
            val text = app.inspector.buildSdkSchemaMarkdown()
            app.mediaExport.copyToClipboard("Atelier AI Controller SDK", text)
            showToast("Copied API Schema + Workspace Context")
        }
    }

    fun rollbackLastControllerRun() {
        val txId = lastTransactionId.value
        if (txId.isBlank()) { showToast("Nothing to roll back"); return }
        viewModelScope.launch {
            val count = app.workspaceRepo.rollbackTransaction(txId)
            settings.emitUiEvent(StudioUiEvent.SyncWorkspace)
            showToast("Rolled back $count file(s)")
        }
    }

    fun loadMacro(macro: ControllerMacro) {
        _controllerValue.value = TextFieldValue(macro.code, TextRange(0))
        showToast("Loaded macro: ${macro.name}")
    }

    fun saveControllerMacro(name: String, description: String) {
        val saved = settings.saveMacro(name, description, _controllerValue.value.text)
        showToast("Saved macro: ${saved.name}")
    }

    fun deleteControllerMacro(id: String) {
        settings.deleteMacro(id)
    }

    // ---- Introspection & Clipboard Copy Helpers ----
    suspend fun loadInspectorSection(tabIdx: Int): String = withContext(Dispatchers.IO) {
        syncInspectorLiveState()
        when (tabIdx) {
            0 -> app.inspector.buildFullSnapshotMarkdown()
            1 -> app.inspector.buildFileTreeMarkdown()
            2 -> app.inspector.buildActiveFileAndErrorsMarkdown()
            3 -> app.inspector.buildSettingsAndStatusMarkdown()
            else -> app.inspector.buildSdkSchemaMarkdown()
        }
    }

    fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) { showToast("Nothing to copy"); return }
        app.mediaExport.copyToClipboard(label, text)
        showToast("Copied $label")
    }

    fun copyErrorSummary() {
        val ex = executionState.value
        val report = buildString {
            appendLine("=== ATELIER ERROR LOG ===")
            appendLine("File : ${_activeFile.value?.relativePath ?: "none"}")
            appendLine("Mode : ${ex.modeLabel}")
            appendLine("Exit : ${ex.lastExitCode ?: "n/a"} ${ex.lastExitCode?.let { "(${decodeExitSignal(it)})" } ?: ""}")
            if (ex.diagnostics.isNotEmpty()) {
                appendLine("\n--- Diagnostics ---")
                ex.diagnostics.forEach { d -> appendLine("[${d.severity}] ${d.source} L${d.line}: ${d.message}") }
            }
            val tail = app.coordinator.getConsoleTail(90)
            if (tail.isNotBlank()) {
                appendLine("\n--- Console Tail ---")
                appendLine(tail)
            }
        }
        copyToClipboard("Error Log", report)
    }

    fun copyFullConsoleLog() = copyToClipboard("Console Log", consoleLog.value.ifBlank { app.coordinator.getConsoleTail(400) })

    fun copyDiagnosticsList() {
        val list = executionState.value.diagnostics
        if (list.isEmpty()) { showToast("No issues to copy"); return }
        val text = list.joinToString("\n") { "[${it.severity}] ${it.source} L${it.line}: ${it.message}" }
        copyToClipboard("Issues", text)
    }

    fun exportTextToDocs(baseTitle: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = app.mediaExport.saveTextDocumentToStorage(content, baseTitle, "Snapshots")
            showToast(if (res.success) "Saved to ${res.displayLocation}" else res.error)
        }
    }

    fun readCrashReport(entry: CrashReportEntry): String = app.crashReporter.readReportBody(entry)
    fun dismissPendingCrashAlert() = app.crashReporter.dismissPendingAlert()
    fun clearAllCrashReports() = app.crashReporter.clearAllReports()

    fun evaluateRepl(code: String) {
        if (code.isBlank() || _replBusy.value) return
        if (runtimeState.value !is RuntimeState.Ready) { showToast("Install runtime first to use REPL."); return }
        viewModelScope.launch {
            _replBusy.value = true
            val res = app.coordinator.replSession.evaluate(code)
            val entry = ReplEntry(replSeq++, code, res.stdout.trimEnd(), res.stderr.trimEnd(), res.latex, res.hostImagePath, res.ok)
            _replEntries.value = (_replEntries.value + entry).takeLast(60)
            _replVars.value = res.vars
            if (res.hostImagePath.isNotBlank() && File(res.hostImagePath).exists()) {
                app.coordinator.setPreviewArtifact(
                    OutputArtifact(res.hostImagePath, OutputKind.IMAGE, "REPL Figure", VideoTelemetry(sizeBytes = File(res.hostImagePath).length()), timestamp = System.currentTimeMillis())
                )
            }
            _replBusy.value = false
        }
    }

    fun resetRepl() {
        viewModelScope.launch {
            app.coordinator.replSession.reset()
            _replEntries.value = emptyList()
            _replVars.value = emptyList()
        }
    }

    fun saveActiveArtifactToStorage() {
        val art = executionState.value.activeArtifact
        if (art.path.isBlank() || !File(art.path).exists()) { showToast("No rendered output to save."); return }
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(art.path)
            val res = when (art.kind) {
                OutputKind.VIDEO -> app.mediaExport.saveVideoToStorage(file, art.title.ifBlank { "atelier_video" })
                OutputKind.IMAGE -> app.mediaExport.saveImageToStorage(file, art.title.ifBlank { "atelier_image" })
                OutputKind.PDF -> app.mediaExport.saveDocumentToStorage(file, art.title.ifBlank { "atelier_doc" }, "application/pdf")
                OutputKind.NONE -> return@launch
            }
            showToast(if (res.success) "Saved to ${res.displayLocation}" else res.error)
        }
    }

    fun shareActiveArtifact(activityContext: Context) {
        val art = executionState.value.activeArtifact
        if (art.path.isBlank() || !File(art.path).exists()) return
        val mime = when (art.kind) { OutputKind.VIDEO -> "video/*"; OutputKind.IMAGE -> "image/*"; OutputKind.PDF -> "application/pdf"; OutputKind.NONE -> "*/*" }
        app.mediaExport.shareFile(activityContext, File(art.path), mime)
    }

    fun selectScene(scene: String) = settings.selectScene(scene)
    fun selectPreset(p: ManimPreset) = settings.selectPreset(p)
    fun selectOutputFormat(f: OutputFormat) = settings.selectOutputFormat(f)
    fun setCustomDimensions(w: Int, h: Int, fps: Int) = settings.setCustomDimensions(w, h, fps)
    fun selectRenderMode(m: ManimRenderMode) = settings.selectRenderMode(m)
    fun setAnimRange(start: Int, end: Int) = settings.setAnimRange(start, end)
    fun setDisableCache(v: Boolean) = settings.setDisableCache(v)
    fun setAutoSaveStorage(v: Boolean) = settings.setAutoSaveStorage(v)
    fun setAutoExportCrashReports(v: Boolean) = settings.setAutoExportCrashReports(v)
    fun toggleTheme() = settings.toggleTheme()
    fun setFontSize(sp: Int) = settings.setFontSize(sp)
    fun setComputeProfile(p: ComputeProfile) = settings.setComputeProfile(p)

    fun startRuntimeInstall() = app.runtimeManager.startInstall()
    fun cancelRuntimeInstall() = app.runtimeManager.cancelInstall()
    fun refreshRuntimeState() = app.runtimeManager.refreshState()
    fun reinstallRuntime() = app.runtimeManager.reinstallRuntime()
    fun setUseNoSeccomp(v: Boolean) { app.runtimeManager.useNoSeccomp = v }
    fun getUseNoSeccomp(): Boolean = app.runtimeManager.useNoSeccomp
    fun setCustomManifestUrl(url: String) { app.runtimeManager.customManifestUrl = url; app.runtimeManager.refreshState() }
    fun getActiveManifestUrl(): String = app.runtimeManager.activeManifestUrl()
    fun clearManimCache() {
        viewModelScope.launch(Dispatchers.IO) {
            File(app.runtimeManager.outputsDir, "manim").deleteRecursively()
            showToast("Manim media cache cleared")
        }
    }

    fun showToast(msg: String) {
        _toastMessage.value = msg
        viewModelScope.launch {
            delay(2800L)
            if (_toastMessage.value == msg) _toastMessage.value = ""
        }
    }

    private fun syncInspectorLiveState() {
        val f = _activeFile.value
        val v = _editorValue.value
        val pos = min(v.selection.start, v.text.length)
        val cursorLine = v.text.substring(0, pos).count { it == '\n' } + 1
        app.inspector.activeFileName = f?.name ?: ""
        app.inspector.activeFilePath = f?.relativePath ?: ""
        app.inspector.activeFileLanguage = f?.language ?: ""
        app.inspector.activeFileDirty = _isDirty.value
        app.inspector.activeEditorText = v.text
        app.inspector.activeCursorLine = cursorLine
        app.inspector.activeDetectedScenes = _detectedScenes.value
        app.crashReporter.activeFilePath = f?.relativePath ?: ""
    }

    private fun refreshDetectedScenes(code: String, language: String) {
        if (language == "LATEX") {
            _detectedScenes.value = emptyList()
            settings.selectScene("")
            return
        }
        val found = detectManimScenes(code)
        _detectedScenes.value = found
        if (settings.selectedScene.value !in found) settings.selectScene(found.firstOrNull() ?: "")
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(2000L)
            flushSaveIfDirty()
        }
    }

    private suspend fun flushSaveIfDirty() {
        val file = _activeFile.value ?: return
        if (!_isDirty.value) return
        app.workspaceRepo.saveFile(file, _editorValue.value.text, label = "Autosave", triggerType = "EDIT")
        _isDirty.value = false
    }

    private fun updateUndoFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun probeVideoLight(file: File): VideoTelemetry {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var fps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
            if (fps <= 0.0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dur > 0L) {
                val frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                if (frames > 0L) fps = frames * 1000.0 / dur.toDouble()
            }
            VideoTelemetry(w, h, if (fps > 0.0) fps else 30.0, dur, "H264", file.length())
        } catch (_: Throwable) {
            VideoTelemetry(sizeBytes = file.length())
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun guestToHostFile(guestPath: String): File = app.prootEngine.guestToHostPath(guestPath)

    private fun timelinesDir(): File = File(app.runtimeManager.outputsDir, "timelines").apply { mkdirs() }
    private fun timelineFileFor(name: String): File = File(timelinesDir(), if (name.endsWith(".json")) name else "$name.timeline.json")

    fun refreshTimelines() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = timelinesDir()
            val names = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name.removeSuffix(".timeline.json").removeSuffix(".json") }
                ?.ifEmpty { listOf("episode_01") } ?: listOf("episode_01")
            _availableTimelines.value = names
            val activeName = _timelineUi.value.name.takeIf { it in names } ?: names.first()
            loadTimelineByNameSync(activeName)
        }
    }

    fun selectTimeline(name: String) {
        viewModelScope.launch(Dispatchers.IO) { loadTimelineByNameSync(name) }
    }

    fun createTimelineProject(name: String) {
        val clean = name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "episode_${System.currentTimeMillis() % 1000}" }
        viewModelScope.launch(Dispatchers.IO) {
            val f = timelineFileFor(clean)
            if (!f.exists()) {
                val doc = JSONObject().put("schema", "atelier.timeline.v2").put("name", clean).put("preset", "youtube_1080p60")
                    .put("caption_style", "minimal_3b1b").put("updatedAt", System.currentTimeMillis() / 1000)
                    .put("v2_overlays", JSONArray()).put("video_clips", JSONArray()).put("voice_clips", JSONArray())
                    .put("sfx_clips", JSONArray()).put("music_clips", JSONArray()).put("captions", JSONArray()).put("chapters", JSONArray())
                f.writeText(doc.toString(2), Charsets.UTF_8)
            }
            refreshTimelines()
            loadTimelineByNameSync(clean)
            showToast("Opened timeline: $clean")
        }
    }

    private fun readTimelineDoc(name: String): JSONObject {
        val f = timelineFileFor(name)
        if (!f.exists()) {
            return JSONObject().put("schema", "atelier.timeline.v2").put("name", name).put("preset", "youtube_1080p60")
                .put("caption_style", "minimal_3b1b").put("v2_overlays", JSONArray()).put("video_clips", JSONArray())
                .put("voice_clips", JSONArray()).put("sfx_clips", JSONArray()).put("music_clips", JSONArray())
                .put("captions", JSONArray()).put("chapters", JSONArray())
        }
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrElse { JSONObject().put("name", name) }
    }

    private fun writeTimelineDocAndReload(name: String, doc: JSONObject) {
        doc.put("schema", "atelier.timeline.v2")
        doc.put("updatedAt", System.currentTimeMillis() / 1000)
        timelineFileFor(name).writeText(doc.toString(2), Charsets.UTF_8)
        loadTimelineByNameSync(name)
    }

    private fun loadTimelineByNameSync(name: String) {
        val doc = readTimelineDoc(name)
        val vClips = mutableListOf<EditClipModel>()
        val vArr = doc.optJSONArray("video_clips") ?: JSONArray()
        var totalV = 0.0
        for (i in 0 until vArr.length()) {
            val o = vArr.getJSONObject(i)
            val p = o.optString("path")
            val host = guestToHostFile(p)
            val srcDur = if (host.exists()) max(0.5, probeVideoLight(host).durationMs / 1000.0) else 5.0
            val t0 = o.optDouble("trim_start", 0.0).coerceAtLeast(0.0)
            val t1Raw = o.optDouble("trim_end", 0.0)
            val t1 = if (t1Raw > t0 + 0.05) min(srcDur, t1Raw) else srcDur
            val spd = o.optDouble("speed", 1.0).coerceIn(0.25, 4.0)
            val frz = o.optDouble("freeze_tail_sec", 0.0).coerceAtLeast(0.0)
            val eff = max(0.2, (t1 - t0) / spd) + frz
            totalV += eff
            vClips.add(EditClipModel(i, p, o.optString("label", File(p).name), t0, t1, eff, spd, frz, o.optString("transition", "none"), o.optDouble("zoom_start", 1.0), o.optDouble("zoom_end", 1.0), o.optDouble("pan_x", 0.0), o.optDouble("pan_y", 0.0)))
        }
        val ovList = mutableListOf<EditOverlayModel>()
        val ovArr = doc.optJSONArray("v2_overlays") ?: JSONArray()
        for (i in 0 until ovArr.length()) {
            val o = ovArr.getJSONObject(i)
            ovList.add(EditOverlayModel(i, o.optString("path"), o.optDouble("start", 0.0), o.optDouble("end", 4.0), o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.18).toFloat(), o.optDouble("scale", 0.35).toFloat(), o.optDouble("opacity", 1.0).toFloat()))
        }
        fun parseAudioArr(arr: JSONArray?, defaultDur: Double): List<EditAudioClipModel> {
            val list = mutableListOf<EditAudioClipModel>()
            if (arr == null) return list
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.optString("path")
                val hf = guestToHostFile(p)
                val d = o.optDouble("duration", 0.0).takeIf { it > 0.05 } ?: if (hf.exists() && hf.length() > 44L) max(0.3, (hf.length() - 44.0) / 48000.0) else defaultDur
                list.add(EditAudioClipModel(i, p, o.optDouble("start", 0.0), d, o.optDouble("gain_db", 0.0), o.optString("text", ""), o.optBoolean("trim_silence", false)))
            }
            return list
        }
        val voiceList = parseAudioArr(doc.optJSONArray("voice_clips"), 2.5)
        val sfxList = parseAudioArr(doc.optJSONArray("sfx_clips"), 0.7)
        val musicList = parseAudioArr(doc.optJSONArray("music_clips"), max(10.0, totalV))
        val caps = mutableListOf<EditCaptionModel>()
        val capArr = doc.optJSONArray("captions") ?: JSONArray()
        for (i in 0 until capArr.length()) {
            val o = capArr.getJSONObject(i)
            caps.add(EditCaptionModel(o.optDouble("start", 0.0), o.optDouble("end", 2.0), o.optString("text", "")))
        }
        val chaps = mutableListOf<EditChapterModel>()
        val chArr = doc.optJSONArray("chapters") ?: JSONArray()
        for (i in 0 until chArr.length()) {
            val o = chArr.getJSONObject(i)
            chaps.add(EditChapterModel(o.optDouble("time", 0.0), o.optString("title", "")))
        }
        val maxAudioEnd = (voiceList.maxOfOrNull { it.start + it.durationSec } ?: 0.0)
        _timelineUi.value = TimelineUiState(
            name = doc.optString("name", name),
            preset = doc.optString("preset", "youtube_1080p60"),
            captionStyle = doc.optString("caption_style", "minimal_3b1b"),
            videoClips = vClips, overlays = ovList, voiceClips = voiceList,
            sfxClips = sfxList, musicClips = musicList, captions = caps, chapters = chaps,
            totalDurationSec = max(totalV, maxAudioEnd)
        )
        voiceList.firstOrNull()?.let { vc ->
            val f = guestToHostFile(vc.path)
            if (f.exists() && f.length() > 44L) _voiceWaveformPeaks.value = decodeQuickWavPeaks(f, 36)
        }
    }

    private fun decodeQuickWavPeaks(file: File, bins: Int): List<Float> = runCatching {
        val bytes = file.readBytes()
        val dataLen = (bytes.size - 44).coerceAtLeast(0)
        val totalSamples = dataLen / 2
        if (totalSamples < bins) return@runCatching emptyList()
        val step = max(1, totalSamples / bins)
        val out = ArrayList<Float>(bins)
        for (b in 0 until bins) {
            var peak = 0
            val s0 = b * step
            val s1 = min(totalSamples, s0 + step)
            var i = s0
            while (i < s1) {
                val off = 44 + i * 2
                val lo = bytes[off].toInt() and 0xFF
                val hi = bytes[off + 1].toInt()
                val v = kotlin.math.abs((hi shl 8) or lo)
                if (v > peak) peak = v
                i += max(1, step / 24)
            }
            out.add((peak / 32768f).coerceIn(0.08f, 1f))
        }
        out
    }.getOrDefault(emptyList())

    fun addActiveRenderToTimeline() {
        val art = executionState.value.activeArtifact
        if (art.kind != OutputKind.VIDEO || art.path.isBlank() || !File(art.path).exists()) {
            showToast("Render a Manim video first"); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val clipsDir = File(app.runtimeManager.outputsDir, "clips").apply { mkdirs() }
            val safe = "${art.title.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${System.currentTimeMillis() % 10000}.mp4"
            val dest = File(clipsDir, safe)
            File(art.path).copyTo(dest, overwrite = true)
            val name = _timelineUi.value.name
            val doc = readTimelineDoc(name)
            val arr = doc.optJSONArray("video_clips") ?: JSONArray().also { doc.put("video_clips", it) }
            arr.put(JSONObject().put("path", "/outputs/clips/$safe").put("label", art.title.ifBlank { safe }).put("trim_start", 0.0).put("trim_end", 0.0).put("speed", 1.0).put("freeze_tail_sec", 0.0).put("transition", "none").put("zoom_start", 1.0).put("zoom_end", 1.0))
            writeTimelineDocAndReload(name, doc)
            showToast("Added ${art.title} to V1")
        }
    }

    fun importVoiceManifestToTimeline() {
        val manGuest = _latestManifestPath.value.ifBlank { "/outputs/audio/timing_manifest.json" }
        val host = guestToHostFile(manGuest)
        if (!host.exists()) { showToast("Synthesize a voice script in Audio tab first"); return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val man = JSONObject(host.readText(Charsets.UTF_8))
                val cues = man.optJSONArray("cues") ?: JSONArray()
                val name = _timelineUi.value.name
                val doc = readTimelineDoc(name)
                val vArr = JSONArray()
                val cArr = JSONArray()
                for (i in 0 until cues.length()) {
                    val c = cues.getJSONObject(i)
                    vArr.put(JSONObject().put("path", c.getString("wavPath")).put("start", c.getDouble("startSec")).put("gain_db", 0.0).put("text", c.optString("text")))
                    cArr.put(JSONObject().put("start", c.getDouble("startSec")).put("end", c.getDouble("endSec")).put("text", c.optString("text")).put("words", c.optJSONArray("words") ?: JSONArray()))
                }
                doc.put("voice_clips", vArr).put("captions", cArr)
                writeTimelineDocAndReload(name, doc)
                showToast("Imported ${cues.length()} voice cues & captions")
            }.onFailure { showToast(it.message ?: "Voice import failed") }
        }
    }

    fun addLatestMusicToTimeline() {
        val latest = _musicFiles.value.firstOrNull() ?: run { showToast("Compose a score in Audio tab first"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val name = _timelineUi.value.name
            val doc = readTimelineDoc(name)
            val mArr = JSONArray().put(JSONObject().put("path", "/outputs/audio/music/${latest.name}").put("start", 0.0).put("duration", 0.0).put("gain_db", -11.0).put("fade_in", 1.2).put("fade_out", 2.2))
            doc.put("music_clips", mArr)
            writeTimelineDocAndReload(name, doc)
            showToast("Added BGM: ${latest.name}")
        }
    }

    fun splitVideoClipAt(playheadSec: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val tl = _timelineUi.value
            var acc = 0.0
            val target = tl.videoClips.firstOrNull { c ->
                val hit = playheadSec > acc + 0.25 && playheadSec < acc + c.durationSec - 0.25
                if (!hit) acc += c.durationSec
                hit
            } ?: run { showToast("Move playhead inside a V1 clip to split"); return@launch }

            val localSplit = target.trimStart + (playheadSec - acc) * target.speed
            val doc = readTimelineDoc(tl.name)
            val oldArr = doc.optJSONArray("video_clips") ?: return@launch
            val newArr = JSONArray()
            for (i in 0 until oldArr.length()) {
                val obj = oldArr.getJSONObject(i)
                if (i == target.index) {
                    val left = JSONObject(obj.toString()).put("trim_end", localSplit).put("freeze_tail_sec", 0.0)
                    val right = JSONObject(obj.toString()).put("trim_start", localSplit).put("label", "${target.label.substringBeforeLast('.')}_B")
                    newArr.put(left); newArr.put(right)
                } else newArr.put(obj)
            }
            doc.put("video_clips", newArr)
            writeTimelineDocAndReload(tl.name, doc)
            showToast("Split clip at ${String.format(Locale.US, "%.2fs", playheadSec)}")
        }
    }

    fun updateVideoClip(index: Int, trimStart: Double, trimEnd: Double, speed: Double, freezeTail: Double, transition: String, zoomStart: Double, zoomEnd: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = _timelineUi.value.name
            val doc = readTimelineDoc(name)
            val arr = doc.optJSONArray("video_clips") ?: return@launch
            if (index !in 0 until arr.length()) return@launch
            arr.getJSONObject(index).put("trim_start", trimStart).put("trim_end", trimEnd).put("speed", speed)
                .put("freeze_tail_sec", freezeTail).put("transition", transition).put("zoom_start", zoomStart).put("zoom_end", zoomEnd)
            writeTimelineDocAndReload(name, doc)
        }
    }

    fun deleteVideoClip(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = _timelineUi.value.name
            val doc = readTimelineDoc(name)
            val oldArr = doc.optJSONArray("video_clips") ?: return@launch
            val newArr = JSONArray()
            for (i in 0 until oldArr.length()) if (i != index) newArr.put(oldArr.getJSONObject(i))
            doc.put("video_clips", newArr)
            writeTimelineDocAndReload(name, doc)
        }
    }

    fun setTimelineCaptionStyle(style: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = _timelineUi.value.name
            val doc = readTimelineDoc(name).put("caption_style", style)
            writeTimelineDocAndReload(name, doc)
        }
    }

    fun addTimelineChapter(timeSec: Double, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = _timelineUi.value.name
            val doc = readTimelineDoc(name)
            val arr = doc.optJSONArray("chapters") ?: JSONArray().also { doc.put("chapters", it) }
            arr.put(JSONObject().put("time", timeSec).put("title", title))
            writeTimelineDocAndReload(name, doc)
            showToast("Chapter added: $title")
        }
    }

    fun runPacingScan() {
        val targetPath = _timelineUi.value.videoClips.firstOrNull()?.path ?: run {
            val art = executionState.value.activeArtifact
            if (art.kind == OutputKind.VIDEO && File(art.path).exists()) {
                val dst = File(File(app.runtimeManager.outputsDir, "exports").apply { mkdirs() }, "_pacing_src.mp4")
                File(art.path).copyTo(dst, overwrite = true)
                "/outputs/exports/_pacing_src.mp4"
            } else ""
        }
        if (targetPath.isBlank()) { showToast("Add or render a video first"); return }
        _pacingReport.value = null
        val py = "import atelier, json\nrep = atelier.pacing.analyze('$targetPath')\nwith open('/outputs/exports/_pacing_report.json','w') as f: json.dump(rep, f)\nprint(rep['summary'])\n"
        app.coordinator.runAudioOrTimelineJob("Pacing Scan", "Analyzing silences, static stretches & LUFS...", py, autoSaveVideo = false) { code, _ ->
            if (code == 0) {
                val rf = File(app.runtimeManager.outputsDir, "exports/_pacing_report.json")
                if (rf.exists()) runCatching { _pacingReport.value = JSONObject(rf.readText(Charsets.UTF_8)) }
            }
        }
    }

    fun autoTrimTimelineDeadAir() {
        viewModelScope.launch(Dispatchers.IO) {
            val name = _timelineUi.value.name
            val doc = readTimelineDoc(name)
            val arr = doc.optJSONArray("voice_clips") ?: JSONArray()
            for (i in 0 until arr.length()) arr.getJSONObject(i).put("trim_silence", true)
            writeTimelineDocAndReload(name, doc)
            showToast("Auto-Trim Dead Air enabled on voice track")
        }
    }

    fun generateThumbnail(timeSec: Double, title: String, subtitle: String, mathTex: String) {
        val vidGuest = _timelineUi.value.videoClips.firstOrNull()?.path ?: ""
        val sTitle = title.replace("\"", "'")
        val sSub = subtitle.replace("\"", "'")
        val sMath = mathTex.replace("\\", "\\\\").replace("\"", "'")
        val py = "import atelier\nres = atelier.thumbnail.create(video_path='$vidGuest', time_sec=$timeSec, title=\"$sTitle\", subtitle=\"$sSub\", math_tex=\"$sMath\", out_png='/outputs/thumbnails/latest_thumb.png')\nprint(res['summary'])\n"
        app.coordinator.runAudioOrTimelineJob("Thumbnail Studio", "Rendering 1280x720 PNG...", py, autoSaveVideo = false) { code, _ ->
            val f = File(app.runtimeManager.outputsDir, "thumbnails/latest_thumb.png")
            if (code == 0 && f.exists()) {
                _latestThumbnailHostPath.value = f.absolutePath
                app.mediaExport.saveImageToStorage(f, "thumb_${_timelineUi.value.name}")
                showToast("Thumbnail saved to Pictures/Atelier/")
            }
        }
    }

    fun exportCurrentTimeline(preset: String, hardwareFast: Boolean, fullPublishBundle: Boolean) {
        val tl = _timelineUi.value
        if (tl.videoClips.isEmpty()) { showToast("Add at least one video clip to V1"); return }
        if (hardwareFast && !fullPublishBundle) {
            val (w, h) = when (preset) {
                "youtube_720p30" -> 1280 to 720
                "youtube_4k60" -> 3840 to 2160
                "shorts_9x16_60", "shorts_9x16_30" -> 1080 to 1920
                "square_1x1" -> 1080 to 1080
                else -> 1920 to 1080
            }
            val specs = tl.videoClips.map {
                HardwareClipSpec(guestToHostFile(it.path).absolutePath, (it.trimStart * 1000).toLong(), (it.trimEnd * 1000).toLong(), it.zoomStart.toFloat())
            }
            val outFile = File(File(app.runtimeManager.outputsDir, "exports").apply { mkdirs() }, "${tl.name}_hw_${System.currentTimeMillis() % 10000}.mp4")
            app.coordinator.runHardwareVideoExport(specs, outFile, w, h, app.hardwareExporter, settings.autoSaveStorage.value) { ok ->
                if (ok) settings.setStudioMode(StudioMode.CODE)
            }
            return
        }
        val py = if (fullPublishBundle) {
            "import atelier\nres = atelier.export.publish_bundle('${tl.name}', include_short=True, master_preset='$preset')\nprint('__ATELIER_ARTIFACT__:VIDEO:' + res['masterMp4'], flush=True)\nprint(res['summary'])\n"
        } else {
            "import atelier\nres = atelier.export.render_timeline('${tl.name}', preset='$preset', publish_to_stage=False)\nprint('__ATELIER_ARTIFACT__:VIDEO:' + res['outputPath'], flush=True)\nprint(res['summary'])\n"
        }
        val title = if (fullPublishBundle) "Publish Kit · ${tl.name}" else "Export · $preset"
        app.coordinator.runAudioOrTimelineJob(title, "Rendering $preset + mastering -14 LUFS...", py, autoSaveVideo = settings.autoSaveStorage.value) { code, _ ->
            if (code == 0) settings.setStudioMode(StudioMode.CODE)
        }
    }

    fun dubCurrentTimeline(targetLang: String, modelId: String, translatedLinesRaw: String) {
        val tl = _timelineUi.value
        val lines = translatedLinesRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val qLines = JSONObject.quote(JSONArray(lines).toString())
        val py = "import atelier, json\nlines = json.loads($qLines)\nres = atelier.dub.timeline('${tl.name}', target_lang='$targetLang', translated_lines=lines, voice_model='$modelId')\nprint(res['summary'])\n"
        app.coordinator.runAudioOrTimelineJob("Dub ($targetLang)", "Synthesizing dub & auto-retiming...", py, autoSaveVideo = false) { code, _ ->
            if (code == 0) {
                refreshTimelines()
                selectTimeline("${tl.name}_dub_${targetLang.lowercase()}")
                showToast("Dubbed timeline ready (${targetLang.uppercase()})")
            }
        }
    }

    fun openFormulaPreviewFromCursor() {
        val v = _editorValue.value
        val sel = if (!v.selection.collapsed) v.text.substring(min(v.selection.start, v.selection.end), max(v.selection.start, v.selection.end)) else {
            val pos = min(v.selection.start, v.text.length)
            val s = v.text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val e = v.text.indexOf('\n', pos).let { if (it < 0) v.text.length else it }
            v.text.substring(s, e)
        }
        val rx = Regex("""(?:MathTex|Tex)\(\s*r?["']{1,3}(.+?)["']{1,3}""")
        val extracted = rx.find(sel)?.groupValues?.get(1) ?: sel.trim()
        _formulaInitial.value = extracted
        _formulaPreviewError.value = ""
        if (extracted.isNotBlank()) renderFormulaPreview(extracted)
    }

    fun renderFormulaPreview(expr: String) {
        if (_formulaBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _formulaBusy.value = true
            _formulaPreviewError.value = ""
            try {
                val safe = expr.replace("\\", "\\\\").replace("\"", "\\\"")
                val py = "import atelier; r = atelier.manim.preview_formula(\"$safe\"); print('FORMULA_PNG:' + r['path'])"
                val (code, out) = app.coordinator.runStandalonePythonForBatch(py)
                val pngGuest = out.lines().firstOrNull { it.startsWith("FORMULA_PNG:") }?.removePrefix("FORMULA_PNG:")?.trim().orEmpty()
                if (code == 0 && pngGuest.isNotBlank()) {
                    _formulaPreviewPath.value = guestToHostFile(pngGuest).absolutePath
                } else {
                    _formulaPreviewError.value = out.takeLast(400).ifBlank { "Formula compile failed" }
                }
            } catch (t: Throwable) {
                _formulaPreviewError.value = t.message ?: "Formula error"
            } finally {
                _formulaBusy.value = false
            }
        }
    }

    fun runGitAction(action: String, arg1: String, arg2: String) {
        if (_gitBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _gitBusy.value = true
            try {
                val py = when (action) {
                    "init" -> "import atelier; r = atelier.git.init(); print(r['stdout'] or r['stderr'] or 'Initialized git repo')"
                    "status" -> "import atelier; r = atelier.git.status(); print(r['stdout'] or r['stderr'] or 'Clean working tree')"
                    "diff" -> "import atelier; r = atelier.git.diff(); print(r['stdout'] or 'No unstaged changes')"
                    "log" -> "import atelier; r = atelier.git.log(20); print(r['stdout'] or r['stderr'] or 'No commits yet')"
                    "commit" -> "import atelier; r = atelier.git.commit_all(${JSONObject.quote(arg1)}); print(r['stdout'] or r['stderr'])"
                    "branch" -> "import atelier; r = atelier.git.run(['checkout', '-B', ${JSONObject.quote(arg1)}]); print(r['stdout'] or r['stderr'])"
                    "remote" -> "import atelier; atelier.git.run(['remote', 'remove', 'origin']); r = atelier.git.run(['remote', 'add', 'origin', ${JSONObject.quote(arg1)}]); print(r['stdout'] or r['stderr'] or 'Origin updated')"
                    "pull" -> "import atelier; r = atelier.git.run(['pull']); print(r['stdout'] or r['stderr'])"
                    "push" -> "import atelier; r = atelier.git.run(['push', '-u', 'origin', 'HEAD']); print(r['stdout'] or r['stderr'])"
                    else -> "print('Unknown git command')"
                }
                val (_, out) = app.coordinator.runStandalonePythonForBatch(py)
                _gitOutput.value = out.trim()
            } catch (t: Throwable) {
                _gitOutput.value = "Error: ${t.message}"
            } finally {
                _gitBusy.value = false
            }
        }
    }

    fun saveBrandKit(cfg: BrandKitConfig) {
        app.automation.updateBrand(cfg)
        showToast("Brand Kit saved")
    }

    fun exportBrandThemeFile() {
        viewModelScope.launch {
            val rel = app.automation.exportManimThemeFile("atelier_theme.py")
            showToast("Generated /workspace/$rel")
        }
    }

    fun buildUnifiedBlueprint(name: String, mdScript: String) {
        val clean = name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "blueprint_ep" }
        val qScript = JSONObject.quote(mdScript)
        val py = "import atelier\nres = atelier.blueprint.build($qScript, timeline_name='$clean')\nprint(res['summary'])\n"
        app.coordinator.runAudioOrTimelineJob("Episode Blueprint", "Synthesizing voice, SFX, music & timeline...", py, autoSaveVideo = false) { code, _ ->
            if (code == 0) {
                refreshAudioAssets()
                refreshTimelines()
                selectTimeline(clean)
                settings.setStudioMode(StudioMode.EDIT)
            }
        }
    }

    fun enqueueActiveSceneBatch() {
        val f = _activeFile.value ?: run { showToast("Open a Manim file first"); return }
        val sc = settings.selectedScene.value.ifBlank { _detectedScenes.value.firstOrNull() ?: "" }
        if (sc.isBlank()) { showToast("No Manim scene detected"); return }
        app.automation.enqueueJob("MANIM_RENDER", "Render $sc (${f.name})", f.relativePath, sc)
        showToast("Queued $sc in Batch Queue")
    }

    fun enqueueActiveTimelineBatch(preset: String = "") {
        val tl = _timelineUi.value
        val p = preset.ifBlank { tl.preset }
        app.automation.enqueueJob("TIMELINE_EXPORT", "Export ${tl.name} ($p)", tl.name, p)
        showToast("Queued ${tl.name} ($p)")
    }

    fun startBatchQueue() = app.automation.startBatchQueue()
    fun pauseBatchQueue() = app.automation.pauseBatchQueue()
    fun removeBatchJob(id: String) = app.automation.removeJob(id)
    fun clearFinishedBatchJobs() = app.automation.clearCompletedOrAll(false)

    override fun onCleared() {
        super.onCleared()
        app.coordinator.replSession.close()
        app.voiceEngine.release()
    }
}
