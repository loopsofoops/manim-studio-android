package com.atelier.manim.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object StarterTemplates {
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
For every real parameter $\alpha > 0$,
\[
    \int_{-\infty}^{\infty} e^{-\alpha x^2}\,dx = \sqrt{\frac{\pi}{\alpha}}.
\]
\end{theorem}

\begin{proof}
Let $I(\alpha) = \int_{-\infty}^{\infty} e^{-\alpha x^2}\,dx$. Squaring and passing to polar coordinates $(r,\theta)$ yields
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
ax.plot(t, env, "--", color="#52525B", linewidth=1.2, label=r"$e^{-x^2/2}$")
ax.plot(t, -env, "--", color="#52525B", linewidth=1.2)
ax.plot(t, y, color="#0A84FF", linewidth=2.2, label=r"$e^{-x^2/2}\cos(3x)$")
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

class WorkspaceRepository(
    context: Context,
    private val dao: AtelierDao,
    private val blobStore: HistoryBlobStore
) {
    val workspaceDir: File = File(context.filesDir, "workspace").apply { mkdirs() }

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
                name = candidate,
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
        val disk = resolveDiskFile(entity)
        writeAtomic(disk, content)

        val now = System.currentTimeMillis()
        val hash = blobStore.writeBlob(content)
        val latest = dao.getLatestRevision(entity.id)

        val updatedEntity = entity.copy(
            updatedAt = now,
            lastOutputPath = if (outputPath.isNotEmpty()) outputPath else entity.lastOutputPath,
            lastOutputType = if (outputPath.isNotEmpty()) outputType else entity.lastOutputType,
            lastOutputMeta = if (outputMeta.isNotEmpty()) outputMeta else entity.lastOutputMeta
        )
        dao.updateFile(updatedEntity)

        if (triggerType == "EDIT" && latest != null && latest.contentHash == hash && outputPath.isEmpty()) {
            return@withContext latest
        }

        val rev = FileRevisionEntity(
            fileId = entity.id,
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
                name = safe,
                relativePath = safe,
                language = lang,
                updatedAt = System.currentTimeMillis()
            )
            dao.updateFile(updated)
            updated
        }

    suspend fun deleteFile(entity: ProjectFileEntity) = withContext(Dispatchers.IO) {
        resolveDiskFile(entity).delete()
        dao.deleteFileById(entity.id)
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun sanitizeFileName(raw: String, language: String): String {
        val cleaned = raw.trim().replace(Regex("[/\\\\:*?\"<>|\\s]+"), "_").trim('_', '.')
        val base = cleaned.ifBlank { "untitled" }
        val hasExt = base.contains('.') && base.substringAfterLast('.').length in 1..6
        if (hasExt) return base
        return when (language) {
            "LATEX" -> "$base.tex"
            else -> "$base.py"
        }
    }

    private fun inferLanguage(fileName: String, fallback: String): String = when {
        fileName.endsWith(".tex", ignoreCase = true) -> "LATEX"
        fileName.endsWith(".pyx", ignoreCase = true) -> "PYTHON"
        fileName.endsWith(".py", ignoreCase = true) -> if (fallback == "LATEX") "PYTHON_MANIM" else fallback
        else -> fallback
    }
}
