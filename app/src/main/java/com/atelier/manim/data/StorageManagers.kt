package com.atelier.manim.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

class HistoryBlobStore(context: Context) {
    private val blobsDir = File(context.filesDir, "history_blobs").apply { mkdirs() }
    private val artifactsDir = File(context.filesDir, "history_artifacts").apply { mkdirs() }

    fun writeBlob(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val hash = sha256Hex(bytes)
        val target = File(blobsDir, "$hash.txt")
        if (!target.exists()) {
            val tmp = File(blobsDir, "$hash.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return hash
    }

    fun readBlob(hash: String): String? {
        val target = File(blobsDir, "$hash.txt")
        return if (target.exists()) runCatching { target.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun snapshotArtifact(sourceFile: File, tag: String): String {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return ""
        val ext = sourceFile.extension.ifBlank { "bin" }
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_-]"), "_").take(36)
        val dest = File(artifactsDir, "${safeTag}_${System.currentTimeMillis()}.$ext")
        return runCatching {
            sourceFile.copyTo(dest, overwrite = true)
            pruneArtifactsIfNeeded(maxTotalBytes = 350L * 1024L * 1024L)
            dest.absolutePath
        }.getOrDefault(sourceFile.absolutePath)
    }

    fun totalArtifactsBytes(): Long = artifactsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearArtifacts() {
        artifactsDir.listFiles()?.forEach { it.delete() }
    }

    private fun pruneArtifactsIfNeeded(maxTotalBytes: Long) {
        val files = artifactsDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxTotalBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

enum class DiffKind { SAME, ADDED, REMOVED }

data class DiffLine(
    val kind: DiffKind,
    val oldNumber: Int?,
    val newNumber: Int?,
    val text: String
)

object LineDiff {
    fun compute(oldText: String, newText: String): List<DiffLine> {
        val a = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
        val b = if (newText.isEmpty()) emptyList() else newText.split("\n")
        val n = a.size
        val m = b.size

        if (n * m > 1_200_000) {
            val out = ArrayList<DiffLine>(n + m)
            val limit = max(n, m)
            for (i in 0 until limit) {
                val o = a.getOrNull(i)
                val nw = b.getOrNull(i)
                when {
                    o == nw && o != null -> out.add(DiffLine(DiffKind.SAME, i + 1, i + 1, o))
                    else -> {
                        if (o != null) out.add(DiffLine(DiffKind.REMOVED, i + 1, null, o))
                        if (nw != null) out.add(DiffLine(DiffKind.ADDED, null, i + 1, nw))
                    }
                }
            }
            return out
        }

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val result = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    result.add(DiffLine(DiffKind.SAME, i + 1, j + 1, a[i]))
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    result.add(DiffLine(DiffKind.REMOVED, i + 1, null, a[i]))
                    i++
                }
                else -> {
                    result.add(DiffLine(DiffKind.ADDED, null, j + 1, b[j]))
                    j++
                }
            }
        }
        while (i < n) {
            result.add(DiffLine(DiffKind.REMOVED, i + 1, null, a[i]))
            i++
        }
        while (j < m) {
            result.add(DiffLine(DiffKind.ADDED, null, j + 1, b[j]))
            j++
        }
        return result
    }
}

data class ExportResult(
    val success: Boolean,
    val displayLocation: String,
    val uriString: String = "",
    val error: String = ""
)

class MediaExportManager(private val context: Context) {

    fun saveVideoToStorage(sourceFile: File, baseTitle: String): ExportResult {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return ExportResult(false, "", error = "Source video file is empty.")
        }
        val ext = sourceFile.extension.lowercase().ifBlank { "mp4" }
        val mime = when (ext) {
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        val fileName = "${sanitizeTitle(baseTitle)}_${System.currentTimeMillis()}.$ext"

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Atelier")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore failed to create video entry.")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out, 65536) }
                    } ?: error("Cannot open MediaStore output stream.")
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    ExportResult(true, "Movies/Atelier/$fileName", uri.toString())
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
            } else {
                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
                val outDir = File(baseDir, "Atelier").apply { mkdirs() }
                val dest = File(outDir, fileName)
                sourceFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
                ExportResult(true, dest.absolutePath, dest.toURI().toString())
            }
        }.getOrElse { e ->
            ExportResult(false, "", error = e.message ?: "Failed to save video.")
        }
    }

    fun saveImageToStorage(sourceFile: File, baseTitle: String): ExportResult {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return ExportResult(false, "", error = "Source image is empty.")
        }
        val ext = sourceFile.extension.lowercase().ifBlank { "png" }
        val mime = when (ext) {
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            else -> "image/png"
        }
        val fileName = "${sanitizeTitle(baseTitle)}_${System.currentTimeMillis()}.$ext"

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Atelier")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore failed to create image entry.")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out, 65536) }
                    } ?: error("Cannot open MediaStore output stream.")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    ExportResult(true, "Pictures/Atelier/$fileName", uri.toString())
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
            } else {
                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                val outDir = File(baseDir, "Atelier").apply { mkdirs() }
                val dest = File(outDir, fileName)
                sourceFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
                ExportResult(true, dest.absolutePath, dest.toURI().toString())
            }
        }.getOrElse { e ->
            ExportResult(false, "", error = e.message ?: "Failed to save image.")
        }
    }

    fun saveDocumentToStorage(sourceFile: File, baseTitle: String, mimeType: String): ExportResult {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return ExportResult(false, "", error = "Source document is empty.")
        }
        val ext = sourceFile.extension.lowercase().ifBlank { "pdf" }
        val fileName = "${sanitizeTitle(baseTitle)}_${System.currentTimeMillis()}.$ext"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Atelier")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: error("MediaStore failed to create document entry.")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out, 65536) }
                    } ?: error("Cannot open document output stream.")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    ExportResult(true, "Documents/Atelier/$fileName", uri.toString())
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
            } else {
                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
                val outDir = File(baseDir, "Atelier").apply { mkdirs() }
                val dest = File(outDir, fileName)
                sourceFile.copyTo(dest, overwrite = true)
                ExportResult(true, dest.absolutePath, dest.toURI().toString())
            }
        }.getOrElse { e ->
            ExportResult(false, "", error = e.message ?: "Failed to save document.")
        }
    }

    fun shareFile(activityContext: Context, file: File, mimeType: String) {
        if (!file.exists()) return
        runCatching {
            val shareCopy = File(context.cacheDir, file.name)
            if (file.canonicalPath != shareCopy.canonicalPath) {
                file.copyTo(shareCopy, overwrite = true)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareCopy
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share ${file.name}")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activityContext.startActivity(chooser)
        }
    }

    private fun sanitizeTitle(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        return cleaned.ifBlank { "atelier_render" }.take(min(48, max(cleaned.length, 1)))
    }
}
