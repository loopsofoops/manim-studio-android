package com.atelier.manim.runtime

enum class DiagnosticSeverity { ERROR, WARNING }

data class DiagnosticItem(
    val line: Int,
    val message: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val source: String = ""
)

object DiagnosticParser {
    private val pyTraceRegex = Regex("""File "([^"]+)", line (\d+)""")
    private val pyErrorLineRegex = Regex("""^([A-Za-z_][A-Za-z0-9_]*(?:Error|Exception|Warning)):\s*(.+)$""")
    private val texFileLineRegex = Regex("""^[^:\n]+\.tex:(\d+):\s*(.+)$""")
    private val texClassicLineRegex = Regex("""^l\.(\d+)\s*(.*)$""")

    fun parse(logText: String, targetFileName: String): List<DiagnosticItem> {
        val items = mutableListOf<DiagnosticItem>()
        var pendingPyLine: Int? = null
        var pendingTexError: String? = null

        for (raw in logText.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val pyMatch = pyTraceRegex.find(line)
            if (pyMatch != null) {
                val path = pyMatch.groupValues[1]
                val lineNum = pyMatch.groupValues[2].toIntOrNull()
                if (lineNum != null && (path.endsWith(targetFileName) || path.contains("/workspace/"))) {
                    pendingPyLine = lineNum
                }
                continue
            }

            val pyErr = pyErrorLineRegex.find(line)
            if (pyErr != null) {
                items.add(
                    DiagnosticItem(
                        line = pendingPyLine ?: 1,
                        message = "${pyErr.groupValues[1]}: ${pyErr.groupValues[2]}",
                        severity = if (pyErr.groupValues[1].endsWith("Warning")) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR,
                        source = "Python"
                    )
                )
                pendingPyLine = null
                continue
            }

            val texMatch = texFileLineRegex.find(line)
            if (texMatch != null) {
                val lineNum = texMatch.groupValues[1].toIntOrNull() ?: 1
                items.add(DiagnosticItem(line = lineNum, message = texMatch.groupValues[2].trim(), source = "LaTeX"))
                continue
            }

            if (line.startsWith("! ")) {
                pendingTexError = line.removePrefix("! ").trim()
                continue
            }

            val texClassic = texClassicLineRegex.find(line)
            if (texClassic != null && pendingTexError != null) {
                val lineNum = texClassic.groupValues[1].toIntOrNull() ?: 1
                items.add(DiagnosticItem(line = lineNum, message = pendingTexError, source = "LaTeX"))
                pendingTexError = null
            }
        }
        return items.distinctBy { "${it.line}:${it.message}" }
    }
}

data class RuntimeManifest(
    val version: String,
    val sha256: String,
    val compressedBytes: Long,
    val estimatedExtractedBytes: Long,
    val downloadUrl: String,
    val pythonVersion: String,
    val manimVersion: String,
    val gccVersion: String,
    val texVersion: String
)

data class RuntimeInfo(
    val version: String,
    val pythonVersion: String,
    val manimVersion: String,
    val gccVersion: String,
    val texVersion: String,
    val useNoSeccomp: Boolean,
    val rootfsSizeBytes: Long
)

sealed class RuntimeState {
    data object Checking : RuntimeState()
    data class NotInstalled(
        val freeBytes: Long,
        val requiredBytes: Long,
        val manifest: RuntimeManifest?,
        val prootPresent: Boolean,
        val statusMessage: String = ""
    ) : RuntimeState()
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) : RuntimeState()
    data class Verifying(val progress: Float) : RuntimeState()
    data class Extracting(
        val extractedFiles: Int,
        val currentEntry: String,
        val progress: Float
    ) : RuntimeState()
    data class SelfTesting(val step: String) : RuntimeState()
    data class Ready(val info: RuntimeInfo) : RuntimeState()
    data class Error(val message: String) : RuntimeState()
}

enum class OutputKind { NONE, VIDEO, IMAGE, PDF }

data class VideoTelemetry(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 30.0,
    val durationMs: Long = 0L,
    val codec: String = "",
    val sizeBytes: Long = 0L
) {
    fun summaryBadge(): String {
        val parts = mutableListOf<String>()
        if (width > 0 && height > 0) parts.add("${width}x${height}")
        if (fps > 0.0) parts.add("${if (fps % 1.0 == 0.0) fps.toInt().toString() else String.format("%.1f", fps)}fps")
        if (codec.isNotEmpty()) parts.add(codec)
        if (sizeBytes > 0L) parts.add(formatBytesShort(sizeBytes))
        return parts.joinToString(" · ")
    }
}

data class OutputArtifact(
    val path: String = "",
    val kind: OutputKind = OutputKind.NONE,
    val title: String = "",
    val telemetry: VideoTelemetry = VideoTelemetry(),
    val exportedLocation: String = "",
    val revisionId: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ManimPreset(
    val label: String,
    val shortLabel: String,
    val width: Int,
    val height: Int,
    val fps: Int
) {
    LOW_480P("480p · 15 fps (Draft)", "480p15", 854, 480, 15),
    MED_720P("720p · 30 fps (Standard)", "720p30", 1280, 720, 30),
    HIGH_1080P("1080p · 60 fps (Master)", "1080p60", 1920, 1080, 60),
    QHD_1440P("1440p · 60 fps (Studio)", "1440p60", 2560, 1440, 60),
    UHD_4K("2160p · 60 fps (4K)", "4K60", 3840, 2160, 60),
    VERTICAL_1080("1080x1920 · 60 fps (9:16)", "9:16", 1080, 1920, 60),
    SQUARE_1080("1080x1080 · 60 fps (1:1)", "1:1", 1080, 1080, 60),
    CUSTOM("Custom Resolution", "Custom", 1280, 720, 30)
}

enum class OutputFormat(val label: String, val ext: String) {
    MP4("MP4 (H.264)", "mp4"),
    GIF("Animated GIF", "gif"),
    PNG_LAST("Last Frame PNG", "png"),
    WEBM_ALPHA("Transparent WebM", "webm")
}

enum class ManimRenderMode(val label: String, val shortLabel: String) {
    FULL("Full Scene", "Full"),
    DRAFT("Fast Draft (480p15)", "Draft"),
    STILL("Last Frame PNG (-s)", "Still"),
    RANGE("Animation Range (-n)", "Range")
}

enum class ComputeProfile(val label: String, val shortLabel: String, val threads: Int) {
    ECO_2T("Eco (2 Threads)", "2T", 2),
    BALANCED_4T("Balanced (4 Threads)", "4T", 4),
    MAX_6T("Max (6 Threads)", "6T", 6)
}

data class CrashReportEntry(
    val id: String,
    val fileName: String,
    val filePath: String,
    val kind: String,
    val title: String,
    val summary: String,
    val timestampMs: Long,
    val exportedLocation: String = ""
)

fun decodeExitSignal(exitCode: Int): String = when (exitCode) {
    0 -> "OK"
    1 -> "General Error"
    2 -> "Usage / Syntax Error"
    124 -> "Timed Out"
    126 -> "Permission Denied"
    127 -> "Command Not Found"
    130 -> "SIGINT (Interrupted)"
    134 -> "SIGABRT (Aborted)"
    135 -> "SIGBUS (Bus Error)"
    137 -> "SIGKILL (Likely Out of Memory / LMK)"
    139 -> "SIGSEGV (Segmentation Fault)"
    143 -> "SIGTERM (Terminated)"
    else -> if (exitCode > 128) "Signal ${exitCode - 128}" else "Exit $exitCode"
}

data class ExecutionState(
    val isRunning: Boolean = false,
    val modeLabel: String = "",
    val stageLabel: String = "",
    val progress: Float = -1f,
    val compilerReport: String = "",
    val diagnostics: List<DiagnosticItem> = emptyList(),
    val lastExitCode: Int? = null,
    val elapsedMs: Long = 0L,
    val activeArtifact: OutputArtifact = OutputArtifact(),
    val bannerMessage: String = "",
    val bannerIsError: Boolean = false,
    val lastErrorSummary: String = "",
    val lastCrashReportPath: String = ""
)

data class ReplVar(val name: String, val type: String, val valueRepr: String)

data class ReplEntry(
    val id: Long,
    val input: String,
    val stdout: String,
    val stderr: String,
    val latex: String = "",
    val imagePath: String = "",
    val ok: Boolean = true
)

fun formatBytesShort(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
