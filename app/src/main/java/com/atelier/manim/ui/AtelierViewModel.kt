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
import com.atelier.manim.runtime.VoiceDownloadState
import com.atelier.manim.runtime.VoiceModelSpec
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

    fun synthesizeAudioStudioScript(modelId: String, sid: Int, speed: Float) {
        val lines = _audioScriptText.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) { showToast("Enter at least one narration line"); return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _voiceStudioSummary.value = "Synthesizing ${lines.size} cue(s)..."
                val res = app.voiceEngine.synthesizeScript(lines = lines, modelId = modelId, sid = sid, speed = speed, normalizeMath = true)
                _latestManifestPath.value = res.optString("manifestPath", "")
                val total = res.optDouble("totalDurationSec", 0.0)
                _voiceStudioSummary.value = "Ready: ${res.optInt("cueCount")} cues · ${String.format("%.1fs", total)} · SRT saved"
                val firstCueWav = res.optJSONArray("cues")?.optJSONObject(0)?.optString("wavPath", "").orEmpty()
                if (firstCueWav.isNotBlank()) app.voiceEngine.playPreviewWav(app.prootEngine.guestToHostPath(firstCueWav).absolutePath)
                showToast("Voice script & captions.srt ready (${String.format("%.1fs", total)})")
            }.onFailure {
                _voiceStudioSummary.value = "Error: ${it.message}"
                showToast(it.message ?: "Synthesis failed")
            }
        }
    }

    fun previewAudioScriptLine1(modelId: String, sid: Int, speed: Float) {
        val line = _audioScriptText.value.lines().firstOrNull { it.isNotBlank() }.orEmpty()
        if (line.isBlank()) { showToast("Script is empty"); return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val res = app.voiceEngine.synthesize(text = line, modelId = modelId, sid = sid, speed = speed, normalizeMath = true)
                app.voiceEngine.playPreviewWav(res.wavHostPath)
                showToast("Playing line 1 (${String.format("%.1fs", res.durationSec)})")
            }.onFailure { showToast(it.message ?: "Voice preview failed") }
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
        // Copy active snapshot into /outputs/exports/_src_active.mp4 so PRoot FFmpeg can access it cleanly
        val srcCopy = File(File(app.runtimeManager.outputsDir, "exports").apply { mkdirs() }, "_src_active.mp4")
        runCatching { File(art.path).copyTo(srcCopy, overwrite = true) }.onFailure {
            showToast("Failed staging source video"); return
        }
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

    override fun onCleared() {
        super.onCleared()
        app.coordinator.replSession.close()
        app.voiceEngine.release()
    }
}
