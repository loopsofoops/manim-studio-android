package com.atelier.manim.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import com.atelier.manim.BuildConfig
import com.github.luben.zstd.ZstdInputStream
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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

class RuntimeManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("atelier_runtime", Context.MODE_PRIVATE)

    val rootfsDir: File = File(context.filesDir, "rootfs")
    val stagingDir: File = File(context.filesDir, "rootfs.staging")
    val downloadDir: File = File(context.filesDir, "runtime_dl").apply { mkdirs() }
    val fakeProcDir: File = File(context.filesDir, "proot_fake_proc").apply { mkdirs() }
    val userSiteDir: File = File(context.filesDir, "user_site").apply { mkdirs() }
    val outputsDir: File = File(context.filesDir, "outputs").apply { mkdirs() }
    private val readyMarker = File(rootfsDir, ".atelier_ready")

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Checking)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var installJob: Job? = null

    val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    val prootBinary: File
        get() = File(nativeLibDir, "libproot.so")

    val loaderBinary: File
        get() = File(nativeLibDir, "libproot-loader.so")

    var useNoSeccomp: Boolean
        get() = prefs.getBoolean("use_no_seccomp", true)
        set(value) = prefs.edit().putBoolean("use_no_seccomp", value).apply()

    var customManifestUrl: String
        get() = prefs.getString("custom_manifest_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_manifest_url", value.trim()).apply()

    fun defaultManifestUrl(): String {
        val repo = BuildConfig.GITHUB_REPO
        val tag = BuildConfig.RUNTIME_TAG
        return "https://github.com/$repo/releases/download/$tag/rootfs-manifest.json"
    }

    fun activeManifestUrl(): String = customManifestUrl.ifBlank { defaultManifestUrl() }

    fun hasProotBinaries(): Boolean = prootBinary.exists() && loaderBinary.exists()

    fun freeStorageBytes(): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun refreshState() {
        scope.launch {
            _state.value = RuntimeState.Checking
            ensureFakeProcFiles()

            if (readyMarker.exists() && hasProotBinaries()) {
                val info = readInstalledInfo()
                if (info != null) {
                    _state.value = RuntimeState.Ready(info)
                    return@launch
                }
            }

            val free = freeStorageBytes()
            val manifest = fetchManifestSilent()
            val required = max(1_800_000_000L, (manifest?.estimatedExtractedBytes ?: 1_400_000_000L) + 450_000_000L)
            _state.value = RuntimeState.NotInstalled(
                freeBytes = free,
                requiredBytes = required,
                manifest = manifest,
                prootPresent = hasProotBinaries(),
                statusMessage = when {
                    !hasProotBinaries() -> "PRoot binaries missing from APK native libraries."
                    manifest == null -> "Runtime manifest not yet reachable. Verify build-rootfs finished, or tap Retry."
                    else -> ""
                }
            )
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        refreshState()
    }

    fun startInstall() {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            try {
                if (!hasProotBinaries()) {
                    _state.value = RuntimeState.Error("PRoot native binaries are missing in this APK build.")
                    return@launch
                }
                _state.value = RuntimeState.SelfTesting("Fetching runtime manifest...")
                val manifest = fetchManifest()
                val required = max(1_600_000_000L, manifest.estimatedExtractedBytes + 350_000_000L)
                val free = freeStorageBytes()
                if (free < required) {
                    _state.value = RuntimeState.Error(
                        "Insufficient storage: ${formatBytesShort(free)} free, ${formatBytesShort(required)} needed."
                    )
                    return@launch
                }

                val partFile = File(downloadDir, "rootfs-arm64.tar.zst.part")
                downloadWithResume(manifest.downloadUrl, partFile, manifest.compressedBytes)

                if (manifest.sha256.isNotBlank()) {
                    verifySha256(partFile, manifest.sha256)
                }

                extractRootfsAtomic(partFile, manifest.compressedBytes)
                partFile.delete()

                ensureFakeProcFiles()
                ensureRootfsNetworking()

                _state.value = RuntimeState.SelfTesting("Verifying Python, Manim, LaTeX, FFmpeg, and GCC...")
                val verifiedNoSeccomp = runSelfTestAndProbeSeccomp()
                useNoSeccomp = verifiedNoSeccomp

                val info = RuntimeInfo(
                    version = manifest.version,
                    pythonVersion = manifest.pythonVersion,
                    manimVersion = manifest.manimVersion,
                    gccVersion = manifest.gccVersion,
                    texVersion = manifest.texVersion,
                    useNoSeccomp = verifiedNoSeccomp,
                    rootfsSizeBytes = manifest.estimatedExtractedBytes
                )
                writeInstalledInfo(info)
                _state.value = RuntimeState.Ready(info)
            } catch (_: CancellationException) {
                refreshState()
            } catch (t: Throwable) {
                _state.value = RuntimeState.Error(t.message ?: "Runtime installation failed.")
            }
        }
    }

    fun reinstallRuntime() {
        scope.launch {
            installJob?.cancel()
            readyMarker.delete()
            rootfsDir.deleteRecursively()
            stagingDir.deleteRecursively()
            File(downloadDir, "rootfs-arm64.tar.zst.part").delete()
            refreshState()
        }
    }

    fun ensureFakeProcFiles() {
        fakeProcDir.mkdirs()
        val cores = max(1, Runtime.getRuntime().availableProcessors())
        val statText = buildString {
            appendLine("cpu  1000 0 1000 100000 0 0 0 0 0 0")
            for (i in 0 until cores) appendLine("cpu$i 250 0 250 25000 0 0 0 0 0 0")
            appendLine("intr 0")
            appendLine("ctxt 10000")
            appendLine("btime 1700000000")
            appendLine("processes 100")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
        }
        File(fakeProcDir, "stat").writeText(statText)
        File(fakeProcDir, "version").writeText("Linux version 6.1.0-atelier (gcc 13.2.0) #1 SMP PREEMPT aarch64\n")
        File(fakeProcDir, "uptime").writeText("3600.00 7200.00\n")
        File(fakeProcDir, "loadavg").writeText("0.15 0.12 0.10 1/128 4096\n")
        File(fakeProcDir, "vmstat").writeText("nr_free_pages 262144\nnr_active_anon 65536\n")
    }

    fun ensureRootfsNetworking() {
        val etc = File(rootfsDir, "etc").apply { mkdirs() }
        val resolv = File(etc, "resolv.conf")
        if (!resolv.exists() || resolv.length() == 0L) {
            runCatching { resolv.delete() }
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        val hosts = File(etc, "hosts")
        if (!hosts.exists() || hosts.length() == 0L) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
        File(rootfsDir, "tmp").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0x1FF) }
        }
    }

    private fun fetchManifestSilent(): RuntimeManifest? = runCatching { fetchManifest() }.getOrNull()

    private fun fetchManifest(): RuntimeManifest {
        val conn = openRedirectSafe(activeManifestUrl(), rangeStart = -1L)
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("Manifest HTTP $code from ${activeManifestUrl()}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            return RuntimeManifest(
                version = json.optString("version", "1.0.0"),
                sha256 = json.optString("sha256", ""),
                compressedBytes = json.optLong("compressedBytes", 0L),
                estimatedExtractedBytes = json.optLong("estimatedExtractedBytes", 1_400_000_000L),
                downloadUrl = json.getString("downloadUrl"),
                pythonVersion = json.optString("pythonVersion", "3.12"),
                manimVersion = json.optString("manimVersion", "0.19+"),
                gccVersion = json.optString("gccVersion", "13"),
                texVersion = json.optString("texVersion", "TeX Live")
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadWithResume(url: String, partFile: File, expectedTotal: Long) =
        withContext(Dispatchers.IO) {
            var existing = if (partFile.exists()) partFile.length() else 0L
            if (expectedTotal > 0L && existing == expectedTotal) {
                _state.value = RuntimeState.Downloading(existing, expectedTotal, 0L)
                return@withContext
            }
            if (expectedTotal > 0L && existing > expectedTotal) {
                partFile.delete()
                existing = 0L
            }

            var conn = openRedirectSafe(url, rangeStart = if (existing > 0L) existing else -1L)
            var code = conn.responseCode
            if (code == 416) {
                conn.disconnect()
                partFile.delete()
                existing = 0L
                conn = openRedirectSafe(url, rangeStart = -1L)
                code = conn.responseCode
            }

            try {
                if (code !in 200..299) error("Download failed with HTTP $code")
                val append = (code == 206 && existing > 0L)
                if (!append) existing = 0L

                val contentLen = conn.contentLengthLong
                val total = when {
                    append && contentLen > 0L -> existing + contentLen
                    contentLen > 0L -> contentLen
                    expectedTotal > 0L -> expectedTotal
                    else -> 0L
                }

                var downloaded = existing
                var lastReportTime = System.currentTimeMillis()
                var lastReportBytes = downloaded
                val buf = ByteArray(131072)

                conn.inputStream.use { input ->
                    FileOutputStream(partFile, append).use { out ->
                        while (isActive) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            val dt = now - lastReportTime
                            if (dt >= 250L) {
                                val speed = ((downloaded - lastReportBytes) * 1000L) / max(1L, dt)
                                _state.value = RuntimeState.Downloading(downloaded, total, speed)
                                lastReportTime = now
                                lastReportBytes = downloaded
                            }
                        }
                        out.fd.sync()
                    }
                }
                _state.value = RuntimeState.Downloading(downloaded, max(total, downloaded), 0L)
            } finally {
                conn.disconnect()
            }
        }

    private fun openRedirectSafe(startUrl: String, rangeStart: Long): HttpURLConnection {
        var current = startUrl
        repeat(8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Atelier-Android/1.0")
                if (rangeStart > 0L) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) error("HTTP redirect without Location header")
                current = URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        error("Too many HTTP redirects downloading runtime.")
    }

    private suspend fun verifySha256(file: File, expectedHex: String) = withContext(Dispatchers.IO) {
        val total = max(1L, file.length())
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(131072)
        var readTotal = 0L
        var lastTick = 0L

        FileInputStream(file).use { input ->
            while (isActive) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                readTotal += n
                val now = System.currentTimeMillis()
                if (now - lastTick >= 200L) {
                    _state.value = RuntimeState.Verifying(min(1f, readTotal.toFloat() / total.toFloat()))
                    lastTick = now
                }
            }
        }
        val actual = md.digest().joinToString("") { String.format("%02x", it) }
        if (!actual.equals(expectedHex.trim(), ignoreCase = true)) {
            file.delete()
            error("SHA-256 verification failed. Partial file removed; please retry.")
        }
        _state.value = RuntimeState.Verifying(1f)
    }

    private suspend fun extractRootfsAtomic(archiveFile: File, compressedBytes: Long) =
        withContext(Dispatchers.IO) {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            val canonicalRoot = stagingDir.canonicalPath
            val deferredHardlinks = mutableListOf<Pair<String, File>>()
            val totalCompressed = max(1L, if (compressedBytes > 0L) compressedBytes else archiveFile.length())
            val buf = ByteArray(131072)
            var fileCount = 0
            var lastTick = 0L

            FileInputStream(archiveFile).use { fis ->
                val channel = fis.channel
                BufferedInputStream(fis, 262144).use { bis ->
                    ZstdInputStream(bis).use { zstd ->
                        TarArchiveInputStream(zstd).use { tar ->
                            var entry: TarArchiveEntry? = tar.nextEntry
                            while (entry != null && isActive) {
                                val rawName = entry.name.removePrefix("./").removePrefix("/")
                                if (rawName.isNotEmpty() && !rawName.contains("..")) {
                                    val dest = File(stagingDir, rawName)
                                    val parentCanonical = dest.parentFile?.apply { mkdirs() }?.canonicalPath ?: canonicalRoot
                                    if (parentCanonical == canonicalRoot || parentCanonical.startsWith("$canonicalRoot/")) {
                                        when {
                                            entry.isDirectory -> {
                                                dest.mkdirs()
                                                runCatching { Os.chmod(dest.absolutePath, (entry.mode and 0x1FF) or 0x1C0) }
                                            }
                                            entry.isSymbolicLink -> {
                                                if (dest.exists() || isSymlink(dest)) dest.delete()
                                                runCatching { Os.symlink(entry.linkName, dest.absolutePath) }
                                            }
                                            entry.isLink -> {
                                                deferredHardlinks.add(entry.linkName.removePrefix("./").removePrefix("/") to dest)
                                            }
                                            entry.isFile -> {
                                                if (dest.exists() || isSymlink(dest)) dest.delete()
                                                FileOutputStream(dest).use { out ->
                                                    while (true) {
                                                        val n = tar.read(buf)
                                                        if (n <= 0) break
                                                        out.write(buf, 0, n)
                                                    }
                                                }
                                                runCatching { Os.chmod(dest.absolutePath, (entry.mode and 0x1FF) or 0x180) }
                                            }
                                        }
                                        fileCount++
                                        val now = System.currentTimeMillis()
                                        if (now - lastTick >= 200L) {
                                            val pos = runCatching { channel.position() }.getOrDefault(0L)
                                            val frac = min(0.99f, pos.toFloat() / totalCompressed.toFloat())
                                            _state.value = RuntimeState.Extracting(fileCount, rawName.takeLast(44), frac)
                                            lastTick = now
                                        }
                                    }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
            }

            for ((targetRel, dest) in deferredHardlinks) {
                val src = File(stagingDir, targetRel)
                if (src.exists() && src.isFile) {
                    dest.parentFile?.mkdirs()
                    runCatching {
                        src.copyTo(dest, overwrite = true)
                        Os.chmod(dest.absolutePath, 0x1ED)
                    }
                }
            }

            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            if (!stagingDir.renameTo(rootfsDir)) error("Failed to finalize rootfs directory move.")
            _state.value = RuntimeState.Extracting(fileCount, "Complete", 1f)
        }

    private fun isSymlink(file: File): Boolean = runCatching {
        (Os.lstat(file.absolutePath).st_mode and 0xF000) == 0xA000
    }.getOrDefault(false)

    private fun runSelfTestAndProbeSeccomp(): Boolean {
        val engine = ProotEngine(context, this)
        val cmd = listOf(
            "/opt/atelier-venv/bin/python3", "-c",
            "import manim, numpy, sympy, Cython; print('ATELIER_SELF_TEST_OK')"
        )
        for (noSeccomp in listOf(true, false)) {
            val res = engine.runQuickProbe(cmd, forceNoSeccomp = noSeccomp)
            if (res.first == 0 && res.second.contains("ATELIER_SELF_TEST_OK")) return noSeccomp
        }
        error("Runtime self-test failed inside PRoot. Confirm 64-bit ARM64 device.")
    }

    private fun writeInstalledInfo(info: RuntimeInfo) {
        val json = JSONObject().apply {
            put("version", info.version)
            put("pythonVersion", info.pythonVersion)
            put("manimVersion", info.manimVersion)
            put("gccVersion", info.gccVersion)
            put("texVersion", info.texVersion)
            put("useNoSeccomp", info.useNoSeccomp)
            put("rootfsSizeBytes", info.rootfsSizeBytes)
        }
        readyMarker.writeText(json.toString(2))
    }

    private fun readInstalledInfo(): RuntimeInfo? = runCatching {
        val json = JSONObject(readyMarker.readText())
        RuntimeInfo(
            version = json.optString("version", "1.0.0"),
            pythonVersion = json.optString("pythonVersion", "3.12"),
            manimVersion = json.optString("manimVersion", "0.19+"),
            gccVersion = json.optString("gccVersion", "13"),
            texVersion = json.optString("texVersion", "TeX Live"),
            useNoSeccomp = json.optBoolean("useNoSeccomp", true),
            rootfsSizeBytes = json.optLong("rootfsSizeBytes", 0L)
        )
    }.getOrNull()
}
