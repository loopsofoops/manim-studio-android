package com.atelier.manim.runtime

import android.content.Context
import android.media.MediaPlayer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class VoiceEngineKind { KOKORO, KITTEN, VITS_PIPER }

data class VoiceModelSpec(
    val id: String,
    val name: String,
    val kind: VoiceEngineKind,
    val approxSizeLabel: String,
    val license: String,
    val description: String,
    val archiveUrl: String,
    val extractedFolderName: String,
    val modelFileName: String,
    val voicesFileName: String = "",
    val tokensFileName: String = "tokens.txt",
    val dataDirName: String = "espeak-ng-data",
    val lexiconFileName: String = "",
    val defaultSid: Int = 0,
    val speakerLabels: List<String> = emptyList()
)

data class VoiceDownloadState(
    val activeModelId: String = "",
    val stage: String = "",
    val progress: Float = -1f,
    val error: String = ""
)

data class WordTimestamp(val word: String, val startSec: Double, val endSec: Double)

data class SpeechResult(
    val wavHostPath: String,
    val wavGuestPath: String,
    val durationSec: Double,
    val sampleRate: Int,
    val normalizedText: String,
    val cached: Boolean,
    val modelId: String,
    val sid: Int,
    val words: List<WordTimestamp>
)

class OfflineVoiceEngine(
    private val context: Context,
    private val runtimeManager: RuntimeManager,
    private val settingsStore: StudioSettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val modelsRoot: File = File(context.filesDir, "voice_models").apply { mkdirs() }
    private val dlDir: File = File(context.filesDir, "voice_dl").apply { mkdirs() }
    val cacheDir: File = File(File(runtimeManager.outputsDir, "audio"), "tts_cache").apply { mkdirs() }
    val normalizer = MathSpeechNormalizer(context)

    val catalog: List<VoiceModelSpec> = listOf(
        VoiceModelSpec(
            id = "kitten_nano_en",
            name = "Kitten Nano EN v0.1 (FP16)",
            kind = VoiceEngineKind.KITTEN,
            approxSizeLabel = "~35 MB",
            license = "Apache-2.0",
            description = "Ultra-light fast English narrator (8 voices, lowest RAM for quick drafts)",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2",
            extractedFolderName = "kitten-nano-en-v0_1-fp16",
            modelFileName = "model.fp16.onnx",
            voicesFileName = "voices.bin",
            defaultSid = 0,
            speakerLabels = listOf("0: Female 1", "1: Male 1", "2: Female 2", "3: Male 2", "4: Female 3", "5: Male 3", "6: Female 4", "7: Male 4")
        ),
        VoiceModelSpec(
            id = "kokoro_en_019",
            name = "Kokoro EN v0.19 (FP32 Studio)",
            kind = VoiceEngineKind.KOKORO,
            approxSizeLabel = "~305 MB",
            license = "Apache-2.0",
            description = "Broadcast-grade documentary English (11 voices; FP32 avoids ARM64 INT8 distortion)",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2",
            extractedFolderName = "kokoro-en-v0_19",
            modelFileName = "model.onnx",
            voicesFileName = "voices.bin",
            defaultSid = 9,
            speakerLabels = listOf(
                "0: af", "1: af_bella", "2: af_nicole", "3: af_sarah", "4: af_sky",
                "5: am_adam", "6: am_michael", "7: bf_emma", "8: bf_isabella", "9: bm_george", "10: bm_lewis"
            )
        ),
        VoiceModelSpec(
            id = "kokoro_multi_10",
            name = "Kokoro Multi-Lang v1.0 (FP32)",
            kind = VoiceEngineKind.KOKORO,
            approxSizeLabel = "~330 MB",
            license = "Apache-2.0",
            description = "53 voices across English, Hindi, Japanese, French, Spanish & more",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
            extractedFolderName = "kokoro-multi-lang-v1_0",
            modelFileName = "model.onnx",
            voicesFileName = "voices.bin",
            lexiconFileName = "lexicon-us-en.txt",
            defaultSid = 0
        ),
        VoiceModelSpec(
            id = "piper_en_ryan",
            name = "Piper EN-US Ryan High",
            kind = VoiceEngineKind.VITS_PIPER,
            approxSizeLabel = "~64 MB",
            license = "MIT",
            description = "Crisp deep male technical narrator, very fast on mobile CPU",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-high.tar.bz2",
            extractedFolderName = "vits-piper-en_US-ryan-high",
            modelFileName = "en_US-ryan-high.onnx",
            defaultSid = 0,
            speakerLabels = listOf("0: Ryan (US Male)")
        ),
        VoiceModelSpec(
            id = "piper_hi_rohan",
            name = "Piper Hindi Rohan Medium",
            kind = VoiceEngineKind.VITS_PIPER,
            approxSizeLabel = "~62 MB",
            license = "MIT",
            description = "Clean native Hindi male voice for Indian math channel narration/dubs",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-rohan-medium.tar.bz2",
            extractedFolderName = "vits-piper-hi_IN-rohan-medium",
            modelFileName = "hi_IN-rohan-medium.onnx",
            defaultSid = 0,
            speakerLabels = listOf("0: Rohan (Hindi Male)")
        )
    )

    private val _installedIds = MutableStateFlow<Set<String>>(emptySet())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    private val _downloadState = MutableStateFlow(VoiceDownloadState())
    val downloadState: StateFlow<VoiceDownloadState> = _downloadState.asStateFlow()

    private val ttsLock = Any()
    private var loadedModelId: String = ""
    private var ttsInstance: OfflineTts? = null
    private var downloadJob: Job? = null
    private var previewPlayer: MediaPlayer? = null

    init { refreshInstalled() }

    fun refreshInstalled() {
        _installedIds.value = catalog.filter { isModelInstalled(it) }.map { it.id }.toSet()
    }

    fun isModelInstalled(spec: VoiceModelSpec): Boolean {
        val dir = modelDir(spec)
        return File(dir, spec.modelFileName).exists() && File(dir, spec.tokensFileName).exists()
    }

    fun resolveSpec(modelId: String): VoiceModelSpec {
        if (modelId.isNotBlank()) {
            catalog.firstOrNull { it.id.equals(modelId, true) }?.let { return it }
        }
        val installed = catalog.firstOrNull { isModelInstalled(it) }
        return installed ?: catalog.first()
    }

    fun startInstallModel(modelId: String) {
        val spec = catalog.firstOrNull { it.id == modelId } ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                installModelBlocking(spec)
            } catch (_: CancellationException) {
                _downloadState.value = VoiceDownloadState()
            } catch (t: Throwable) {
                _downloadState.value = VoiceDownloadState(error = t.message ?: "Voice model install failed.")
            }
        }
    }

    fun cancelInstall() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = VoiceDownloadState()
    }

    suspend fun installModelBlocking(spec: VoiceModelSpec): Boolean = withContext(Dispatchers.IO) {
        if (isModelInstalled(spec)) {
            refreshInstalled()
            return@withContext true
        }
        val partFile = File(dlDir, "${spec.id}.tar.bz2.part")
        _downloadState.value = VoiceDownloadState(spec.id, "Downloading ${spec.name}...", 0f)
        downloadWithResume(spec.archiveUrl, partFile, spec.id, spec.name)

        _downloadState.value = VoiceDownloadState(spec.id, "Extracting ${spec.name}...", 0.92f)
        extractTarBz2Atomic(partFile, spec)
        partFile.delete()

        if (!isModelInstalled(spec)) error("Archive extracted but expected ${spec.modelFileName} was not found.")
        refreshInstalled()
        _downloadState.value = VoiceDownloadState()
        true
    }

    fun deleteModel(modelId: String) {
        val spec = catalog.firstOrNull { it.id == modelId } ?: return
        synchronized(ttsLock) {
            if (loadedModelId == modelId) releaseLocked()
        }
        modelDir(spec).deleteRecursively()
        File(dlDir, "${spec.id}.tar.bz2.part").delete()
        refreshInstalled()
    }

    fun release() {
        synchronized(ttsLock) { releaseLocked() }
        runCatching { previewPlayer?.stop(); previewPlayer?.release() }
        previewPlayer = null
    }

    fun playPreviewWav(hostPath: String) {
        runCatching {
            previewPlayer?.stop()
            previewPlayer?.release()
            val mp = MediaPlayer()
            mp.setDataSource(hostPath)
            mp.prepare()
            mp.start()
            previewPlayer = mp
        }
    }

    fun synthesize(
        text: String,
        modelId: String = "",
        sid: Int = -1,
        speed: Float = 1.0f,
        normalizeMath: Boolean = true,
        customOutName: String = ""
    ): SpeechResult {
        val spec = resolveSpec(modelId)
        if (!isModelInstalled(spec)) {
            error("Voice model '${spec.id}' is not installed. Download it in Settings -> Offline Voice Models or call atelier.voice.install('${spec.id}').")
        }
        val effSid = if (sid >= 0) sid else spec.defaultSid
        val effSpeed = speed.coerceIn(0.5f, 2.0f)
        val spoken = if (normalizeMath) normalizer.normalize(text) else text.trim()
        if (spoken.isBlank()) error("Empty narration text after normalization.")

        cacheDir.mkdirs()
        val key = sha256("${spec.id}|$effSid|${String.format(Locale.US, "%.2f", effSpeed)}|$spoken").take(20)
        val fileName = if (customOutName.isNotBlank()) {
            val clean = customOutName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (clean.endsWith(".wav", true)) clean else "$clean.wav"
        } else "tts_${spec.id}_${effSid}_$key.wav"
        val outFile = File(cacheDir, fileName)
        val guestPath = "/outputs/audio/tts_cache/${outFile.name}"

        if (outFile.exists() && outFile.length() > 44L) {
            val (sr, dur) = readWavHeaderInfo(outFile)
            val words = estimateWordTimestamps(spoken, dur)
            return SpeechResult(outFile.absolutePath, guestPath, dur, sr, spoken, true, spec.id, effSid, words)
        }

        val audio = synchronized(ttsLock) {
            val engine = ensureLoadedLocked(spec)
            engine.generate(text = spoken, sid = effSid, speed = effSpeed)
        }
        if (audio.samples.isEmpty()) error("TTS returned empty audio samples.")
        writeNormalizedPcm16Wav(outFile, audio.samples, audio.sampleRate)
        val durationSec = audio.samples.size.toDouble() / max(1, audio.sampleRate).toDouble()
        val words = estimateWordTimestamps(spoken, durationSec)
        return SpeechResult(outFile.absolutePath, guestPath, durationSec, audio.sampleRate, spoken, false, spec.id, effSid, words)
    }

    fun synthesizeScript(
        lines: List<String>,
        modelId: String = "",
        sid: Int = -1,
        speed: Float = 1.0f,
        pauseSec: Double = 0.28,
        normalizeMath: Boolean = true,
        manifestName: String = "timing_manifest.json",
        srtName: String = "captions.srt"
    ): JSONObject {
        val cuesArr = JSONArray()
        val srtSb = StringBuilder()
        var cursorSec = 0.0
        var srtIdx = 1

        lines.forEachIndexed { idx, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val res = synthesize(trimmed, modelId, sid, speed, normalizeMath)
            val start = cursorSec
            val end = cursorSec + res.durationSec
            val wordsArr = JSONArray()
            res.words.forEach { w ->
                wordsArr.put(JSONObject().put("word", w.word).put("start", round3(start + w.startSec)).put("end", round3(start + w.endSec)))
            }
            cuesArr.put(JSONObject().apply {
                put("index", idx)
                put("text", trimmed)
                put("spokenText", res.normalizedText)
                put("wavPath", res.wavGuestPath)
                put("startSec", round3(start))
                put("endSec", round3(end))
                put("durationSec", round3(res.durationSec))
                put("cached", res.cached)
                put("words", wordsArr)
            })
            srtSb.appendLine(srtIdx++)
            srtSb.appendLine("${formatSrtTime(start)} --> ${formatSrtTime(end)}")
            srtSb.appendLine(trimmed)
            srtSb.appendLine()
            cursorSec = end + max(0.0, pauseSec)
        }

        val audioOutDir = File(runtimeManager.outputsDir, "audio").apply { mkdirs() }
        val manifestFile = File(audioOutDir, manifestName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val srtFile = File(audioOutDir, srtName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val payload = JSONObject().apply {
            put("totalDurationSec", round3(cursorSec))
            put("cueCount", cuesArr.length())
            put("manifestPath", "/outputs/audio/${manifestFile.name}")
            put("srtPath", "/outputs/audio/${srtFile.name}")
            put("cues", cuesArr)
        }
        manifestFile.writeText(payload.toString(2), Charsets.UTF_8)
        srtFile.writeText(srtSb.toString(), Charsets.UTF_8)
        return payload
    }

    fun catalogJson(): JSONArray = JSONArray().apply {
        for (spec in catalog) {
            put(JSONObject().apply {
                put("id", spec.id)
                put("name", spec.name)
                put("kind", spec.kind.name)
                put("size", spec.approxSizeLabel)
                put("license", spec.license)
                put("installed", isModelInstalled(spec))
                put("defaultSid", spec.defaultSid)
                put("speakers", JSONArray(spec.speakerLabels))
            })
        }
    }

    fun cacheStatsSummary(): String {
        val wavs = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: emptyList()
        val bytes = wavs.sumOf { it.length() }
        return "${wavs.size} cached WAVs (${formatBytesShort(bytes)})"
    }

    private fun modelDir(spec: VoiceModelSpec): File = File(modelsRoot, spec.extractedFolderName)

    private fun ensureLoadedLocked(spec: VoiceModelSpec): OfflineTts {
        val cur = ttsInstance
        if (cur != null && loadedModelId == spec.id) return cur
        releaseLocked()

        val dir = modelDir(spec)
        val modelPath = File(dir, spec.modelFileName).absolutePath
        val tokensPath = File(dir, spec.tokensFileName).absolutePath
        val dataDirPath = File(dir, spec.dataDirName).takeIf { it.exists() }?.absolutePath ?: ""
        val lexiconPath = if (spec.lexiconFileName.isNotBlank()) {
            File(dir, spec.lexiconFileName).takeIf { it.exists() }?.absolutePath ?: ""
        } else ""
        val voicesPath = if (spec.voicesFileName.isNotBlank()) File(dir, spec.voicesFileName).absolutePath else ""
        val threads = settingsStore.computeProfile.value.threads.coerceIn(2, 4)

        val modelCfg = when (spec.kind) {
            VoiceEngineKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = modelPath, voices = voicesPath, tokens = tokensPath,
                    dataDir = dataDirPath, lexicon = lexiconPath
                ),
                numThreads = threads, debug = false, provider = "cpu"
            )
            VoiceEngineKind.KITTEN -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = modelPath, voices = voicesPath, tokens = tokensPath, dataDir = dataDirPath
                ),
                numThreads = threads, debug = false, provider = "cpu"
            )
            VoiceEngineKind.VITS_PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath, lexicon = lexiconPath, tokens = tokensPath, dataDir = dataDirPath
                ),
                numThreads = threads, debug = false, provider = "cpu"
            )
        }
        val created = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelCfg, maxNumSentences = 1))
        ttsInstance = created
        loadedModelId = spec.id
        return created
    }

    private fun releaseLocked() {
        runCatching { ttsInstance?.release() }
        ttsInstance = null
        loadedModelId = ""
    }

    private suspend fun downloadWithResume(url: String, partFile: File, modelId: String, name: String) = withContext(Dispatchers.IO) {
        var existing = if (partFile.exists()) partFile.length() else 0L
        var conn = openRedirectSafe(url, if (existing > 0L) existing else -1L)
        var code = conn.responseCode
        if (code == 416) {
            conn.disconnect(); partFile.delete(); existing = 0L
            conn = openRedirectSafe(url, -1L); code = conn.responseCode
        }
        try {
            if (code !in 200..299) error("HTTP $code downloading $name")
            val append = (code == 206 && existing > 0L)
            if (!append) existing = 0L
            val contentLen = conn.contentLengthLong
            val total = if (contentLen > 0L) (if (append) existing + contentLen else contentLen) else 0L
            var downloaded = existing
            var lastTick = 0L
            val buf = ByteArray(131072)
            conn.inputStream.use { inp ->
                FileOutputStream(partFile, append).use { out ->
                    while (isActive) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 250L) {
                            val frac = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 0.9f) else -1f
                            _downloadState.value = VoiceDownloadState(
                                modelId, "Downloading $name (${formatBytesShort(downloaded)} / ${formatBytesShort(total)})", frac
                            )
                            lastTick = now
                        }
                    }
                    out.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTarBz2Atomic(archive: File, spec: VoiceModelSpec) {
        val stage = File(modelsRoot, ".stage_${spec.id}")
        if (stage.exists()) stage.deleteRecursively()
        stage.mkdirs()
        val canonicalStage = stage.canonicalPath
        val buf = ByteArray(131072)

        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis, 262144).use { bis ->
                BZip2CompressorInputStream(bis, true).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        while (entry != null) {
                            val raw = entry.name.removePrefix("./").removePrefix("/")
                            if (raw.isNotEmpty() && !raw.contains("..")) {
                                val dest = File(stage, raw)
                                val pCanon = dest.parentFile?.apply { mkdirs() }?.canonicalPath ?: canonicalStage
                                if (pCanon == canonicalStage || pCanon.startsWith("$canonicalStage/")) {
                                    if (entry.isDirectory) dest.mkdirs()
                                    else if (entry.isFile) {
                                        FileOutputStream(dest).use { out ->
                                            while (true) {
                                                val n = tar.read(buf); if (n <= 0) break; out.write(buf, 0, n)
                                            }
                                        }
                                    }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
        val nested = File(stage, spec.extractedFolderName)
        val sourceDir = if (nested.exists() && nested.isDirectory) nested else stage
        val target = modelDir(spec)
        if (target.exists()) target.deleteRecursively()
        if (!sourceDir.renameTo(target)) {
            sourceDir.copyRecursively(target, overwrite = true)
        }
        if (stage.exists()) stage.deleteRecursively()
    }

    private fun openRedirectSafe(startUrl: String, rangeStart: Long): HttpURLConnection {
        var current = startUrl
        repeat(8) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 45_000; requestMethod = "GET"
                setRequestProperty("User-Agent", "Atelier-Android/1.3")
                if (rangeStart > 0L) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                if (loc.isNullOrBlank()) error("Redirect without Location")
                current = URL(URL(current), loc).toString()
            } else return c
        }
        error("Too many redirects")
    }

    private fun writeNormalizedPcm16Wav(outFile: File, samples: FloatArray, sampleRate: Int) {
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        val gain = if (peak > 1e-4f) min(1.0f, 0.89f / peak) else 1.0f
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()

        val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s * gain * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            pcm.putShort(v)
        }
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out -> out.write(header); out.write(pcm.array()); out.fd.sync() }
    }

    private fun readWavHeaderInfo(file: File): Pair<Int, Double> = runCatching {
        val hdr = ByteArray(44)
        FileInputStream(file).use { it.read(hdr) }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val sr = bb.getInt(24).coerceAtLeast(8000)
        val dataSize = max(0L, file.length() - 44L)
        sr to (dataSize.toDouble() / (sr * 2.0))
    }.getOrDefault(24000 to 0.0)

    private fun estimateWordTimestamps(text: String, totalDurSec: Double): List<WordTimestamp> {
        val tokens = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || totalDurSec <= 0.0) return emptyList()
        val weights = tokens.map { w ->
            val base = max(2, w.count { it.isLetterOrDigit() }).toDouble()
            val punctBonus = when { w.endsWith(".") || w.endsWith("!") || w.endsWith("?") -> 4.0; w.endsWith(",") || w.endsWith(";") -> 2.0; else -> 0.0 }
            base + punctBonus
        }
        val sumW = weights.sum().coerceAtLeast(1.0)
        var t = 0.0
        return tokens.mapIndexed { i, w ->
            val d = totalDurSec * (weights[i] / sumW)
            val item = WordTimestamp(w, round3(t), round3(t + d))
            t += d
            item
        }
    }

    private fun formatSrtTime(sec: Double): String {
        val msTotal = max(0L, (sec * 1000.0).roundToInt().toLong())
        val h = msTotal / 3600000L; val m = (msTotal % 3600000L) / 60000L; val s = (msTotal % 60000L) / 1000L; val ms = msTotal % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    private fun round3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { String.format("%02x", it) }
}
