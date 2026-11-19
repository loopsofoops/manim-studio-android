package com.atelier.manim.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class StudioSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("atelier_ui", Context.MODE_PRIVATE)
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

    private val _selectedScene = MutableStateFlow("")
    val selectedScene: StateFlow<String> = _selectedScene.asStateFlow()

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
    fun selectScene(scene: String) { _selectedScene.value = scene }

    fun toJson(useNoSeccomp: Boolean): JSONObject = JSONObject().apply {
        put("preset", _preset.value.name)
        put("output_format", _outputFormat.value.name)
        put("custom_width", _customWidth.value)
        put("custom_height", _customHeight.value)
        put("custom_fps", _customFps.value)
        put("render_mode", _renderMode.value.name)
        put("anim_range", "${_animRangeStart.value},${_animRangeEnd.value}")
        put("disable_cache", _disableCache.value)
        put("auto_save_storage", _autoSaveStorage.value)
        put("auto_export_crash_reports", _autoExportCrashReports.value)
        put("dark_theme", _isDarkTheme.value)
        put("font_size_sp", _fontSizeSp.value)
        put("compute_profile", "${_computeProfile.value.name} (${_computeProfile.value.threads}T)")
        put("selected_scene", _selectedScene.value)
        put("use_no_seccomp", useNoSeccomp)
    }
}
