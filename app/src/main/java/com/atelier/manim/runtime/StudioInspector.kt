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
    private val dao: AtelierDao
) {
    @Volatile var activeFileName: String = ""
    @Volatile var activeFilePath: String = ""
    @Volatile var activeFileLanguage: String = ""
    @Volatile var activeFileDirty: Boolean = false
    @Volatile var activeEditorText: String = ""
    @Volatile var activeCursorLine: Int = 1
    @Volatile var activeDetectedScenes: List<String> = emptyList()

    fun buildSettingsAndStatusMarkdown(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val batt = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val tempTenths = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val bLvl = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val bScale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val bPct = if (bLvl >= 0 && bScale > 0) (bLvl * 100) / bScale else -1
        val st = runtimeManager.state.value
        val userPkgs = runtimeManager.userSiteDir.listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(".dist-info") && !it.name.startsWith("__") && it.name != "bin" }
            ?.map { it.name }?.sorted() ?: emptyList()
        val setJson = settingsStore.toJson(runtimeManager.useNoSeccomp)

        return buildString {
            appendLine("# ATELIER STATUS & SETTINGS")
            appendLine("- Time: ${formatIso(System.currentTimeMillis())}")
            appendLine("- App: Atelier ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${context.packageName} · ${BuildConfig.GITHUB_REPO}")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · ${Runtime.getRuntime().availableProcessors()} cores")
            appendLine("- RAM: ${mi.availMem / 1048576L} MB free / ${mi.totalMem / 1048576L} MB total (app PSS ${Debug.getPss() / 1024L} MB, lowMem=${mi.lowMemory})")
            appendLine("- Battery: ${bPct}% · Temp: ${if (tempTenths > 0) "${tempTenths / 10.0} C" else "n/a"}")
            appendLine("- Storage: ${formatBytesShort(runtimeManager.freeStorageBytes())} free | workspace=${formatBytesShort(dirBytes(workspaceRepo.workspaceDir, 500))} | outputs=${formatBytesShort(dirBytes(runtimeManager.outputsDir, 500))} | userSite=${formatBytesShort(dirBytes(runtimeManager.userSiteDir, 1000))}")
            appendLine("\n## Runtime")
            appendLine("- State: ${st.javaClass.simpleName} · PRoot: ${runtimeManager.hasProotBinaries()} · NoSeccomp: ${runtimeManager.useNoSeccomp}")
            if (st is RuntimeState.Ready) {
                appendLine("- Toolchain: Python ${st.info.pythonVersion} · Manim ${st.info.manimVersion} · GCC ${st.info.gccVersion} · Rootfs ${formatBytesShort(st.info.rootfsSizeBytes)}")
            } else if (st is RuntimeState.Error) {
                appendLine("- Runtime Error: ${st.message}")
            }
            if (userPkgs.isNotEmpty()) appendLine("- /user-site packages (${userPkgs.size}): ${userPkgs.joinToString(", ")}")
            appendLine("\n## Settings")
            val keys = setJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                appendLine("- $k: ${setJson.opt(k)}")
            }
        }
    }

    fun buildFileTreeMarkdown(): String {
        val entities = runBlocking { dao.getAllFiles() }
        val byPath = entities.associateBy { it.relativePath }
        val disk = workspaceRepo.listDiskTree()
        val folders = disk.filter { it.isDirectory }.map { it.relativePath }
        val files = disk.filter { !it.isDirectory }
        val recentOut = runtimeManager.outputsDir.walkTopDown().maxDepth(4)
            .filter { it.isFile && !it.absolutePath.contains("partial_movie_files") }
            .sortedByDescending { it.lastModified() }.take(12).toList()

        return buildString {
            appendLine("# ATELIER WORKSPACE STRUCTURE (${files.size} files)")
            appendLine("- Active: ${activeFilePath.ifBlank { "none" }} (dirty=$activeFileDirty, cursorLine=$activeCursorLine)")
            if (folders.isNotEmpty()) appendLine("- Folders: ${folders.joinToString(", ")}")
            for (entry in files) {
                val f = File(workspaceRepo.workspaceDir, entry.relativePath)
                val ent = byPath[entry.relativePath]
                val text = if (entry.relativePath.endsWith(".py", true) && f.length() < 512_000L) {
                    runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                } else ""
                val scenes = if (text.isNotEmpty()) detectManimScenes(text) else emptyList()
                val lines = if (text.isNotEmpty()) text.count { it == '\n' } + 1 else if (entry.relativePath.endsWith(".tex", true) && f.length() < 512_000L) runCatching { f.readLines().size }.getOrDefault(0) else 0
                val mark = if (entry.relativePath == activeFilePath) "*" else "-"
                val sc = if (scenes.isNotEmpty()) " | scenes=${scenes.joinToString(",")}" else ""
                val out = ent?.lastOutputMeta?.takeIf { it.isNotBlank() }?.let { " | out=$it" } ?: ""
                appendLine("$mark ${entry.relativePath} (${ent?.language ?: workspaceRepo.inferLanguageForPath(entry.relativePath)}, ${lines}L, ${formatBytesShort(f.length())}$sc$out)")
            }
            if (recentOut.isNotEmpty()) {
                appendLine("\n## Recent /outputs Artifacts")
                for (o in recentOut) {
                    appendLine("- /outputs/${o.relativeTo(runtimeManager.outputsDir).path} (${formatBytesShort(o.length())}, ${formatIso(o.lastModified())})")
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
            if (logTail.isNotBlank()) {
                appendLine("\n## Console Log Tail\n```\n$logTail\n```")
            }
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
