package com.atelier.manim.runtime

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import java.io.File

data class HardwareClipSpec(
    val hostPath: String,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val zoom: Float = 1.0f
)

class HardwareVideoExporter(private val context: Context) {

    @OptIn(UnstableApi::class)
    suspend fun transcodeClips(
        clips: List<HardwareClipSpec>,
        outFile: File,
        targetWidth: Int,
        targetHeight: Int,
        removeAudio: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): File {
        require(clips.isNotEmpty()) { "No video clips provided for hardware export." }
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val deferred = CompletableDeferred<File>()
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            try {
                val editedItems = clips.map { spec ->
                    val f = File(spec.hostPath)
                    require(f.exists()) { "Missing clip: ${spec.hostPath}" }
                    val itemBuilder = MediaItem.Builder().setUri(Uri.fromFile(f))
                    if (spec.trimStartMs > 0L || spec.trimEndMs > spec.trimStartMs) {
                        val cc = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(spec.trimStartMs)
                        if (spec.trimEndMs > spec.trimStartMs) cc.setEndPositionMs(spec.trimEndMs)
                        itemBuilder.setClippingConfiguration(cc.build())
                    }
                    val vEffects = mutableListOf<Effect>()
                    if (spec.zoom > 1.01f) {
                        vEffects.add(ScaleAndRotateTransformation.Builder().setScale(spec.zoom, spec.zoom).build())
                    }
                    vEffects.add(Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT))
                    EditedMediaItem.Builder(itemBuilder.build())
                        .setRemoveAudio(removeAudio)
                        .setEffects(Effects(emptyList(), vEffects))
                        .build()
                }
                val seq = EditedMediaItemSequence(editedItems)
                val comp = Composition.Builder(listOf(seq)).build()
                val progressHolder = ProgressHolder()
                var pollRunnable: Runnable? = null

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            pollRunnable?.let { mainHandler.removeCallbacks(it) }
                            onProgress(1f)
                            if (!deferred.isCompleted) deferred.complete(outFile)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            pollRunnable?.let { mainHandler.removeCallbacks(it) }
                            if (!deferred.isCompleted) deferred.completeExceptionally(exportException)
                        }
                    })
                    .build()

                pollRunnable = object : Runnable {
                    override fun run() {
                        if (deferred.isCompleted) return
                        val st = transformer.getProgress(progressHolder)
                        if (st == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((progressHolder.progress / 100f).coerceIn(0f, 0.99f))
                        }
                        mainHandler.postDelayed(this, 250L)
                    }
                }
                transformer.start(comp, outFile.absolutePath)
                mainHandler.postDelayed(pollRunnable, 250L)
            } catch (t: Throwable) {
                if (!deferred.isCompleted) deferred.completeExceptionally(t)
            }
        }
        return deferred.await()
    }
}
