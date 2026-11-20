package com.atelier.manim.runtime

import com.atelier.manim.data.MediaExportManager
import com.atelier.manim.data.WorkspaceRepository
import com.atelier.manim.data.detectManimScenes
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AtelierBridgeServer(
    private val runtimeManager: RuntimeManager,
    private val settingsStore: StudioSettingsStore,
    private val workspaceRepo: WorkspaceRepository,
    private val coordinator: ExecutionCoordinator,
    private val crashReporter: CrashReporter,
    private val inspector: StudioInspector,
    private val mediaExport: MediaExportManager,
    private val voiceEngine: OfflineVoiceEngine,
    private val hardwareExporter: HardwareVideoExporter,
    private val automation: StudioAutomationManager
) {
    val token: String = ByteArray(24).apply { SecureRandom().nextBytes(this) }
        .joinToString("") { String.format("%02x", it) }

    @Volatile
    var port: Int = 0
        private set

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    fun start() {
        if (running) return
        runCatching {
            val ss = ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            running = true
            val workers = Executors.newCachedThreadPool()
            pool = workers
            workers.execute {
                while (running) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    workers.execute { handleClient(client) }
                }
            }
        }
    }

    fun stop() {
        running = false
        port = 0
        runCatching { serverSocket?.close() }
        runCatching { pool?.shutdownNow() }
        serverSocket = null
        pool = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val headerBytes = readUntilHeaderEnd(input) ?: return
            val lines = headerBytes.toString(Charsets.UTF_8).split("\r\n")
            val reqParts = lines.firstOrNull().orEmpty().split(" ")
            val httpMethod = reqParts.getOrNull(0).orEmpty()
            val path = reqParts.getOrNull(1).orEmpty()

            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            if (headers["x-atelier-token"] != token) {
                writeJson(s, 401, JSONObject().put("ok", false).put("error", "Unauthorized"))
                return
            }
            if (httpMethod != "POST" || path != "/rpc") {
                writeJson(s, 404, JSONObject().put("ok", false).put("error", "Not found"))
                return
            }

            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 12 * 1024 * 1024) {
                writeJson(s, 413, JSONObject().put("ok", false).put("error", "Payload too large"))
                return
            }
            val bodyBuf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(bodyBuf, read, len - read)
                if (n <= 0) break
                read += n
            }

            val resp = try {
                val req = JSONObject(String(bodyBuf, 0, read, Charsets.UTF_8))
                val method = req.getString("method")
                val params = req.optJSONObject("params") ?: JSONObject()
                JSONObject().put("ok", true).put("result", dispatch(method, params))
            } catch (t: Throwable) {
                JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
            }
            writeJson(s, 200, resp)
        }
    }

    private fun dispatch(method: String, p: JSONObject): Any = when (method) {
        "inspect.snapshot" -> if (p.optString("format", "markdown").equals("json", true)) inspector.getFullSnapshotJson() else inspector.buildFullSnapshotMarkdown()
        "inspect.status" -> inspector.getHardwareAndAppStatusJson()
        "app.telemetry" -> automation.telemetryJson()
        "inspect.runtime" -> inspector.getRuntimeStatusJson()
        "inspect.settings" -> inspector.getSettingsJson()
        "inspect.file_tree" -> inspector.getFileTreeJson(p.optBoolean("include_scenes", true))
        "inspect.active_file" -> inspector.getActiveFileJson(p.optBoolean("include_content", true))
        "inspect.execution" -> inspector.getExecutionJson()
        "inspect.console_log" -> coordinator.getConsoleTail(p.optInt("tail", 200))
        "inspect.crash_reports" -> inspector.getCrashReportsJson(p.optInt("limit", 5), p.optBoolean("include_body", true))
        "inspect.render_history" -> inspector.getRenderHistoryJson(p.optInt("limit", 20))
        "inspect.sdk_schema" -> inspector.buildSdkSchemaMarkdown()

        "files.list" -> runBlocking {
            val folder = p.optString("folder", "").trim().trim('/')
            workspaceRepo.syncWorkspaceFromDisk()
            JSONArray().apply {
                for (f in workspaceRepo.getAllFiles()) {
                    if (folder.isEmpty() || f.relativePath.startsWith("$folder/")) {
                        put(JSONObject().put("id", f.id).put("name", f.name).put("path", f.relativePath).put("language", f.language).put("updatedAt", f.updatedAt))
                    }
                }
            }
        }
        "files.read" -> runBlocking {
            val path = requirePath(p)
            val ent = workspaceRepo.findOrSyncFile(path) ?: error("File not found: $path")
            JSONObject().put("path", ent.relativePath).put("content", workspaceRepo.readFile(ent))
        }
        "files.write" -> runBlocking {
            val ent = workspaceRepo.writeByPath(requirePath(p), p.getString("content"), p.optString("label", "AI Controller"), p.optString("language", "AUTO"))
            settingsStore.emitUiEvent(StudioUiEvent.ReloadIfActive(ent.relativePath))
            JSONObject().put("path", ent.relativePath).put("id", ent.id).put("summary", "wrote ${ent.relativePath}")
        }
        "files.create" -> runBlocking {
            val path = requirePath(p)
            val lang = p.optString("language", "AUTO").let { if (it == "AUTO") workspaceRepo.inferLanguageForPath(path) else it }
            val ent = workspaceRepo.createFile(path, lang, p.optString("content", ""))
            JSONObject().put("path", ent.relativePath).put("id", ent.id).put("summary", "created ${ent.relativePath}")
        }
        "files.patch" -> runBlocking {
            val (ent, count) = workspaceRepo.patchFile(requirePath(p), p.getString("old"), p.getString("new"), p.optInt("count", 1), p.optString("label", "AI Patch"))
            settingsStore.emitUiEvent(StudioUiEvent.ReloadIfActive(ent.relativePath))
            JSONObject().put("path", ent.relativePath).put("replacements", count).put("summary", "patched ${ent.relativePath} ($count)")
        }
        "files.mkdir" -> runBlocking {
            val dir = workspaceRepo.createFolder(requirePath(p))
            val rel = dir.relativeTo(workspaceRepo.workspaceDir).path
            JSONObject().put("path", rel).put("summary", "mkdir $rel")
        }
        "files.delete" -> runBlocking {
            val path = requirePath(p)
            val ent = workspaceRepo.findOrSyncFile(path) ?: error("File not found: $path")
            workspaceRepo.deleteFile(ent)
            settingsStore.emitUiEvent(StudioUiEvent.SyncWorkspace)
            JSONObject().put("deleted", ent.relativePath).put("summary", "deleted ${ent.relativePath}")
        }
        "files.rename" -> runBlocking {
            val oldPath = p.getString("old_path")
            val ent = workspaceRepo.findOrSyncFile(oldPath) ?: error("File not found: $oldPath")
            val renamed = workspaceRepo.renameFile(ent, p.getString("new_path"))
            settingsStore.emitUiEvent(StudioUiEvent.SyncWorkspace)
            JSONObject().put("path", renamed.relativePath).put("summary", "$oldPath -> ${renamed.relativePath}")
        }
        "files.open" -> {
            val path = requirePath(p)
            settingsStore.emitUiEvent(StudioUiEvent.OpenFile(path))
            JSONObject().put("opened", path)
        }
        "files.sync_disk" -> runBlocking {
            val added = workspaceRepo.syncWorkspaceFromDisk()
            settingsStore.emitUiEvent(StudioUiEvent.SyncWorkspace)
            JSONObject().put("added", added).put("summary", "synced ($added new)")
        }
        "files.revisions" -> runBlocking {
            val ent = workspaceRepo.findOrSyncFile(requirePath(p)) ?: error("File not found")
            JSONArray().apply {
                workspaceRepo.getRevisionsSnapshot(ent.id).forEach { r ->
                    put(JSONObject().put("id", r.id).put("label", r.label).put("triggerType", r.triggerType).put("lineCount", r.lineCount).put("sizeBytes", r.sizeBytes).put("createdAt", r.createdAt))
                }
            }
        }
        "files.restore_revision" -> runBlocking {
            val ent = workspaceRepo.findOrSyncFile(requirePath(p)) ?: error("File not found")
            val revId = p.getLong("revision_id")
            val rev = workspaceRepo.getRevisionsSnapshot(ent.id).firstOrNull { it.id == revId } ?: error("Revision #$revId not found")
            workspaceRepo.restoreRevision(ent, rev)
            settingsStore.emitUiEvent(StudioUiEvent.ReloadIfActive(ent.relativePath))
            JSONObject().put("restored", revId).put("path", ent.relativePath)
        }
        "files.snapshot" -> runBlocking {
            val ent = workspaceRepo.findOrSyncFile(requirePath(p)) ?: error("File not found")
            val rev = workspaceRepo.saveFile(ent, workspaceRepo.readFile(ent), label = p.optString("label", "AI Snapshot"), triggerType = "AI_CTRL")
            JSONObject().put("revisionId", rev?.id ?: 0L).put("path", ent.relativePath)
        }

        "settings.get" -> settingsStore.toJson(runtimeManager.useNoSeccomp)
        "settings.set" -> {
            val changed = settingsStore.applyPatch(p) { runtimeManager.useNoSeccomp = it }
            JSONObject().put("changed", JSONArray(changed)).put("settings", settingsStore.toJson(runtimeManager.useNoSeccomp))
        }

        "manim.detect_scenes" -> runBlocking {
            val ent = workspaceRepo.findOrSyncFile(requirePath(p)) ?: error("File not found")
            JSONArray(detectManimScenes(workspaceRepo.readFile(ent)))
        }
        "manim.render" -> runBlocking {
            val path = requirePath(p)
            val ent = workspaceRepo.findOrSyncFile(path) ?: error("File not found: $path")
            val code = workspaceRepo.readFile(ent)
            val scene = p.optString("scene").ifBlank { detectManimScenes(code).firstOrNull() ?: error("No Manim scene found in $path") }
            val preset = p.optString("preset").takeIf { it.isNotBlank() }?.let { raw ->
                ManimPreset.entries.firstOrNull { it.name.equals(raw, true) || it.shortLabel.equals(raw, true) }
            } ?: settingsStore.preset.value
            val format = p.optString("output_format").takeIf { it.isNotBlank() }?.let { raw ->
                OutputFormat.entries.firstOrNull { it.name.equals(raw, true) || it.ext.equals(raw, true) }
            } ?: settingsStore.outputFormat.value
            val rMode = p.optString("render_mode").takeIf { it.isNotBlank() }?.let { raw ->
                ManimRenderMode.entries.firstOrNull { it.name.equals(raw, true) }
            } ?: settingsStore.renderMode.value
            val rangeStr = p.optString("anim_range", "")
            val (rStart, rEnd) = if (rangeStr.contains(",")) {
                val sp = rangeStr.split(",")
                (sp.getOrNull(0)?.trim()?.toIntOrNull() ?: 0) to (sp.getOrNull(1)?.trim()?.toIntOrNull() ?: 3)
            } else settingsStore.animRangeStart.value to settingsStore.animRangeEnd.value

            coordinator.runManimBlockingForBridge(
                file = ent, code = code, sceneName = scene, preset = preset,
                customWidth = settingsStore.customWidth.value, customHeight = settingsStore.customHeight.value,
                customFps = settingsStore.customFps.value, outputFormat = format, renderMode = rMode,
                animRangeStart = rStart, animRangeEnd = rEnd,
                disableCaching = if (p.has("disable_cache")) p.optBoolean("disable_cache") else settingsStore.disableCache.value,
                autoSaveToStorage = if (p.has("auto_save")) p.optBoolean("auto_save") else settingsStore.autoSaveStorage.value
            )
        }
        "latex.compile" -> runBlocking {
            val ent = workspaceRepo.findOrSyncFile(requirePath(p)) ?: error("File not found")
            val autoSave = if (p.has("auto_save")) p.optBoolean("auto_save") else settingsStore.autoSaveStorage.value
            coordinator.compileLatexBlockingForBridge(ent, workspaceRepo.readFile(ent), autoSave)
        }
        "python.run" -> runBlocking {
            val ent = workspaceRepo.findOrSyncFile(requirePath(p)) ?: error("File not found")
            coordinator.runPythonBlockingForBridge(ent, workspaceRepo.readFile(ent))
        }
        "python.compile" -> runBlocking {
            val ent = workspaceRepo.findOrSyncFile(requirePath(p)) ?: error("File not found")
            coordinator.compilePythonBlockingForBridge(ent, workspaceRepo.readFile(ent), p.optBoolean("native", false))
        }
        "pip.install" -> coordinator.runPipBlockingForBridge("install", p.optString("packages", ""))
        "pip.list" -> coordinator.runPipBlockingForBridge("list", "")

        "voice.models" -> voiceEngine.catalogJson()
        "voice.asr_models" -> voiceEngine.asrCatalogJson()
        "voice.install" -> runBlocking {
            val id = p.getString("model")
            val spec = voiceEngine.catalog.firstOrNull { it.id == id } ?: error("Unknown voice model: $id")
            voiceEngine.installModelBlocking(spec)
            JSONObject().put("installed", true).put("model", spec.id).put("summary", "installed ${spec.id}")
        }
        "voice.delete" -> {
            val id = p.getString("model")
            voiceEngine.deleteModel(id)
            JSONObject().put("deleted", id)
        }
        "voice.normalize_math" -> JSONObject().put("normalized", voiceEngine.normalizer.normalize(p.getString("text")))
        "voice.glossary_get" -> JSONObject().apply { voiceEngine.normalizer.getGlossary().forEach { (k, v) -> put(k, v) } }
        "voice.glossary_set" -> {
            val obj = p.getJSONObject("entries")
            val map = linkedMapOf<String, String>()
            val ks = obj.keys()
            while (ks.hasNext()) { val k = ks.next(); map[k] = obj.optString(k, "") }
            voiceEngine.normalizer.setGlossaryEntries(map)
            JSONObject().put("updated", map.size)
        }
        "voice.say" -> {
            val refRaw = p.optString("ref_wav", "")
            val refHost = if (refRaw.isBlank()) "" else resolveGuestOrHost(refRaw).absolutePath
            val res = voiceEngine.synthesize(
                text = p.getString("text"),
                modelId = p.optString("model", ""),
                sid = p.optInt("sid", -1),
                speed = p.optDouble("speed", 1.0).toFloat(),
                normalizeMath = p.optBoolean("normalize_math", true),
                customOutName = p.optString("out_name", ""),
                refWavHostPath = refHost,
                lang = p.optString("lang", "")
            )
            val wordsArr = JSONArray().apply {
                res.words.forEach { w -> put(JSONObject().put("word", w.word).put("start", w.startSec).put("end", w.endSec)) }
            }
            JSONObject().apply {
                put("path", res.wavGuestPath)
                put("hostPath", res.wavHostPath)
                put("duration", res.durationSec)
                put("sampleRate", res.sampleRate)
                put("normalizedText", res.normalizedText)
                put("cached", res.cached)
                put("model", res.modelId)
                put("sid", res.sid)
                put("words", wordsArr)
                put("summary", String.format(java.util.Locale.US, "%.2fs (%s)", res.durationSec, if (res.cached) "cache" else res.modelId))
            }
        }
        "voice.synthesize_script" -> {
            val arr = p.getJSONArray("lines")
            val lines = (0 until arr.length()).map { arr.getString(it) }
            val refRaw = p.optString("ref_wav", "")
            val refHost = if (refRaw.isBlank()) "" else resolveGuestOrHost(refRaw).absolutePath
            voiceEngine.synthesizeScript(
                lines = lines,
                modelId = p.optString("model", ""),
                sid = p.optInt("sid", -1),
                speed = p.optDouble("speed", 1.0).toFloat(),
                pauseSec = p.optDouble("pause_sec", 0.28),
                normalizeMath = p.optBoolean("normalize_math", true),
                manifestName = p.optString("manifest_name", "timing_manifest.json"),
                srtName = p.optString("srt_name", "captions.srt"),
                lang = p.optString("lang", ""),
                refWavHostPath = refHost
            )
        }
        "voice.align" -> {
            val wavHost = resolveGuestOrHost(p.getString("wav_path")).absolutePath
            voiceEngine.alignWavAudio(wavHost, p.optString("model", ""))
        }
        "voice.release" -> { voiceEngine.release(); JSONObject().put("released", true) }
        "export.hardware_transcode" -> runBlocking {
            automation.releaseHeavyAuxiliaryEngines()
            val inFile = resolveGuestOrHost(p.getString("in_path"))
            val outFile = resolveGuestOrHost(p.getString("out_path"))
            val resFile = hardwareExporter.transcodeClips(
                clips = listOf(HardwareClipSpec(inFile.absolutePath, p.optLong("trim_start_ms", 0L), p.optLong("trim_end_ms", 0L), p.optDouble("zoom", 1.0).toFloat())),
                outFile = outFile,
                targetWidth = p.optInt("width", 1920),
                targetHeight = p.optInt("height", 1080)
            )
            JSONObject().put("outputPath", p.getString("out_path")).put("sizeBytes", resFile.length()).put("summary", "HW transcoded ${resFile.name}")
        }
        "brand.get" -> automation.brandToJson()
        "brand.set" -> { automation.patchBrand(p); automation.brandToJson() }
        "brand.export_theme" -> runBlocking {
            val rel = automation.exportManimThemeFile(p.optString("path", "atelier_theme.py"))
            JSONObject().put("path", rel).put("summary", "exported $rel")
        }
        "batch.list" -> automation.queueToJson()
        "batch.enqueue" -> {
            val item = automation.enqueueJob(p.optString("kind", "MANIM_RENDER"), p.optString("title", "Job"), p.getString("target"), p.optString("extra", ""))
            JSONObject().put("id", item.id).put("status", item.status).put("summary", "queued ${item.title}")
        }
        "batch.start" -> { automation.startBatchQueue(); JSONObject().put("started", true) }
        "batch.pause" -> { automation.pauseBatchQueue(); JSONObject().put("paused", true) }
        "batch.clear" -> { automation.clearCompletedOrAll(false); JSONObject().put("cleared", true) }
        "stage.publish_video" -> coordinator.publishVideoToStageFromBridge(
            guestPath = p.getString("guest_path"),
            title = p.optString("title", ""),
            autoSave = settingsStore.autoSaveStorage.value
        )

        "app.switch_mode" -> {
            val mode = StudioMode.entries.firstOrNull { it.name.equals(p.optString("mode"), true) } ?: error("Expected CODE, EDIT, AUDIO, or CONTROL")
            settingsStore.setStudioMode(mode)
            JSONObject().put("studioMode", mode.name)
        }
        "app.toast" -> {
            settingsStore.emitUiEvent(StudioUiEvent.ShowToast(p.optString("message", "")))
            JSONObject().put("shown", true)
        }
        "app.notify" -> {
            crashReporter.postCustomNotification(p.optString("title", "Atelier"), p.optString("message", ""), p.optString("copy_text", ""))
            JSONObject().put("notified", true)
        }
        "app.clipboard_set" -> {
            val t = p.optString("text", "")
            mediaExport.copyToClipboard("Atelier", t)
            JSONObject().put("copiedChars", t.length).put("summary", "copied ${t.length} chars")
        }
        "app.clipboard_get" -> JSONObject().put("text", mediaExport.readFromClipboard())
        "app.export_artifact" -> {
            val art = coordinator.state.value.activeArtifact
            if (art.path.isBlank() || !File(art.path).exists()) error("No active artifact")
            val f = File(art.path)
            val res = when (art.kind) {
                OutputKind.VIDEO -> mediaExport.saveVideoToStorage(f, art.title.ifBlank { "atelier_video" })
                OutputKind.IMAGE -> mediaExport.saveImageToStorage(f, art.title.ifBlank { "atelier_image" })
                OutputKind.PDF -> mediaExport.saveDocumentToStorage(f, art.title.ifBlank { "atelier_doc" }, "application/pdf")
                OutputKind.NONE -> error("No active artifact")
            }
            JSONObject().put("success", res.success).put("location", res.displayLocation).put("error", res.error)
        }
        "app.macros_list" -> JSONArray().apply {
            settingsStore.macros.value.forEach { m -> put(JSONObject().put("id", m.id).put("name", m.name).put("description", m.description).put("code", m.code)) }
        }
        "app.macros_save" -> {
            val m = settingsStore.saveMacro(p.getString("name"), p.optString("description", ""), p.getString("code"))
            JSONObject().put("id", m.id).put("name", m.name)
        }
        "app.macros_delete" -> JSONObject().put("deleted", settingsStore.deleteMacro(p.getString("id")))

        "tx.begin" -> JSONObject().put("txId", workspaceRepo.beginTransaction(p.optString("label", "AI Controller")))
        "tx.rollback" -> runBlocking {
            val count = workspaceRepo.rollbackTransaction(p.optString("tx_id", ""))
            settingsStore.emitUiEvent(StudioUiEvent.SyncWorkspace)
            JSONObject().put("restoredFiles", count).put("summary", "rolled back $count file(s)")
        }

        else -> error("Unknown RPC method: $method")
    }

    private fun requirePath(p: JSONObject): String =
        p.optString("path", "").trim().ifBlank { error("Missing required 'path' parameter") }

    private fun resolveGuestOrHost(path: String): File = when {
        path.startsWith("/outputs/") -> File(runtimeManager.outputsDir, path.removePrefix("/outputs/"))
        path.startsWith("/workspace/") -> File(workspaceRepo.workspaceDir, path.removePrefix("/workspace/"))
        path.startsWith("/user-site/") -> File(runtimeManager.userSiteDir, path.removePrefix("/user-site/"))
        else -> File(path)
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < 65536) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> return out.toByteArray()
                b == 13 -> 1
                else -> 0
            }
        }
        return null
    }

    private fun writeJson(socket: Socket, code: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        val hdr = "HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }
}
