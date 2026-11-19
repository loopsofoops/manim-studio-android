package com.atelier.manim.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import com.atelier.manim.BuildConfig
import com.atelier.manim.data.AtelierDao
import com.atelier.manim.data.WorkspaceRepository
import com.atelier.manim.data.detectManimScenes
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudioInspector(
    private val context: Context,
    private val runtimeManager: RuntimeManager,
    private val settingsStore: StudioSettingsStore,
    private val workspaceRepo: WorkspaceRepository,
    private val coordinator: ExecutionCoordinator,
    private val crashReporter: CrashReporter,
    private val dao: AtelierDao,
    private val bridgePortProvider: () -> Int = { 0 }
) {
    @Volatile var activeFileName: String = ""
    @Volatile var activeFilePath: String = ""
    @Volatile var activeFileLanguage: String = ""
    @Volatile var activeFileDirty: Boolean = false
    @Volatile var activeEditorText: String = ""
    @Volatile var activeCursorLine: Int = 1
    @Volatile var activeDetectedScenes: List<String> = emptyList()

    fun getHardwareAndAppStatusJson(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val batt = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val tempTenths = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val bLvl = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val bScale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val bPct = if (bLvl >= 0 && bScale > 0) (bLvl * 100) / bScale else -1
        val rtBytes = (runtimeManager.state.value as? RuntimeState.Ready)?.info?.rootfsSizeBytes ?: 0L
        return JSONObject().apply {
            put("timestamp", formatIso(System.currentTimeMillis()))
            put("appName", "Atelier")
            put("packageName", context.packageName)
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("githubRepo", BuildConfig.GITHUB_REPO)
            put("runtimeTag", BuildConfig.RUNTIME_TAG)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("cpuCores", Runtime.getRuntime().availableProcessors())
            put("ramAvailableMb", mi.availMem / 1048576L)
            put("ramTotalMb", mi.totalMem / 1048576L)
            put("ramLowMemory", mi.lowMemory)
            put("appPssMb", Debug.getPss() / 1024L)
            put("batteryPercent", bPct)
            put("batteryTempC", if (tempTenths > 0) tempTenths / 10.0 else JSONObject.NULL)
            put("freeStorageBytes", runtimeManager.freeStorageBytes())
            put("bridgePort", bridgePortProvider())
            put("storageBreakdown", JSONObject().apply {
                put("rootfsBytes", rtBytes)
                put("workspaceBytes", dirBytes(workspaceRepo.workspaceDir, 500))
                put("outputsBytes", dirBytes(runtimeManager.outputsDir, 500))
                put("userSiteBytes", dirBytes(runtimeManager.userSiteDir, 1000))
                put("crashReportsBytes", dirBytes(crashReporter.reportsDir, 100))
            })
        }
    }

    fun getRuntimeStatusJson(): JSONObject {
        val st = runtimeManager.state.value
        val userPkgs = runtimeManager.userSiteDir.listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(".dist-info") && !it.name.startsWith("__") && it.name != "bin" }
            ?.map { it.name }?.sorted() ?: emptyList()
        return JSONObject().apply {
            put("state", st.javaClass.simpleName)
            put("prootPresent", runtimeManager.hasProotBinaries())
            put("useNoSeccomp", runtimeManager.useNoSeccomp)
            put("computeThreads", settingsStore.computeProfile.value.threads)
            put("activeManifestUrl", runtimeManager.activeManifestUrl())
            put("sdkDeployed", File(runtimeManager.sdkDir, "atelier/__init__.py").exists())
            when (st) {
                is RuntimeState.Ready -> {
                    put("version", st.info.version)
                    put("pythonVersion", st.info.pythonVersion)
                    put("manimVersion", st.info.manimVersion)
                    put("gccVersion", st.info.gccVersion)
                    put("texVersion", st.info.texVersion)
                    put("rootfsSizeBytes", st.info.rootfsSizeBytes)
                }
                is RuntimeState.Error -> put("error", st.message)
                is RuntimeState.NotInstalled -> put("statusMessage", st.statusMessage)
                else -> {}
            }
            put("userSitePackages", JSONArray(userPkgs))
        }
    }

    fun getSettingsJson(): JSONObject = settingsStore.toJson(runtimeManager.useNoSeccomp)

    fun getFileTreeJson(includeScenes: Boolean = true): JSONObject {
        val entities = runBlocking { dao.getAllFiles() }
        val byPath = entities.associateBy { it.relativePath }
        val disk = workspaceRepo.listDiskTree()
        val foldersArr = JSONArray()
        val filesArr = JSONArray()
        for (entry in disk) {
            if (entry.isDirectory) { foldersArr.put(entry.relativePath); continue }
            val f = File(workspaceRepo.workspaceDir, entry.relativePath)
            val ent = byPath[entry.relativePath]
            val text = if (includeScenes && entry.relativePath.endsWith(".py", true) && f.length() < 512_000L) {
                runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
            } else ""
            val scenes = if (text.isNotEmpty()) detectManimScenes(text) else emptyList()
            val lines = if (text.isNotEmpty()) text.count { it == '\n' } + 1 else if (entry.relativePath.endsWith(".tex", true) && f.length() < 512_000L) runCatching { f.readLines().size }.getOrDefault(0) else 0
            filesArr.put(JSONObject().apply {
                put("path", entry.relativePath)
                put("name", f.name)
                put("language", ent?.language ?: workspaceRepo.inferLanguageForPath(entry.relativePath))
                put("sizeBytes", f.length())
                put("lineCount", lines)
                put("modified", formatIso(f.lastModified()))
                put("isActive", entry.relativePath == activeFilePath)
                if (scenes.isNotEmpty()) put("scenes", JSONArray(scenes))
                if (ent != null && ent.lastOutputMeta.isNotBlank()) put("lastOutputMeta", ent.lastOutputMeta)
            })
        }
        val outputsArr = JSONArray()
        runtimeManager.outputsDir.walkTopDown().maxDepth(4)
            .filter { it.isFile && !it.absolutePath.contains("partial_movie_files") }
            .sortedByDescending { it.lastModified() }.take(15).forEach { o ->
                outputsArr.put(JSONObject().put("path", "/outputs/${o.relativeTo(runtimeManager.outputsDir).path}").put("sizeBytes", o.length()).put("modified", formatIso(o.lastModified())))
            }
        return JSONObject().apply {
            put("workspaceRoot", "/workspace")
            put("activeFilePath", activeFilePath)
            put("folders", foldersArr)
            put("fileCount", filesArr.length())
            put("files", filesArr)
            put("recentOutputs", outputsArr)
        }
    }

    fun getActiveFileJson(includeContent: Boolean = true): JSONObject = JSONObject().apply {
        put("name", activeFileName)
        put("relativePath", activeFilePath)
        put("language", activeFileLanguage)
        put("isDirty", activeFileDirty)
        put("cursorLine", activeCursorLine)
        put("lineCount", if (activeEditorText.isEmpty()) 0 else activeEditorText.count { it == '\n' } + 1)
        put("detectedScenes", JSONArray(activeDetectedScenes))
        put("selectedScene", settingsStore.selectedScene.value)
        if (includeContent) put("content", activeEditorText)
    }

    fun getExecutionJson(): JSONObject {
        val ex = coordinator.state.value
        val diags = JSONArray()
        ex.diagnostics.forEach { d -> diags.put(JSONObject().put("line", d.line).put("severity", d.severity.name).put("source", d.source).put("message", d.message)) }
        return JSONObject().apply {
            put("isRunning", ex.isRunning)
            put("isControllerRun", ex.isControllerRun)
            put("modeLabel", ex.modeLabel)
            put("stageLabel", ex.stageLabel)
            put("lastExitCode", ex.lastExitCode ?: JSONObject.NULL)
            put("lastExitSignal", ex.lastExitCode?.let { decodeExitSignal(it) } ?: "")
            put("elapsedMs", ex.elapsedMs)
            put("bannerMessage", ex.bannerMessage)
            put("lastErrorSummary", ex.lastErrorSummary)
            put("diagnostics", diags)
            put("activeArtifact", JSONObject().apply {
                put("kind", ex.activeArtifact.kind.name)
                put("title", ex.activeArtifact.title)
                put("path", ex.activeArtifact.path)
                put("telemetry", ex.activeArtifact.telemetry.summaryBadge())
                put("exportedLocation", ex.activeArtifact.exportedLocation)
            })
        }
    }

    fun getCrashReportsJson(limit: Int = 5, includeBody: Boolean = false): JSONObject {
        val arr = JSONArray()
        crashReporter.reports.value.take(limit).forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("kind", r.kind); put("title", r.title); put("summary", r.summary)
                put("timestamp", formatIso(r.timestampMs)); put("filePath", r.filePath)
                if (includeBody) put("body", crashReporter.readReportBody(r).take(6000))
            })
        }
        return JSONObject().put("reportCount", crashReporter.reports.value.size).put("reports", arr).put("systemExitReasons", crashReporter.collectSystemExitReasonsJson())
    }

    fun getRenderHistoryJson(limit: Int = 20): JSONArray {
        val arr = JSONArray()
        runBlocking { dao.getRecentRenderHistory(limit) }.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("sceneName", r.sceneName); put("preset", r.preset); put("outputType", r.outputType)
                put("width", r.width); put("height", r.height); put("fps", r.fps); put("durationMs", r.durationMs)
                put("fileSizeBytes", r.fileSizeBytes); put("createdAt", formatIso(r.createdAt))
            })
        }
        return arr
    }

    fun getFullSnapshotJson(): JSONObject = JSONObject().apply {
        put("app", getHardwareAndAppStatusJson())
        put("runtime", getRuntimeStatusJson())
        put("settings", getSettingsJson())
        put("activeFile", getActiveFileJson(false))
        put("fileTree", getFileTreeJson(true))
        put("execution", getExecutionJson())
        put("consoleTail", coordinator.getConsoleTail(80))
        put("crashes", getCrashReportsJson(5, false))
        put("renderHistory", getRenderHistoryJson(10))
    }

    fun buildSettingsAndStatusMarkdown(): String {
        val app = getHardwareAndAppStatusJson()
        val rt = getRuntimeStatusJson()
        val set = getSettingsJson()
        val stg = app.getJSONObject("storageBreakdown")
        return buildString {
            appendLine("# ATELIER STATUS & SETTINGS")
            appendLine("- Time: ${app.optString("timestamp")}")
            appendLine("- App: Atelier ${app.optString("versionName")} (${app.optInt("versionCode")}) · Bridge :${app.optInt("bridgePort")}")
            appendLine("- Device: ${app.optString("device")} · Android ${app.optString("androidRelease")} (API ${app.optInt("sdkInt")}) · ${app.optString("abi")} · ${app.optInt("cpuCores")} cores")
            appendLine("- RAM: ${app.optLong("ramAvailableMb")} MB free / ${app.optLong("ramTotalMb")} MB total (app PSS ${app.optLong("appPssMb")} MB, lowMem=${app.optBoolean("ramLowMemory")})")
            appendLine("- Battery: ${app.optInt("batteryPercent")}% · Temp: ${if (app.isNull("batteryTempC")) "n/a" else "${app.optDouble("batteryTempC")} C"}")
            appendLine("- Storage: ${formatBytesShort(app.optLong("freeStorageBytes"))} free | workspace=${formatBytesShort(stg.optLong("workspaceBytes"))} | outputs=${formatBytesShort(stg.optLong("outputsBytes"))} | userSite=${formatBytesShort(stg.optLong("userSiteBytes"))}")
            appendLine("\n## Runtime")
            appendLine("- State: ${rt.optString("state")} · PRoot: ${rt.optBoolean("prootPresent")} · NoSeccomp: ${rt.optBoolean("useNoSeccomp")} · SDK: ${rt.optBoolean("sdkDeployed")}")
            if (rt.has("pythonVersion")) appendLine("- Toolchain: Python ${rt.optString("pythonVersion")} · Manim ${rt.optString("manimVersion")} · GCC ${rt.optString("gccVersion")}")
            val pkgs = rt.optJSONArray("userSitePackages")
            if (pkgs != null && pkgs.length() > 0) appendLine("- /user-site (${pkgs.length()}): ${(0 until pkgs.length()).map { pkgs.getString(it) }.joinToString(", ")}")
            appendLine("\n## Settings")
            val keys = set.keys()
            while (keys.hasNext()) { val k = keys.next(); appendLine("- $k: ${set.opt(k)}") }
        }
    }

    fun buildFileTreeMarkdown(): String {
        val tree = getFileTreeJson(true)
        val files = tree.getJSONArray("files")
        val folders = tree.getJSONArray("folders")
        val outputs = tree.getJSONArray("recentOutputs")
        return buildString {
            appendLine("# ATELIER WORKSPACE STRUCTURE (${files.length()} files)")
            appendLine("- Active: ${activeFilePath.ifBlank { "none" }} (dirty=$activeFileDirty, cursorLine=$activeCursorLine)")
            if (folders.length() > 0) appendLine("- Folders: ${(0 until folders.length()).map { folders.getString(it) }.joinToString(", ")}")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val mark = if (f.optBoolean("isActive")) "*" else "-"
                val scArr = f.optJSONArray("scenes")
                val sc = if (scArr != null && scArr.length() > 0) " | scenes=${(0 until scArr.length()).map { scArr.getString(it) }.joinToString(",")}" else ""
                val out = f.optString("lastOutputMeta").takeIf { it.isNotBlank() }?.let { " | out=$it" } ?: ""
                appendLine("$mark ${f.optString("path")} (${f.optString("language")}, ${f.optInt("lineCount")}L, ${formatBytesShort(f.optLong("sizeBytes"))}$sc$out)")
            }
            if (outputs.length() > 0) {
                appendLine("\n## Recent /outputs Artifacts")
                for (i in 0 until outputs.length()) {
                    val o = outputs.getJSONObject(i)
                    appendLine("- ${o.optString("path")} (${formatBytesShort(o.optLong("sizeBytes"))}, ${o.optString("modified")})")
                }
            }
        }
    }

    fun buildActiveFileAndErrorsMarkdown(): String {
        val ex = coordinator.state.value
        val logTail = coordinator.getConsoleTail(120)
        return buildString {
            appendLine("# ATELIER ACTIVE FILE & DIAGNOSTICS")
            appendLine("- File: $activeFilePath ($activeFileLanguage, dirty=$activeFileDirty, cursorLine=$activeCursorLine)")
            appendLine("- Scenes: ${activeDetectedScenes.joinToString(", ").ifBlank { "none" }} | Selected: ${settingsStore.selectedScene.value}")
            appendLine("- Last Run: ${ex.modeLabel.ifBlank { "none" }} | Stage: ${ex.stageLabel} | Exit: ${ex.lastExitCode ?: "n/a"} ${ex.lastExitCode?.let { "(${decodeExitSignal(it)})" } ?: ""} | ${ex.elapsedMs}ms")
            if (ex.diagnostics.isNotEmpty()) {
                appendLine("\n## Diagnostics (${ex.diagnostics.size})")
                ex.diagnostics.forEach { d -> appendLine("- [${d.severity}] ${d.source} L${d.line}: ${d.message}") }
            }
            if (logTail.isNotBlank()) appendLine("\n## Console Log Tail\n```\n$logTail\n```")
            appendLine("\n## Source ($activeFilePath)\n```")
            activeEditorText.lines().forEachIndexed { i, ln -> appendLine(String.format(Locale.US, "%4d | %s", i + 1, ln)) }
            appendLine("```")
        }
    }

    fun buildFullSnapshotMarkdown(): String {
        val ex = coordinator.state.value
        val crashes = crashReporter.reports.value.take(5)
        val exits = crashReporter.collectSystemExitReasonsJson()
        val renders = runBlocking { dao.getRecentRenderHistory(8) }
        val logTail = coordinator.getConsoleTail(80)
        return buildString {
            appendLine(buildSettingsAndStatusMarkdown().trimEnd())
            appendLine()
            appendLine(buildFileTreeMarkdown().trimEnd())
            appendLine("\n## Execution & Output")
            appendLine("- Running: ${ex.isRunning} | Mode: ${ex.modeLabel.ifBlank { "idle" }} | Stage: ${ex.stageLabel.ifBlank { "idle" }}")
            appendLine("- Exit: ${ex.lastExitCode ?: "n/a"} ${ex.lastExitCode?.let { "(${decodeExitSignal(it)})" } ?: ""} | Elapsed: ${ex.elapsedMs}ms")
            appendLine("- Active Output: ${ex.activeArtifact.kind} ${ex.activeArtifact.title} (${ex.activeArtifact.telemetry.summaryBadge()})")
            if (ex.diagnostics.isNotEmpty()) ex.diagnostics.forEach { d -> appendLine("- [${d.severity}] ${d.source} L${d.line}: ${d.message}") }
            if (logTail.isNotBlank()) appendLine("```\n${logTail.takeLast(3500)}\n```")
            if (renders.isNotEmpty()) {
                appendLine("\n## Recent Renders (${renders.size})")
                renders.forEach { r -> appendLine("- ${formatIso(r.createdAt)} · ${r.sceneName} · ${r.preset} · ${formatBytesShort(r.fileSizeBytes)}") }
            }
            appendLine("\n## Crash & Failure Reports (${crashReporter.reports.value.size})")
            if (crashes.isEmpty()) appendLine("- None recorded.")
            else crashes.forEach { c -> appendLine("- [${c.kind}] ${formatIso(c.timestampMs)} · ${c.title}: ${c.summary}") }
            if (exits.length() > 0) {
                appendLine("\n## Recent OS Exit Reasons")
                for (i in 0 until minOf(4, exits.length())) {
                    val o = exits.getJSONObject(i)
                    appendLine("- ${o.optString("timestamp")} · ${o.optString("reason")} (status=${o.optInt("status")}, PSS=${o.optLong("pssMb")}MB): ${o.optString("description")}")
                }
            }
        }
    }

    fun buildSdkSchemaMarkdown(): String = buildString {
        appendLine("# ATELIER AI CONTROLLER SDK (`import atelier`)")
        appendLine("Paste pure Python into the Control tab and tap Paste & Run. Every file edit is wrapped in an atomic rollback transaction.")
        appendLine("\n## Introspection")
        appendLine("- `atelier.inspect.full_snapshot(format='markdown'|'json')`")
        appendLine("- `atelier.inspect.status()` / `runtime()` / `settings()` / `execution()`")
        appendLine("- `atelier.inspect.file_tree(include_scenes=True)` / `active_file(include_content=True)`")
        appendLine("- `atelier.inspect.console_log(tail=200)` / `crash_reports(limit=5, include_body=True)` / `render_history(limit=20)`")
        appendLine("\n## Files & Folders (relative to /workspace)")
        appendLine("- `atelier.files.list(folder='')` / `read(path)` / `write(path, content, label='AI Controller')`")
        appendLine("- `atelier.files.create(path, content='', language='AUTO')` / `patch(path, old, new, count=1)`")
        appendLine("- `atelier.files.mkdir(path)` / `rename(old_path, new_path)` / `delete(path)` / `open(path)` / `sync_disk()`")
        appendLine("- `atelier.files.snapshot(path, label)` / `revisions(path)` / `restore_revision(path, revision_id)`")
        appendLine("\n## Settings & Execution")
        appendLine("- `atelier.settings.get()`")
        appendLine("- `atelier.settings.set(preset='LOW_480P'|'MED_720P'|'HIGH_1080P'|'QHD_1440P'|'UHD_4K'|'VERTICAL_1080'|'SQUARE_1080'|'CUSTOM', output_format='MP4'|'GIF'|'PNG_LAST'|'WEBM_ALPHA', render_mode='FULL'|'DRAFT'|'STILL'|'RANGE', anim_range_start=0, anim_range_end=3, disable_cache=False, auto_save_storage=True, auto_export_crash_reports=True, dark_theme=True, font_size=13, compute_profile='ECO_2T'|'BALANCED_4T'|'MAX_6T', studio_mode='CODE'|'CONTROL')`")
        appendLine("- `atelier.manim.detect_scenes(path)`")
        appendLine("- `atelier.manim.render(path, scene='', preset='', output_format='', render_mode='', anim_range='', disable_cache=None, auto_save=None)`")
        appendLine("- `atelier.manim.render_draft(path, scene='')` / `render_still(path, scene='')` / `render_range(path, scene, start, end)`")
        appendLine("- `atelier.latex.compile(path, auto_save=None)` / `atelier.python.run(path)` / `atelier.python.compile(path, native=False)`")
        appendLine("- `atelier.pip.install(packages)` / `atelier.pip.list()`")
        appendLine("\n## App, Clipboard, Macros & Rollback")
        appendLine("- `atelier.app.switch_mode('CODE'|'CONTROL')` / `toast(msg)` / `notify(title, msg, copy_text='')`")
        appendLine("- `atelier.app.clipboard_set(text)` / `clipboard_get()` / `export_artifact()`")
        appendLine("- `atelier.app.macros_list()` / `macros_save(name, code, description='')` / `macros_delete(id_or_name)`")
        appendLine("- `atelier.tx.begin(label)` / `atelier.tx.rollback(tx_id='')`")
        appendLine()
        appendLine(buildFileTreeMarkdown().trimEnd())
    }

    private fun dirBytes(dir: File, maxFiles: Int): Long {
        if (!dir.exists()) return 0L
        var total = 0L; var c = 0
        for (f in dir.walkTopDown()) {
            if (f.isFile) { total += f.length(); if (++c >= maxFiles) break }
        }
        return total
    }

    private fun formatIso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
