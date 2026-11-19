package com.atelier.manim.runtime

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import com.atelier.manim.data.AtelierDao
import com.atelier.manim.data.HistoryBlobStore
import com.atelier.manim.data.MediaExportManager
import com.atelier.manim.data.ProjectFileEntity
import com.atelier.manim.data.RenderHistoryEntity
import com.atelier.manim.data.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import kotlin.math.max
import kotlin.math.min

class ExecutionCoordinator(
    private val context: Context,
    private val prootEngine: ProotEngine,
    private val runtimeManager: RuntimeManager,
    private val workspaceRepo: WorkspaceRepository,
    private val mediaExport: MediaExportManager,
    private val dao: AtelierDao,
    private val blobStore: HistoryBlobStore,
    private val crashReporter: CrashReporter,
    private val onBeforeHeavyRender: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskMutex = Mutex()
    private val runIdGen = AtomicLong(0L)

    private val _state = MutableStateFlow(ExecutionState())
    val state: StateFlow<ExecutionState> = _state.asStateFlow()

    private val _consoleLog = MutableStateFlow("")
    val consoleLog: StateFlow<String> = _consoleLog.asStateFlow()

    private val logLock = Any()
    private val logBuffer = StringBuilder()
    @Volatile private var lastLogFlushMs = 0L

    val replSession = ReplSession(prootEngine)

    private var activeJob: Job? = null
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeStdin: BufferedWriter? = null
    @Volatile private var bridgeSubProcess: Process? = null

    private val _controllerSteps = MutableStateFlow<List<ControllerStep>>(emptyList())
    val controllerSteps: StateFlow<List<ControllerStep>> = _controllerSteps.asStateFlow()

    private val _lastTransactionId = MutableStateFlow("")
    val lastTransactionId: StateFlow<String> = _lastTransactionId.asStateFlow()

    private val stepIdGen = AtomicLong(1L)

    private val animProgressRegex = Regex("""Animation\s+(\d+)\s*:.*?(\d{1,3})%""")
    private val pctRegex = Regex("""(\d{1,3})%""")

    fun sendStdin(line: String) {
        scope.launch {
            runCatching {
                activeStdin?.let { w ->
                    w.write(line)
                    w.newLine()
                    w.flush()
                }
                appendLog("> $line")
            }
        }
    }

    fun cancelActiveTask() {
        runIdGen.incrementAndGet()
        runCatching { bridgeSubProcess?.destroyForcibly() }
        runCatching { activeProcess?.destroyForcibly() }
        activeJob?.cancel()
        activeJob = null
        bridgeSubProcess = null
        activeProcess = null
        activeStdin = null
        RenderForegroundService.stop(context)
        flushLogNow()
        _state.update {
            it.copy(
                isRunning = false,
                stageLabel = "Cancelled",
                progress = -1f,
                bannerMessage = "Execution cancelled.",
                bannerIsError = false
            )
        }
    }

    fun setPreviewArtifact(artifact: OutputArtifact) {
        _state.update { it.copy(activeArtifact = artifact) }
    }

    fun clearBanner() {
        _state.update { it.copy(bannerMessage = "", bannerIsError = false) }
    }

    fun clearConsole() {
        synchronized(logLock) { logBuffer.clear() }
        _consoleLog.value = ""
        _state.update { it.copy(diagnostics = emptyList(), lastErrorSummary = "") }
    }

    fun getConsoleTail(maxLines: Int = 120): String {
        val text = synchronized(logLock) { logBuffer.toString() }
        if (text.isEmpty()) return ""
        val lines = text.lines()
        return if (lines.size <= maxLines) text else lines.takeLast(maxLines).joinToString("\n")
    }

    fun renderManim(
        file: ProjectFileEntity,
        code: String,
        sceneName: String,
        preset: ManimPreset,
        customWidth: Int,
        customHeight: Int,
        customFps: Int,
        outputFormat: OutputFormat,
        renderMode: ManimRenderMode = ManimRenderMode.FULL,
        animRangeStart: Int = 0,
        animRangeEnd: Int = 3,
        disableCaching: Boolean,
        autoSaveToStorage: Boolean
    ) {
        launchTask(modeLabel = "Manim · $sceneName", initialStage = "Preparing scene...") { startMs ->
            onBeforeHeavyRender()
            val savedRev = workspaceRepo.saveFile(file, code, label = "Pre-render", triggerType = "EDIT")
            val effFormat = if (renderMode == ManimRenderMode.STILL) OutputFormat.PNG_LAST else outputFormat
            val w = when {
                renderMode == ManimRenderMode.DRAFT -> 854
                preset == ManimPreset.CUSTOM -> max(160, customWidth)
                else -> preset.width
            }
            val h = when {
                renderMode == ManimRenderMode.DRAFT -> 480
                preset == ManimPreset.CUSTOM -> max(160, customHeight)
                else -> preset.height
            }
            val fps = when {
                renderMode == ManimRenderMode.DRAFT -> 15
                preset == ManimPreset.CUSTOM -> max(1, min(120, customFps))
                else -> preset.fps
            }

            val mediaSubdir = File(runtimeManager.outputsDir, "manim").apply { mkdirs() }
            val outStem = "${sceneName}_${System.currentTimeMillis()}"
            val outFileName = "$outStem.${effFormat.ext}"

            val args = mutableListOf(
                "/opt/atelier-venv/bin/manim", "render",
                "--media_dir", "/outputs/manim",
                "-r", "$w,$h",
                "--fps", "$fps",
                "-o", outFileName
            )
            when (effFormat) {
                OutputFormat.MP4 -> args.addAll(listOf("--format", "mp4"))
                OutputFormat.GIF -> args.addAll(listOf("--format", "gif"))
                OutputFormat.PNG_LAST -> args.add("-s")
                OutputFormat.WEBM_ALPHA -> args.addAll(listOf("--format", "webm", "-t"))
            }
            if (renderMode == ManimRenderMode.RANGE) {
                args.addAll(listOf("-n", "$animRangeStart,$animRangeEnd"))
            }
            if (disableCaching) args.add("--disable_caching")
            args.add("/workspace/${file.relativePath}")
            if (sceneName.isNotBlank()) args.add(sceneName)
            crashReporter.lastCommand = args.joinToString(" ")

            RenderForegroundService.update(context, "Rendering $sceneName", "${w}x${h} · ${fps}fps", -1f)
            appendLog("$ manim render -r $w,$h --fps $fps ${file.relativePath} $sceneName")

            var lastNotifMs = 0L
            val (exitCode, fullLog) = runStreamingProcess(args) { line ->
                val lower = line.lowercase()
                val stage = when {
                    "writing" in lower && "tex" in lower -> "Compiling LaTeX..."
                    "combining" in lower -> "Encoding final video..."
                    "partial movie" in lower -> "Caching segment..."
                    else -> null
                }
                val animMatch = animProgressRegex.find(line)
                val pct = animMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: pctRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (stage != null || pct != null) {
                    val frac = pct?.let { min(0.98f, it / 100f) } ?: _state.value.progress
                    val label = stage ?: animMatch?.let { "Rendering Animation ${it.groupValues[1]} (${pct}%)" }
                        ?: _state.value.stageLabel
                    _state.update { it.copy(stageLabel = label, progress = frac) }
                    val now = System.currentTimeMillis()
                    if (now - lastNotifMs > 900L) {
                        RenderForegroundService.update(context, "Rendering $sceneName", label, frac)
                        lastNotifMs = now
                    }
                }
            }

            val diagnostics = DiagnosticParser.parse(fullLog, file.name)
            val elapsed = System.currentTimeMillis() - startMs

            if (exitCode != 0) {
                val failEntry = crashReporter.recordExecutionFailure(
                    mode = "Manim · $sceneName", fileName = file.relativePath,
                    command = args.joinToString(" "), exitCode = exitCode,
                    elapsedMs = elapsed, diagnostics = diagnostics, logTail = fullLog
                )
                val msg = diagnostics.firstOrNull()?.let { "L${it.line}: ${it.message}" } ?: "Render failed (${decodeExitSignal(exitCode)})"
                _state.update {
                    it.copy(
                        isRunning = false, stageLabel = "Render failed (exit $exitCode)", progress = -1f,
                        diagnostics = diagnostics, lastExitCode = exitCode, elapsedMs = elapsed,
                        bannerMessage = msg, bannerIsError = true, lastErrorSummary = msg, lastCrashReportPath = failEntry.filePath
                    )
                }
                return@launchTask
            }

            val produced = locateLatestOutput(mediaSubdir, outStem, effFormat.ext)
            if (produced == null || !produced.exists() || produced.length() == 0L) {
                val msg = "Manim exited cleanly but no ${effFormat.ext.uppercase()} output was found."
                val failEntry = crashReporter.recordExecutionFailure(
                    mode = "Manim · $sceneName", fileName = file.relativePath,
                    command = args.joinToString(" "), exitCode = -1,
                    elapsedMs = elapsed, diagnostics = diagnostics, logTail = fullLog
                )
                _state.update {
                    it.copy(
                        isRunning = false, stageLabel = "No output produced", progress = -1f,
                        lastExitCode = exitCode, elapsedMs = elapsed,
                        bannerMessage = msg, bannerIsError = true, lastErrorSummary = msg, lastCrashReportPath = failEntry.filePath
                    )
                }
                return@launchTask
            }

            val kind = when (effFormat) {
                OutputFormat.MP4, OutputFormat.WEBM_ALPHA -> OutputKind.VIDEO
                OutputFormat.GIF, OutputFormat.PNG_LAST -> OutputKind.IMAGE
            }
            val telemetry = if (kind == OutputKind.VIDEO) probeVideo(produced) else VideoTelemetry(
                width = w, height = h, fps = fps.toDouble(), sizeBytes = produced.length()
            )
            val snapshotPath = blobStore.snapshotArtifact(produced, "${file.name}_$sceneName")
            val exportRes = if (autoSaveToStorage) {
                if (kind == OutputKind.VIDEO) {
                    mediaExport.saveVideoToStorage(produced, "${file.name.substringBeforeLast('.')}_$sceneName")
                } else {
                    mediaExport.saveImageToStorage(produced, "${file.name.substringBeforeLast('.')}_$sceneName")
                }
            } else null

            val rev = workspaceRepo.saveFile(
                entity = file,
                content = code,
                label = "Render $sceneName (${preset.shortLabel})",
                triggerType = "RENDER_OK",
                outputPath = snapshotPath,
                outputType = kind.name,
                outputMeta = telemetry.summaryBadge()
            )

            dao.insertRenderHistory(
                RenderHistoryEntity(
                    fileId = file.id,
                    revisionId = rev?.id ?: (savedRev?.id ?: 0L),
                    sceneName = sceneName,
                    preset = "${w}x${h}@${fps}",
                    outputPath = snapshotPath,
                    outputType = kind.name,
                    exportedUri = exportRes?.uriString ?: "",
                    width = telemetry.width,
                    height = telemetry.height,
                    fps = telemetry.fps,
                    durationMs = telemetry.durationMs,
                    fileSizeBytes = produced.length(),
                    createdAt = System.currentTimeMillis()
                )
            )

            val artifact = OutputArtifact(
                path = snapshotPath,
                kind = kind,
                title = sceneName.ifBlank { file.name },
                telemetry = telemetry,
                exportedLocation = exportRes?.displayLocation ?: "",
                revisionId = rev?.id ?: 0L,
                timestamp = System.currentTimeMillis()
            )

            val banner = when {
                exportRes?.success == true -> "Saved to ${exportRes.displayLocation}"
                exportRes != null && !exportRes.success -> "Rendered (storage export failed: ${exportRes.error})"
                else -> "Render complete (${String.format("%.1fs", elapsed / 1000.0)})"
            }

            _state.update {
                it.copy(
                    isRunning = false,
                    stageLabel = "Complete",
                    progress = 1f,
                    diagnostics = diagnostics,
                    lastExitCode = 0,
                    elapsedMs = elapsed,
                    activeArtifact = artifact,
                    bannerMessage = banner
                )
            }
        }
    }

    fun runPython(file: ProjectFileEntity, code: String) {
        launchTask(modeLabel = "Python · ${file.name}", initialStage = "Executing script...") { startMs ->
            workspaceRepo.saveFile(file, code, label = "Run Python", triggerType = "EDIT")
            RenderForegroundService.update(context, "Running ${file.name}", "CPython 3.12", -1f)
            appendLog("$ python3 /workspace/${file.relativePath}")

            var latestImageHost: File? = null
            val (exitCode, fullLog) = runStreamingProcess(
                listOf("/opt/atelier-venv/bin/python3", "-u", "/usr/local/bin/atelier-run.py", "/workspace/${file.relativePath}")
            ) { line ->
                if (line.startsWith("__ATELIER_ARTIFACT__:IMAGE:")) {
                    val guestPath = line.removePrefix("__ATELIER_ARTIFACT__:IMAGE:").trim()
                    val hostFile = prootEngine.guestToHostPath(guestPath)
                    if (hostFile.exists() && hostFile.length() > 0L) {
                        latestImageHost = hostFile
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            val diagnostics = DiagnosticParser.parse(fullLog, file.name)
            var updatedArtifact = _state.value.activeArtifact

            latestImageHost?.let { img ->
                val snap = blobStore.snapshotArtifact(img, "${file.name}_plot")
                workspaceRepo.saveFile(
                    file, code,
                    label = "Python Plot",
                    triggerType = "RENDER_OK",
                    outputPath = snap,
                    outputType = OutputKind.IMAGE.name,
                    outputMeta = formatBytesShort(img.length())
                )
                updatedArtifact = OutputArtifact(
                    path = snap,
                    kind = OutputKind.IMAGE,
                    title = "${file.name} · Figure",
                    telemetry = VideoTelemetry(sizeBytes = img.length()),
                    timestamp = System.currentTimeMillis()
                )
            }

            _state.update {
                it.copy(
                    isRunning = false,
                    stageLabel = if (exitCode == 0) "Finished (${elapsed}ms)" else "Exited ($exitCode)",
                    progress = -1f,
                    diagnostics = diagnostics,
                    lastExitCode = exitCode,
                    elapsedMs = elapsed,
                    activeArtifact = updatedArtifact,
                    bannerMessage = if (exitCode == 0) {
                        if (latestImageHost != null) "Executed · Figure captured" else "Finished in ${elapsed}ms"
                    } else {
                        val failEntry = crashReporter.recordExecutionFailure("Python", file.relativePath, "python3 /workspace/${file.relativePath}", exitCode, elapsed, diagnostics, fullLog)
                        diagnostics.firstOrNull()?.let { "L${it.line}: ${it.message}" } ?: "Python failed (${decodeExitSignal(exitCode)}) · saved ${failEntry.fileName}"
                    },
                    bannerIsError = exitCode != 0,
                    lastErrorSummary = if (exitCode != 0) (diagnostics.firstOrNull()?.message ?: decodeExitSignal(exitCode)) else ""
                )
            }
        }
    }

    fun compilePython(file: ProjectFileEntity, code: String, nativeCython: Boolean) {
        launchTask(
            modeLabel = if (nativeCython) "Cython / GCC · ${file.name}" else "Compiler · ${file.name}",
            initialStage = if (nativeCython) "Compiling Cython + GCC -O3..." else "Analyzing AST & bytecode..."
        ) { startMs ->
            workspaceRepo.saveFile(file, code, label = "Compile Check", triggerType = "EDIT")
            val args = mutableListOf("/opt/atelier-venv/bin/python3", "/usr/local/bin/atelier-compile.py")
            if (nativeCython) args.add("--native")
            args.add("/workspace/${file.relativePath}")

            val (exitCode, fullLog) = runStreamingProcess(args)
            val elapsed = System.currentTimeMillis() - startMs
            val diagnostics = DiagnosticParser.parse(fullLog, file.name)
            _state.update {
                it.copy(
                    isRunning = false,
                    stageLabel = if (exitCode == 0) "Compiled cleanly" else "Compile error",
                    progress = -1f,
                    compilerReport = fullLog,
                    diagnostics = diagnostics,
                    lastExitCode = exitCode,
                    elapsedMs = elapsed,
                    bannerMessage = if (exitCode == 0) {
                        if (nativeCython) "Native ARM64 .so built (${elapsed}ms)" else "AST & Bytecode verified (${elapsed}ms)"
                    } else {
                        diagnostics.firstOrNull()?.message ?: "Compilation failed"
                    }
                )
            }
        }
    }

    fun compileLatex(file: ProjectFileEntity, code: String, autoSavePdf: Boolean) {
        launchTask(modeLabel = "LaTeX · ${file.name}", initialStage = "Running pdflatex (pass 1/2)...") { startMs ->
            workspaceRepo.saveFile(file, code, label = "Pre-compile TeX", triggerType = "EDIT")
            val texOutDir = File(runtimeManager.outputsDir, "tex").apply { mkdirs() }
            RenderForegroundService.update(context, "Compiling ${file.name}", "pdflatex", -1f)
            appendLog("$ pdflatex -interaction=nonstopmode -file-line-error /workspace/${file.relativePath}")

            val cmd = listOf(
                "/bin/bash", "-lc",
                "pdflatex -interaction=nonstopmode -file-line-error -output-directory=/outputs/tex \"/workspace/${file.relativePath}\" && " +
                    "pdflatex -interaction=nonstopmode -file-line-error -output-directory=/outputs/tex \"/workspace/${file.relativePath}\""
            )
            val (exitCode, fullLog) = runStreamingProcess(cmd)
            val elapsed = System.currentTimeMillis() - startMs
            val diagnostics = DiagnosticParser.parse(fullLog, file.name)

            val pdfName = "${file.name.substringBeforeLast('.')}.pdf"
            val pdfFile = File(texOutDir, pdfName)

            if (!pdfFile.exists() || pdfFile.length() == 0L) {
                val failEntry = crashReporter.recordExecutionFailure("LaTeX", file.relativePath, cmd.last(), exitCode, elapsed, diagnostics, fullLog)
                val msg = diagnostics.firstOrNull()?.let { "L${it.line}: ${it.message}" } ?: "pdflatex failed (${decodeExitSignal(exitCode)})"
                _state.update {
                    it.copy(
                        isRunning = false, stageLabel = "LaTeX compile failed", progress = -1f,
                        diagnostics = diagnostics, lastExitCode = exitCode, elapsedMs = elapsed,
                        bannerMessage = msg, bannerIsError = true, lastErrorSummary = msg, lastCrashReportPath = failEntry.filePath
                    )
                }
                return@launchTask
            }

            val snap = blobStore.snapshotArtifact(pdfFile, "${file.name}_tex")
            val exportRes = if (autoSavePdf) {
                mediaExport.saveDocumentToStorage(pdfFile, file.name.substringBeforeLast('.'), "application/pdf")
            } else null

            val rev = workspaceRepo.saveFile(
                entity = file,
                content = code,
                label = "LaTeX PDF Build",
                triggerType = "RENDER_OK",
                outputPath = snap,
                outputType = OutputKind.PDF.name,
                outputMeta = formatBytesShort(pdfFile.length())
            )

            val artifact = OutputArtifact(
                path = snap,
                kind = OutputKind.PDF,
                title = pdfName,
                telemetry = VideoTelemetry(sizeBytes = pdfFile.length()),
                exportedLocation = exportRes?.displayLocation ?: "",
                revisionId = rev?.id ?: 0L,
                timestamp = System.currentTimeMillis()
            )
            _state.update {
                it.copy(
                    isRunning = false,
                    stageLabel = "PDF ready",
                    progress = 1f,
                    diagnostics = diagnostics,
                    lastExitCode = exitCode,
                    elapsedMs = elapsed,
                    activeArtifact = artifact,
                    bannerMessage = if (exportRes?.success == true) "Saved to ${exportRes.displayLocation}" else "Compiled PDF (${elapsed}ms)"
                )
            }
        }
    }

    fun runPip(action: String, packageSpec: String) {
        val cleanPkg = packageSpec.trim()
        launchTask(modeLabel = "pip $action", initialStage = "Running pip $action...") { startMs ->
            val cmd = when (action) {
                "install" -> listOf(
                    "/opt/atelier-venv/bin/pip", "install",
                    "--target", "/user-site", "--upgrade", "--prefer-binary"
                ) + cleanPkg.split(Regex("\\s+")).filter { it.isNotBlank() }
                "uninstall" -> listOf("/opt/atelier-venv/bin/pip", "uninstall", "-y", cleanPkg)
                else -> listOf("/opt/atelier-venv/bin/pip", "list")
            }
            appendLog("$ ${cmd.joinToString(" ")}")
            val (exitCode, _) = runStreamingProcess(cmd)
            val elapsed = System.currentTimeMillis() - startMs
            _state.update {
                it.copy(
                    isRunning = false,
                    stageLabel = if (exitCode == 0) "pip completed" else "pip failed ($exitCode)",
                    progress = -1f,
                    lastExitCode = exitCode,
                    elapsedMs = elapsed,
                    bannerMessage = if (exitCode == 0) "pip $action finished" else "pip $action exited $exitCode"
                )
            }
        }
    }

    fun runControllerScript(code: String) {
        val txId = workspaceRepo.beginTransaction("AI Controller")
        _lastTransactionId.value = txId
        _controllerSteps.value = emptyList()
        launchTask(modeLabel = "AI Controller", initialStage = "Executing controller script...", isController = true) { startMs ->
            val scriptHost = File(runtimeManager.outputsDir, ".atelier_ctrl.py")
            scriptHost.writeText(code, Charsets.UTF_8)
            crashReporter.lastCommand = "python3 /outputs/.atelier_ctrl.py"
            RenderForegroundService.update(context, "AI Controller", "Running automation script...", -1f)
            appendLog("$ python3 /outputs/.atelier_ctrl.py")

            val (exitCode, fullLog) = runStreamingProcess(
                guestCommand = listOf("/opt/atelier-venv/bin/python3", "-u", "/outputs/.atelier_ctrl.py"),
                extraEnv = mapOf("ATELIER_CONTROLLER_MODE" to "1")
            ) { line ->
                if (line.startsWith("__ATELIER_CTRL_STEP__:")) {
                    runCatching {
                        val o = JSONObject(line.removePrefix("__ATELIER_CTRL_STEP__:"))
                        val step = ControllerStep(
                            id = stepIdGen.getAndIncrement(),
                            method = o.optString("method"),
                            status = o.optString("status", "ok"),
                            detail = o.optString("detail", ""),
                            timestampMs = System.currentTimeMillis()
                        )
                        _controllerSteps.update { cur ->
                            if (step.status != "running" && cur.isNotEmpty() && cur.last().method == step.method && cur.last().status == "running") {
                                (cur.dropLast(1) + step).takeLast(120)
                            } else {
                                (cur + step).takeLast(120)
                            }
                        }
                        _state.update { it.copy(stageLabel = "${step.method} (${step.status})") }
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            val diagnostics = DiagnosticParser.parse(fullLog, ".atelier_ctrl.py")
            val failEntry = if (exitCode != 0) {
                crashReporter.recordExecutionFailure("AI Controller", ".atelier_ctrl.py", "controller script", exitCode, elapsed, diagnostics, fullLog)
            } else null

            _state.update {
                it.copy(
                    isRunning = false,
                    stageLabel = if (exitCode == 0) "Controller done (${elapsed}ms)" else "Controller failed ($exitCode)",
                    progress = -1f,
                    diagnostics = diagnostics,
                    lastExitCode = exitCode,
                    elapsedMs = elapsed,
                    bannerMessage = if (exitCode == 0) "Controller finished (${elapsed}ms)" else (diagnostics.firstOrNull()?.message ?: "Controller exited $exitCode"),
                    bannerIsError = exitCode != 0,
                    lastErrorSummary = if (exitCode != 0) (diagnostics.firstOrNull()?.message ?: decodeExitSignal(exitCode)) else "",
                    lastCrashReportPath = failEntry?.filePath ?: ""
                )
            }
        }
    }

    suspend fun runManimBlockingForBridge(
        file: ProjectFileEntity,
        code: String,
        sceneName: String,
        preset: ManimPreset,
        customWidth: Int,
        customHeight: Int,
        customFps: Int,
        outputFormat: OutputFormat,
        renderMode: ManimRenderMode,
        animRangeStart: Int,
        animRangeEnd: Int,
        disableCaching: Boolean,
        autoSaveToStorage: Boolean
    ): JSONObject {
        val startMs = System.currentTimeMillis()
        onBeforeHeavyRender()
        val savedRev = workspaceRepo.saveFile(file, code, label = "AI Pre-render", triggerType = "AI_CTRL")
        val effFormat = if (renderMode == ManimRenderMode.STILL) OutputFormat.PNG_LAST else outputFormat
        val w = when { renderMode == ManimRenderMode.DRAFT -> 854; preset == ManimPreset.CUSTOM -> max(160, customWidth); else -> preset.width }
        val h = when { renderMode == ManimRenderMode.DRAFT -> 480; preset == ManimPreset.CUSTOM -> max(160, customHeight); else -> preset.height }
        val fps = when { renderMode == ManimRenderMode.DRAFT -> 15; preset == ManimPreset.CUSTOM -> max(1, min(120, customFps)); else -> preset.fps }
        val mediaSubdir = File(runtimeManager.outputsDir, "manim").apply { mkdirs() }
        val outStem = "${sceneName}_${System.currentTimeMillis()}"
        val outFileName = "$outStem.${effFormat.ext}"
        val args = mutableListOf("/opt/atelier-venv/bin/manim", "render", "--media_dir", "/outputs/manim", "-r", "$w,$h", "--fps", "$fps", "-o", outFileName)
        when (effFormat) {
            OutputFormat.MP4 -> args.addAll(listOf("--format", "mp4"))
            OutputFormat.GIF -> args.addAll(listOf("--format", "gif"))
            OutputFormat.PNG_LAST -> args.add("-s")
            OutputFormat.WEBM_ALPHA -> args.addAll(listOf("--format", "webm", "-t"))
        }
        if (renderMode == ManimRenderMode.RANGE) args.addAll(listOf("-n", "$animRangeStart,$animRangeEnd"))
        if (disableCaching) args.add("--disable_caching")
        args.add("/workspace/${file.relativePath}")
        if (sceneName.isNotBlank()) args.add(sceneName)

        appendLog("[Bridge] $ manim render -r $w,$h --fps $fps ${file.relativePath} $sceneName")
        val (exitCode, fullLog) = runBridgeSubProcess(args)
        val elapsed = System.currentTimeMillis() - startMs
        val diagnostics = DiagnosticParser.parse(fullLog, file.name)
        val diagJson = org.json.JSONArray().apply { diagnostics.forEach { put("L${it.line}: ${it.message}") } }

        if (exitCode != 0) {
            crashReporter.recordExecutionFailure("Bridge Manim · $sceneName", file.relativePath, args.joinToString(" "), exitCode, elapsed, diagnostics, fullLog)
            return JSONObject().put("exitCode", exitCode).put("elapsedMs", elapsed).put("diagnostics", diagJson).put("logTail", fullLog.takeLast(4000))
        }
        val produced = locateLatestOutput(mediaSubdir, outStem, effFormat.ext)
            ?: return JSONObject().put("exitCode", -1).put("elapsedMs", elapsed).put("error", "No output file found")
        val kind = if (effFormat == OutputFormat.MP4 || effFormat == OutputFormat.WEBM_ALPHA) OutputKind.VIDEO else OutputKind.IMAGE
        val telemetry = if (kind == OutputKind.VIDEO) probeVideo(produced) else VideoTelemetry(w, h, fps.toDouble(), sizeBytes = produced.length())
        val snap = blobStore.snapshotArtifact(produced, "${file.name}_$sceneName")
        val exp = if (autoSaveToStorage) {
            if (kind == OutputKind.VIDEO) mediaExport.saveVideoToStorage(produced, "${file.name.substringBeforeLast('.')}_$sceneName")
            else mediaExport.saveImageToStorage(produced, "${file.name.substringBeforeLast('.')}_$sceneName")
        } else null
        val rev = workspaceRepo.saveFile(file, code, label = "AI Render $sceneName", triggerType = "RENDER_OK", outputPath = snap, outputType = kind.name, outputMeta = telemetry.summaryBadge())
        dao.insertRenderHistory(RenderHistoryEntity(fileId = file.id, revisionId = rev?.id ?: (savedRev?.id ?: 0L), sceneName = sceneName, preset = "${w}x${h}@${fps}", outputPath = snap, outputType = kind.name, exportedUri = exp?.uriString ?: "", width = telemetry.width, height = telemetry.height, fps = telemetry.fps, durationMs = telemetry.durationMs, fileSizeBytes = produced.length(), createdAt = System.currentTimeMillis()))
        setPreviewArtifact(OutputArtifact(snap, kind, sceneName, telemetry, exp?.displayLocation ?: "", rev?.id ?: 0L, System.currentTimeMillis()))
        return JSONObject().apply {
            put("exitCode", 0); put("elapsedMs", elapsed); put("outputPath", snap)
            put("exportedLocation", exp?.displayLocation ?: ""); put("telemetry", telemetry.summaryBadge())
            put("diagnostics", diagJson); put("summary", "$sceneName · ${telemetry.summaryBadge()}")
        }
    }

    suspend fun compileLatexBlockingForBridge(file: ProjectFileEntity, code: String, autoSavePdf: Boolean): JSONObject {
        val startMs = System.currentTimeMillis()
        workspaceRepo.saveFile(file, code, label = "AI TeX", triggerType = "AI_CTRL")
        val texOutDir = File(runtimeManager.outputsDir, "tex").apply { mkdirs() }
        val cmd = listOf("/bin/bash", "-lc", "pdflatex -interaction=nonstopmode -file-line-error -output-directory=/outputs/tex '/workspace/${file.relativePath}' && pdflatex -interaction=nonstopmode -file-line-error -output-directory=/outputs/tex '/workspace/${file.relativePath}'")
        val (exitCode, fullLog) = runBridgeSubProcess(cmd)
        val elapsed = System.currentTimeMillis() - startMs
        val pdf = File(texOutDir, "${file.name.substringBeforeLast('.')}.pdf")
        if (!pdf.exists() || pdf.length() == 0L) {
            return JSONObject().put("exitCode", exitCode).put("elapsedMs", elapsed).put("logTail", fullLog.takeLast(4000))
        }
        val snap = blobStore.snapshotArtifact(pdf, "${file.name}_tex")
        val exp = if (autoSavePdf) mediaExport.saveDocumentToStorage(pdf, file.name.substringBeforeLast('.'), "application/pdf") else null
        setPreviewArtifact(OutputArtifact(snap, OutputKind.PDF, pdf.name, VideoTelemetry(sizeBytes = pdf.length()), exp?.displayLocation ?: "", 0L, System.currentTimeMillis()))
        return JSONObject().put("exitCode", 0).put("elapsedMs", elapsed).put("outputPath", snap).put("exportedLocation", exp?.displayLocation ?: "")
    }

    suspend fun runPythonBlockingForBridge(file: ProjectFileEntity, code: String): JSONObject {
        val startMs = System.currentTimeMillis()
        workspaceRepo.saveFile(file, code, label = "AI Run Python", triggerType = "AI_CTRL")
        val (exitCode, fullLog) = runBridgeSubProcess(listOf("/opt/atelier-venv/bin/python3", "-u", "/usr/local/bin/atelier-run.py", "/workspace/${file.relativePath}"))
        return JSONObject().put("exitCode", exitCode).put("elapsedMs", System.currentTimeMillis() - startMs).put("output", fullLog.takeLast(8000))
    }

    suspend fun compilePythonBlockingForBridge(file: ProjectFileEntity, code: String, nativeCython: Boolean): JSONObject {
        val startMs = System.currentTimeMillis()
        workspaceRepo.saveFile(file, code, label = "AI Compile", triggerType = "AI_CTRL")
        val args = mutableListOf("/opt/atelier-venv/bin/python3", "/usr/local/bin/atelier-compile.py")
        if (nativeCython) args.add("--native")
        args.add("/workspace/${file.relativePath}")
        val (exitCode, fullLog) = runBridgeSubProcess(args)
        return JSONObject().put("exitCode", exitCode).put("elapsedMs", System.currentTimeMillis() - startMs).put("report", fullLog.takeLast(8000))
    }

    fun runPipBlockingForBridge(action: String, pkgSpec: String): JSONObject {
        val cmd = if (action == "install") {
            listOf("/opt/atelier-venv/bin/pip", "install", "--target", "/user-site", "--upgrade", "--prefer-binary") + pkgSpec.split(Regex("\\s+")).filter { it.isNotBlank() }
        } else listOf("/opt/atelier-venv/bin/pip", "list")
        val (exitCode, out) = runBridgeSubProcess(cmd)
        return JSONObject().put("exitCode", exitCode).put("output", out.takeLast(8000))
    }

    private fun runBridgeSubProcess(guestCommand: List<String>): Pair<Int, String> {
        val (pb, tmpDir) = prootEngine.createProcessBuilder(guestCommand, mergeErrorStream = true)
        val full = StringBuilder()
        var proc: Process? = null
        return try {
            proc = pb.start()
            bridgeSubProcess = proc
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    full.appendLine(line)
                    if (!line.startsWith("__ATELIER_ARTIFACT__:")) appendLog(line)
                }
            }
            val exit = proc.waitFor()
            flushLogNow()
            exit to full.toString()
        } finally {
            runCatching { proc?.destroyForcibly() }
            runCatching { tmpDir.deleteRecursively() }
            if (bridgeSubProcess === proc) bridgeSubProcess = null
        }
    }

    fun runShellCommand(command: String) {
        val clean = command.trim()
        if (clean.isEmpty()) return
        launchTask(modeLabel = "Shell", initialStage = "Executing shell...") { startMs ->
            appendLog("$ $clean")
            val (exitCode, _) = runStreamingProcess(listOf("/bin/bash", "-lc", clean))
            val elapsed = System.currentTimeMillis() - startMs
            _state.update {
                it.copy(
                    isRunning = false,
                    stageLabel = "Exit $exitCode (${elapsed}ms)",
                    progress = -1f,
                    lastExitCode = exitCode,
                    elapsedMs = elapsed
                )
            }
        }
    }

    private fun launchTask(
        modeLabel: String,
        initialStage: String,
        isController: Boolean = false,
        block: suspend (startMs: Long) -> Unit
    ) {
        if (runtimeManager.state.value !is RuntimeState.Ready) {
            _state.update { it.copy(bannerMessage = "Install the offline runtime first to execute code.", bannerIsError = true) }
            return
        }
        val myRunId = runIdGen.incrementAndGet()
        scope.launch {
            taskMutex.withLock {
                val prev = activeJob
                if (prev != null && prev.isActive) {
                    runCatching { activeProcess?.destroyForcibly() }
                    runCatching { prev.cancelAndJoin() }
                }
                if (runIdGen.get() != myRunId) return@withLock

                activeJob = scope.launch {
                    val startMs = System.currentTimeMillis()
                    crashReporter.activeMode = modeLabel
                    synchronized(logLock) { logBuffer.clear() }
                    _consoleLog.value = ""
                    _state.update {
                        it.copy(
                            isRunning = true, modeLabel = modeLabel, stageLabel = initialStage,
                            progress = -1f, diagnostics = emptyList(), lastExitCode = null,
                            bannerMessage = "", bannerIsError = false, lastErrorSummary = "",
                            isControllerRun = isController
                        )
                    }
                    try {
                        block(startMs)
                    } catch (_: CancellationException) {
                        // Cancelled cleanly
                    } catch (t: Throwable) {
                        appendLog("ERROR: ${t.message}")
                        val entry = crashReporter.recordExecutionFailure(
                            mode = modeLabel, fileName = crashReporter.activeFilePath,
                            command = crashReporter.lastCommand, exitCode = -1,
                            elapsedMs = System.currentTimeMillis() - startMs,
                            diagnostics = emptyList(), logTail = "${t.stackTraceToString()}\n${getConsoleTail(80)}"
                        )
                        _state.update {
                            it.copy(
                                isRunning = false, stageLabel = "Failed", progress = -1f,
                                bannerMessage = t.message ?: "Execution failed.", bannerIsError = true,
                                lastErrorSummary = t.message ?: "Execution failed.", lastCrashReportPath = entry.filePath
                            )
                        }
                    } finally {
                        flushLogNow()
                        if (runIdGen.get() == myRunId) {
                            activeProcess = null
                            activeStdin = null
                            RenderForegroundService.stop(context)
                            crashReporter.activeMode = ""
                        }
                    }
                }
            }
        }
    }

    private fun runStreamingProcess(
        guestCommand: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {}
    ): Pair<Int, String> {
        var attempt = 0
        while (true) {
            attempt++
            val t0 = System.currentTimeMillis()
            val (exit, output) = runStreamingOnce(guestCommand, extraEnv, onLine)
            val dt = System.currentTimeMillis() - t0
            val isTransientColdSignal = attempt == 1 &&
                exit in listOf(134, 135, 139) &&
                dt < 3000L &&
                !output.contains("Traceback") &&
                output.length < 400
            if (isTransientColdSignal) {
                appendLog("[Atelier] Transient PRoot startup signal ($exit) detected; retrying cleanly...")
                Thread.sleep(220L)
                continue
            }
            return exit to output
        }
    }

    private fun runStreamingOnce(
        guestCommand: List<String>,
        extraEnv: Map<String, String>,
        onLine: (String) -> Unit
    ): Pair<Int, String> {
        val (pb, tmpDir) = prootEngine.createProcessBuilder(guestCommand, mergeErrorStream = true, extraEnv = extraEnv)
        val full = StringBuilder()
        var proc: Process? = null
        var stdinWriter: BufferedWriter? = null
        return try {
            proc = pb.start()
            activeProcess = proc
            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            activeStdin = stdinWriter
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    full.appendLine(line)
                    if (!line.startsWith("__ATELIER_ARTIFACT__:") && !line.startsWith("__ATELIER_CTRL_STEP__:")) {
                        appendLog(line)
                    }
                    onLine(line)
                }
            }
            val exit = proc.waitFor()
            flushLogNow()
            exit to full.toString()
        } finally {
            runCatching { stdinWriter?.close() }
            runCatching { proc?.destroyForcibly() }
            runCatching { tmpDir.deleteRecursively() }
            if (activeProcess === proc) activeProcess = null
            if (activeStdin === stdinWriter) activeStdin = null
        }
    }

    private fun appendLog(line: String) {
        val now = System.currentTimeMillis()
        var snapshot: String? = null
        synchronized(logLock) {
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(line)
            if (logBuffer.length > 52_000) {
                logBuffer.delete(0, logBuffer.length - 46_000)
            }
            if (now - lastLogFlushMs >= 120L) {
                lastLogFlushMs = now
                snapshot = logBuffer.toString()
            }
        }
        snapshot?.let { _consoleLog.value = it }
    }

    private fun flushLogNow() {
        val snap = synchronized(logLock) {
            lastLogFlushMs = System.currentTimeMillis()
            logBuffer.toString()
        }
        _consoleLog.value = snap
    }

    private fun locateLatestOutput(rootDir: File, stem: String, ext: String): File? {
        val candidates = rootDir.walkTopDown()
            .filter { it.isFile && it.extension.equals(ext, ignoreCase = true) && !it.absolutePath.contains("partial_movie_files") }
            .toList()
        return candidates.firstOrNull { it.name.startsWith(stem) }
            ?: candidates.maxByOrNull { it.lastModified() }
    }

    private fun probeVideo(videoFile: File): VideoTelemetry {
        val guestPath = if (videoFile.absolutePath.startsWith(runtimeManager.outputsDir.absolutePath)) {
            "/outputs/" + videoFile.absolutePath.removePrefix(runtimeManager.outputsDir.absolutePath).trimStart('/')
        } else null

        if (guestPath != null) {
            val (code, out) = prootEngine.runQuickProbe(
                listOf("/opt/atelier-venv/bin/python3", "/usr/local/bin/atelier-probe.py", guestPath)
            )
            if (code == 0) {
                runCatching {
                    val json = JSONObject(out.trim())
                    return VideoTelemetry(
                        width = json.optInt("width", 0),
                        height = json.optInt("height", 0),
                        fps = json.optDouble("fps", 30.0),
                        durationMs = json.optLong("durationMs", 0L),
                        codec = json.optString("codec", "H264"),
                        sizeBytes = videoFile.length()
                    )
                }
            }
        }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(videoFile.absolutePath)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var fps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
            if (fps <= 0.0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dur > 0L) {
                val frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                if (frames > 0L) fps = frames * 1000.0 / dur.toDouble()
            }
            VideoTelemetry(w, h, if (fps > 0.0) fps else 30.0, dur, "H264", videoFile.length())
        } catch (_: Throwable) {
            VideoTelemetry(sizeBytes = videoFile.length())
        } finally {
            runCatching { mmr.release() }
        }
    }
}
