package com.atelier.manim.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.atelier.manim.runtime.ExecutionState
import com.atelier.manim.ui.icons.AtelierIcons
import com.atelier.manim.ui.theme.LocalAtelierColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max

data class EditClipModel(
    val index: Int,
    val path: String,
    val label: String,
    val trimStart: Double,
    val trimEnd: Double,
    val durationSec: Double,
    val speed: Double,
    val freezeTailSec: Double,
    val transition: String,
    val zoomStart: Double,
    val zoomEnd: Double,
    val panX: Double,
    val panY: Double
)

data class EditAudioClipModel(
    val index: Int,
    val path: String,
    val start: Double,
    val durationSec: Double,
    val gainDb: Double,
    val text: String,
    val trimSilence: Boolean
)

data class EditCaptionModel(val start: Double, val end: Double, val text: String)
data class EditOverlayModel(val index: Int, val path: String, val start: Double, val end: Double, val x: Float, val y: Float, val scale: Float, val opacity: Float)
data class EditChapterModel(val time: Double, val title: String)

data class TimelineUiState(
    val name: String = "episode_01",
    val preset: String = "youtube_1080p60",
    val captionStyle: String = "minimal_3b1b",
    val videoClips: List<EditClipModel> = emptyList(),
    val overlays: List<EditOverlayModel> = emptyList(),
    val voiceClips: List<EditAudioClipModel> = emptyList(),
    val sfxClips: List<EditAudioClipModel> = emptyList(),
    val musicClips: List<EditAudioClipModel> = emptyList(),
    val captions: List<EditCaptionModel> = emptyList(),
    val chapters: List<EditChapterModel> = emptyList(),
    val totalDurationSec: Double = 10.0
)

@OptIn(UnstableApi::class)
@Composable
fun EditStudioWorkspace(
    execution: ExecutionState,
    timeline: TimelineUiState,
    availableTimelines: List<String>,
    voiceWaveformPeaks: List<Float>,
    pacingReport: JSONObject?,
    latestThumbnailHostPath: String,
    guestToHost: (String) -> File,
    onSelectTimeline: (String) -> Unit,
    onCreateTimeline: (String) -> Unit,
    onAddActiveRenderClip: () -> Unit,
    onImportVoiceManifest: () -> Unit,
    onAddLatestMusic: () -> Unit,
    onSplitVideoAt: (Double) -> Unit,
    onUpdateVideoClip: (index: Int, trimStart: Double, trimEnd: Double, speed: Double, freezeTail: Double, transition: String, zoomStart: Double, zoomEnd: Double) -> Unit,
    onDeleteVideoClip: (Int) -> Unit,
    onSetCaptionStyle: (String) -> Unit,
    onAddChapterAt: (Double, String) -> Unit,
    onRunPacingScan: () -> Unit,
    onAutoTrimDeadAir: () -> Unit,
    onGenerateThumbnail: (timeSec: Double, title: String, subtitle: String, mathTex: String) -> Unit,
    onExportTimeline: (preset: String, hardwareFast: Boolean, fullPublishBundle: Boolean) -> Unit,
    onEnqueueBatchExport: (preset: String) -> Unit,
    onOpenDubbingDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAtelierColors.current
    val context = LocalContext.current

    var playheadSec by remember(timeline.name) { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }
    var pxPerSec by remember { mutableFloatStateOf(36f) }
    var selectedClipIdx by remember(timeline.name) { mutableIntStateOf(-1) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showThumbDialog by remember { mutableStateOf(false) }
    var showPacingDialog by remember { mutableStateOf(false) }
    var showNewTlDialog by remember { mutableStateOf(false) }
    var showChapterDialog by remember { mutableStateOf(false) }

    val totalDur = max(6.0, timeline.totalDurationSec)
    val isPortrait = timeline.preset.contains("9x16")
    val isSquare = timeline.preset.contains("1x1")
    val previewAspect = when { isPortrait -> 9f / 16f; isSquare -> 1f; else -> 16f / 9f }

    val clipStarts = remember(timeline.videoClips) {
        var acc = 0.0
        timeline.videoClips.map { c -> val s = acc; acc += c.durationSec; s }
    }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    LaunchedEffect(timeline.videoClips) {
        val items = timeline.videoClips.mapNotNull { c ->
            val f = guestToHost(c.path)
            if (!f.exists()) null else {
                val b = MediaItem.Builder().setUri(Uri.fromFile(f))
                if (c.trimStart > 0.0 || c.trimEnd > c.trimStart) {
                    val cc = MediaItem.ClippingConfiguration.Builder().setStartPositionMs((c.trimStart * 1000).toLong())
                    if (c.trimEnd > c.trimStart) cc.setEndPositionMs((c.trimEnd * 1000).toLong())
                    b.setClippingConfiguration(cc.build())
                }
                b.build()
            }
        }
        exoPlayer.setMediaItems(items)
        exoPlayer.prepare()
    }

    LaunchedEffect(execution.isRunning) { if (execution.isRunning) exoPlayer.pause() }

    LaunchedEffect(exoPlayer, clipStarts) {
        while (true) {
            if (exoPlayer.isPlaying && clipStarts.isNotEmpty()) {
                val idx = exoPlayer.currentMediaItemIndex.coerceIn(0, clipStarts.lastIndex)
                val spd = timeline.videoClips.getOrNull(idx)?.speed?.coerceAtLeast(0.25) ?: 1.0
                playheadSec = (clipStarts[idx] + (exoPlayer.currentPosition / 1000.0) / spd).coerceIn(0.0, totalDur)
            }
            delay(33L)
        }
    }

    fun seekTimelineTo(targetSec: Double) {
        val clamped = targetSec.coerceIn(0.0, totalDur)
        playheadSec = clamped
        if (clipStarts.isEmpty()) return
        var targetIdx = 0
        for (i in clipStarts.indices) {
            if (clamped >= clipStarts[i]) targetIdx = i
        }
        val clip = timeline.videoClips.getOrNull(targetIdx) ?: return
        val localMs = (((clamped - clipStarts[targetIdx]) * clip.speed) * 1000.0).toLong().coerceAtLeast(0L)
        if (targetIdx < exoPlayer.mediaItemCount) {
            exoPlayer.seekTo(targetIdx, localMs)
        }
    }

    val activeClipIdx = remember(playheadSec, clipStarts) {
        var res = 0
        for (i in clipStarts.indices) if (playheadSec >= clipStarts[i]) res = i
        res
    }
    val activeClip = timeline.videoClips.getOrNull(activeClipIdx)
    val liveZoom = remember(playheadSec, activeClip, clipStarts) {
        if (activeClip == null) 1f else {
            val local = ((playheadSec - clipStarts.getOrElse(activeClipIdx) { 0.0 }) / max(0.1, activeClip.durationSec)).coerceIn(0.0, 1.0)
            (activeClip.zoomStart + (activeClip.zoomEnd - activeClip.zoomStart) * local).toFloat().coerceIn(1f, 3f)
        }
    }
    val activeCaption = remember(playheadSec, timeline.captions) {
        timeline.captions.firstOrNull { playheadSec in it.start..it.end }?.text ?: ""
    }
    val activeOverlay = remember(playheadSec, timeline.overlays) {
        timeline.overlays.firstOrNull { playheadSec in it.start..it.end }
    }
    var overlayBitmap by remember(activeOverlay?.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(activeOverlay?.path) {
        overlayBitmap = activeOverlay?.let { ov ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(guestToHost(ov.path).absolutePath) }.getOrNull() }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            availableTimelines.forEach { tn ->
                PillChip(tn, tn == timeline.name) { onSelectTimeline(tn) }
            }
            PillChip("+ New", false) { showNewTlDialog = true }
            PillChip("Thumb", false) { showThumbDialog = true }
            PillChip("Pacing", pacingReport != null) { showPacingDialog = true; onRunPacingScan() }
            PillChip("Dub", false, onOpenDubbingDialog)
            PillChip("Export", true) { showExportDialog = true }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        if (execution.isRunning) {
            Column(modifier = Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${execution.modeLabel} · ${execution.stageLabel}", color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (execution.progress in 0f..1f) LinearProgressIndicator(progress = { execution.progress }, color = colors.accent, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(color = colors.accent, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(206.dp).background(Color(0xFF050507)).clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            if (timeline.videoClips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Empty Timeline", color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    PillChip("+ Add Active Render to V1", true, onAddActiveRenderClip)
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxHeight().aspectRatio(previewAspect, matchHeightConstraintsFirst = true).clipToBounds()
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
                        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = liveZoom, scaleY = liveZoom)
                    )
                    overlayBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).fillMaxWidth(activeOverlay?.scale ?: 0.35f)
                                .graphicsLayer(alpha = activeOverlay?.opacity ?: 1f)
                        )
                    }
                    if (activeCaption.isNotBlank() && timeline.captionStyle != "none") {
                        val capBg = when (timeline.captionStyle) {
                            "academic_box" -> Color(0xD90D0D14)
                            "highlighted_shorts" -> Color(0x99000000)
                            else -> Color(0x73000000)
                        }
                        val capFg = if (timeline.captionStyle == "highlighted_shorts") Color(0xFFFDE047) else Color(0xFFF5F5F7)
                        Text(
                            text = activeCaption,
                            color = capFg,
                            fontSize = if (timeline.captionStyle == "highlighted_shorts") 12.sp else 11.sp,
                            fontWeight = if (timeline.captionStyle == "highlighted_shorts") FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp)
                                .clip(RoundedCornerShape(6.dp)).background(capBg).padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Text(
                text = "${timeline.preset} · ${String.format(Locale.US, "%.2fs / %.2fs", playheadSec, totalDur)}",
                color = Color(0xFFE5E5EA), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xA6000000)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(colors.accent).clickable {
                    if (isPlaying) exoPlayer.pause() else { if (playheadSec >= totalDur - 0.1) seekTimelineTo(0.0); exoPlayer.play() }
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (isPlaying) AtelierIcons.Pause else AtelierIcons.Play, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            PillChip("+ Video", false, onAddActiveRenderClip)
            PillChip("+ Voice", false, onImportVoiceManifest)
            PillChip("+ BGM", false, onAddLatestMusic)
            PillChip("Split", false) { onSplitVideoAt(playheadSec) }
            PillChip("+ Chap", false) { showChapterDialog = true }
            PillChip("Caps: ${timeline.captionStyle.substringBefore('_')}", false) {
                val styles = listOf("minimal_3b1b", "highlighted_shorts", "academic_box", "none")
                onSetCaptionStyle(styles[(styles.indexOf(timeline.captionStyle) + 1) % styles.size])
            }
            PillChip("-", false) { pxPerSec = (pxPerSec * 0.78f).coerceIn(14f, 110f) }
            PillChip("+", false) { pxPerSec = (pxPerSec * 1.28f).coerceIn(14f, 110f) }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        val timelineWidthDp = max(360f, (totalDur.toFloat() + 2f) * pxPerSec).dp
        val hScroll = rememberScrollState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(colors.background)) {
            Row(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(modifier = Modifier.width(42.dp).background(colors.surface).padding(top = 22.dp)) {
                    listOf("V2", "V1", "T1", "A1", "A2", "A3").forEach { trk ->
                        Box(modifier = Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                            Text(trk, color = colors.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(color = colors.hairline)
                    }
                }
                Box(modifier = Modifier.width(1.dp).height(234.dp).background(colors.hairline))

                Box(modifier = Modifier.weight(1f).horizontalScroll(hScroll)) {
                    Column(modifier = Modifier.width(timelineWidthDp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(22.dp).background(colors.surface).clickable {
                            seekTimelineTo((playheadSec + 1.0) % totalDur)
                        }) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val step = if (pxPerSec < 25f) 5 else 2
                                var s = 0
                                while (s <= totalDur.toInt() + 1) {
                                    val x = s * pxPerSec * density
                                    drawLine(colors.hairline, Offset(x, size.height * 0.55f), Offset(x, size.height), 1f)
                                    s += step
                                }
                                timeline.chapters.forEach { ch ->
                                    val cx = ch.time.toFloat() * pxPerSec * density
                                    drawCircle(colors.warning, 4f, Offset(cx, size.height * 0.45f))
                                }
                            }
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                for (s in 0..totalDur.toInt() step (if (pxPerSec < 25f) 5 else 2)) {
                                    Box(modifier = Modifier.width((pxPerSec * (if (pxPerSec < 25f) 5 else 2)).dp).clickable { seekTimelineTo(s.toDouble()) }) {
                                        Text("${s}s", color = colors.textMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = colors.hairline)

                        TrackLane(34.dp) {
                            timeline.overlays.forEach { ov ->
                                Box(
                                    modifier = Modifier.padding(start = (ov.start * pxPerSec).dp, top = 4.dp, bottom = 4.dp)
                                        .width(max(24f, ((ov.end - ov.start) * pxPerSec).toFloat()).dp).fillMaxHeight()
                                        .clip(RoundedCornerShape(5.dp)).background(Color(0xFF3B2C5A)).border(1.dp, Color(0xFFA78BFA), RoundedCornerShape(5.dp))
                                        .padding(horizontal = 5.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) { Text(File(ov.path).name, color = Color.White, fontSize = 9.sp, maxLines = 1) }
                            }
                        }

                        TrackLane(34.dp) {
                            Row(modifier = Modifier.fillMaxHeight().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                timeline.videoClips.forEachIndexed { idx, clip ->
                                    val isSel = selectedClipIdx == idx
                                    Box(
                                        modifier = Modifier.width(max(36f, (clip.durationSec * pxPerSec).toFloat()).dp).fillMaxHeight()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSel) colors.accent else Color(0xFF16273B))
                                            .border(1.dp, if (isSel) Color.White else colors.accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                selectedClipIdx = if (isSel) -1 else idx
                                                seekTimelineTo(clipStarts.getOrElse(idx) { 0.0 })
                                            }
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        val tag = buildString {
                                            append(clip.label.substringBeforeLast('.'))
                                            if (clip.speed != 1.0) append(" ${clip.speed}x")
                                            if (clip.freezeTailSec > 0.0) append(" +${clip.freezeTailSec}s")
                                            if (clip.transition != "none") append(" [${clip.transition.take(4)}]")
                                        }
                                        Text(tag, color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        TrackLane(34.dp) {
                            timeline.captions.forEach { cap ->
                                Box(
                                    modifier = Modifier.padding(start = (cap.start * pxPerSec).dp, top = 5.dp, bottom = 5.dp)
                                        .width(max(22f, ((cap.end - cap.start) * pxPerSec).toFloat()).dp).fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp)).background(Color(0xFF2B2412)).border(1.dp, colors.warning.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .clickable { seekTimelineTo(cap.start) }.padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) { Text(cap.text, color = Color(0xFFFDE047), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }

                        TrackLane(34.dp) {
                            timeline.voiceClips.forEach { vc ->
                                Box(
                                    modifier = Modifier.padding(start = (vc.start * pxPerSec).dp, top = 4.dp, bottom = 4.dp)
                                        .width(max(22f, (vc.durationSec * pxPerSec).toFloat()).dp).fillMaxHeight()
                                        .clip(RoundedCornerShape(5.dp)).background(Color(0xFF102A1E)).border(1.dp, colors.success.copy(alpha = 0.65f), RoundedCornerShape(5.dp))
                                        .clickable { seekTimelineTo(vc.start) }
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp)) {
                                        val peaks = if (voiceWaveformPeaks.isNotEmpty()) voiceWaveformPeaks else List(18) { 0.35f + (it % 4) * 0.14f }
                                        val dx = size.width / max(1, peaks.size)
                                        peaks.forEachIndexed { pi, pk ->
                                            val hBar = size.height * pk.coerceIn(0.12f, 1f)
                                            val x = pi * dx
                                            drawLine(colors.success.copy(alpha = 0.8f), Offset(x, (size.height - hBar) / 2f), Offset(x, (size.height + hBar) / 2f), max(1.5f, dx * 0.55f))
                                        }
                                    }
                                }
                            }
                        }

                        TrackLane(34.dp) {
                            timeline.sfxClips.forEach { sf ->
                                Box(
                                    modifier = Modifier.padding(start = (sf.start * pxPerSec).dp, top = 6.dp, bottom = 6.dp)
                                        .width(max(24f, (sf.durationSec * pxPerSec).toFloat()).dp).fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp)).background(Color(0xFF331C2B)).border(1.dp, Color(0xFFF472B6), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) { Text(File(sf.path).nameWithoutExtension.removePrefix("sfx_"), color = Color(0xFFFBCFE8), fontSize = 8.sp, maxLines = 1) }
                            }
                        }

                        TrackLane(34.dp) {
                            timeline.musicClips.forEach { mc ->
                                val d = if (mc.durationSec > 0.5) mc.durationSec else totalDur
                                Box(
                                    modifier = Modifier.padding(start = (mc.start * pxPerSec).dp, top = 4.dp, bottom = 4.dp)
                                        .width(max(40f, (d * pxPerSec).toFloat()).dp).fillMaxHeight()
                                        .clip(RoundedCornerShape(5.dp)).background(Color(0xFF132238)).border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(5.dp))
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        var bt = 0.0
                                        while (bt < d) {
                                            val bx = (bt / d).toFloat() * size.width
                                            drawLine(Color(0x3338BDF8), Offset(bx, 0f), Offset(bx, size.height), 1f)
                                            bt += 0.714
                                        }
                                        val path = Path()
                                        val steps = 80
                                        for (si in 0..steps) {
                                            val frac = si.toFloat() / steps
                                            val t = mc.start + frac * d
                                            val speaking = timeline.voiceClips.any { t in it.start..(it.start + it.durationSec) }
                                            val y = if (speaking) size.height * 0.74f else size.height * 0.28f
                                            val x = frac * size.width
                                            if (si == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                        }
                                        drawPath(path, Color(0xFF38BDF8), style = Stroke(width = 2f))
                                    }
                                    Text(File(mc.path).nameWithoutExtension.take(24), color = Color(0xFFBAE6FD), fontSize = 8.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.padding(start = (playheadSec * pxPerSec).dp).width(2.dp).height(234.dp).background(colors.error)
                    )
                }
            }
        }

        val selClip = timeline.videoClips.getOrNull(selectedClipIdx)
        if (selClip != null) {
            HorizontalDivider(thickness = 1.dp, color = colors.accent.copy(alpha = 0.7f))
            Column(
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Clip #${selClip.index + 1}: ${selClip.label}", color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Delete", color = colors.error, fontSize = 11.sp, modifier = Modifier.clickable { onDeleteVideoClip(selClip.index); selectedClipIdx = -1 })
                        Text("Done", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { selectedClipIdx = -1 })
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5, 1.0, 1.5, 2.0).forEach { sp ->
                        PillChip("${sp}x", selClip.speed == sp) {
                            onUpdateVideoClip(selClip.index, selClip.trimStart, selClip.trimEnd, sp, selClip.freezeTailSec, selClip.transition, selClip.zoomStart, selClip.zoomEnd)
                        }
                    }
                    PillChip("Freeze +1.5s", selClip.freezeTailSec > 0.0) {
                        val nextF = if (selClip.freezeTailSec > 0.0) 0.0 else 1.5
                        onUpdateVideoClip(selClip.index, selClip.trimStart, selClip.trimEnd, selClip.speed, nextF, selClip.transition, selClip.zoomStart, selClip.zoomEnd)
                    }
                    PillChip("Zoom 1->1.25", selClip.zoomEnd > 1.05) {
                        val z1 = if (selClip.zoomEnd > 1.05) 1.0 else 1.25
                        onUpdateVideoClip(selClip.index, selClip.trimStart, selClip.trimEnd, selClip.speed, selClip.freezeTailSec, selClip.transition, 1.0, z1)
                    }
                    listOf("none", "dissolve", "dip_black", "slide_left").forEach { tr ->
                        PillChip("Tr:$tr", selClip.transition == tr) {
                            onUpdateVideoClip(selClip.index, selClip.trimStart, selClip.trimEnd, selClip.speed, selClip.freezeTailSec, tr, selClip.zoomStart, selClip.zoomEnd)
                        }
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        var expPreset by remember { mutableStateOf(timeline.preset) }
        var hwFast by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = colors.surface,
            title = { Text("Export Timeline & Publish Kit", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Preset", color = colors.textSecondary, fontSize = 11.sp)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("youtube_720p30" to "720p30", "youtube_1080p60" to "1080p60", "youtube_4k60" to "4K60", "shorts_9x16_60" to "9:16 Short", "square_1x1" to "1:1").forEach { (k, l) ->
                            PillChip(l, expPreset == k) { expPreset = k }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillChip("FFmpeg Broadcast Master", !hwFast) { hwFast = false }
                        PillChip("Media3 HW Fast", hwFast) { hwFast = true }
                    }
                    Text(
                        if (hwFast) "Uses Dimensity 7300 MediaCodec hardware H.264 encoder for ultra-fast V1 clip assembly."
                        else "Applies xfade transitions, V2 overlays, ASS kinetic captions, sidechain auto-ducking & -14 LUFS mastering.",
                        color = colors.textMuted, fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillChip("Queue Batch", false) { onEnqueueBatchExport(expPreset); showExportDialog = false }
                        PillChip("Full YouTube Bundle", true) { onExportTimeline(expPreset, false, true); showExportDialog = false }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onExportTimeline(expPreset, hwFast, false); showExportDialog = false }) { Text("Export Now", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Cancel", color = colors.textSecondary) } }
        )
    }

    if (showThumbDialog) {
        var titleDraft by remember { mutableStateOf(timeline.name.replace("_", " ").replaceFirstChar { it.uppercase() }) }
        var subDraft by remember { mutableStateOf("Visual Mathematical Intuition") }
        var mathDraft by remember { mutableStateOf("e^{i\\pi} + 1 = 0") }
        var mobileFeedMode by remember { mutableStateOf(false) }
        var thumbBmp by remember(latestThumbnailHostPath) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(latestThumbnailHostPath) {
            if (latestThumbnailHostPath.isNotBlank() && File(latestThumbnailHostPath).exists()) {
                thumbBmp = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(latestThumbnailHostPath) }.getOrNull() }
            }
        }
        AlertDialog(
            onDismissRequest = { showThumbDialog = false },
            containerColor = colors.surface,
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Thumbnail Studio", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    PillChip(if (mobileFeedMode) "Feed Preview" else "Full 16:9", mobileFeedMode) { mobileFeedMode = !mobileFeedMode }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    thumbBmp?.let { bmp ->
                        if (mobileFeedMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.background).padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    Text("04:12", color = Color.White, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0xCC000000)).padding(horizontal = 3.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(titleDraft, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Text("Atelier Math · 128K views", color = colors.textMuted, fontSize = 9.sp)
                                }
                            }
                        } else {
                            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                        }
                    }
                    EditInputField(titleDraft, { titleDraft = it }, "Title")
                    EditInputField(subDraft, { subDraft = it }, "Subtitle")
                    EditInputField(mathDraft, { mathDraft = it }, "LaTeX Badge (optional)")
                }
            },
            confirmButton = {
                TextButton(onClick = { onGenerateThumbnail(playheadSec, titleDraft, subDraft, mathDraft) }) { Text("Render PNG", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { showThumbDialog = false }) { Text("Close", color = colors.textSecondary) } }
        )
    }

    if (showPacingDialog) {
        AlertDialog(
            onDismissRequest = { showPacingDialog = false },
            containerColor = colors.surface,
            title = { Text("Retention & Pacing Inspector", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (pacingReport == null) {
                        Text("Scanning video motion, voice silences (>1.1s), and -14 LUFS loudness...", color = colors.textSecondary, fontSize = 12.sp)
                    } else {
                        val score = pacingReport.optInt("retentionScore", 0)
                        val lufs = if (pacingReport.isNull("integratedLufs")) "n/a" else String.format(Locale.US, "%.1f LUFS", pacingReport.optDouble("integratedLufs"))
                        Text("Retention Score: $score / 100 · Loudness: $lufs", color = if (score >= 80) colors.success else colors.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        val issues = pacingReport.optJSONArray("issues") ?: JSONArray()
                        if (issues.length() == 0) {
                            Text("No dead air, clipping, or static stretches detected.", color = colors.success, fontSize = 12.sp)
                        } else {
                            for (i in 0 until issues.length()) {
                                val o = issues.getJSONObject(i)
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(colors.surfaceElevated)
                                        .clickable { seekTimelineTo(o.optDouble("start", 0.0)); showPacingDialog = false }.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(o.optString("kind"), color = colors.warning, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                    Text(o.optString("detail"), color = colors.textPrimary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onAutoTrimDeadAir(); showPacingDialog = false }) { Text("Auto-Trim Dead Air", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { showPacingDialog = false }) { Text("Close", color = colors.textSecondary) } }
        )
    }

    if (showNewTlDialog) {
        var nameDraft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewTlDialog = false }, containerColor = colors.surface,
            title = { Text("New Timeline", color = colors.textPrimary, fontSize = 15.sp) },
            text = { EditInputField(nameDraft, { nameDraft = it }, "episode_02") },
            confirmButton = { TextButton(onClick = { if (nameDraft.isNotBlank()) { onCreateTimeline(nameDraft.trim()); showNewTlDialog = false } }) { Text("Create", color = colors.accent) } },
            dismissButton = { TextButton(onClick = { showNewTlDialog = false }) { Text("Cancel", color = colors.textSecondary) } }
        )
    }

    if (showChapterDialog) {
        var chapTitle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showChapterDialog = false }, containerColor = colors.surface,
            title = { Text("Add Chapter at ${String.format(Locale.US, "%.1fs", playheadSec)}", color = colors.textPrimary, fontSize = 15.sp) },
            text = { EditInputField(chapTitle, { chapTitle = it }, "The Geometric Proof") },
            confirmButton = { TextButton(onClick = { if (chapTitle.isNotBlank()) { onAddChapterAt(playheadSec, chapTitle.trim()); showChapterDialog = false } }) { Text("Add", color = colors.accent) } },
            dismissButton = { TextButton(onClick = { showChapterDialog = false }) { Text("Cancel", color = colors.textSecondary) } }
        )
    }
}

@Composable
private fun TrackLane(height: androidx.compose.ui.unit.Dp, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val colors = LocalAtelierColors.current
    Box(modifier = Modifier.fillMaxWidth().height(height).background(colors.background), content = content)
    HorizontalDivider(color = colors.hairline)
}

@Composable
private fun EditInputField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = LocalAtelierColors.current
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceElevated)
            .border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = colors.textMuted, fontSize = 12.sp); inner() }
    )
}
