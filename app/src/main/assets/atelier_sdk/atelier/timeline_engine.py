"""Atelier Non-Destructive Multi-Track Timeline & Broadcast FFmpeg Engine (v2)."""

from __future__ import annotations

import array
import json
import math
import os
import subprocess
import time
import wave
from typing import Any, Dict, List, Optional

TIMELINES_DIR = "/outputs/timelines"
EXPORTS_DIR = "/outputs/exports"
PUBLISH_DIR = "/outputs/publish"

EXPORT_PRESETS = {
    "youtube_720p30": (1280, 720, 30, "16:9"),
    "youtube_1080p60": (1920, 1080, 60, "16:9"),
    "youtube_4k60": (3840, 2160, 60, "16:9"),
    "shorts_9x16_60": (1080, 1920, 60, "9:16"),
    "shorts_9x16_30": (1080, 1920, 30, "9:16"),
    "square_1x1": (1080, 1080, 30, "1:1"),
}

TRANSITION_MAP = {
    "dissolve": "fade",
    "dip_black": "fadeblack",
    "slide_left": "slideleft",
    "slide_right": "slideright",
    "wipe_left": "wipeleft",
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


def _fmt_ass_ts(sec: float) -> str:
    cs_total = max(0, int(round(sec * 100.0)))
    h, rem = divmod(cs_total, 360000)
    m, rem = divmod(rem, 6000)
    s, cs = divmod(rem, 100)
    return f"{h}:{m:02d}:{s:02d}.{cs:02d}"


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


def list_timelines() -> List[Dict[str, Any]]:
    os.makedirs(TIMELINES_DIR, exist_ok=True)
    out = []
    for fn in sorted(os.listdir(TIMELINES_DIR)):
        if fn.endswith(".json"):
            p = os.path.join(TIMELINES_DIR, fn)
            try:
                with open(p, "r", encoding="utf-8") as f:
                    d = json.load(f)
                out.append({
                    "name": d.get("name", fn.replace(".timeline.json", "")),
                    "path": p,
                    "preset": d.get("preset", "youtube_1080p60"),
                    "videoCount": len(d.get("video_clips", [])),
                    "voiceCount": len(d.get("voice_clips", [])),
                    "updatedAt": d.get("updatedAt", 0),
                })
            except Exception:
                pass
    return sorted(out, key=lambda x: x.get("updatedAt", 0), reverse=True)


def _ensure_v2_defaults(doc: Dict[str, Any]) -> Dict[str, Any]:
    doc.setdefault("schema", "atelier.timeline.v2")
    doc.setdefault("preset", "youtube_1080p60")
    doc.setdefault("caption_style", "minimal_3b1b")
    doc.setdefault("v2_overlays", [])
    doc.setdefault("video_clips", [])
    doc.setdefault("voice_clips", [])
    doc.setdefault("sfx_clips", [])
    doc.setdefault("music_clips", [])
    doc.setdefault("captions", [])
    doc.setdefault("chapters", [])
    return doc


def create_timeline(name: str = "episode_01", preset: str = "youtube_1080p60") -> Dict[str, Any]:
    path = resolve_timeline_path(name)
    doc = _ensure_v2_defaults({"name": name, "preset": preset, "updatedAt": int(time.time())})
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
    return {"path": path, "timeline": doc, "summary": f"created {os.path.basename(path)}"}


def load_timeline(name_or_path: str) -> Dict[str, Any]:
    path = resolve_timeline_path(name_or_path)
    if not os.path.exists(path):
        return create_timeline(name_or_path)["timeline"]
    with open(path, "r", encoding="utf-8") as f:
        return _ensure_v2_defaults(json.load(f))


def save_timeline(name_or_path: str, doc: Dict[str, Any]) -> str:
    path = resolve_timeline_path(name_or_path)
    _ensure_v2_defaults(doc)
    doc["updatedAt"] = int(time.time())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
    return path


def extract_waveform_peaks(wav_path: str, bins: int = 96) -> List[float]:
    if not os.path.exists(wav_path):
        return []
    try:
        with wave.open(wav_path, "rb") as wf:
            n_ch = wf.getnchannels()
            sw = wf.getsampwidth()
            n_frames = wf.getnframes()
            if sw != 2 or n_frames <= 0:
                return []
            raw = wf.readframes(min(n_frames, 44100 * 180))
        pcm = array.array("h")
        pcm.frombytes(raw)
        step = max(1, len(pcm) // (max(8, bins) * n_ch)) * n_ch
        peaks: List[float] = []
        for i in range(0, len(pcm), step):
            chunk = pcm[i : i + step : n_ch]
            if chunk:
                mx = max(abs(min(chunk)), abs(max(chunk))) / 32768.0
                peaks.append(round(min(1.0, mx), 3))
        return peaks[:bins]
    except Exception:
        return []


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


def export_ass_captions(captions: List[Dict[str, Any]], out_ass: str, w: int, h: int, style_name: str = "minimal_3b1b") -> str:
    os.makedirs(os.path.dirname(os.path.abspath(out_ass)), exist_ok=True)
    is_vert = h > w
    font_sz = int((56 if style_name == "highlighted_shorts" else 44) * (h / 1080.0))
    margin_v = int((260 if is_vert else 54) * (h / 1080.0))
    if style_name == "highlighted_shorts":
        style_line = f"Style: Default,DejaVu Sans,{font_sz},&H000AF8FF,&H00FFFFFF,&H0008080C,&H90000000,-1,0,0,0,100,100,0,0,1,3,1,2,40,40,{margin_v},1"
    elif style_name == "academic_box":
        style_line = f"Style: Default,DejaVu Sans,{font_sz},&H00F5F5F7,&H00FF840A,&H00000000,&HC80D0D12,0,0,0,0,100,100,0,0,3,12,0,2,56,56,{margin_v},1"
    else:
        style_line = f"Style: Default,DejaVu Sans,{font_sz},&H00F5F5F7,&H00FF840A,&H0006070A,&H70000000,0,0,0,0,100,100,0,0,1,2,1,2,48,48,{margin_v},1"

    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        f"PlayResX: {w}",
        f"PlayResY: {h}",
        "WrapStyle: 0",
        "",
        "[V4+ Styles]",
        "Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding",
        style_line,
        "",
        "[Events]",
        "Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text",
    ]
    for c in sorted(captions, key=lambda x: float(x.get("start", 0.0))):
        st = float(c.get("start", 0.0))
        en = max(st + 0.12, float(c.get("end", st + 1.5)))
        txt = str(c.get("text", "")).replace("\n", " ").replace("{", "(").replace("}", ")").strip()
        if not txt:
            continue
        words = c.get("words") or []
        if style_name == "highlighted_shorts" and words:
            parts = []
            for wd in words:
                dur_cs = max(8, int(round((float(wd.get("end", 0.0)) - float(wd.get("start", 0.0))) * 100.0)))
                w_txt = str(wd.get("word", "")).replace("{", "").replace("}", "")
                parts.append(f"{{\\kf{dur_cs}}}{w_txt}")
            body = " ".join(parts)
        elif style_name == "highlighted_shorts":
            toks = txt.split()
            per_cs = max(10, int(round(((en - st) / max(1, len(toks))) * 100.0)))
            body = " ".join(f"{{\\kf{per_cs}}}{t}" for t in toks)
        else:
            body = txt
        lines.append(f"Dialogue: 0,{_fmt_ass_ts(st)},{_fmt_ass_ts(en)},Default,,0,0,0,,{body}")
    with open(out_ass, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return out_ass


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
    os.makedirs(os.path.dirname(os.path.abspath(out_wav)), exist_ok=True)
    valid_voice = [c for c in voice_clips if c.get("path") and os.path.exists(c["path"]) and _probe_duration(c["path"]) > 0.05]
    valid_sfx = [c for c in sfx_clips if c.get("path") and os.path.exists(c["path"]) and _probe_duration(c["path"]) > 0.03]
    valid_music = [c for c in music_clips if c.get("path") and os.path.exists(c["path"]) and _probe_duration(c["path"]) > 0.1]

    if not valid_voice and not valid_sfx and not valid_music:
        _run_ffmpeg(["ffmpeg", "-y", "-f", "lavfi", "-i", f"anullsrc=r=44100:cl=stereo:d={max(1.0, total_duration):.3f}", out_wav])
        return out_wav

    cmd = ["ffmpeg", "-y"]
    fc_parts: List[str] = []
    idx = 0

    voice_labels: List[str] = []
    for c in valid_voice:
        cmd += ["-i", c["path"]]
        delay_ms = max(0, int(round((float(c.get("start", 0.0)) + float(c.get("audio_offset_sec", 0.0))) * 1000.0)))
        gain = float(c.get("gain_db", 0.0))
        dur_c = _probe_duration(c["path"])
        trim_s = (
            "silenceremove=start_periods=1:start_threshold=-45dB:stop_periods=-1:stop_duration=0.65:stop_threshold=-45dB,"
            if (c.get("trim_silence") and dur_c > 1.2)
            else ""
        )
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
        fi = min(float(c.get("fade_in", 1.2)), dur * 0.3)
        fo = min(float(c.get("fade_out", 2.2)), dur * 0.35)
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
        bgm_src = music_labels[0] if len(music_labels) == 1 else "[bgm_raw]"
        if len(music_labels) > 1:
            fc_parts.append(f"{''.join(music_labels)}amix=inputs={len(music_labels)}:duration=longest:normalize=0[bgm_raw]")
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

    if tail_pad > 0.05:
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
    burn_captions: Optional[bool] = None,
) -> Dict[str, Any]:
    doc = load_timeline(name_or_path)
    p_key = preset or doc.get("preset", "youtube_1080p60")
    w, h, fps, aspect = EXPORT_PRESETS.get(p_key, EXPORT_PRESETS["youtube_1080p60"])
    v_clips = [c for c in doc.get("video_clips", []) if c.get("path") and os.path.exists(c["path"])]
    if not v_clips:
        raise ValueError("Timeline has no existing video clips to render.")
    overlays = [ov for ov in doc.get("v2_overlays", []) if ov.get("path") and os.path.exists(ov["path"])]

    os.makedirs(EXPORTS_DIR, exist_ok=True)
    ts = int(time.time())
    stem = str(doc.get("name", "episode")).replace(" ", "_")
    if not out_mp4:
        out_mp4 = os.path.join(EXPORTS_DIR, f"{stem}_{p_key}_{ts}.mp4")

    cmd = ["ffmpeg", "-y"]
    vf_chains: List[str] = []
    clip_labels: List[str] = []
    clip_durs: List[float] = []
    clip_trans: List[tuple] = []

    for i, c in enumerate(v_clips):
        cmd += ["-i", c["path"]]
        src_dur = max(0.2, _probe_duration(c["path"]))
        t0 = max(0.0, float(c.get("trim_start", 0.0)))
        t1 = float(c.get("trim_end", 0.0))
        seg_end = min(src_dur, t1) if t1 > t0 + 0.05 else src_dur
        speed = max(0.25, min(4.0, float(c.get("speed", 1.0))))
        freeze = max(0.0, float(c.get("freeze_tail_sec", 0.0)))
        eff_dur = max(0.15, (seg_end - t0) / speed) + freeze
        clip_durs.append(eff_dur)
        tr_name = str(c.get("transition", "none")).lower()
        tr_dur = max(0.15, min(1.5, float(c.get("transition_dur", 0.45))))
        clip_trans.append((tr_name, tr_dur))

        z0 = max(1.0, min(3.0, float(c.get("zoom_start", 1.0))))
        z1 = max(1.0, min(3.0, float(c.get("zoom_end", z0))))
        px = max(-0.45, min(0.45, float(c.get("pan_x", 0.0))))
        py = max(-0.45, min(0.45, float(c.get("pan_y", 0.0))))

        chain = f"[{i}:v]trim=start={t0:.3f}:end={seg_end:.3f},setpts=(PTS-STARTPTS)/{speed:.4f},fps={fps}"
        if aspect == "9:16" and shorts_reframe == "blur_pillarbox":
            base_lbl = f"vb{i}"
            vf_chains.append(
                f"{chain},split=2[bg{i}][fg{i}];"
                f"[bg{i}]scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},boxblur=24:4[bgb{i}];"
                f"[fg{i}]scale={w}:{h}:force_original_aspect_ratio=decrease[fgs{i}];"
                f"[bgb{i}][fgs{i}]overlay=(W-w)/2:(H-h)/2,setsar=1[{base_lbl}]"
            )
            post = f"[{base_lbl}]"
        else:
            post = f"{chain},scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=#06070A,setsar=1"

        if z0 > 1.01 or z1 > 1.01:
            z_max = max(z0, z1)
            sw, sh = int(round(w * z_max)), int(round(h * z_max))
            prog = f"min(1,t/{max(0.1, eff_dur):.3f})"
            z_expr = f"({z0:.3f}+({z1 - z0:.3f})*{prog})"
            cw_expr = f"trunc({w}*({z_max:.3f}/{z_expr})/2)*2"
            ch_expr = f"trunc({h}*({z_max:.3f}/{z_expr})/2)*2"
            cx_expr = f"max(0,min(iw-ow,(iw-ow)/2+({px:.3f}*iw)))"
            cy_expr = f"max(0,min(ih-oh,(ih-oh)/2+({py:.3f}*ih)))"
            post += f",scale={sw}:{sh},crop=w='{cw_expr}':h='{ch_expr}':x='{cx_expr}':y='{cy_expr}',scale={w}:{h}"

        if freeze > 0.05:
            post += f",tpad=stop_mode=clone:stop_duration={freeze:.3f}"
        post += ",format=yuv420p"
        vf_chains.append(f"{post}[vc{i}]")
        clip_labels.append(f"[vc{i}]")

    has_xfade = len(clip_labels) > 1 and any(tr[0] in TRANSITION_MAP for tr in clip_trans[:-1])
    if len(clip_labels) == 1:
        cur_v = clip_labels[0]
        total_vid_dur = clip_durs[0]
    elif not has_xfade:
        vf_chains.append(f"{''.join(clip_labels)}concat=n={len(clip_labels)}:v=1:a=0[vcat]")
        cur_v = "[vcat]"
        total_vid_dur = sum(clip_durs)
    else:
        cur_v = clip_labels[0]
        cum_dur = clip_durs[0]
        for idx_c in range(1, len(clip_labels)):
            tr_name, tr_dur = clip_trans[idx_c - 1]
            ff_tr = TRANSITION_MAP.get(tr_name, "fade")
            safe_d = min(tr_dur, clip_durs[idx_c - 1] * 0.38, clip_durs[idx_c] * 0.38)
            safe_d = max(0.08, safe_d) if tr_name in TRANSITION_MAP else 0.04
            offset = max(0.05, cum_dur - safe_d)
            out_lbl = f"[vx{idx_c}]"
            vf_chains.append(f"{cur_v}{clip_labels[idx_c]}xfade=transition={ff_tr}:duration={safe_d:.3f}:offset={offset:.3f}{out_lbl}")
            cur_v = out_lbl
            cum_dur = offset + clip_durs[idx_c]
        total_vid_dur = cum_dur

    next_in = len(v_clips)
    for ov_i, ov in enumerate(overlays):
        cmd += ["-loop", "1", "-i", ov["path"]]
        st = max(0.0, float(ov.get("start", 0.0)))
        en = max(st + 0.2, float(ov.get("end", total_vid_dur)))
        sc = max(0.08, min(1.0, float(ov.get("scale", 0.35))))
        ox = float(ov.get("x", 0.5))
        oy = float(ov.get("y", 0.18))
        alpha = max(0.05, min(1.0, float(ov.get("opacity", 1.0))))
        ow = max(32, int(w * sc))
        ov_lbl = f"[ov{ov_i}]"
        vf_chains.append(f"[{next_in}:v]scale={ow}:-1,format=rgba,colorchannelmixer=aa={alpha:.2f}{ov_lbl}")
        nxt_v = f"[vo{ov_i}]"
        vf_chains.append(
            f"{cur_v}{ov_lbl}overlay=x='({ox:.3f}*W-w/2)':y='({oy:.3f}*H-h/2)':enable='between(t,{st:.3f},{en:.3f})':shortest=1{nxt_v}"
        )
        cur_v = nxt_v
        next_in += 1

    cap_style = str(doc.get("caption_style", "minimal_3b1b")).lower()
    should_burn = (cap_style != "none") if burn_captions is None else bool(burn_captions)
    ass_path = ""
    if should_burn and doc.get("captions"):
        ass_path = export_ass_captions(doc["captions"], os.path.join(EXPORTS_DIR, f"_caps_{ts}.ass"), w, h, cap_style)
        esc_ass = ass_path.replace("\\", "\\\\").replace(":", "\\:")
        vf_chains.append(f"{cur_v}ass='{esc_ass}',format=yuv420p[vout]")
    else:
        vf_chains.append(f"{cur_v}format=yuv420p[vout]")

    voice_clips = list(doc.get("voice_clips", []))
    if not voice_clips:
        t_cur = 0.0
        for idx_c, c in enumerate(v_clips):
            if _has_audio_stream(c["path"]):
                voice_clips.append({"path": c["path"], "start": t_cur, "gain_db": 0.0, "audio_offset_sec": float(c.get("audio_offset_sec", 0.0))})
            t_cur += clip_durs[idx_c]

    mix_wav = os.path.join(EXPORTS_DIR, f"_tl_mix_{ts}.wav")
    master_audio_mix(
        voice_clips, doc.get("sfx_clips", []), doc.get("music_clips", []),
        total_vid_dur, mix_wav, target_lufs=target_lufs, duck_db=duck_db,
    )
    cmd += ["-i", mix_wav]
    audio_idx = next_in

    cmd += [
        "-filter_complex", ";".join(vf_chains),
        "-map", "[vout]", "-map", f"{audio_idx}:a:0",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-shortest", out_mp4,
    ]
    _run_ffmpeg(cmd)
    for tmpf in (mix_wav, ass_path):
        if tmpf:
            try:
                os.remove(tmpf)
            except OSError:
                pass

    srt_path = export_srt_file(doc["captions"], os.path.join(EXPORTS_DIR, f"{stem}_{ts}.srt")) if doc.get("captions") else ""
    chap_path = export_chapters_file(doc["chapters"], os.path.join(EXPORTS_DIR, f"{stem}_{ts}_chapters.txt")) if doc.get("chapters") else ""

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


def export_publish_bundle(
    name_or_path: str,
    include_short: bool = True,
    master_preset: str = "youtube_1080p60",
    thumbnail_title: str = "",
    thumbnail_subtitle: str = "",
    thumbnail_math: str = "",
) -> Dict[str, Any]:
    from . import studio_engines as _se

    doc = load_timeline(name_or_path)
    stem = str(doc.get("name", "episode")).replace(" ", "_")
    ts = int(time.time())
    bundle_dir = os.path.join(PUBLISH_DIR, f"{stem}_{ts}")
    os.makedirs(bundle_dir, exist_ok=True)

    master_mp4 = os.path.join(bundle_dir, f"{stem}_master_16x9.mp4")
    m_res = render_timeline(name_or_path, preset=master_preset, out_mp4=master_mp4)
    short_path = ""
    if include_short:
        s_mp4 = os.path.join(bundle_dir, f"{stem}_short_9x16.mp4")
        s_res = render_timeline(name_or_path, preset="shorts_9x16_60", out_mp4=s_mp4)
        short_path = s_res["outputPath"]

    srt_path = export_srt_file(doc["captions"], os.path.join(bundle_dir, "captions.srt")) if doc.get("captions") else ""
    chap_path = export_chapters_file(doc["chapters"], os.path.join(bundle_dir, "chapters.txt")) if doc.get("chapters") else ""
    thumb_path = os.path.join(bundle_dir, "thumbnail.png")
    t_res = _se.render_thumbnail(
        video_path=master_mp4,
        time_sec=min(2.5, max(0.2, m_res["duration"] * 0.4)),
        title=thumbnail_title or stem.replace("_", " ").title(),
        subtitle=thumbnail_subtitle,
        math_tex=thumbnail_math,
        out_png=thumb_path,
    )
    meta = {
        "bundleDir": bundle_dir,
        "masterMp4": master_mp4,
        "shortMp4": short_path,
        "thumbnailPng": t_res["path"],
        "srtPath": srt_path,
        "chaptersPath": chap_path,
        "duration": m_res["duration"],
        "summary": f"Publish bundle ready in {os.path.basename(bundle_dir)}",
    }
    with open(os.path.join(bundle_dir, "publish_metadata.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    return meta
