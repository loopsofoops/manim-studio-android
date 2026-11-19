package com.atelier.manim.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed class StudioUiEvent {
    data class OpenFile(val relativePath: String) : StudioUiEvent()
    data class ReloadIfActive(val relativePath: String) : StudioUiEvent()
    data class ShowToast(val message: String) : StudioUiEvent()
    data object SyncWorkspace : StudioUiEvent()
}

class StudioSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("atelier_ui", Context.MODE_PRIVATE)
    private val macrosFile = File(context.filesDir, "controller_macros.json")
    private val defaultProfile = if (Runtime.getRuntime().availableProcessors() >= 6) {
        ComputeProfile.BALANCED_4T
    } else ComputeProfile.ECO_2T

    private val _preset = MutableStateFlow(
        runCatching { ManimPreset.valueOf(prefs.getString("preset", ManimPreset.MED_720P.name)!!) }
            .getOrDefault(ManimPreset.MED_720P)
    )
    val preset: StateFlow<ManimPreset> = _preset.asStateFlow()

    private val _outputFormat = MutableStateFlow(
        runCatching { OutputFormat.valueOf(prefs.getString("output_format", OutputFormat.MP4.name)!!) }
            .getOrDefault(OutputFormat.MP4)
    )
    val outputFormat: StateFlow<OutputFormat> = _outputFormat.asStateFlow()

    private val _customWidth = MutableStateFlow(prefs.getInt("custom_w", 1280))
    val customWidth: StateFlow<Int> = _customWidth.asStateFlow()

    private val _customHeight = MutableStateFlow(prefs.getInt("custom_h", 720))
    val customHeight: StateFlow<Int> = _customHeight.asStateFlow()

    private val _customFps = MutableStateFlow(prefs.getInt("custom_fps", 30))
    val customFps: StateFlow<Int> = _customFps.asStateFlow()

    private val _renderMode = MutableStateFlow(
        runCatching { ManimRenderMode.valueOf(prefs.getString("render_mode", ManimRenderMode.FULL.name)!!) }
            .getOrDefault(ManimRenderMode.FULL)
    )
    val renderMode: StateFlow<ManimRenderMode> = _renderMode.asStateFlow()

    private val _animRangeStart = MutableStateFlow(prefs.getInt("anim_range_start", 0))
    val animRangeStart: StateFlow<Int> = _animRangeStart.asStateFlow()

    private val _animRangeEnd = MutableStateFlow(prefs.getInt("anim_range_end", 3))
    val animRangeEnd: StateFlow<Int> = _animRangeEnd.asStateFlow()

    private val _disableCache = MutableStateFlow(prefs.getBoolean("disable_cache", false))
    val disableCache: StateFlow<Boolean> = _disableCache.asStateFlow()

    private val _autoSaveStorage = MutableStateFlow(prefs.getBoolean("auto_save_storage", true))
    val autoSaveStorage: StateFlow<Boolean> = _autoSaveStorage.asStateFlow()

    private val _autoExportCrashReports = MutableStateFlow(prefs.getBoolean("auto_export_crash", true))
    val autoExportCrashReports: StateFlow<Boolean> = _autoExportCrashReports.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean("dark_theme", true))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _fontSizeSp = MutableStateFlow(prefs.getInt("editor_font_sp", 13))
    val fontSizeSp: StateFlow<Int> = _fontSizeSp.asStateFlow()

    private val _computeProfile = MutableStateFlow(
        runCatching { ComputeProfile.valueOf(prefs.getString("compute_profile", defaultProfile.name)!!) }
            .getOrDefault(defaultProfile)
    )
    val computeProfile: StateFlow<ComputeProfile> = _computeProfile.asStateFlow()

    private val _studioMode = MutableStateFlow(StudioMode.CODE)
    val studioMode: StateFlow<StudioMode> = _studioMode.asStateFlow()

    private val _selectedScene = MutableStateFlow("")
    val selectedScene: StateFlow<String> = _selectedScene.asStateFlow()

    private val _macros = MutableStateFlow(loadMacros())
    val macros: StateFlow<List<ControllerMacro>> = _macros.asStateFlow()

    private val _uiEvents = MutableSharedFlow<StudioUiEvent>(extraBufferCapacity = 32)
    val uiEvents: SharedFlow<StudioUiEvent> = _uiEvents.asSharedFlow()

    fun selectPreset(p: ManimPreset) { _preset.value = p; prefs.edit().putString("preset", p.name).apply() }
    fun selectOutputFormat(f: OutputFormat) { _outputFormat.value = f; prefs.edit().putString("output_format", f.name).apply() }
    fun setCustomDimensions(w: Int, h: Int, fps: Int) {
        val cw = w.coerceIn(160, 4096); val ch = h.coerceIn(160, 4096); val cf = fps.coerceIn(1, 120)
        _customWidth.value = cw; _customHeight.value = ch; _customFps.value = cf
        prefs.edit().putInt("custom_w", cw).putInt("custom_h", ch).putInt("custom_fps", cf).apply()
    }
    fun selectRenderMode(m: ManimRenderMode) { _renderMode.value = m; prefs.edit().putString("render_mode", m.name).apply() }
    fun setAnimRange(start: Int, end: Int) {
        val s = start.coerceAtLeast(0); val e = end.coerceAtLeast(s)
        _animRangeStart.value = s; _animRangeEnd.value = e
        prefs.edit().putInt("anim_range_start", s).putInt("anim_range_end", e).apply()
    }
    fun setDisableCache(v: Boolean) { _disableCache.value = v; prefs.edit().putBoolean("disable_cache", v).apply() }
    fun setAutoSaveStorage(v: Boolean) { _autoSaveStorage.value = v; prefs.edit().putBoolean("auto_save_storage", v).apply() }
    fun setAutoExportCrashReports(v: Boolean) { _autoExportCrashReports.value = v; prefs.edit().putBoolean("auto_export_crash", v).apply() }
    fun toggleTheme() = setDarkTheme(!_isDarkTheme.value)
    fun setDarkTheme(v: Boolean) { _isDarkTheme.value = v; prefs.edit().putBoolean("dark_theme", v).apply() }
    fun setFontSize(sp: Int) {
        val c = sp.coerceIn(10, 22); _fontSizeSp.value = c; prefs.edit().putInt("editor_font_sp", c).apply()
    }
    fun setComputeProfile(p: ComputeProfile) { _computeProfile.value = p; prefs.edit().putString("compute_profile", p.name).apply() }
    fun setStudioMode(m: StudioMode) { _studioMode.value = m }
    fun selectScene(scene: String) { _selectedScene.value = scene }
    fun emitUiEvent(event: StudioUiEvent) { _uiEvents.tryEmit(event) }

    fun saveMacro(name: String, description: String, code: String): ControllerMacro {
        val clean = name.trim().ifBlank { "Macro" }
        val existing = _macros.value.firstOrNull { it.name.equals(clean, true) }
        val item = ControllerMacro(existing?.id ?: UUID.randomUUID().toString().take(8), clean, description.trim(), code)
        val updated = _macros.value.filterNot { it.id == item.id } + item
        _macros.value = updated
        persistMacros(updated)
        return item
    }

    fun deleteMacro(idOrName: String): Boolean {
        val before = _macros.value
        val after = before.filterNot { it.id == idOrName || it.name.equals(idOrName, true) }
        if (after.size == before.size) return false
        _macros.value = after
        persistMacros(after)
        return true
    }

    fun toJson(useNoSeccomp: Boolean): JSONObject = JSONObject().apply {
        put("preset", _preset.value.name)
        put("output_format", _outputFormat.value.name)
        put("custom_width", _customWidth.value)
        put("custom_height", _customHeight.value)
        put("custom_fps", _customFps.value)
        put("render_mode", _renderMode.value.name)
        put("anim_range_start", _animRangeStart.value)
        put("anim_range_end", _animRangeEnd.value)
        put("disable_cache", _disableCache.value)
        put("auto_save_storage", _autoSaveStorage.value)
        put("auto_export_crash_reports", _autoExportCrashReports.value)
        put("dark_theme", _isDarkTheme.value)
        put("font_size_sp", _fontSizeSp.value)
        put("compute_profile", _computeProfile.value.name)
        put("compute_threads", _computeProfile.value.threads)
        put("studio_mode", _studioMode.value.name)
        put("selected_scene", _selectedScene.value)
        put("use_no_seccomp", useNoSeccomp)
    }

    fun applyPatch(params: JSONObject, setNoSeccomp: (Boolean) -> Unit): List<String> {
        val changed = mutableListOf<String>()
        if (params.has("preset")) {
            val raw = params.optString("preset").uppercase()
            ManimPreset.entries.firstOrNull { it.name == raw || it.shortLabel.equals(raw, true) }?.let { selectPreset(it); changed.add("preset=${it.name}") }
        }
        if (params.has("output_format")) {
            val raw = params.optString("output_format").uppercase()
            OutputFormat.entries.firstOrNull { it.name == raw || it.ext.equals(raw, true) }?.let { selectOutputFormat(it); changed.add("output_format=${it.name}") }
        }
        if (params.has("custom_width") || params.has("custom_height") || params.has("custom_fps")) {
            setCustomDimensions(params.optInt("custom_width", _customWidth.value), params.optInt("custom_height", _customHeight.value), params.optInt("custom_fps", _customFps.value))
            changed.add("custom=${_customWidth.value}x${_customHeight.value}@${_customFps.value}")
        }
        if (params.has("render_mode")) {
            val raw = params.optString("render_mode").uppercase()
            ManimRenderMode.entries.firstOrNull { it.name == raw }?.let { selectRenderMode(it); changed.add("render_mode=${it.name}") }
        }
        if (params.has("anim_range_start") || params.has("anim_range_end")) {
            setAnimRange(params.optInt("anim_range_start", _animRangeStart.value), params.optInt("anim_range_end", _animRangeEnd.value))
            changed.add("anim_range=${_animRangeStart.value},${_animRangeEnd.value}")
        }
        if (params.has("disable_cache")) { setDisableCache(params.optBoolean("disable_cache")); changed.add("disable_cache=${_disableCache.value}") }
        if (params.has("auto_save_storage")) { setAutoSaveStorage(params.optBoolean("auto_save_storage")); changed.add("auto_save_storage=${_autoSaveStorage.value}") }
        if (params.has("auto_export_crash_reports")) { setAutoExportCrashReports(params.optBoolean("auto_export_crash_reports")); changed.add("auto_export_crash_reports=${_autoExportCrashReports.value}") }
        if (params.has("dark_theme")) { setDarkTheme(params.optBoolean("dark_theme")); changed.add("dark_theme=${_isDarkTheme.value}") }
        if (params.has("font_size") || params.has("font_size_sp")) {
            val sp = if (params.has("font_size")) params.optInt("font_size") else params.optInt("font_size_sp")
            setFontSize(sp); changed.add("font_size=${_fontSizeSp.value}")
        }
        if (params.has("compute_profile")) {
            val raw = params.optString("compute_profile").uppercase()
            ComputeProfile.entries.firstOrNull { it.name == raw || it.shortLabel.equals(raw, true) }?.let { setComputeProfile(it); changed.add("compute_profile=${it.name}") }
        } else if (params.has("compute_threads")) {
            val t = params.optInt("compute_threads", 4)
            val p = when { t <= 2 -> ComputeProfile.ECO_2T; t <= 4 -> ComputeProfile.BALANCED_4T; else -> ComputeProfile.MAX_6T }
            setComputeProfile(p); changed.add("compute_profile=${p.name}")
        }
        if (params.has("use_no_seccomp")) { val v = params.optBoolean("use_no_seccomp"); setNoSeccomp(v); changed.add("use_no_seccomp=$v") }
        if (params.has("selected_scene")) { selectScene(params.optString("selected_scene")); changed.add("selected_scene=${_selectedScene.value}") }
        if (params.has("studio_mode")) {
            StudioMode.entries.firstOrNull { it.name.equals(params.optString("studio_mode"), true) }?.let { setStudioMode(it); changed.add("studio_mode=${it.name}") }
        }
        return changed
    }

    private fun loadMacros(): List<ControllerMacro> {
        if (macrosFile.exists()) {
            val parsed = runCatching {
                val arr = JSONArray(macrosFile.readText(Charsets.UTF_8))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ControllerMacro(o.optString("id", UUID.randomUUID().toString().take(8)), o.optString("name", "Macro"), o.optString("description", ""), o.optString("code", ""))
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        val defaults = listOf(
            ControllerMacro(
                id = "diag_clip",
                name = "Copy Diagnostics",
                description = "Query complete studio snapshot and copy to clipboard",
                code = "import atelier\n\nsnap = atelier.inspect.full_snapshot(format=\"markdown\")\natelier.app.clipboard_set(snap)\natelier.app.toast(\"Full snapshot copied\")\nprint(snap[:1500])\n"
            ),
            ControllerMacro(
                id = "batch_scenes",
                name = "Batch Render Active",
                description = "Detect and render every Manim scene in the active file",
                code = "import atelier\n\nactive = atelier.inspect.active_file(include_content=False)\npath = active[\"relativePath\"]\nscenes = atelier.manim.detect_scenes(path)\nprint(\"Scenes:\", scenes)\nfor sc in scenes:\n    res = atelier.manim.render(path, scene=sc)\n    print(sc, \"exit=\", res.get(\"exitCode\"), res.get(\"outputPath\"))\natelier.app.toast(f\"Rendered {len(scenes)} scene(s)\")\n"
            ),
            ControllerMacro(
                id = "draft_check",
                name = "Quick Draft Check",
                description = "Render active scene in 480p15 Draft and copy error log on failure",
                code = "import atelier\n\nactive = atelier.inspect.active_file(include_content=False)\npath = active[\"relativePath\"]\nscenes = atelier.manim.detect_scenes(path)\nif not scenes:\n    raise RuntimeError(\"No Manim scenes in \" + path)\nres = atelier.manim.render_draft(path, scene=scenes[0])\nif res.get(\"exitCode\", 1) != 0:\n    atelier.app.clipboard_set(atelier.inspect.console_log(tail=90))\n    atelier.app.toast(\"Draft failed - error copied\")\nelse:\n    atelier.app.toast(\"Draft ready: \" + str(res.get(\"telemetry\", \"\")))\n"
            )
        )
        persistMacros(defaults)
        return defaults
    }

    private fun persistMacros(list: List<ControllerMacro>) {
        runCatching {
            val arr = JSONArray()
            for (m in list) arr.put(JSONObject().put("id", m.id).put("name", m.name).put("description", m.description).put("code", m.code))
            macrosFile.writeText(arr.toString(2), Charsets.UTF_8)
        }
    }
}
