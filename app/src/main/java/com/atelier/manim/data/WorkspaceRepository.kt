package com.atelier.manim.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object StarterTemplates {
    private const val D = "$"

    val fourierManim: String = """
from manim import *
import numpy as np


class EulerFourierScene(Scene):
    def construct(self):
        self.camera.background_color = "#070709"

        title = MathTex(r"e^{i\theta} = \cos\theta + i\sin\theta", font_size=46)
        title.to_edge(UP, buff=0.6)
        title.set_color("#F5F5F7")

        axes = Axes(
            x_range=[-2.2, 2.2, 1],
            y_range=[-1.6, 1.6, 1],
            x_length=6.4,
            y_length=4.6,
            axis_config={"color": "#484852", "stroke_width": 1.8},
            tips=False,
        ).shift(DOWN * 0.35)

        circle = Circle(radius=axes.x_axis.unit_size, color="#0A84FF", stroke_width=2.5)
        circle.move_to(axes.c2p(0, 0))

        theta = ValueTracker(0.0)
        dot = always_redraw(
            lambda: Dot(
                axes.c2p(np.cos(theta.get_value()), np.sin(theta.get_value())),
                color="#FFFFFF",
                radius=0.075,
            )
        )
        radius_line = always_redraw(
            lambda: Line(
                axes.c2p(0, 0),
                axes.c2p(np.cos(theta.get_value()), np.sin(theta.get_value())),
                color="#0A84FF",
                stroke_width=3.0,
            )
        )
        proj_cos = always_redraw(
            lambda: DashedLine(
                axes.c2p(np.cos(theta.get_value()), 0),
                axes.c2p(np.cos(theta.get_value()), np.sin(theta.get_value())),
                color="#8E8E93",
                stroke_width=1.6,
            )
        )

        self.play(Write(title), Create(axes), run_time=1.0)
        self.play(Create(circle), FadeIn(dot, radius_line, proj_cos), run_time=0.8)
        self.play(theta.animate.set_value(2 * PI), run_time=3.2, rate_func=smooth)
        self.wait(0.6)
""".trimStart()

    val surface3dManim: String = """
from manim import *
import numpy as np


class HarmonicSurface3D(ThreeDScene):
    def construct(self):
        self.camera.background_color = "#070709"
        self.set_camera_orientation(phi=64 * DEGREES, theta=-42 * DEGREES)

        axes = ThreeDAxes(
            x_range=[-3, 3, 1],
            y_range=[-3, 3, 1],
            z_range=[-1.5, 1.5, 0.5],
            x_length=6,
            y_length=6,
            z_length=3.2,
        )

        surface = Surface(
            lambda u, v: axes.c2p(
                u, v, 0.75 * np.sin(np.sqrt(u**2 + v**2) * 1.8) * np.exp(-0.18 * (u**2 + v**2))
            ),
            u_range=[-2.6, 2.6],
            v_range=[-2.6, 2.6],
            resolution=(24, 24),
        )
        surface.set_fill_by_value(axes=axes, colorscale=["#1D4ED8", "#0A84FF", "#E5E7EB"], axis=2)

        label = MathTex(r"z(r) = \frac{3}{4}\sin(1.8r)e^{-0.18r^2}", font_size=36)
        label.to_corner(UL, buff=0.5)
        self.add_fixed_in_frame_mobjects(label)

        self.play(Create(axes), Write(label), run_time=1.0)
        self.play(Create(surface), run_time=1.4)
        self.begin_ambient_camera_rotation(rate=0.45)
        self.wait(3.0)
        self.stop_ambient_camera_rotation()
""".trimStart()

    val latexDocument: String = """
\documentclass[11pt,a4paper]{article}
\usepackage[margin=1in]{geometry}
\usepackage{amsmath,amssymb,amsthm}
\usepackage{lmodern}
\usepackage[T1]{fontenc}

\newtheorem{theorem}{Theorem}

\title{\textbf{Gaussian Integral \& Fourier Duality}}
\author{Atelier LaTeX Studio}
\date{\today}

\begin{document}
\maketitle

\begin{theorem}[Gaussian Integral]
For every real parameter ${D}\alpha > 0${D},
\[
    \int_{-\infty}^{\infty} e^{-\alpha x^2}\,dx = \sqrt{\frac{\pi}{\alpha}}.
\]
\end{theorem}

\begin{proof}
Let ${D}I(\alpha) = \int_{-\infty}^{\infty} e^{-\alpha x^2}\,dx${D}. Squaring and passing to polar coordinates ${D}(r,\theta)${D} yields
\[
    I(\alpha)^2 = \int_{0}^{2\pi}\!\!\int_{0}^{\infty} e^{-\alpha r^2}\,r\,dr\,d\theta
    = 2\pi \left[-\frac{e^{-\alpha r^2}}{2\alpha}\right]_{0}^{\infty}
    = \frac{\pi}{\alpha}.
\]
Taking the positive square root completes the proof.
\end{proof}

\section*{Spectral Identity}
The centered Gaussian is an eigenfunction of the unitary Fourier transform:
\[
    \widehat{g}(\xi) = \frac{1}{\sqrt{2\pi}} \int_{-\infty}^{\infty} e^{-x^2/2} e^{-i x \xi}\,dx = e^{-\xi^2/2}.
\]

\end{document}
""".trimStart()

    val pythonScientific: String = """
import numpy as np
import sympy as sp
import matplotlib.pyplot as plt

x = sp.Symbol("x", real=True)
expr = sp.exp(-x**2 / 2) * sp.cos(3 * x)
integral = sp.integrate(expr, (x, -sp.oo, sp.oo))
print("Symbolic expression:", expr)
print("Definite integral  :", sp.simplify(integral))
print("LaTeX              :", sp.latex(sp.simplify(integral)))

t = np.linspace(-4.0, 4.0, 800)
y = np.exp(-t**2 / 2.0) * np.cos(3.0 * t)
env = np.exp(-t**2 / 2.0)

fig, ax = plt.subplots(figsize=(6.4, 3.8), facecolor="#0B0B0E")
ax.set_facecolor("#0B0B0E")
ax.plot(t, env, "--", color="#52525B", linewidth=1.2, label=r"${D}e^{-x^2/2}${D}")
ax.plot(t, -env, "--", color="#52525B", linewidth=1.2)
ax.plot(t, y, color="#0A84FF", linewidth=2.2, label=r"${D}e^{-x^2/2}\cos(3x)${D}")
ax.set_title("Gaussian Wave Packet", color="#F5F5F7", fontsize=12, pad=10)
ax.tick_params(colors="#A1A1AA", labelsize=9)
for spine in ax.spines.values():
    spine.set_color("#27272A")
ax.grid(color="#1F1F24", linewidth=0.8)
ax.legend(facecolor="#141418", edgecolor="#27272A", labelcolor="#F5F5F7")
plt.show()
""".trimStart()

    fun blankFor(language: String): String = when (language) {
        "LATEX" -> "\\documentclass[11pt]{article}\n\\usepackage{amsmath,amssymb}\n\\begin{document}\n\n\\[\n  e^{i\\pi} + 1 = 0\n\\]\n\n\\end{document}\n"
        "PYTHON" -> "import math\nimport numpy as np\n\nprint(\"Atelier Python ready\")\n"
        else -> "from manim import *\n\n\nclass MainScene(Scene):\n    def construct(self):\n        eq = MathTex(r\"e^{i\\pi} + 1 = 0\")\n        self.play(Write(eq))\n        self.wait(0.8)\n"
    }
}

fun detectManimScenes(code: String): List<String> {
    val classRegex = Regex("""^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*:""", RegexOption.MULTILINE)
    val knownBases = mutableSetOf(
        "Scene", "ThreeDScene", "MovingCameraScene", "ZoomedScene",
        "VectorScene", "LinearTransformationScene", "VoiceoverScene",
        "SpecialThreeDScene", "GraphScene"
    )
    val classes = classRegex.findAll(code).map { m ->
        val name = m.groupValues[1]
        val bases = m.groupValues[2].split(",").map { it.trim().substringAfterLast('.') }
        name to bases
    }.toList()

    val scenes = linkedSetOf<String>()
    var changed = true
    while (changed) {
        changed = false
        for ((name, bases) in classes) {
            if (name !in scenes && bases.any { it in knownBases }) {
                scenes.add(name)
                knownBases.add(name)
                changed = true
            }
        }
    }
    return scenes.toList()
}

data class DiskTreeEntry(val relativePath: String, val isDirectory: Boolean)

class WorkspaceRepository(
    private val appContext: Context,
    private val dao: AtelierDao,
    private val blobStore: HistoryBlobStore
) {
    val workspaceDir: File = File(appContext.filesDir, "workspace").apply { mkdirs() }
    private val writeMutex = Mutex()
    private val txLock = Any()
    private var activeTxId: String = ""
    private val transactions = linkedMapOf<String, MutableMap<String, String?>>()

    fun beginTransaction(label: String = "AI Controller"): String {
        synchronized(txLock) {
            val id = "tx_${System.currentTimeMillis()}"
            activeTxId = id
            transactions[id] = linkedMapOf()
            if (transactions.size > 10) transactions.remove(transactions.keys.first())
            return id
        }
    }

    private fun recordPreMutation(relPath: String, existed: Boolean, oldContent: String?) {
        synchronized(txLock) {
            val map = transactions[activeTxId] ?: return
            if (relPath !in map) map[relPath] = if (existed) oldContent else null
        }
    }

    suspend fun rollbackTransaction(txId: String = ""): Int = withContext(Dispatchers.IO) {
        val snapshot: Map<String, String?> = synchronized(txLock) {
            val targetId = txId.ifBlank { activeTxId }
            val m = transactions.remove(targetId) ?: return@withContext 0
            if (activeTxId == targetId) activeTxId = ""
            m.toMap()
        }
        var count = 0
        for ((rel, prevContent) in snapshot) {
            val ent = dao.getFileByPath(rel)
            if (prevContent == null) {
                if (ent != null) deleteFile(ent) else File(workspaceDir, rel).delete()
            } else {
                if (ent != null) saveFile(ent, prevContent, label = "Rollback TX", triggerType = "RESTORE")
                else createFile(rel, inferLanguageForPath(rel), prevContent)
            }
            count++
        }
        count
    }

    suspend fun getAllFiles(): List<ProjectFileEntity> = withContext(Dispatchers.IO) { dao.getAllFiles() }
    suspend fun getRevisionsSnapshot(fileId: Long): List<FileRevisionEntity> = withContext(Dispatchers.IO) { dao.getRevisionsForFile(fileId) }

    suspend fun findOrSyncFile(rawPath: String): ProjectFileEntity? = withContext(Dispatchers.IO) {
        val clean = rawPath.trim().removePrefix("/workspace/").trimStart('/')
        dao.getFileByPath(clean)?.let { return@withContext it }
        syncWorkspaceFromDisk()
        dao.getFileByPath(clean) ?: dao.getAllFiles().firstOrNull { it.name == clean }
    }

    suspend fun writeByPath(rawPath: String, content: String, label: String = "AI Controller", language: String = "AUTO"): ProjectFileEntity = withContext(Dispatchers.IO) {
        val clean = rawPath.trim().removePrefix("/workspace/").trimStart('/')
        val existing = findOrSyncFile(clean)
        if (existing != null) {
            val prev = runCatching { readFile(existing) }.getOrNull()
            recordPreMutation(existing.relativePath, true, prev)
            saveFile(existing, content, label = label, triggerType = "AI_CTRL")
            dao.getFileById(existing.id) ?: existing
        } else {
            val lang = if (language == "AUTO") inferLanguageForPath(clean) else language
            val created = createFile(clean, lang, content)
            recordPreMutation(created.relativePath, false, null)
            created
        }
    }

    suspend fun patchFile(rawPath: String, oldStr: String, newStr: String, count: Int = 1, label: String = "AI Patch"): Pair<ProjectFileEntity, Int> = withContext(Dispatchers.IO) {
        val ent = findOrSyncFile(rawPath) ?: error("File not found: $rawPath")
        val src = readFile(ent)
        if (!src.contains(oldStr)) error("Target snippet not found in ${ent.relativePath}")
        recordPreMutation(ent.relativePath, true, src)
        var replaced = 0
        val updated = if (count <= 0) {
            var cur = src
            while (cur.contains(oldStr)) { cur = cur.replaceFirst(oldStr, newStr); replaced++ }
            cur
        } else {
            var cur = src
            repeat(count) {
                if (cur.contains(oldStr)) { cur = cur.replaceFirst(oldStr, newStr); replaced++ }
            }
            cur
        }
        saveFile(ent, updated, label = label, triggerType = "AI_CTRL")
        (dao.getFileById(ent.id) ?: ent) to replaced
    }

    suspend fun createFolder(rawPath: String): File = withContext(Dispatchers.IO) {
        val clean = rawPath.trim().removePrefix("/workspace/").trim('/')
            .split('/').filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { it.replace(Regex("[:*?\"<>|\\s]+"), "_") }
        if (clean.isEmpty()) error("Invalid folder path")
        File(workspaceDir, clean).apply { mkdirs() }
    }

    fun observeFiles(): Flow<List<ProjectFileEntity>> = dao.observeFiles()

    fun observeRevisions(fileId: Long): Flow<List<FileRevisionEntity>> = dao.observeRevisions(fileId)

    fun resolveDiskFile(entity: ProjectFileEntity): File = File(workspaceDir, entity.relativePath)

    suspend fun ensureSeeded(): List<ProjectFileEntity> = withContext(Dispatchers.IO) {
        val existing = dao.getAllFiles()
        if (existing.isNotEmpty()) return@withContext existing

        val seeds = listOf(
            Triple("fourier_epicycles.py", "PYTHON_MANIM", StarterTemplates.fourierManim),
            Triple("surface_wave_3d.py", "PYTHON_MANIM", StarterTemplates.surface3dManim),
            Triple("theorem_notes.tex", "LATEX", StarterTemplates.latexDocument),
            Triple("wave_packet.py", "PYTHON", StarterTemplates.pythonScientific)
        )
        for ((name, lang, content) in seeds) {
            createFile(name, lang, content)
        }
        dao.getAllFiles()
    }

    suspend fun readFile(entity: ProjectFileEntity): String = withContext(Dispatchers.IO) {
        val disk = resolveDiskFile(entity)
        if (disk.exists()) {
            disk.readText(Charsets.UTF_8)
        } else {
            val rev = dao.getLatestRevision(entity.id)
            val restored = rev?.let { blobStore.readBlob(it.contentHash) } ?: StarterTemplates.blankFor(entity.language)
            writeAtomic(disk, restored)
            restored
        }
    }

    suspend fun createFile(rawName: String, language: String, content: String): ProjectFileEntity =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeFileName(rawName, language)
            var candidate = safeName
            var idx = 2
            while (dao.getFileByPath(candidate) != null) {
                val base = safeName.substringBeforeLast('.')
                val ext = safeName.substringAfterLast('.', "")
                candidate = if (ext.isEmpty()) "${base}_$idx" else "${base}_$idx.$ext"
                idx++
            }
            val now = System.currentTimeMillis()
            val disk = File(workspaceDir, candidate)
            writeAtomic(disk, content)

            val entity = ProjectFileEntity(
                name = candidate.substringAfterLast('/'),
                relativePath = candidate,
                language = language,
                createdAt = now,
                updatedAt = now
            )
            val id = dao.insertFile(entity)
            val saved = entity.copy(id = id)
            val hash = blobStore.writeBlob(content)
            dao.insertRevision(
                FileRevisionEntity(
                    fileId = id,
                    contentHash = hash,
                    sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                    lineCount = content.count { it == '\n' } + 1,
                    createdAt = now,
                    label = "Initial Version",
                    triggerType = "SAVE"
                )
            )
            saved
        }

    suspend fun saveFile(
        entity: ProjectFileEntity,
        content: String,
        label: String = "Saved",
        triggerType: String = "SAVE",
        outputPath: String = "",
        outputType: String = "NONE",
        outputMeta: String = ""
    ): FileRevisionEntity? = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val fresh = dao.getFileById(entity.id) ?: entity
            val disk = resolveDiskFile(fresh)
            writeAtomic(disk, content)

            val now = System.currentTimeMillis()
            val hash = blobStore.writeBlob(content)
            val latest = dao.getLatestRevision(fresh.id)

            val updatedEntity = fresh.copy(
                updatedAt = now,
                lastOutputPath = if (outputPath.isNotEmpty()) outputPath else fresh.lastOutputPath,
                lastOutputType = if (outputPath.isNotEmpty()) outputType else fresh.lastOutputType,
                lastOutputMeta = if (outputMeta.isNotEmpty()) outputMeta else fresh.lastOutputMeta
            )
            dao.updateFile(updatedEntity)

            if (triggerType == "EDIT" && latest != null && latest.contentHash == hash && outputPath.isEmpty()) {
                return@withLock latest
            }

            val rev = FileRevisionEntity(
                fileId = fresh.id,
                contentHash = hash,
                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                lineCount = content.count { it == '\n' } + 1,
                createdAt = now,
                label = label,
                triggerType = triggerType,
                outputPath = outputPath,
                outputType = outputType,
                outputMeta = outputMeta
            )
            val revId = dao.insertRevision(rev)
            rev.copy(id = revId)
        }
    }

    suspend fun restoreRevision(
        entity: ProjectFileEntity,
        revision: FileRevisionEntity
    ): Pair<String, FileRevisionEntity?> = withContext(Dispatchers.IO) {
        val text = blobStore.readBlob(revision.contentHash) ?: readFile(entity)
        val newRev = saveFile(
            entity = entity,
            content = text,
            label = "Restored from #${revision.id}",
            triggerType = "RESTORE",
            outputPath = revision.outputPath,
            outputType = revision.outputType,
            outputMeta = revision.outputMeta
        )
        text to newRev
    }

    suspend fun renameFile(entity: ProjectFileEntity, newRawName: String): ProjectFileEntity =
        withContext(Dispatchers.IO) {
            val safe = sanitizeFileName(newRawName, entity.language)
            if (safe == entity.relativePath || dao.getFileByPath(safe) != null) return@withContext entity
            val oldDisk = resolveDiskFile(entity)
            val newDisk = File(workspaceDir, safe)
            if (oldDisk.exists()) oldDisk.renameTo(newDisk)
            val lang = inferLanguage(safe, entity.language)
            val updated = entity.copy(
                name = safe.substringAfterLast('/'),
                relativePath = safe,
                language = lang,
                updatedAt = System.currentTimeMillis()
            )
            dao.updateFile(updated)
            updated
        }

    suspend fun deleteFile(entity: ProjectFileEntity) = withContext(Dispatchers.IO) {
        val prev = runCatching { readFile(entity) }.getOrNull()
        recordPreMutation(entity.relativePath, true, prev)
        resolveDiskFile(entity).delete()
        dao.deleteFileById(entity.id)
    }

    suspend fun syncWorkspaceFromDisk(): Int = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            var added = 0
            val existing = dao.getAllFiles().associateBy { it.relativePath }
            workspaceDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                val rel = f.relativeTo(workspaceDir).path
                if (rel !in existing && !rel.contains("/__pycache__/") && !rel.startsWith("__pycache__/")) {
                    val lang = inferLanguageForPath(rel)
                    val now = f.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    val ent = ProjectFileEntity(name = f.name, relativePath = rel, language = lang, createdAt = now, updatedAt = now)
                    val id = dao.insertFile(ent)
                    if (f.length() < 1_000_000L && isEditableTextFile(rel)) {
                        val txt = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                        val hash = blobStore.writeBlob(txt)
                        dao.insertRevision(FileRevisionEntity(fileId = id, contentHash = hash, sizeBytes = f.length(), lineCount = txt.count { it == '\n' } + 1, createdAt = now, label = "Synced from Disk", triggerType = "SAVE"))
                    }
                    added++
                }
            }
            added
        }
    }

    suspend fun importDocumentUris(uris: List<Uri>, folder: String = ""): List<ProjectFileEntity> = withContext(Dispatchers.IO) {
        val imported = mutableListOf<ProjectFileEntity>()
        val cleanFolder = folder.trim().trim('/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        for (uri in uris) {
            runCatching {
                val rawName = resolveDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.py"
                val relPath = if (cleanFolder.isNotEmpty()) "$cleanFolder/$rawName" else rawName
                val lang = inferLanguageForPath(relPath)
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                val safeRel = sanitizeFileName(relPath, lang)
                val target = File(workspaceDir, safeRel)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> out.write(bytes); out.fd.sync() }
                val existing = dao.getFileByPath(safeRel)
                val now = System.currentTimeMillis()
                val entity = if (existing != null) {
                    val upd = existing.copy(updatedAt = now, language = lang)
                    dao.updateFile(upd); upd
                } else {
                    val ent = ProjectFileEntity(name = target.name, relativePath = safeRel, language = lang, createdAt = now, updatedAt = now)
                    ent.copy(id = dao.insertFile(ent))
                }
                if (bytes.size < 1_000_000 && isEditableTextFile(safeRel)) {
                    val txt = bytes.toString(Charsets.UTF_8)
                    val hash = blobStore.writeBlob(txt)
                    dao.insertRevision(FileRevisionEntity(fileId = entity.id, contentHash = hash, sizeBytes = bytes.size.toLong(), lineCount = txt.count { it == '\n' } + 1, createdAt = now, label = "Imported via SAF", triggerType = "SAVE"))
                }
                imported.add(entity)
            }
        }
        imported
    }

    fun listDiskTree(): List<DiskTreeEntry> {
        val out = mutableListOf<DiskTreeEntry>()
        workspaceDir.walkTopDown().forEach { f ->
            if (f == workspaceDir) return@forEach
            val rel = f.relativeTo(workspaceDir).path
            if (f.name.startsWith(".") || rel.contains("__pycache__")) return@forEach
            out.add(DiskTreeEntry(rel, f.isDirectory))
        }
        return out.sortedBy { it.relativePath }
    }

    fun inferLanguageForPath(path: String): String = when {
        path.endsWith(".tex", ignoreCase = true) -> "LATEX"
        path.endsWith(".pyx", ignoreCase = true) -> "PYTHON"
        path.endsWith(".py", ignoreCase = true) -> "PYTHON_MANIM"
        else -> "ASSET"
    }

    private fun isEditableTextFile(path: String): Boolean =
        listOf(".py", ".tex", ".pyx", ".txt", ".json", ".md", ".csv", ".yaml", ".yml", ".svg").any { path.endsWith(it, true) }

    private fun resolveDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    private fun sanitizeFileName(raw: String, language: String): String {
        val segments = raw.trim().replace('\\', '/').split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .map { seg -> seg.replace(Regex("[:*?\"<>|\\s]+"), "_").trim('_', '.') }
            .filter { it.isNotBlank() }
        val joined = if (segments.isEmpty()) "untitled" else segments.joinToString("/")
        val leaf = joined.substringAfterLast('/')
        val hasExt = leaf.contains('.') && leaf.substringAfterLast('.').length in 1..6
        if (hasExt) return joined
        return when (language) {
            "LATEX" -> "$joined.tex"
            "ASSET" -> joined
            else -> "$joined.py"
        }
    }

    private fun inferLanguage(fileName: String, fallback: String): String = when {
        fileName.endsWith(".tex", ignoreCase = true) -> "LATEX"
        fileName.endsWith(".pyx", ignoreCase = true) -> "PYTHON"
        fileName.endsWith(".py", ignoreCase = true) -> if (fallback == "LATEX") "PYTHON_MANIM" else fallback
        else -> fallback
    }
}
