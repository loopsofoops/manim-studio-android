package com.atelier.manim.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import com.atelier.manim.data.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class HardwareTelemetry(
    val ramAvailMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val appPssMb: Long = 0L,
    val batteryPercent: Int = -1,
    val batteryTempC: Float = 0f,
    val isLowMemory: Boolean = false
)

data class BrandKitConfig(
    val channelName: String = "Atelier Math",
    val bgColor: String = "#06070A",
    val primaryColor: String = "#F5F5F7",
    val accentColor: String = "#0A84FF",
    val highlightColor: String = "#FDE047",
    val secondaryColor: String = "#34D399",
    val watermark: String = "",
    val defaultNarratorModel: String = "kokoro_en_019",
    val defaultNarratorSid: Int = 9,
    val defaultMusicStyle: String = "felt_piano",
    val defaultMusicKey: String = "D",
    val defaultMusicBpm: Int = 84
)

data class BatchJobItem(
    val id: String,
    val kind: String,
    val title: String,
    val target: String,
    val extra: String = "",
    val status: String = "QUEUED",
    val resultSummary: String = ""
)

class StudioAutomationManager(
    private val context: Context,
    private val runtimeManager: RuntimeManager,
    private val workspaceRepo: WorkspaceRepository,
    private val settingsStore: StudioSettingsStore,
    private val coordinator: ExecutionCoordinator,
    private val voiceEngine: OfflineVoiceEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val brandFile = File(runtimeManager.outputsDir, "brand.json")
    private val queueFile = File(context.filesDir, "batch_queue.json")

    private val _telemetry = MutableStateFlow(readHardwareTelemetry())
    val telemetry: StateFlow<HardwareTelemetry> = _telemetry.asStateFlow()

    private val _brand = MutableStateFlow(loadBrand())
    val brand: StateFlow<BrandKitConfig> = _brand.asStateFlow()

    private val _batchQueue = MutableStateFlow(loadQueue())
    val batchQueue: StateFlow<List<BatchJobItem>> = _batchQueue.asStateFlow()

    private val _batchRunning = MutableStateFlow(false)
    val batchRunning: StateFlow<Boolean> = _batchRunning.asStateFlow()

    private val _thermalPaused = MutableStateFlow(false)
    val thermalPaused: StateFlow<Boolean> = _thermalPaused.asStateFlow()

    private var batchJob: Job? = null

    init {
        scope.launch {
            while (isActive) {
                _telemetry.value = readHardwareTelemetry()
                delay(4000L)
            }
        }
    }

    fun releaseHeavyAuxiliaryEngines() {
        voiceEngine.release()
    }

    fun readHardwareTelemetry(): HardwareTelemetry {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val batt = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val tempT = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val lvl = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return HardwareTelemetry(
            ramAvailMb = mi.availMem / 1048576L,
            ramTotalMb = mi.totalMem / 1048576L,
            appPssMb = Debug.getPss() / 1024L,
            batteryPercent = if (lvl >= 0 && sc > 0) (lvl * 100) / sc else -1,
            batteryTempC = tempT / 10.0f,
            isLowMemory = mi.lowMemory
        )
    }

    fun telemetryJson(): JSONObject = readHardwareTelemetry().let { t ->
        JSONObject().put("ramAvailMb", t.ramAvailMb).put("ramTotalMb", t.ramTotalMb)
            .put("appPssMb", t.appPssMb).put("batteryPercent", t.batteryPercent)
            .put("batteryTempC", t.batteryTempC.toDouble()).put("isLowMemory", t.isLowMemory)
            .put("thermalPaused", _thermalPaused.value)
    }

    fun updateBrand(cfg: BrandKitConfig) {
        _brand.value = cfg
        saveBrand(cfg)
    }

    fun patchBrand(p: JSONObject): BrandKitConfig {
        val c = _brand.value
        val updated = c.copy(
            channelName = p.optString("channelName", c.channelName),
            bgColor = p.optString("bgColor", c.bgColor),
            primaryColor = p.optString("primaryColor", c.primaryColor),
            accentColor = p.optString("accentColor", c.accentColor),
            highlightColor = p.optString("highlightColor", c.highlightColor),
            secondaryColor = p.optString("secondaryColor", c.secondaryColor),
            watermark = p.optString("watermark", c.watermark),
            defaultNarratorModel = p.optString("defaultNarratorModel", c.defaultNarratorModel),
            defaultNarratorSid = p.optInt("defaultNarratorSid", c.defaultNarratorSid),
            defaultMusicStyle = p.optString("defaultMusicStyle", c.defaultMusicStyle),
            defaultMusicKey = p.optString("defaultMusicKey", c.defaultMusicKey),
            defaultMusicBpm = p.optInt("defaultMusicBpm", c.defaultMusicBpm)
        )
        updateBrand(updated)
        return updated
    }

    fun brandToJson(): JSONObject = _brand.value.let { b ->
        JSONObject().put("channelName", b.channelName).put("bgColor", b.bgColor)
            .put("primaryColor", b.primaryColor).put("accentColor", b.accentColor)
            .put("highlightColor", b.highlightColor).put("secondaryColor", b.secondaryColor)
            .put("watermark", b.watermark).put("defaultNarratorModel", b.defaultNarratorModel)
            .put("defaultNarratorSid", b.defaultNarratorSid).put("defaultMusicStyle", b.defaultMusicStyle)
            .put("defaultMusicKey", b.defaultMusicKey).put("defaultMusicBpm", b.defaultMusicBpm)
    }

    suspend fun exportManimThemeFile(relPath: String = "atelier_theme.py"): String {
        val b = _brand.value
        val code = buildString {
            appendLine("# Auto-generated by Atelier Channel Brand Kit (${b.channelName})")
            appendLine("CHANNEL_NAME = \"${b.channelName.replace("\"", "'")}\"")
            appendLine("BG_COLOR = \"${b.bgColor}\"")
            appendLine("TEXT_PRIMARY = \"${b.primaryColor}\"")
            appendLine("ACCENT_COLOR = \"${b.accentColor}\"")
            appendLine("HIGHLIGHT_COLOR = \"${b.highlightColor}\"")
            appendLine("SECONDARY_COLOR = \"${b.secondaryColor}\"")
            appendLine("WATERMARK = \"${b.watermark.replace("\"", "'")}\"")
            appendLine("")
            appendLine("def apply_brand(scene):")
            appendLine("    scene.camera.background_color = BG_COLOR")
        }
        val ent = workspaceRepo.writeByPath(relPath, code, label = "Brand Kit Export", language = "PYTHON")
        settingsStore.emitUiEvent(StudioUiEvent.SyncWorkspace)
        return ent.relativePath
    }

    fun enqueueJob(kind: String, title: String, target: String, extra: String = ""): BatchJobItem {
        val item = BatchJobItem(UUID.randomUUID().toString().take(8), kind.uppercase(), title, target, extra, "QUEUED")
        val updated = _batchQueue.value + item
        _batchQueue.value = updated
        persistQueue(updated)
        return item
    }

    fun removeJob(id: String) {
        val updated = _batchQueue.value.filterNot { it.id == id }
        _batchQueue.value = updated
        persistQueue(updated)
    }

    fun clearCompletedOrAll(all: Boolean = false) {
        val updated = if (all) emptyList() else _batchQueue.value.filter { it.status == "QUEUED" || it.status == "RUNNING" }
        _batchQueue.value = updated
        persistQueue(updated)
    }

    fun pauseBatchQueue() {
        batchJob?.cancel()
        batchJob = null
        _batchRunning.value = false
        _thermalPaused.value = false
    }

    fun startBatchQueue() {
        if (_batchRunning.value) return
        batchJob = scope.launch {
            _batchRunning.value = true
            try {
                while (isActive) {
                    val next = _batchQueue.value.firstOrNull { it.status == "QUEUED" || it.status == "PAUSED_THERMAL" } ?: break
                    var t = readHardwareTelemetry()
                    if (t.batteryTempC > 42.0f) {
                        _thermalPaused.value = true
                        updateJobStatus(next.id, "PAUSED_THERMAL", "Cooling down (${t.batteryTempC} C)...")
                        RenderForegroundService.update(context, "Batch Paused (Thermal)", "Battery ${t.batteryTempC} C — cooling to 38.5 C", -1f)
                        while (isActive && t.batteryTempC > 38.5f) {
                            delay(5000L)
                            t = readHardwareTelemetry()
                            _telemetry.value = t
                        }
                        _thermalPaused.value = false
                    }

                    updateJobStatus(next.id, "RUNNING", "Executing...")
                    releaseHeavyAuxiliaryEngines()
                    RenderForegroundService.update(context, "Batch Queue", next.title, -1f)

                    val (ok, summary) = runBatchItem(next)
                    updateJobStatus(next.id, if (ok) "DONE" else "FAILED", summary)
                    delay(800L)
                }
            } finally {
                _batchRunning.value = false
                _thermalPaused.value = false
                RenderForegroundService.stop(context)
            }
        }
    }

    private suspend fun runBatchItem(item: BatchJobItem): Pair<Boolean, String> = runCatching {
        when (item.kind) {
            "MANIM_RENDER" -> {
                val ent = workspaceRepo.findOrSyncFile(item.target) ?: error("File not found: ${item.target}")
                val code = workspaceRepo.readFile(ent)
                val sc = item.extra.ifBlank { com.atelier.manim.data.detectManimScenes(code).firstOrNull() ?: error("No scene found") }
                val res = coordinator.runManimBlockingForBridge(
                    file = ent, code = code, sceneName = sc, preset = settingsStore.preset.value,
                    customWidth = settingsStore.customWidth.value, customHeight = settingsStore.customHeight.value,
                    customFps = settingsStore.customFps.value, outputFormat = settingsStore.outputFormat.value,
                    renderMode = ManimRenderMode.FULL, animRangeStart = 0, animRangeEnd = 3,
                    disableCaching = settingsStore.disableCache.value, autoSaveToStorage = settingsStore.autoSaveStorage.value
                )
                (res.optInt("exitCode", 1) == 0) to res.optString("summary", "Exit ${res.optInt("exitCode", 1)}")
            }
            "TIMELINE_EXPORT" -> {
                val preset = item.extra.ifBlank { "youtube_1080p60" }
                val py = "import atelier; r = atelier.export.render_timeline('${item.target.replace("'", "")}', preset='$preset'); print(r.get('summary','ok'))"
                val (code, out) = coordinator.runStandalonePythonForBatch(py)
                (code == 0) to out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120)
            }
            "PUBLISH_BUNDLE" -> {
                val py = "import atelier; r = atelier.export.publish_bundle('${item.target.replace("'", "")}'); print(r.get('summary','ok'))"
                val (code, out) = coordinator.runStandalonePythonForBatch(py)
                (code == 0) to out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120)
            }
            else -> {
                val (code, out) = coordinator.runStandalonePythonForBatch(item.target)
                (code == 0) to out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120)
            }
        }
    }.getOrElse { false to (it.message ?: "Batch job error") }

    private fun updateJobStatus(id: String, status: String, summary: String) {
        val updated = _batchQueue.value.map { if (it.id == id) it.copy(status = status, resultSummary = summary) else it }
        _batchQueue.value = updated
        persistQueue(updated)
    }

    fun queueToJson(): JSONArray = JSONArray().apply {
        _batchQueue.value.forEach { j ->
            put(JSONObject().put("id", j.id).put("kind", j.kind).put("title", j.title).put("target", j.target).put("extra", j.extra).put("status", j.status).put("resultSummary", j.resultSummary))
        }
    }

    private fun loadBrand(): BrandKitConfig {
        if (!brandFile.exists()) return BrandKitConfig()
        return runCatching {
            val o = JSONObject(brandFile.readText(Charsets.UTF_8))
            BrandKitConfig(
                o.optString("channelName", "Atelier Math"), o.optString("bgColor", "#06070A"),
                o.optString("primaryColor", "#F5F5F7"), o.optString("accentColor", "#0A84FF"),
                o.optString("highlightColor", "#FDE047"), o.optString("secondaryColor", "#34D399"),
                o.optString("watermark", ""), o.optString("defaultNarratorModel", "kokoro_en_019"),
                o.optInt("defaultNarratorSid", 9), o.optString("defaultMusicStyle", "felt_piano"),
                o.optString("defaultMusicKey", "D"), o.optInt("defaultMusicBpm", 84)
            )
        }.getOrDefault(BrandKitConfig())
    }

    private fun saveBrand(cfg: BrandKitConfig) {
        runCatching {
            brandFile.parentFile?.mkdirs()
            brandFile.writeText(brandToJson().toString(2), Charsets.UTF_8)
        }
    }

    private fun loadQueue(): List<BatchJobItem> {
        if (!queueFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(queueFile.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BatchJobItem(
                    o.optString("id"), o.optString("kind", "MANIM_RENDER"), o.optString("title"),
                    o.optString("target"), o.optString("extra"),
                    if (o.optString("status") == "RUNNING") "QUEUED" else o.optString("status", "QUEUED"),
                    o.optString("resultSummary")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persistQueue(list: List<BatchJobItem>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { j ->
                arr.put(JSONObject().put("id", j.id).put("kind", j.kind).put("title", j.title).put("target", j.target).put("extra", j.extra).put("status", j.status).put("resultSummary", j.resultSummary))
            }
            queueFile.writeText(arr.toString(2), Charsets.UTF_8)
        }
    }
}
