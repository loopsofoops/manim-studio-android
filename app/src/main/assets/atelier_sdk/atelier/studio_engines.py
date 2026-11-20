"""Atelier Pro Studio Engines (Phase 3B + Phase 4)."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import time
from typing import Any, Dict, List, Optional

from PIL import Image, ImageDraw, ImageEnhance, ImageFont

from . import music_engine as _music
from . import timeline_engine as _tl

BRAND_PATH = "/outputs/brand.json"
SA3_DIR = "/user-site/stable_audio_3"


def _load_brand() -> Dict[str, Any]:
    defaults = {
        "channelName": "Atelier Math",
        "bgColor": "#06070A",
        "primaryColor": "#F5F5F7",
        "accentColor": "#0A84FF",
        "highlightColor": "#FDE047",
        "secondaryColor": "#34D399",
        "watermark": "",
        "defaultNarratorModel": "kokoro_en_019",
        "defaultNarratorSid": 9,
        "defaultMusicStyle": "felt_piano",
        "defaultMusicKey": "D",
        "defaultMusicBpm": 84,
    }
    if os.path.exists(BRAND_PATH):
        try:
            with open(BRAND_PATH, "r", encoding="utf-8") as f:
                defaults.update(json.load(f))
        except Exception:
            pass
    return defaults


def _find_font(bold: bool = True, size: int = 64) -> ImageFont.FreeTypeFont:
    candidates = (
        [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        ]
        if bold
        else [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        ]
    )
    for p in candidates:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()


def preview_formula(tex_expr: str, out_png: str = "", bg_hex: str = "#06070A", fg_hex: str = "#F5F5F7") -> Dict[str, Any]:
    expr = tex_expr.strip()
    if not expr:
        raise ValueError("Formula expression is empty.")
    os.makedirs("/outputs/previews", exist_ok=True)
    if not out_png:
        out_png = f"/outputs/previews/formula_{int(time.time())}.png"

    m = re.search(r'(?:MathTex|Tex)\(\s*r?["\']{1,3}(.+?)["\']{1,3}', expr, re.DOTALL)
    if m:
        expr = m.group(1).strip()
    expr = expr.strip("$").strip()
    if not expr:
        raise ValueError("Could not extract LaTeX formula from selection.")

    with tempfile.TemporaryDirectory() as td:
        tex_file = os.path.join(td, "eq.tex")
        body = (
            "\\documentclass[preview,border=14pt]{standalone}\n"
            "\\usepackage{amsmath,amssymb,amsfonts,xcolor}\n"
            f"\\definecolor{{bg}}{{HTML}}{{{bg_hex.lstrip('#')}}}\n"
            f"\\definecolor{{fg}}{{HTML}}{{{fg_hex.lstrip('#')}}}\n"
            "\\pagecolor{bg}\n"
            "\\begin{document}\n"
            f"\\color{{fg}}$\\displaystyle {expr}$\n"
            "\\end{document}\n"
        )
        with open(tex_file, "w", encoding="utf-8") as f:
            f.write(body)
        p = subprocess.run(
            ["pdflatex", "-interaction=nonstopmode", "-halt-on-error", "-output-directory", td, tex_file],
            capture_output=True, text=True,
        )
        pdf_path = os.path.join(td, "eq.pdf")
        if p.returncode != 0 or not os.path.exists(pdf_path):
            raise RuntimeError(f"LaTeX formula compile failed:\n{(p.stdout + p.stderr)[-1200:]}")
        stem = os.path.join(td, "eq_img")
        subprocess.run(["pdftoppm", "-png", "-r", "320", "-singlefile", pdf_path, stem], check=True)
        shutil.copy2(stem + ".png", out_png)
    return {"path": out_png, "expr": expr, "summary": f"Formula preview ready ({os.path.basename(out_png)})"}


def render_thumbnail(
    video_path: str = "",
    time_sec: float = 1.5,
    title: str = "Euler's Identity",
    subtitle: str = "Why e^(i pi) + 1 = 0",
    math_tex: str = "",
    accent_hex: str = "",
    out_png: str = "",
) -> Dict[str, Any]:
    brand = _load_brand()
    accent = accent_hex or brand.get("accentColor", "#0A84FF")
    os.makedirs("/outputs/thumbnails", exist_ok=True)
    if not out_png:
        out_png = f"/outputs/thumbnails/thumb_{int(time.time())}.png"

    W, H = 1280, 720
    if video_path and os.path.exists(video_path):
        tmp_frame = f"/outputs/thumbnails/_raw_{int(time.time())}.png"
        try:
            subprocess.run(
                ["ffmpeg", "-y", "-ss", f"{max(0.0, time_sec):.2f}", "-i", video_path, "-frames:v", "1", "-vf", f"scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H}", tmp_frame],
                capture_output=True, check=True,
            )
            base = Image.open(tmp_frame).convert("RGBA")
            os.remove(tmp_frame)
        except Exception:
            base = Image.new("RGBA", (W, H), (8, 10, 16, 255))
    else:
        base = Image.new("RGBA", (W, H), (8, 10, 16, 255))

    base = ImageEnhance.Contrast(base).enhance(1.12)
    grad = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for y in range(H):
        a = int(195 * max(0.0, (y - H * 0.42) / (H * 0.58)))
        gd.line([(0, y), (W, y)], fill=(5, 6, 10, a))
    for x in range(int(W * 0.55)):
        a = int(135 * (1.0 - x / (W * 0.55)))
        gd.line([(x, 0), (x, H)], fill=(5, 6, 10, a))
    comp = Image.alpha_composite(base, grad)

    draw = ImageDraw.Draw(comp)
    ac_hex = accent.lstrip("#")
    ac_rgb = tuple(int(ac_hex[i : i + 2], 16) for i in (0, 2, 4)) if len(ac_hex) == 6 else (10, 132, 255)
    draw.rectangle([54, 48, 66, 210], fill=(*ac_rgb, 255))

    f_title = _find_font(True, 86)
    f_sub = _find_font(False, 40)
    f_brand = _find_font(True, 26)

    words = (title or "").split()
    t_lines: List[str] = []
    cur = ""
    for wd in words:
        cand = (cur + " " + wd).strip()
        if len(cand) <= 19 or not cur:
            cur = cand
        else:
            t_lines.append(cur)
            cur = wd
    if cur:
        t_lines.append(cur)
    t_lines = t_lines[:2]

    y_pos = 52
    for ln in t_lines:
        draw.text((92 + 3, y_pos + 3), ln, font=f_title, fill=(0, 0, 0, 210))
        draw.text((92, y_pos), ln, font=f_title, fill=(248, 250, 252, 255))
        y_pos += 96

    if subtitle:
        draw.text((92 + 2, y_pos + 12), subtitle[:42], font=f_sub, fill=(0, 0, 0, 200))
        draw.text((92, y_pos + 10), subtitle[:42], font=f_sub, fill=(203, 213, 225, 255))

    if math_tex.strip():
        try:
            fp = preview_formula(math_tex, bg_hex="#0C0E14", fg_hex="#FDE047")["path"]
            fimg = Image.open(fp).convert("RGBA")
            scale = min(560.0 / max(1, fimg.width), 170.0 / max(1, fimg.height))
            nw, nh = max(40, int(fimg.width * scale)), max(30, int(fimg.height * scale))
            fimg = fimg.resize((nw, nh), Image.Resampling.LANCZOS)
            box = (54, H - nh - 78, 54 + nw + 36, H - 46)
            draw.rounded_rectangle(box, radius=18, fill=(12, 14, 20, 225), outline=(*ac_rgb, 210), width=3)
            comp.alpha_composite(fimg, (72, H - nh - 62))
        except Exception:
            pass

    wm = brand.get("watermark") or brand.get("channelName", "")
    if wm:
        draw.text((W - 340, H - 56), wm[:26].upper(), font=f_brand, fill=(220, 226, 236, 170))

    comp.convert("RGB").save(out_png, "PNG", optimize=True)
    return {"path": out_png, "width": W, "height": H, "summary": f"Thumbnail saved ({os.path.basename(out_png)})"}


def analyze_pacing(video_path: str, silence_min_sec: float = 1.1, static_min_sec: float = 6.0) -> Dict[str, Any]:
    if not os.path.exists(video_path):
        raise FileNotFoundError(f"Video not found: {video_path}")
    dur = _tl._probe_duration(video_path)
    issues: List[Dict[str, Any]] = []

    silences: List[Dict[str, float]] = []
    integrated_lufs: Optional[float] = None
    true_peak_db: Optional[float] = None
    if _tl._has_audio_stream(video_path):
        p_sil = subprocess.run(
            ["ffmpeg", "-i", video_path, "-af", f"silencedetect=noise=-42dB:d={silence_min_sec:.2f},ebur128=peak=true", "-f", "null", "-"],
            capture_output=True, text=True,
        )
        cur_start: Optional[float] = None
        for ln in p_sil.stderr.splitlines():
            m_st = re.search(r"silence_start:\s*([0-9.]+)", ln)
            m_en = re.search(r"silence_end:\s*([0-9.]+)\s*\|\s*silence_duration:\s*([0-9.]+)", ln)
            if m_st:
                cur_start = float(m_st.group(1))
            elif m_en:
                en, d_s = float(m_en.group(1)), float(m_en.group(2))
                st = cur_start if cur_start is not None else max(0.0, en - d_s)
                silences.append({"start": round(st, 2), "end": round(en, 2), "duration": round(d_s, 2)})
                issues.append({"kind": "DEAD_AIR", "start": round(st, 2), "end": round(en, 2), "detail": f"Silence gap {d_s:.1f}s ({st:.1f}s - {en:.1f}s)"})
                cur_start = None
            m_i = re.search(r"^\s*I:\s*(-?[0-9.]+)\s*LUFS", ln)
            m_tp = re.search(r"^\s*Peak:\s*(-?[0-9.]+)\s*dBFS", ln)
            if m_i:
                integrated_lufs = float(m_i.group(1))
            if m_tp:
                true_peak_db = float(m_tp.group(1))

    static_stretches: List[Dict[str, float]] = []
    try:
        p_vid = subprocess.run(
            ["ffmpeg", "-i", video_path, "-vf", "fps=1,scale=96:54,format=gray", "-f", "rawvideo", "-"],
            capture_output=True,
        )
        raw = p_vid.stdout
        frame_sz = 96 * 54
        n_frames = len(raw) // frame_sz
        run_start: Optional[int] = None
        for i in range(1, n_frames):
            f0 = raw[(i - 1) * frame_sz : i * frame_sz]
            f1 = raw[i * frame_sz : (i + 1) * frame_sz]
            diff = sum(abs(a - b) for a, b in zip(f0[::4], f1[::4])) / (frame_sz / 4.0)
            if diff < 0.75:
                if run_start is None:
                    run_start = i - 1
            else:
                if run_start is not None and (i - run_start) >= static_min_sec:
                    static_stretches.append({"start": float(run_start), "end": float(i), "duration": float(i - run_start)})
                    issues.append({"kind": "STATIC_VISUAL", "start": float(run_start), "end": float(i), "detail": f"Static screen for {i - run_start}s ({run_start}s - {i}s)"})
                run_start = None
        if run_start is not None and (n_frames - run_start) >= static_min_sec:
            static_stretches.append({"start": float(run_start), "end": float(n_frames), "duration": float(n_frames - run_start)})
            issues.append({"kind": "STATIC_VISUAL", "start": float(run_start), "end": float(n_frames), "detail": f"Static screen for {n_frames - run_start}s ({run_start}s - {n_frames}s)"})
    except Exception:
        pass

    if integrated_lufs is not None and abs(integrated_lufs - (-14.0)) > 2.5:
        issues.append({"kind": "LOUDNESS", "start": 0.0, "end": dur, "detail": f"Loudness {integrated_lufs:.1f} LUFS (YouTube target: -14.0 LUFS)"})
    if true_peak_db is not None and true_peak_db > -0.5:
        issues.append({"kind": "CLIPPING", "start": 0.0, "end": dur, "detail": f"True peak {true_peak_db:.1f} dBFS (risk of clipping; target <= -1.0)"})

    score = max(35, 100 - len(silences) * 7 - len(static_stretches) * 9 - (8 if integrated_lufs and abs(integrated_lufs + 14.0) > 2.5 else 0))
    return {
        "videoPath": video_path,
        "duration": round(dur, 2),
        "retentionScore": int(score),
        "integratedLufs": integrated_lufs,
        "truePeakDb": true_peak_db,
        "silences": silences,
        "staticStretches": static_stretches,
        "issues": issues,
        "summary": f"Score {score}/100 · {len(silences)} silence(s) · {len(static_stretches)} static stretch(es)",
    }


def auto_trim_dead_air(timeline_name: str) -> Dict[str, Any]:
    doc = _tl.load_timeline(timeline_name)
    for vc in doc.get("voice_clips", []):
        vc["trim_silence"] = True
    _tl.save_timeline(timeline_name, doc)
    return {"timeline": timeline_name, "voiceClipsUpdated": len(doc.get("voice_clips", [])), "summary": "Enabled silence trimming on all voice clips"}


def build_episode_blueprint(
    blueprint_text: str,
    timeline_name: str = "blueprint_ep",
    voice_model: str = "",
    sid: int = -1,
    music_style: str = "felt_piano",
) -> Dict[str, Any]:
    from . import rpc as _rpc

    lines_to_speak: List[str] = []
    events: List[Dict[str, Any]] = []
    for raw in blueprint_text.splitlines():
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        tags = re.findall(r"\[(SCENE|SYNC|SFX|MOOD|CHAPTER):\s*([^\]]+)\]", s, re.IGNORECASE)
        clean_line = re.sub(r"\[(SCENE|SYNC|SFX|MOOD|CHAPTER):[^\]]+\]", "", s, flags=re.IGNORECASE).strip()
        line_idx = len(lines_to_speak)
        for k, v in tags:
            events.append({"kind": k.upper(), "value": v.strip(), "lineIndex": line_idx})
        if clean_line:
            lines_to_speak.append(clean_line)

    if not lines_to_speak:
        raise ValueError("Blueprint has no narration lines.")

    manifest = _rpc("voice.synthesize_script", {
        "lines": lines_to_speak, "model": voice_model, "sid": int(sid), "speed": 1.0,
        "pause_sec": 0.25, "normalize_math": True, "manifest_name": f"{timeline_name}_manifest.json",
        "srt_name": f"{timeline_name}.srt",
    }, timeout=1800.0)

    cues = manifest.get("cues", [])
    total_dur = max(12.0, float(manifest.get("totalDurationSec", 30.0)) + 2.5)
    energy_map = {"calm": 0.32, "curious": 0.45, "build": 0.72, "climax": 0.95, "resolve": 0.88}
    music_cues: List[Dict[str, Any]] = [{"time": 0.0, "energy": 0.38}]

    _tl.create_timeline(timeline_name, preset="youtube_1080p60")
    doc = _tl.load_timeline(timeline_name)
    for cue in cues:
        doc["voice_clips"].append({"path": cue["wavPath"], "start": cue["startSec"], "gain_db": 0.0, "text": cue["text"]})
        doc["captions"].append({"start": cue["startSec"], "end": cue["endSec"], "text": cue["text"], "words": cue.get("words", [])})

    for ev in events:
        li = min(ev["lineIndex"], max(0, len(cues) - 1))
        t_ev = float(cues[li]["startSec"]) if cues else 0.0
        k, v = ev["kind"], ev["value"]
        if k == "CHAPTER":
            doc["chapters"].append({"time": t_ev, "title": v})
        elif k == "SFX":
            sfx_res = _music.generate_sfx(v.lower())
            doc["sfx_clips"].append({"path": sfx_res["path"], "start": t_ev, "gain_db": -5.0})
        elif k == "MOOD":
            lv = v.lower()
            music_cues.append({"time": t_ev, "energy": energy_map.get(lv, 0.55), "resolve": lv == "resolve"})

    brand = _load_brand()
    bgm = _music.compose_score(
        duration_sec=total_dur,
        style=music_style or brand.get("defaultMusicStyle", "felt_piano"),
        bpm=float(brand.get("defaultMusicBpm", 84)),
        key=str(brand.get("defaultMusicKey", "D")),
        cues=music_cues,
    )
    doc["music_clips"].append({"path": bgm["path"], "start": 0.0, "duration": total_dur, "gain_db": -11.5, "fade_in": 1.2, "fade_out": 2.4})
    tl_path = _tl.save_timeline(timeline_name, doc)
    return {
        "timelinePath": tl_path,
        "manifestPath": manifest.get("manifestPath", ""),
        "bgmPath": bgm["path"],
        "totalDuration": round(total_dur, 2),
        "cueCount": len(cues),
        "summary": f"Blueprint built: {len(cues)} cues, {total_dur:.1f}s, {len(doc['chapters'])} chapters",
    }


def dub_timeline(
    source_timeline: str,
    target_lang: str,
    translated_lines: List[str],
    voice_model: str = "",
    sid: int = -1,
    speed: float = 1.0,
    out_timeline: str = "",
) -> Dict[str, Any]:
    from . import rpc as _rpc

    doc = _tl.load_timeline(source_timeline)
    if not translated_lines:
        translated_lines = [c.get("text", "") for c in doc.get("captions", []) if c.get("text")]
    if not translated_lines:
        raise ValueError("Provide translated_lines or ensure source timeline has captions.")

    new_name = out_timeline or f"{doc.get('name', 'ep')}_dub_{target_lang.lower()}"
    man = _rpc("voice.synthesize_script", {
        "lines": translated_lines, "model": voice_model, "sid": int(sid), "speed": float(speed),
        "pause_sec": 0.25, "normalize_math": True, "lang": target_lang.lower(),
        "manifest_name": f"{new_name}_manifest.json", "srt_name": f"{new_name}.srt",
    }, timeout=1800.0)

    dub_doc = json.loads(json.dumps(doc))
    dub_doc["name"] = new_name
    dub_doc["voice_clips"] = []
    dub_doc["captions"] = []
    for cue in man.get("cues", []):
        dub_doc["voice_clips"].append({"path": cue["wavPath"], "start": cue["startSec"], "gain_db": 0.0, "text": cue["text"]})
        dub_doc["captions"].append({"start": cue["startSec"], "end": cue["endSec"], "text": cue["text"], "words": cue.get("words", [])})

    dub_dur = float(man.get("totalDurationSec", 0.0))
    v_clips = dub_doc.get("video_clips", [])
    if v_clips and dub_dur > 0.0:
        cur_vid_total = 0.0
        for vc in v_clips:
            sd = _tl._probe_duration(vc["path"]) if os.path.exists(vc.get("path", "")) else 0.0
            t0 = float(vc.get("trim_start", 0.0))
            t1 = float(vc.get("trim_end", 0.0)) or sd
            cur_vid_total += max(0.1, (max(t0, t1) - t0) / max(0.25, float(vc.get("speed", 1.0)))) + float(vc.get("freeze_tail_sec", 0.0))
        if dub_dur > cur_vid_total + 0.15:
            v_clips[-1]["freeze_tail_sec"] = round(float(v_clips[-1].get("freeze_tail_sec", 0.0)) + (dub_dur - cur_vid_total) + 0.35, 2)

    path = _tl.save_timeline(new_name, dub_doc)
    return {"timelinePath": path, "name": new_name, "lang": target_lang, "duration": dub_dur, "summary": f"Dubbed '{new_name}' ({target_lang}, {dub_dur:.1f}s)"}


def stable_audio_status() -> Dict[str, Any]:
    repo_ok = os.path.exists(os.path.join(SA3_DIR, "optimized", "tflite", "generate.py"))
    litert_ok = False
    try:
        import ai_edge_litert  # noqa: F401
        litert_ok = True
    except Exception:
        pass
    models_dir = os.path.join(SA3_DIR, "models")
    downloaded = sorted(os.listdir(models_dir)) if os.path.isdir(models_dir) else []
    return {"installed": repo_ok and litert_ok, "repoReady": repo_ok, "litertReady": litert_ok, "downloadedVariants": downloaded, "dir": SA3_DIR}


def install_stable_audio(variant: str = "sm-music", hf_token: str = "") -> Dict[str, Any]:
    os.makedirs(SA3_DIR, exist_ok=True)
    subprocess.run(
        ["/opt/atelier-venv/bin/pip", "install", "--target", "/user-site", "--prefer-binary", "--upgrade", "ai-edge-litert>=2.2.0", "huggingface_hub", "sentencepiece"],
        check=True,
    )
    if not os.path.exists(os.path.join(SA3_DIR, "optimized", "tflite", "generate.py")):
        tmp_clone = SA3_DIR + "_clone"
        shutil.rmtree(tmp_clone, ignore_errors=True)
        subprocess.run(["git", "clone", "--depth", "1", "https://github.com/Stability-AI/stable-audio-3.git", tmp_clone], check=True)
        for item in os.listdir(tmp_clone):
            src = os.path.join(tmp_clone, item)
            dst = os.path.join(SA3_DIR, item)
            if not os.path.exists(dst):
                shutil.move(src, dst)
        shutil.rmtree(tmp_clone, ignore_errors=True)

    from huggingface_hub import snapshot_download

    sub = "sm-sfx" if "sfx" in variant.lower() else "sm-music"
    model_dest = os.path.join(SA3_DIR, "models", sub)
    os.makedirs(model_dest, exist_ok=True)
    snapshot_download(
        repo_id="stabilityai/stable-audio-3-optimized",
        allow_patterns=[f"*{sub}*", "*tflite*", "*spiece*", "*tokenizer*", "*.json"],
        local_dir=model_dest,
        token=hf_token.strip() or None,
    )
    return {"installed": True, "variant": sub, "modelDir": model_dest, "summary": f"Stable Audio 3.0 {sub} installed"}


def generate_stable_audio(prompt: str, seconds: float = 30.0, variant: str = "sm-music", seed: int = 42, steps: int = 8, out_wav: str = "") -> Dict[str, Any]:
    st = stable_audio_status()
    if not st["installed"]:
        raise RuntimeError("Stable Audio 3.0 Small is not installed yet. Run atelier.neural_audio.install('sm-music') or tap Install in Audio Studio.")
    sub = "sm-sfx" if "sfx" in variant.lower() else "sm-music"
    model_dir = os.path.join(SA3_DIR, "models", sub)
    if not os.path.isdir(model_dir):
        raise RuntimeError(f"Model pack '{sub}' not found. Install it first via atelier.neural_audio.install('{sub}').")

    out_dir = "/outputs/audio/sfx" if sub == "sm-sfx" else "/outputs/audio/music"
    os.makedirs(out_dir, exist_ok=True)
    if not out_wav:
        slug = re.sub(r"[^a-z0-9]+", "_", prompt.lower()).strip("_")[:28] or "neural"
        out_wav = os.path.join(out_dir, f"sa3_{sub}_{slug}_{int(seconds)}s.wav")

    gen_py = os.path.join(SA3_DIR, "optimized", "tflite", "generate.py")
    cmd = [
        "/opt/atelier-venv/bin/python3", gen_py,
        "--model-dir", model_dir,
        "--prompt", prompt,
        "--duration", str(min(120.0, max(1.0, float(seconds)))),
        "--steps", str(int(steps)),
        "--seed", str(int(seed)),
        "--output", out_wav,
    ]
    proc = subprocess.run(cmd, cwd=os.path.dirname(gen_py), capture_output=True, text=True)
    if proc.returncode != 0 or not os.path.exists(out_wav):
        raise RuntimeError(f"Stable Audio 3 generation failed:\n{(proc.stdout + proc.stderr)[-2200:]}")
    return {"path": out_wav, "variant": sub, "duration": seconds, "prompt": prompt, "summary": f"Stable Audio 3 {sub}: {os.path.basename(out_wav)}"}


def git_exec(args: List[str], cwd: str = "/workspace") -> Dict[str, Any]:
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    proc = subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True, env=env)
    return {"exitCode": proc.returncode, "stdout": proc.stdout.strip(), "stderr": proc.stderr.strip(), "ok": proc.returncode == 0}


def git_init_safe() -> Dict[str, Any]:
    r = git_exec(["init", "-b", "main"])
    if not r["ok"]:
        r = git_exec(["init"])
    git_exec(["config", "user.name", "Atelier Creator"])
    git_exec(["config", "user.email", "creator@atelier.local"])
    gi = "/workspace/.gitignore"
    if not os.path.exists(gi):
        with open(gi, "w", encoding="utf-8") as f:
            f.write("__pycache__/\n*.pyc\n*.pyo\n.atelier_*\nmedia/\n*.tmp\n")
    return r
