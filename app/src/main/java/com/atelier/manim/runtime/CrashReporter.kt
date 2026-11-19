package com.atelier.manim.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import androidx.core.app.NotificationCompat
import com.atelier.manim.BuildConfig
import com.atelier.manim.MainActivity
import com.atelier.manim.data.MediaExportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReporter(
    private val context: Context,
    private val mediaExport: MediaExportManager,
    private val settingsStore: StudioSettingsStore
) {
    val reportsDir: File = File(context.filesDir, "crash_reports").apply { mkdirs() }
    private val pendingJvmFile = File(context.filesDir, "crash_last_pending.txt")
    private val prefs = context.getSharedPreferences("atelier_crashes", Context.MODE_PRIVATE)

    @Volatile var activeFilePath: String = ""
    @Volatile var activeMode: String = ""
    @Volatile var lastCommand: String = ""

    private val _reports = MutableStateFlow<List<CrashReportEntry>>(emptyList())
    val reports: StateFlow<List<CrashReportEntry>> = _reports.asStateFlow()

    private val _pendingCrashAlert = MutableStateFlow<CrashReportEntry?>(null)
    val pendingCrashAlert: StateFlow<CrashReportEntry?> = _pendingCrashAlert.asStateFlow()

    init { refreshReportsList() }

    fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { threadObj, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val summary = "${throwable.javaClass.simpleName}: ${throwable.message ?: "Uncaught exception"}"
                val body = buildString {
                    appendLine("=== ATELIER APP CRASH REPORT ===")
                    appendLine("Timestamp  : ${formatIso(System.currentTimeMillis())}")
                    appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Device     : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread     : ${threadObj.name}")
                    appendLine("Active File: ${activeFilePath.ifBlank { "none" }}")
                    appendLine("Active Mode: ${activeMode.ifBlank { "idle" }}")
                    appendLine("Last Cmd   : ${lastCommand.ifBlank { "none" }}")
                    appendLine("Memory     : ${memorySummaryLine()}")
                    appendLine("Summary    : $summary")
                    appendLine("\n--- Stack Trace ---\n$sw")
                }
                File(context.filesDir, "crash_last.txt").writeText(body, Charsets.UTF_8)
                pendingJvmFile.writeText(body, Charsets.UTF_8)
                val entry = writeReportFile(
                    kind = "APP_CRASH",
                    title = "App Crashed · ${throwable.javaClass.simpleName}",
                    summary = summary,
                    body = body,
                    exportToPublic = settingsStore.autoExportCrashReports.value
                )
                postFailureNotification(entry.title, summary, entry.filePath)
            }
            previous?.uncaughtException(threadObj, throwable)
        }
    }

    fun checkPreviousSessionExits() {
        runCatching {
            if (pendingJvmFile.exists()) {
                val text = pendingJvmFile.readText(Charsets.UTF_8)
                pendingJvmFile.delete()
                val summaryLine = text.lines().firstOrNull { it.startsWith("Summary") }
                    ?.substringAfter(":")?.trim().orEmpty().ifBlank { "Previous session crashed unexpectedly." }
                val existing = _reports.value.firstOrNull { it.kind == "APP_CRASH" }
                _pendingCrashAlert.value = existing ?: writeReportFile(
                    kind = "APP_CRASH",
                    title = "Previous Session Crashed",
                    summary = summaryLine,
                    body = text,
                    exportToPublic = settingsStore.autoExportCrashReports.value
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
                val lastSeen = prefs.getLong("last_exit_ts", 0L)
                val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
                val abnormal = reasons.firstOrNull { it.timestamp > lastSeen && isAbnormalExitReason(it.reason) }
                if (reasons.isNotEmpty()) {
                    val maxTs = reasons.maxOf { it.timestamp }
                    if (maxTs > lastSeen) prefs.edit().putLong("last_exit_ts", maxTs).apply()
                }
                if (abnormal != null) {
                    val reasonName = exitReasonName(abnormal.reason)
                    val desc = abnormal.description?.takeIf { it.isNotBlank() } ?: "No OS description"
                    val summary = "$reasonName (status=${abnormal.status}, PSS=${abnormal.pss / 1024}MB, RSS=${abnormal.rss / 1024}MB): $desc"
                    val traceText = runCatching {
                        abnormal.traceInputStream?.bufferedReader()?.use { it.readText().take(20_000) } ?: ""
                    }.getOrDefault("")
                    val body = buildString {
                        appendLine("=== ATELIER SYSTEM EXIT DIAGNOSTIC ===")
                        appendLine("Timestamp  : ${formatIso(abnormal.timestamp)}")
                        appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("Device     : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                        appendLine("Reason     : $reasonName (code=${abnormal.reason}, status=${abnormal.status})")
                        appendLine("Importance : ${abnormal.importance}")
                        appendLine("PSS / RSS  : ${abnormal.pss / 1024} MB / ${abnormal.rss / 1024} MB")
                        appendLine("Description: $desc")
                        if (traceText.isNotBlank()) appendLine("\n--- System Trace ---\n$traceText")
                    }
                    val entry = writeReportFile(
                        kind = "OS_EXIT",
                        title = "Process Terminated · $reasonName",
                        summary = summary,
                        body = body,
                        exportToPublic = settingsStore.autoExportCrashReports.value
                    )
                    if (_pendingCrashAlert.value == null) _pendingCrashAlert.value = entry
                    postFailureNotification(entry.title, summary, entry.filePath)
                }
            }
            refreshReportsList()
        }
    }

    fun recordExecutionFailure(
        mode: String,
        fileName: String,
        command: String,
        exitCode: Int,
        elapsedMs: Long,
        diagnostics: List<DiagnosticItem>,
        logTail: String
    ): CrashReportEntry {
        val signalDesc = decodeExitSignal(exitCode)
        val primaryReason = diagnostics.firstOrNull()?.let { "L${it.line}: ${it.message}" } ?: signalDesc
        val body = buildString {
            appendLine("=== ATELIER EXECUTION FAILURE REPORT ===")
            appendLine("Timestamp  : ${formatIso(System.currentTimeMillis())}")
            appendLine("Mode       : $mode")
            appendLine("File       : $fileName")
            appendLine("Exit Code  : $exitCode ($signalDesc)")
            appendLine("Elapsed    : ${elapsedMs}ms")
            appendLine("Memory     : ${memorySummaryLine()}")
            appendLine("Command    : $command")
            if (diagnostics.isNotEmpty()) {
                appendLine("\n--- Parsed Diagnostics (${diagnostics.size}) ---")
                diagnostics.forEach { d -> appendLine("[${d.severity}] ${d.source} L${d.line}: ${d.message}") }
            }
            appendLine("\n--- Output Log Tail ---")
            appendLine(logTail.takeLast(16_000))
        }
        val title = "Failed · ${fileName.ifBlank { mode }}"
        val entry = writeReportFile(
            kind = "EXEC_FAIL",
            title = title,
            summary = primaryReason,
            body = body,
            exportToPublic = settingsStore.autoExportCrashReports.value
        )
        postFailureNotification(title, primaryReason, entry.filePath)
        refreshReportsList()
        return entry
    }

    fun readReportBody(entry: CrashReportEntry): String {
        val f = File(entry.filePath)
        if (!f.exists()) return ""
        val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
        return if (raw.startsWith("META|")) raw.substringAfter("\n", raw) else raw
    }

    fun dismissPendingAlert() { _pendingCrashAlert.value = null }

    fun postCustomNotification(title: String, message: String, copyText: String = "") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_CRASH) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_CRASH, "Atelier Crash & Error Alerts", NotificationManager.IMPORTANCE_HIGH))
            }
        }
        val openIntent = PendingIntent.getActivity(
            context, 4203, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, CHANNEL_CRASH)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true).setContentIntent(openIntent)
        if (copyText.isNotBlank()) {
            val copyIntent = PendingIntent.getBroadcast(
                context, (System.currentTimeMillis() and 0xFFFF).toInt(),
                Intent(context, CrashNotificationReceiver::class.java).apply {
                    action = CrashNotificationReceiver.ACTION_COPY_ERROR
                    putExtra(CrashNotificationReceiver.EXTRA_FALLBACK_TEXT, copyText.take(4000))
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "Copy", copyIntent)
        }
        runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(4198, b.build()) }
    }

    fun clearAllReports() {
        reportsDir.listFiles()?.forEach { it.delete() }
        _pendingCrashAlert.value = null
        refreshReportsList()
    }

    fun collectSystemExitReasonsJson(): JSONArray {
        val arr = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching
                for (info in am.getHistoricalProcessExitReasons(context.packageName, 0, 6)) {
                    arr.put(JSONObject().apply {
                        put("timestamp", formatIso(info.timestamp))
                        put("reason", exitReasonName(info.reason))
                        put("status", info.status)
                        put("pssMb", info.pss / 1024)
                        put("rssMb", info.rss / 1024)
                        put("description", info.description ?: "")
                    })
                }
            }
        }
        return arr
    }

    private fun writeReportFile(
        kind: String, title: String, summary: String, body: String, exportToPublic: Boolean
    ): CrashReportEntry {
        val ts = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
        val prefix = when (kind) { "APP_CRASH" -> "app_crash"; "OS_EXIT" -> "os_exit"; else -> "exec_fail" }
        val fileName = "${prefix}_${stamp}.txt"
        val file = File(reportsDir, fileName)
        val cleanSum = summary.replace("\n", " ").replace("|", "/").take(200)
        runCatching { file.writeText("META|kind=$kind|title=${title.replace("|", "/")}|summary=$cleanSum\n$body", Charsets.UTF_8) }
        val exportedLoc = if (exportToPublic) {
            runCatching { mediaExport.saveTextDocumentToStorage(body, "${prefix}_${stamp}", "CrashReports").displayLocation }.getOrDefault("")
        } else ""
        val all = reportsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (all.size > 30) all.drop(30).forEach { it.delete() }
        return CrashReportEntry(fileName, fileName, file.absolutePath, kind, title, summary, ts, exportedLoc)
    }

    private fun postFailureNotification(title: String, summary: String, reportPath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_CRASH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_CRASH, "Atelier Crash & Error Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Notifies only when an execution fails or the app crashes."
                    }
                )
            }
        }
        val openIntent = PendingIntent.getActivity(
            context, 4202,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val copyIntent = PendingIntent.getBroadcast(
            context, (System.currentTimeMillis() and 0xFFFF).toInt(),
            Intent(context, CrashNotificationReceiver::class.java).apply {
                action = CrashNotificationReceiver.ACTION_COPY_ERROR
                putExtra(CrashNotificationReceiver.EXTRA_REPORT_PATH, reportPath)
                putExtra(CrashNotificationReceiver.EXTRA_FALLBACK_TEXT, "$title\n$summary")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_CRASH)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .addAction(0, "Copy Error", copyIntent)
            .build()
        runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_CRASH, notif) }
    }

    private fun refreshReportsList() {
        _reports.value = reportsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val head = runCatching { f.bufferedReader().use { it.readLine().orEmpty() } }.getOrDefault("")
                var kind = "EXEC_FAIL"; var title = f.nameWithoutExtension; var summary = ""
                if (head.startsWith("META|")) {
                    for (p in head.removePrefix("META|").split("|")) {
                        when {
                            p.startsWith("kind=") -> kind = p.removePrefix("kind=")
                            p.startsWith("title=") -> title = p.removePrefix("title=")
                            p.startsWith("summary=") -> summary = p.removePrefix("summary=")
                        }
                    }
                }
                CrashReportEntry(f.name, f.name, f.absolutePath, kind, title, summary, f.lastModified())
            } ?: emptyList()
    }

    private fun memorySummaryLine(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return "avail=${mi.availMem / 1048576L}MB / total=${mi.totalMem / 1048576L}MB, lowMem=${mi.lowMemory}, appPSS=${Debug.getPss() / 1024L}MB"
    }

    private fun isAbnormalExitReason(r: Int): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (
        r == ApplicationExitInfo.REASON_CRASH || r == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_LOW_MEMORY ||
            r == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE || r == ApplicationExitInfo.REASON_SIGNALED
    )

    private fun exitReasonName(r: Int): String = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "UNKNOWN" else when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY_LMK"
        ApplicationExitInfo.REASON_CRASH -> "APP_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> "REASON_$r"
    }

    private fun formatIso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    companion object {
        private const val CHANNEL_CRASH = "atelier_crash_channel"
        private const val NOTIF_ID_CRASH = 4199
    }
}
