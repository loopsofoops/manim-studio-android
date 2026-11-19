package com.atelier.manim.ui

import android.app.Application
import android.content.Context
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
import com.atelier.manim.runtime.ExecutionState
import com.atelier.manim.runtime.ManimPreset
import com.atelier.manim.runtime.OutputArtifact
import com.atelier.manim.runtime.OutputFormat
import com.atelier.manim.runtime.OutputKind
import com.atelier.manim.runtime.ReplEntry
import com.atelier.manim.runtime.ReplVar
import com.atelier.manim.runtime.RuntimeState
import com.atelier.manim.runtime.VideoTelemetry
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AtelierViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AtelierApp
    private val prefs = application.getSharedPreferences("atelier_ui", Context.MODE_PRIVATE)

    val runtimeState: StateFlow<RuntimeState> = app.runtimeManager.state
    val executionState: StateFlow<ExecutionState> = app.coordinator.state

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

    private val _selectedScene = MutableStateFlow("")
    val selectedScene: StateFlow<String> = _selectedScene.asStateFlow()

    private val _preset = MutableStateFlow(ManimPreset.MED_720P)
    val preset: StateFlow<ManimPreset> = _preset.asStateFlow()

    private val _outputFormat = MutableStateFlow(OutputFormat.MP4)
    val outputFormat: StateFlow<OutputFormat> = _outputFormat.asStateFlow()

    private val _customWidth = MutableStateFlow(1280)
    val customWidth: StateFlow<Int> = _customWidth.asStateFlow()

    private val _customHeight = MutableStateFlow(720)
    val customHeight: StateFlow<Int> = _customHeight.asStateFlow()

    private val _customFps = MutableStateFlow(30)
    val customFps: StateFlow<Int> = _customFps.asStateFlow()

    private val _disableCache = MutableStateFlow(false)
    val disableCache: StateFlow<Boolean> = _disableCache.asStateFlow()

    private val _autoSaveStorage = MutableStateFlow(prefs.getBoolean("auto_save_storage", true))
    val autoSaveStorage: StateFlow<Boolean> = _autoSaveStorage.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean("dark_theme", true))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _fontSizeSp = MutableStateFlow(prefs.getInt("editor_font_sp", 13))
    val fontSizeSp: StateFlow<Int> = _fontSizeSp.asStateFlow()

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
        viewModelScope.launch {
            val all = app.workspaceRepo.ensureSeeded()
            if (all.isNotEmpty()) openFile(all.first())
        }
    }

    fun openFile(file: ProjectFileEntity) {
        viewModelScope.launch {
            flushSaveIfDirty()
            val text = app.workspaceRepo.readFile(file)
            _activeFile.value = file
            _editorValue.value = TextFieldValue(text, TextRange(0))
            _isDirty.value = false
            undoStack.clear()
            redoStack.clear()
            updateUndoFlags()
            refreshDetectedScenes(text, file.language)

            if (file.lastOutputPath.isNotBlank() && File(file.lastOutputPath).exists()) {
                val kind = runCatching { OutputKind.valueOf(file.lastOutputType) }.getOrDefault(OutputKind.NONE)
                if (kind != OutputKind.NONE) {
                    app.coordinator.setPreviewArtifact(
                        OutputArtifact(
                            path = file.lastOutputPath,
                            kind = kind,
                            title = file.name,
                            telemetry = VideoTelemetry(sizeBytes = File(file.lastOutputPath).length())
                        )
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
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_editorValue.value)
        _editorValue.value = undoStack.removeLast()
        _isDirty.value = true
        _activeFile.value?.let { refreshDetectedScenes(_editorValue.value.text, it.language) }
        updateUndoFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_editorValue.value)
        _editorValue.value = redoStack.removeLast()
        _isDirty.value = true
        _activeFile.value?.let { refreshDetectedScenes(_editorValue.value.text, it.language) }
        updateUndoFlags()
    }

    fun manualSave(label: String = "Manual Snapshot") {
        val file = _activeFile.value ?: return
        viewModelScope.launch {
            app.workspaceRepo.saveFile(file, _editorValue.value.text, label = label, triggerType = "SAVE")
            _isDirty.value = false
            showToast("Saved snapshot")
        }
    }

    fun createNewFile(name: String, language: String) {
        viewModelScope.launch {
            val template = StarterTemplates.blankFor(language)
            val created = app.workspaceRepo.createFile(name, language, template)
            openFile(created)
            showToast("Created ${created.name}")
        }
    }

    fun renameActiveFile(file: ProjectFileEntity, newName: String) {
        viewModelScope.launch {
            val renamed = app.workspaceRepo.renameFile(file, newName)
            if (_activeFile.value?.id == file.id) {
                _activeFile.value = renamed
                refreshDetectedScenes(_editorValue.value.text, renamed.language)
            }
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

    fun readRevisionContent(revision: FileRevisionEntity): String =
        app.blobStore.readBlob(revision.contentHash) ?: ""

    fun restoreRevision(revision: FileRevisionEntity) {
        val file = _activeFile.value ?: return
        viewModelScope.launch {
            val (restoredText, _) = app.workspaceRepo.restoreRevision(file, revision)
            _editorValue.value = TextFieldValue(restoredText, TextRange(0))
            _isDirty.value = false
            refreshDetectedScenes(restoredText, file.language)
            previewRevisionOutput(revision)
            showToast("Restored revision #${revision.id}")
        }
    }

    fun previewRevisionOutput(revision: FileRevisionEntity) {
        if (revision.outputPath.isBlank() || !File(revision.outputPath).exists()) return
        val kind = runCatching { OutputKind.valueOf(revision.outputType) }.getOrDefault(OutputKind.NONE)
        if (kind == OutputKind.NONE) return
        app.coordinator.setPreviewArtifact(
            OutputArtifact(
                path = revision.outputPath,
                kind = kind,
                title = revision.label,
                telemetry = VideoTelemetry(sizeBytes = File(revision.outputPath).length()),
                revisionId = revision.id,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun runPrimaryAction() {
        val file = _activeFile.value ?: return
        val code = _editorValue.value.text
        when {
            file.language == "LATEX" || file.name.endsWith(".tex", ignoreCase = true) -> {
                app.coordinator.compileLatex(file, code, _autoSaveStorage.value)
            }
            _detectedScenes.value.isNotEmpty() -> {
                val scene = _selectedScene.value.ifBlank { _detectedScenes.value.first() }
                app.coordinator.renderManim(
                    file = file,
                    code = code,
                    sceneName = scene,
                    preset = _preset.value,
                    customWidth = _customWidth.value,
                    customHeight = _customHeight.value,
                    customFps = _customFps.value,
                    outputFormat = _outputFormat.value,
                    disableCaching = _disableCache.value,
                    autoSaveToStorage = _autoSaveStorage.value
                )
            }
            else -> app.coordinator.runPython(file, code)
        }
    }

    fun runAsPurePython() {
        val file = _activeFile.value ?: return
        app.coordinator.runPython(file, _editorValue.value.text)
    }

    fun compileCurrentFile(nativeCython: Boolean) {
        val file = _activeFile.value ?: return
        app.coordinator.compilePython(file, _editorValue.value.text, nativeCython)
    }

    fun cancelExecution() = app.coordinator.cancelActiveTask()
    fun sendStdin(line: String) = app.coordinator.sendStdin(line)
    fun runPip(action: String, pkg: String) = app.coordinator.runPip(action, pkg)
    fun runShell(cmd: String) = app.coordinator.runShellCommand(cmd)
    fun clearConsole() = app.coordinator.clearConsole()
    fun clearBanner() = app.coordinator.clearBanner()

    fun evaluateRepl(code: String) {
        if (code.isBlank() || _replBusy.value) return
        if (runtimeState.value !is RuntimeState.Ready) {
            showToast("Install runtime first to use REPL.")
            return
        }
        viewModelScope.launch {
            _replBusy.value = true
            val res = app.coordinator.replSession.evaluate(code)
            val entry = ReplEntry(
                id = replSeq++,
                input = code,
                stdout = res.stdout.trimEnd(),
                stderr = res.stderr.trimEnd(),
                latex = res.latex,
                imagePath = res.hostImagePath,
                ok = res.ok
            )
            _replEntries.value = (_replEntries.value + entry).takeLast(60)
            _replVars.value = res.vars
            if (res.hostImagePath.isNotBlank() && File(res.hostImagePath).exists()) {
                app.coordinator.setPreviewArtifact(
                    OutputArtifact(
                        path = res.hostImagePath,
                        kind = OutputKind.IMAGE,
                        title = "REPL Figure",
                        telemetry = VideoTelemetry(sizeBytes = File(res.hostImagePath).length()),
                        timestamp = System.currentTimeMillis()
                    )
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
        if (art.path.isBlank() || !File(art.path).exists()) {
            showToast("No rendered output to save.")
            return
        }
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
        val mime = when (art.kind) {
            OutputKind.VIDEO -> "video/*"
            OutputKind.IMAGE -> "image/*"
            OutputKind.PDF -> "application/pdf"
            OutputKind.NONE -> "*/*"
        }
        app.mediaExport.shareFile(activityContext, File(art.path), mime)
    }

    fun selectScene(scene: String) { _selectedScene.value = scene }
    fun selectPreset(p: ManimPreset) { _preset.value = p }
    fun selectOutputFormat(f: OutputFormat) { _outputFormat.value = f }
    fun setCustomDimensions(w: Int, h: Int, fps: Int) {
        _customWidth.value = w.coerceIn(160, 4096)
        _customHeight.value = h.coerceIn(160, 4096)
        _customFps.value = fps.coerceIn(1, 120)
    }
    fun setDisableCache(v: Boolean) { _disableCache.value = v }
    fun setAutoSaveStorage(v: Boolean) {
        _autoSaveStorage.value = v
        prefs.edit().putBoolean("auto_save_storage", v).apply()
    }
    fun toggleTheme() {
        val next = !_isDarkTheme.value
        _isDarkTheme.value = next
        prefs.edit().putBoolean("dark_theme", next).apply()
    }
    fun setFontSize(sp: Int) {
        val clamped = sp.coerceIn(10, 22)
        _fontSizeSp.value = clamped
        prefs.edit().putInt("editor_font_sp", clamped).apply()
    }

    fun startRuntimeInstall() = app.runtimeManager.startInstall()
    fun cancelRuntimeInstall() = app.runtimeManager.cancelInstall()
    fun refreshRuntimeState() = app.runtimeManager.refreshState()
    fun reinstallRuntime() = app.runtimeManager.reinstallRuntime()
    fun setUseNoSeccomp(v: Boolean) { app.runtimeManager.useNoSeccomp = v }
    fun getUseNoSeccomp(): Boolean = app.runtimeManager.useNoSeccomp
    fun setCustomManifestUrl(url: String) {
        app.runtimeManager.customManifestUrl = url
        app.runtimeManager.refreshState()
    }
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

    private fun refreshDetectedScenes(code: String, language: String) {
        if (language == "LATEX") {
            _detectedScenes.value = emptyList()
            _selectedScene.value = ""
            return
        }
        val found = detectManimScenes(code)
        _detectedScenes.value = found
        if (_selectedScene.value !in found) {
            _selectedScene.value = found.firstOrNull() ?: ""
        }
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

    override fun onCleared() {
        super.onCleared()
        app.coordinator.replSession.close()
    }
}
