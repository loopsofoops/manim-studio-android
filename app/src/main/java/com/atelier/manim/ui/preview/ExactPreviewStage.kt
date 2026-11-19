package com.atelier.manim.ui.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.atelier.manim.runtime.ExecutionState
import com.atelier.manim.runtime.OutputArtifact
import com.atelier.manim.runtime.OutputKind
import com.atelier.manim.ui.icons.AtelierIcons
import com.atelier.manim.ui.theme.LocalAtelierColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun ExactPreviewStage(
    execution: ExecutionState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSaveToStorage: () -> Unit,
    onShare: () -> Unit,
    onCancelRender: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAtelierColors.current
    val artifact = execution.activeArtifact
    var showGrid by remember { mutableStateOf(false) }

    Column(modifier = modifier.background(colors.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF050507))
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            when {
                artifact.kind == OutputKind.VIDEO && artifact.path.isNotBlank() && File(artifact.path).exists() -> {
                    ExactVideoSurface(
                        artifact = artifact,
                        isRenderRunning = execution.isRunning,
                        showGrid = showGrid,
                        isExpanded = isExpanded,
                        onToggleGrid = { showGrid = !showGrid },
                        onToggleExpand = onToggleExpand,
                        onSaveToStorage = onSaveToStorage,
                        onShare = onShare
                    )
                }
                artifact.kind == OutputKind.PDF && artifact.path.isNotBlank() && File(artifact.path).exists() -> {
                    ExactPdfSurface(
                        artifact = artifact,
                        isExpanded = isExpanded,
                        onToggleExpand = onToggleExpand,
                        onSaveToStorage = onSaveToStorage,
                        onShare = onShare
                    )
                }
                artifact.kind == OutputKind.IMAGE && artifact.path.isNotBlank() && File(artifact.path).exists() -> {
                    ExactImageSurface(
                        artifact = artifact,
                        isExpanded = isExpanded,
                        onToggleExpand = onToggleExpand,
                        onSaveToStorage = onSaveToStorage,
                        onShare = onShare
                    )
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "1:1 Stage",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Render a Manim scene, LaTeX document, or Python plot for exact output inspection.",
                            color = colors.textMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Live Render HUD Overlay
            if (execution.isRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC07070A)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = execution.modeLabel.ifBlank { "Executing" },
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Cancel",
                                color = colors.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onCancelRender)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            text = execution.stageLabel.ifBlank { "Working..." },
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (execution.progress in 0f..1f) {
                            LinearProgressIndicator(
                                progress = { execution.progress },
                                color = colors.accent,
                                trackColor = colors.surfaceElevated,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                            )
                        } else {
                            LinearProgressIndicator(
                                color = colors.accent,
                                trackColor = colors.surfaceElevated,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExactVideoSurface(
    artifact: OutputArtifact,
    isRenderRunning: Boolean,
    showGrid: Boolean,
    isExpanded: Boolean,
    onToggleGrid: () -> Unit,
    onToggleExpand: () -> Unit,
    onSaveToStorage: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalAtelierColors.current
    val haptic = LocalHapticFeedback.current

    val fps = if (artifact.telemetry.fps > 0.0) artifact.telemetry.fps else 30.0
    val frameStepMs = max(1L, (1000.0 / fps).roundToLong())
    val aspect = if (artifact.telemetry.width > 0 && artifact.telemetry.height > 0) {
        artifact.telemetry.width.toFloat() / artifact.telemetry.height.toFloat()
    } else 16f / 9f

    var isPlaying by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(true) }
    var speedIdx by remember { mutableIntStateOf(2) }
    val speeds = remember { listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember(artifact.path) { mutableLongStateOf(max(1L, artifact.telemetry.durationMs)) }
    var scale by remember(artifact.path) { mutableFloatStateOf(1f) }
    var offset by remember(artifact.path) { mutableStateOf(Offset.Zero) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && exoPlayer.duration > 0L) {
                    durationMs = exoPlayer.duration
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(artifact.path, artifact.timestamp) {
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(artifact.path))))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(isLooping) {
        exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    LaunchedEffect(speedIdx) {
        exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
    }

    LaunchedEffect(isRenderRunning) {
        if (isRenderRunning) exoPlayer.pause()
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = max(0L, exoPlayer.currentPosition)
            if (exoPlayer.duration > 0L) durationMs = exoPlayer.duration
            delay(33L)
        }
    }

    val currentFrame = ((positionMs * fps) / 1000.0).roundToInt()
    val totalFrames = max(1, ((durationMs * fps) / 1000.0).roundToInt())

    Column(modifier = Modifier.fillMaxSize()) {
        // Exact 1:1 Viewport with Pinch-to-Zoom & Safe Grid
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(artifact.path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale <= 1.02f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(artifact.path) {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(AndroidColor.BLACK)
                            player = exoPlayer
                        }
                    },
                    update = { it.player = exoPlayer },
                    modifier = Modifier.fillMaxSize()
                )

                if (showGrid) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val lineColor = Color.White.copy(alpha = 0.25f)
                        val w = size.width
                        val h = size.height
                        drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), 1f)
                        drawLine(lineColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), 1f)
                        drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), 1f)
                        drawLine(lineColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), 1f)
                        drawLine(lineColor, Offset(w / 2f - 10f, h / 2f), Offset(w / 2f + 10f, h / 2f), 1.5f)
                        drawLine(lineColor, Offset(w / 2f, h / 2f - 10f), Offset(w / 2f, h / 2f + 10f), 1.5f)
                    }
                }
            }

            // Telemetry badge (top-left)
            val badge = artifact.telemetry.summaryBadge()
            if (badge.isNotEmpty()) {
                Text(
                    text = if (scale > 1.05f) "$badge · ${(scale * 100).roundToInt()}%" else badge,
                    color = Color(0xFFE5E5EA),
                    fontSize = 10.sp,
                    style = TextStyle(fontFeatureSettings = "tnum", fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xB3000000))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }

        // Non-Clipping Precision Transport Controls Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Slider(
                    value = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac ->
                        val target = (frac * durationMs).roundToLong()
                        positionMs = target
                        exoPlayer.seekTo(target)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = colors.textPrimary,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.hairline
                    ),
                    modifier = Modifier.weight(1f).height(22.dp)
                )
                Text(
                    text = "${formatTimecode(positionMs)} / ${formatTimecode(durationMs)} · F$currentFrame/$totalFrames",
                    color = colors.textSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    style = TextStyle(fontFeatureSettings = "tnum", fontFamily = FontFamily.Monospace)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StageIconBtn(AtelierIcons.StepBack, false) {
                        exoPlayer.pause()
                        val target = max(0L, exoPlayer.currentPosition - frameStepMs)
                        exoPlayer.seekTo(target)
                        positionMs = target
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    StageIconBtn(if (isPlaying) AtelierIcons.Pause else AtelierIcons.Play, true) {
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
                    StageIconBtn(AtelierIcons.StepForward, false) {
                        exoPlayer.pause()
                        val target = min(durationMs, exoPlayer.currentPosition + frameStepMs)
                        exoPlayer.seekTo(target)
                        positionMs = target
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    Text(
                        text = "${speeds[speedIdx]}x",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        style = TextStyle(fontFeatureSettings = "tnum", fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { speedIdx = (speedIdx + 1) % speeds.size }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StageIconBtn(AtelierIcons.Loop, isLooping) { isLooping = !isLooping }
                    StageIconBtn(AtelierIcons.Grid, showGrid, onToggleGrid)
                    StageIconBtn(AtelierIcons.Save, false, onSaveToStorage)
                    StageIconBtn(AtelierIcons.Share, false, onShare)
                    StageIconBtn(if (isExpanded) AtelierIcons.Collapse else AtelierIcons.Expand, false, onToggleExpand)
                }
            }
        }
    }
}

@Composable
private fun ExactPdfSurface(
    artifact: OutputArtifact,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSaveToStorage: () -> Unit,
    onShare: () -> Unit
) {
    val colors = LocalAtelierColors.current
    var pageIndex by remember(artifact.path) { mutableIntStateOf(0) }
    var pageCount by remember(artifact.path) { mutableIntStateOf(1) }
    var bitmap by remember(artifact.path) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(artifact.path) { mutableFloatStateOf(1f) }
    var offset by remember(artifact.path) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(artifact.path, artifact.timestamp, pageIndex) {
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(artifact.path)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val count = max(1, renderer.pageCount)
                        val idx = pageIndex.coerceIn(0, count - 1)
                        renderer.openPage(idx).use { page ->
                            val bmpW = min(2200, max(900, page.width * 2))
                            val bmpH = max(1, (bmpW.toFloat() * page.height / max(1, page.width)).roundToInt())
                            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            AndroidCanvas(bmp).drawColor(AndroidColor.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            count to bmp
                        }
                    }
                }
            }.getOrNull()
        }
        if (rendered != null) {
            pageCount = rendered.first
            bitmap = rendered.second
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(artifact.path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(1f, 5f)
                        scale = s
                        offset = if (s <= 1.02f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(artifact.path) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                },
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Compiled LaTeX PDF",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageIconBtn(AtelierIcons.StepBack, false) { if (pageIndex > 0) pageIndex-- }
                Text(
                    text = "Page ${pageIndex + 1} / $pageCount",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    style = TextStyle(fontFeatureSettings = "tnum", fontFamily = FontFamily.Monospace)
                )
                StageIconBtn(AtelierIcons.StepForward, false) { if (pageIndex < pageCount - 1) pageIndex++ }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StageIconBtn(AtelierIcons.Save, false, onSaveToStorage)
                StageIconBtn(AtelierIcons.Share, false, onShare)
                StageIconBtn(if (isExpanded) AtelierIcons.Collapse else AtelierIcons.Expand, false, onToggleExpand)
            }
        }
    }
}

@Composable
private fun ExactImageSurface(
    artifact: OutputArtifact,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSaveToStorage: () -> Unit,
    onShare: () -> Unit
) {
    val colors = LocalAtelierColors.current
    var bitmap by remember(artifact.path, artifact.timestamp) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(artifact.path) { mutableFloatStateOf(1f) }
    var offset by remember(artifact.path) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(artifact.path, artifact.timestamp) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(artifact.path) }.getOrNull()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(artifact.path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(1f, 5f)
                        scale = s
                        offset = if (s <= 1.02f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(artifact.path) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                },
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = artifact.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dim = bitmap?.let { "${it.width}x${it.height}" } ?: ""
            Text(
                text = listOf(artifact.title, dim).filter { it.isNotBlank() }.joinToString(" · "),
                color = colors.textSecondary,
                fontSize = 11.sp,
                style = TextStyle(fontFeatureSettings = "tnum", fontFamily = FontFamily.Monospace)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StageIconBtn(AtelierIcons.Save, false, onSaveToStorage)
                StageIconBtn(AtelierIcons.Share, false, onShare)
                StageIconBtn(if (isExpanded) AtelierIcons.Collapse else AtelierIcons.Expand, false, onToggleExpand)
            }
        }
    }
}

@Composable
private fun StageIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAtelierColors.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) colors.accent else colors.textPrimary,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun formatTimecode(ms: Long): String {
    val clamped = max(0L, ms)
    val totalSecs = clamped / 1000L
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    val centis = (clamped % 1000L) / 10L
    return String.format("%02d:%02d.%02d", mins, secs, centis)
}
