package com.atelier.manim.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

class ProotEngine(
    private val context: Context,
    private val runtimeManager: RuntimeManager,
    private val threadsProvider: () -> Int = { 2 }
) {
    private val workspaceDir: File
        get() = File(context.filesDir, "workspace").apply { mkdirs() }

    fun createProcessBuilder(
        guestCommand: List<String>,
        workDir: String = "/workspace",
        forceNoSeccomp: Boolean? = null,
        mergeErrorStream: Boolean = true
    ): Pair<ProcessBuilder, File> {
        runtimeManager.ensureFakeProcFiles()
        runtimeManager.ensureRootfsNetworking()
        runtimeManager.userSiteDir.mkdirs()
        runtimeManager.outputsDir.mkdirs()

        val runTmp = File(File(context.filesDir, "proot_tmp").apply { mkdirs() }, UUID.randomUUID().toString())
        runTmp.mkdirs()

        val fakeProc = runtimeManager.fakeProcDir
        val cmd = mutableListOf(
            runtimeManager.prootBinary.absolutePath,
            "--link2symlink",
            "-0",
            "-r", runtimeManager.rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${File(fakeProc, "stat").absolutePath}:/proc/stat",
            "-b", "${File(fakeProc, "version").absolutePath}:/proc/version",
            "-b", "${File(fakeProc, "uptime").absolutePath}:/proc/uptime",
            "-b", "${File(fakeProc, "loadavg").absolutePath}:/proc/loadavg",
            "-b", "${File(fakeProc, "vmstat").absolutePath}:/proc/vmstat",
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-b", "${runtimeManager.userSiteDir.absolutePath}:/user-site",
            "-b", "${runtimeManager.outputsDir.absolutePath}:/outputs",
            "-w", workDir,
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TMPDIR=/tmp",
            "MPLBACKEND=Agg",
            "PYTHONUNBUFFERED=1",
            "VIRTUAL_ENV=/opt/atelier-venv",
            "PYTHONPATH=/user-site:/workspace",
            "PIP_TARGET=/user-site",
            "OMP_NUM_THREADS=${threadsProvider()}",
            "OPENBLAS_NUM_THREADS=${threadsProvider()}",
            "MKL_NUM_THREADS=${threadsProvider()}",
            "PATH=/opt/atelier-venv/bin:/user-site/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
        cmd.addAll(guestCommand)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(mergeErrorStream)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = runtimeManager.nativeLibDir.absolutePath
        env["PROOT_LOADER"] = runtimeManager.loaderBinary.absolutePath
        env["PROOT_TMP_DIR"] = runTmp.absolutePath
        val noSeccomp = forceNoSeccomp ?: runtimeManager.useNoSeccomp
        if (noSeccomp) {
            env["PROOT_NO_SECCOMP"] = "1"
        } else {
            env.remove("PROOT_NO_SECCOMP")
        }
        return pb to runTmp
    }

    fun runQuickProbe(guestCommand: List<String>, forceNoSeccomp: Boolean? = null): Pair<Int, String> {
        val (pb, runTmp) = createProcessBuilder(guestCommand, forceNoSeccomp = forceNoSeccomp, mergeErrorStream = true)
        return try {
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            exit to out
        } catch (t: Throwable) {
            -1 to (t.message ?: "PRoot launch failed")
        } finally {
            runCatching { runTmp.deleteRecursively() }
        }
    }

    fun guestToHostPath(guestPath: String): File {
        return when {
            guestPath.startsWith("/outputs/") -> File(runtimeManager.outputsDir, guestPath.removePrefix("/outputs/"))
            guestPath.startsWith("/workspace/") -> File(workspaceDir, guestPath.removePrefix("/workspace/"))
            guestPath.startsWith("/user-site/") -> File(runtimeManager.userSiteDir, guestPath.removePrefix("/user-site/"))
            else -> File(runtimeManager.rootfsDir, guestPath.removePrefix("/"))
        }
    }
}

class ReplSession(private val prootEngine: ProotEngine) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var tmpDir: File? = null
    private val lock = Any()

    data class EvalResult(
        val ok: Boolean,
        val stdout: String,
        val stderr: String,
        val vars: List<ReplVar>,
        val latex: String,
        val hostImagePath: String
    )

    suspend fun evaluate(code: String): EvalResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureStarted()
            val w = writer ?: return@withContext EvalResult(false, "", "REPL not started.", emptyList(), "", "")
            val r = reader ?: return@withContext EvalResult(false, "", "REPL reader closed.", emptyList(), "", "")
            return@withContext try {
                val payload = JSONObject().put("code", code).toString()
                w.write(payload)
                w.newLine()
                w.flush()
                val respLine = r.readLine() ?: error("REPL process terminated unexpectedly.")
                parseResponse(respLine)
            } catch (t: Throwable) {
                stopLocked()
                EvalResult(false, "", t.message ?: "REPL error", emptyList(), "", "")
            }
        }
    }

    suspend fun reset(): EvalResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (process == null || process?.isAlive != true) {
                ensureStarted()
            }
            val w = writer
            val r = reader
            return@withContext try {
                if (w != null && r != null) {
                    w.write(JSONObject().put("cmd", "reset").toString())
                    w.newLine()
                    w.flush()
                    val line = r.readLine()
                    if (line != null) parseResponse(line) else EvalResult(true, "Session reset.", "", emptyList(), "", "")
                } else {
                    EvalResult(true, "Session reset.", "", emptyList(), "", "")
                }
            } catch (_: Throwable) {
                stopLocked()
                EvalResult(true, "Session restarted.", "", emptyList(), "", "")
            }
        }
    }

    fun close() {
        synchronized(lock) { stopLocked() }
    }

    private fun ensureStarted() {
        if (process?.isAlive == true && writer != null && reader != null) return
        stopLocked()
        val (pb, td) = prootEngine.createProcessBuilder(
            guestCommand = listOf("/opt/atelier-venv/bin/python3", "-u", "/usr/local/bin/atelier-repl.py"),
            mergeErrorStream = false
        )
        val p = pb.start()
        process = p
        tmpDir = td
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
        // Consume handshake line
        reader?.readLine()
    }

    private fun parseResponse(line: String): EvalResult {
        val json = JSONObject(line)
        val varsArr = json.optJSONArray("vars")
        val vars = mutableListOf<ReplVar>()
        if (varsArr != null) {
            for (i in 0 until varsArr.length()) {
                val o = varsArr.getJSONObject(i)
                vars.add(ReplVar(o.optString("name"), o.optString("type"), o.optString("repr")))
            }
        }
        val guestImg = json.optString("image", "")
        val hostImg = if (guestImg.isNotBlank()) prootEngine.guestToHostPath(guestImg).absolutePath else ""
        return EvalResult(
            ok = json.optBoolean("ok", true),
            stdout = json.optString("stdout", ""),
            stderr = json.optString("stderr", ""),
            vars = vars,
            latex = json.optString("latex", ""),
            hostImagePath = hostImg
        )
    }

    private fun stopLocked() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroyForcibly() }
        runCatching { tmpDir?.deleteRecursively() }
        writer = null
        reader = null
        process = null
        tmpDir = null
    }
}
