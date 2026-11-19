"""Atelier Non-Destructive Timeline & Broadcast FFmpeg Mastering Engine (Phase 3A).

Handles:
- timeline.atelier.json multi-track project recipes (video, voice, sfx, music, captions, chapters)
- Broadcast audio mixing: vocal high-pass + BGM voice-carved EQ + sidechain auto-ducking + YouTube -14 LUFS loudnorm
- Multi-format video export: 16:9 (720p30/1080p60/4K60), 9:16 Shorts vertical reframe, 1:1 square
- Freeze-frame tail extension (tpad) + YouTube publish sidecars (captions.srt + chapters.txt)
"""

from __future__ import annotations

import json
import os
import subprocess
import time
from typing import Any, Dict, List, Optional

TIMELINES_DIR = "/outputs/timelines"
EXPORTS_DIR = "/outputs/exports"

EXPORT_PRESETS = {
    "youtube_720p30": (1280, 720, 30, "16:9"),
    "youtube_1080p60": (1920, 1080, 60, "16:9"),
    "youtube_4k60": (3840, 2160, 60, "16:9"),
    "shorts_9x16_60": (1080, 1920, 60, "9:16"),
    "shorts_9x16_30": (1080, 1920, 30, "9:16"),
    "square_1x1": (1080, 1080, 30, "1:1"),
}


def _probe_duration(path: str) -> float:
    try:
        out = subprocess.check_output(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", path],
            text=True,
        ).strip()
        return max(0.0, float(out))
    except Exception:
        return 0.0


def _has_audio_stream(path: str) -> bool:
    try:
        out = subprocess.check_output(
            ["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", path],
            text=True,
        ).strip()
        return bool(out)
    except Exception:
        return False


def _run_ffmpeg(cmd: List[str]) -> None:
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"FFmpeg failed (exit {proc.returncode}):\n{proc.stderr[-2500:]}")


def _fmt_srt_ts(sec: float) -> str:
    ms_total = max(0, int(round(sec * 1000.0)))
    h, rem = divmod(ms_total, 3600000)
    m, rem = divmod(rem, 60000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def _fmt_chapter_ts(sec: float) -> str:
    total = max(0, int(sec))
    h, rem = divmod(total, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h > 0 else f"{m:02d}:{s:02d}"


def resolve_timeline_path(name_or_path: str) -> str:
    os.makedirs(TIMELINES_DIR, exist_ok=True)
    p = name_or_path.strip()
    if p.startswith("/"):
        return p
    if not p.endswith(".json"):
        p = f"{p}.timeline.json"
    return os.path.join(TIMELINES_DIR, p)


def create_timeline(name: str = "episode_01", preset: str = "youtube_1080p60") -> Dict[str, Any]:
    path = resolve_timeline_path(name)
    doc = {
        "schema": "atelier.timeline.v1",
        "name": name,
        "preset": preset,
        "updatedAt": int(time.time()),
        "video_clips": [],
        "voice_clips": [],
        "sfx_clips": [],
        "music_clips": [],
        "captions": [],
        "chapters": [],
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
    return {"path": path, "timeline": doc, "summary": f"created {os.path.basename(path)}"}


def load_timeline(name_or_path: str) -> Dict[str, Any]:
    path = resolve_timeline_path(name_or_path)
    if not os.path.exists(path):
        return create_timeline(name_or_path)["timeline"]
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_timeline(name_or_path: str, doc: Dict[str, Any]) -> str:
    path = resolve_timeline_path(name_or_path)
    doc["updatedAt"] = int(time.time())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
    return path


def export_srt_file(captions: List[Dict[str, Any]], out_srt: str) -> str:
    os.makedirs(os.path.dirname(os.path.abspath(out_srt)), exist_ok=True)
    lines = []
    for idx, c in enumerate(sorted(captions, key=lambda x: float(x.get("start", 0.0))), 1):
        lines.append(str(idx))
        lines.append(f"{_fmt_srt_ts(float(c['start']))} --> {_fmt_srt_ts(float(c['end']))}")
        lines.append(str(c["text"]).strip())
        lines.append("")
    with open(out_srt, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return out_srt


def export_chapters_file(chapters: List[Dict[str, Any]], out_txt: str) -> str:
    os.makedirs(os.path.dirname(os.path.abspath(out_txt)), exist_ok=True)
    ordered = sorted(chapters, key=lambda x: float(x.get("time", 0.0)))
    if ordered and float(ordered[0].get("time", 0.0)) > 0.5:
        ordered.insert(0, {"time": 0.0, "title": "Intro"})
    body = "\n".join(f"{_fmt_chapter_ts(float(c['time']))} {str(c['title']).strip()}" for c in ordered)
    with open(out_txt, "w", encoding="utf-8") as f:
        f.write(body + ("\n" if body else ""))
    return out_txt


def master_audio_mix(
    voice_clips: List[Dict[str, Any]],
    sfx_clips: List[Dict[str, Any]],
    music_clips: List[Dict[str, Any]],
    total_duration: float,
    out_wav: str,
    target_lufs: float = -14.0,
    duck_db: float = -13.0,
) -> str:
    """Mixes voice, SFX, and music with vocal clarity EQ, sidechain auto-ducking & YouTube -14 LUFS loudnorm."""
    os.makedirs(os.path.dirname(os.path.abspath(out_wav)), exist_ok=True)
    valid_voice = [c for c in voice_clips if c.get("path") and os.path.exists(c["path"])]
    valid_sfx = [c for c in sfx_clips if c.get("path") and os.path.exists(c["path"])]
    valid_music = [c for c in music_clips if c.get("path") and os.path.exists(c["path"])]

    if not valid_voice and not valid_sfx and not valid_music:
        _run_ffmpeg(["ffmpeg", "-y", "-f", "lavfi", "-i", f"anullsrc=r=44100:cl=stereo:d={max(1.0, total_duration):.3f}", out_wav])
        return out_wav

    cmd = ["ffmpeg", "-y"]
    fc_parts: List[str] = []
    idx = 0

    voice_labels: List[str] = []
    for c in valid_voice:
        cmd += ["-i", c["path"]]
        delay_ms = max(0, int(round(float(c.get("start", 0.0)) * 1000.0)))
        gain = float(c.get("gain_db", 0.0))
        trim_s = "silenceremove=start_periods=1:start_threshold=-45dB:stop_periods=-1:stop_duration=0.6:stop_threshold=-45dB," if c.get("trim_silence") else ""
        fc_parts.append(
            f"[{idx}:a]{trim_s}aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,"
            f"highpass=f=85,equalizer=f=3200:t=q:w=1.4:g=2.0,volume={gain:.1f}dB,"
            f"adelay={delay_ms}|{delay_ms}[v{idx}]"
        )
        voice_labels.append(f"[v{idx}]")
        idx += 1

    sfx_labels: List[str] = []
    for c in valid_sfx:
        cmd += ["-i", c["path"]]
        delay_ms = max(0, int(round(float(c.get("start", 0.0)) * 1000.0)))
        gain = float(c.get("gain_db", -5.0))
        fc_parts.append(
            f"[{idx}:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,"
            f"volume={gain:.1f}dB,adelay={delay_ms}|{delay_ms}[s{idx}]"
        )
        sfx_labels.append(f"[s{idx}]")
        idx += 1

    music_labels: List[str] = []
    for c in valid_music:
        cmd += ["-stream_loop", "-1", "-i", c["path"]]
        delay_ms = max(0, int(round(float(c.get("start", 0.0)) * 1000.0)))
        dur = float(c.get("duration", 0.0)) or total_duration
        gain = float(c.get("gain_db", -11.0))
        fi = float(c.get("fade_in", 1.2))
        fo = float(c.get("fade_out", 2.2))
        fo_st = max(0.0, dur - fo)
        fc_parts.append(
            f"[{idx}:a]atrim=0:{dur:.3f},asetpts=PTS-STARTPTS,"
            f"aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,"
            f"equalizer=f=2000:t=q:w=1.2:g=-3.5,volume={gain:.1f}dB,"
            f"afade=t=in:st=0:d={fi:.2f},afade=t=out:st={fo_st:.2f}:d={fo:.2f},"
            f"adelay={delay_ms}|{delay_ms}[m{idx}]"
        )
        music_labels.append(f"[m{idx}]")
        idx += 1

    mix_inputs: List[str] = []

    if voice_labels:
        if len(voice_labels) == 1:
            fc_parts.append(f"{voice_labels[0]}asplit=2[voice_out][voice_sc]")
        else:
            fc_parts.append(f"{''.join(voice_labels)}amix=inputs={len(voice_labels)}:duration=longest:normalize=0,asplit=2[voice_out][voice_sc]")
        mix_inputs.append("[voice_out]")

    if sfx_labels:
        if len(sfx_labels) == 1:
            mix_inputs.append(sfx_labels[0])
        else:
            fc_parts.append(f"{''.join(sfx_labels)}amix=inputs={len(sfx_labels)}:duration=longest:normalize=0[sfx_out]")
            mix_inputs.append("[sfx_out]")

    if music_labels:
        if len(music_labels) == 1:
            bgm_src = music_labels[0]
        else:
            fc_parts.append(f"{''.join(music_labels)}amix=inputs={len(music_labels)}:duration=longest:normalize=0[bgm_raw]")
            bgm_src = "[bgm_raw]"
        if voice_labels and duck_db < -1.0:
            ratio = min(12.0, max(2.5, abs(duck_db) / 2.2))
            fc_parts.append(
                f"{bgm_src}[voice_sc]sidechaincompress=threshold=0.025:ratio={ratio:.1f}:attack=35:release=340:makeup=1[bgm_ducked]"
            )
            mix_inputs.append("[bgm_ducked]")
        else:
            mix_inputs.append(bgm_src)

    if len(mix_inputs) == 1:
        fc_parts.append(f"{mix_inputs[0]}atrim=0:{max(1.0, total_duration):.3f},loudnorm=I={target_lufs:.1f}:TP=-1.0:LRA=11[aout]")
    else:
        fc_parts.append(
            f"{''.join(mix_inputs)}amix=inputs={len(mix_inputs)}:duration=longest:normalize=0,"
            f"atrim=0:{max(1.0, total_duration):.3f},loudnorm=I={target_lufs:.1f}:TP=-1.0:LRA=11[aout]"
        )

    cmd += ["-filter_complex", ";".join(fc_parts), "-map", "[aout]", "-ar", "44100", "-ac", "2", out_wav]
    _run_ffmpeg(cmd)
    return out_wav


def mux_video_with_audio(
    video_path: str,
    voice_clips: Optional[List[Dict[str, Any]]] = None,
    sfx_clips: Optional[List[Dict[str, Any]]] = None,
    music_clips: Optional[List[Dict[str, Any]]] = None,
    out_mp4: str = "",
    keep_video_audio: bool = False,
    target_lufs: float = -14.0,
    duck_db: float = -13.0,
) -> Dict[str, Any]:
    if not os.path.exists(video_path):
        raise FileNotFoundError(f"Video not found: {video_path}")
    os.makedirs(EXPORTS_DIR, exist_ok=True)
    vid_dur = _probe_duration(video_path)
    v_clips = list(voice_clips or [])
    if keep_video_audio and _has_audio_stream(video_path):
        v_clips.insert(0, {"path": video_path, "start": 0.0, "gain_db": 0.0})

    max_voice_end = 0.0
    for vc in v_clips:
        if vc.get("path") and os.path.exists(vc["path"]):
            max_voice_end = max(max_voice_end, float(vc.get("start", 0.0)) + _probe_duration(vc["path"]))
    total_dur = max(vid_dur, max_voice_end + 0.35, 1.0)
    tail_pad = max(0.0, total_dur - vid_dur)

    ts = int(time.time())
    mix_wav = os.path.join(EXPORTS_DIR, f"_mix_{ts}.wav")
    master_audio_mix(v_clips, sfx_clips or [], music_clips or [], total_dur, mix_wav, target_lufs=target_lufs, duck_db=duck_db)

    if not out_mp4:
        stem = os.path.splitext(os.path.basename(video_path))[0]
        out_mp4 = os.path.join(EXPORTS_DIR, f"{stem}_mastered_{ts}.mp4")

    if tail_pad > 0.15:
        _run_ffmpeg([
            "ffmpeg", "-y", "-i", video_path, "-i", mix_wav,
            "-vf", f"tpad=stop_mode=clone:stop_duration={tail_pad:.3f}",
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-shortest", out_mp4,
        ])
    else:
        _run_ffmpeg([
            "ffmpeg", "-y", "-i", video_path, "-i", mix_wav,
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", out_mp4,
        ])
    try:
        os.remove(mix_wav)
    except OSError:
        pass
    return {"outputPath": out_mp4, "duration": round(total_dur, 2), "summary": f"muxed {os.path.basename(out_mp4)} ({total_dur:.1f}s)"}


def render_timeline(
    name_or_path: str,
    preset: str = "",
    out_mp4: str = "",
    target_lufs: float = -14.0,
    duck_db: float = -13.0,
    shorts_reframe: str = "blur_pillarbox",
) -> Dict[str, Any]:
    doc = load_timeline(name_or_path)
    p_key = preset or doc.get("preset", "youtube_1080p60")
    w, h, fps, aspect = EXPORT_PRESETS.get(p_key, EXPORT_PRESETS["youtube_1080p60"])
    v_clips = [c for c in doc.get("video_clips", []) if c.get("path") and os.path.exists(c["path"])]
    if not v_clips:
        raise ValueError("Timeline has no existing video clips to render.")

    os.makedirs(EXPORTS_DIR, exist_ok=True)
    ts = int(time.time())
    stem = str(doc.get("name", "episode")).replace(" ", "_")
    if not out_mp4:
        out_mp4 = os.path.join(EXPORTS_DIR, f"{stem}_{p_key}_{ts}.mp4")

    cmd = ["ffmpeg", "-y"]
    vf_chains: List[str] = []
    concat_labels: List[str] = []
    total_vid_dur = 0.0

    for i, c in enumerate(v_clips):
        cmd += ["-i", c["path"]]
        src_dur = _probe_duration(c["path"])
        t0 = max(0.0, float(c.get("trim_start", 0.0)))
        t1 = float(c.get("trim_end", 0.0))
        seg_end = min(src_dur, t1) if t1 > t0 else src_dur
        speed = max(0.25, min(4.0, float(c.get("speed", 1.0))))
        freeze = max(0.0, float(c.get("freeze_tail_sec", 0.0)))
        eff_dur = max(0.1, (seg_end - t0) / speed) + freeze
        total_vid_dur += eff_dur

        chain = f"[{i}:v]trim=start={t0:.3f}:end={seg_end:.3f},setpts=(PTS-STARTPTS)/{speed:.4f},fps={fps}"
        if aspect == "9:16" and shorts_reframe == "blur_pillarbox":
            vf_chains.append(
                f"{chain},split=2[bg{i}][fg{i}];"
                f"[bg{i}]scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},boxblur=24:4[bgb{i}];"
                f"[fg{i}]scale={w}:{h}:force_original_aspect_ratio=decrease[fgs{i}];"
                f"[bgb{i}][fgs{i}]overlay=(W-w)/2:(H-h)/2,setsar=1"
                + (f",tpad=stop_mode=clone:stop_duration={freeze:.3f}" if freeze > 0.05 else "")
                + f"[vc{i}]"
            )
        else:
            chain += f",scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=#06070A,setsar=1"
            if freeze > 0.05:
                chain += f",tpad=stop_mode=clone:stop_duration={freeze:.3f}"
            vf_chains.append(f"{chain}[vc{i}]")
        concat_labels.append(f"[vc{i}]")

    if len(concat_labels) == 1:
        vf_chains.append(f"{concat_labels[0]}format=yuv420p[vout]")
    else:
        vf_chains.append(f"{''.join(concat_labels)}concat=n={len(concat_labels)}:v=1:a=0,format=yuv420p[vout]")

    # Mix voice + sfx + music into mastered WAV
    mix_wav = os.path.join(EXPORTS_DIR, f"_tl_mix_{ts}.wav")
    master_audio_mix(
        doc.get("voice_clips", []), doc.get("sfx_clips", []), doc.get("music_clips", []),
        total_vid_dur, mix_wav, target_lufs=target_lufs, duck_db=duck_db,
    )
    cmd += ["-i", mix_wav]
    audio_idx = len(v_clips)

    cmd += [
        "-filter_complex", ";".join(vf_chains),
        "-map", "[vout]", "-map", f"{audio_idx}:a:0",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-shortest", out_mp4,
    ]
    _run_ffmpeg(cmd)
    try:
        os.remove(mix_wav)
    except OSError:
        pass

    srt_path = ""
    if doc.get("captions"):
        srt_path = export_srt_file(doc["captions"], os.path.join(EXPORTS_DIR, f"{stem}_{ts}.srt"))
    chap_path = ""
    if doc.get("chapters"):
        chap_path = export_chapters_file(doc["chapters"], os.path.join(EXPORTS_DIR, f"{stem}_{ts}_chapters.txt"))

    return {
        "outputPath": out_mp4,
        "srtPath": srt_path,
        "chaptersPath": chap_path,
        "preset": p_key,
        "width": w,
        "height": h,
        "fps": fps,
        "duration": round(total_vid_dur, 2),
        "summary": f"{os.path.basename(out_mp4)} ({w}x{h}@{fps}, {total_vid_dur:.1f}s)",
    }
