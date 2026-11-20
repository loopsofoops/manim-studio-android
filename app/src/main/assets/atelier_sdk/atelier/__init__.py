"""Atelier Studio Director SDK v2.0 (Complete Phase 1-4 Control Surface)."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

from . import music_engine as _music_dsp
from . import studio_engines as _studio
from . import timeline_engine as _tl_eng


class AtelierError(RuntimeError):
    """Raised when an Atelier bridge RPC call fails."""


def _emit_step(status: str, method: str, detail: str = "") -> None:
    if os.environ.get("ATELIER_CONTROLLER_MODE") == "1":
        payload = json.dumps({"status": status, "method": method, "detail": detail[:220]}, ensure_ascii=False)
        sys.stdout.write(f"__ATELIER_CTRL_STEP__:{payload}\n")
        sys.stdout.flush()


def rpc(method: str, params: Optional[Dict[str, Any]] = None, timeout: float = 1800.0) -> Any:
    port = os.environ.get("ATELIER_BRIDGE_PORT", "").strip()
    token = os.environ.get("ATELIER_BRIDGE_TOKEN", "").strip()
    if not port or not token:
        raise AtelierError("Atelier bridge env vars missing.")
    url = f"http://127.0.0.1:{port}/rpc"
    body = json.dumps({"method": method, "params": params or {}}, ensure_ascii=False).encode("utf-8")
    detail = ""
    if params:
        for k in ("path", "scene", "preset", "mode", "name", "model", "style", "text", "packages", "label", "lang"):
            if k in params and params[k] not in (None, ""):
                detail = f"{k}={str(params[k])[:60]}"
                break
    _emit_step("running", method, detail)
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8", "X-Atelier-Token": token},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        msg = e.read().decode("utf-8", errors="replace")
        _emit_step("error", method, f"HTTP {e.code}: {msg[:180]}")
        raise AtelierError(f"Bridge HTTP {e.code} on {method}: {msg}") from e
    except Exception as e:
        _emit_step("error", method, str(e))
        raise AtelierError(f"Bridge failed on {method}: {e}") from e

    data = json.loads(raw)
    if not data.get("ok", False):
        err = data.get("error", "Unknown bridge error")
        _emit_step("error", method, err)
        raise AtelierError(f"{method}: {err}")

    result = data.get("result")
    ok_detail = detail
    if isinstance(result, dict):
        if result.get("summary"): ok_detail = str(result["summary"])
        elif result.get("outputPath"): ok_detail = os.path.basename(str(result["outputPath"]))
        elif result.get("path"): ok_detail = str(result["path"])
    _emit_step("ok", method, ok_detail)
    return result


class _Inspect:
    def full_snapshot(self, format: str = "markdown") -> Any: return rpc("inspect.snapshot", {"format": format})
    def status(self) -> Dict[str, Any]: return rpc("inspect.status")
    def telemetry(self) -> Dict[str, Any]: return rpc("app.telemetry")
    def runtime(self) -> Dict[str, Any]: return rpc("inspect.runtime")
    def settings(self) -> Dict[str, Any]: return rpc("inspect.settings")
    def file_tree(self, include_scenes: bool = True) -> Dict[str, Any]: return rpc("inspect.file_tree", {"include_scenes": include_scenes})
    def active_file(self, include_content: bool = True) -> Dict[str, Any]: return rpc("inspect.active_file", {"include_content": include_content})
    def execution(self) -> Dict[str, Any]: return rpc("inspect.execution")
    def console_log(self, tail: int = 200) -> str: return str(rpc("inspect.console_log", {"tail": tail}))
    def crash_reports(self, limit: int = 5, include_body: bool = True) -> Dict[str, Any]: return rpc("inspect.crash_reports", {"limit": limit, "include_body": include_body})
    def render_history(self, limit: int = 20) -> List[Dict[str, Any]]: return rpc("inspect.render_history", {"limit": limit})
    def sdk_schema(self) -> str: return str(rpc("inspect.sdk_schema"))


class _Files:
    def list(self, folder: str = "") -> List[Dict[str, Any]]: return rpc("files.list", {"folder": folder})
    def read(self, path: str) -> str: return str(rpc("files.read", {"path": path})["content"])
    def write(self, path: str, content: str, label: str = "AI Controller", language: str = "AUTO") -> Dict[str, Any]: return rpc("files.write", {"path": path, "content": content, "label": label, "language": language})
    def create(self, path: str, content: str = "", language: str = "AUTO") -> Dict[str, Any]: return rpc("files.create", {"path": path, "content": content, "language": language})
    def patch(self, path: str, old: str, new: str, count: int = 1, label: str = "AI Patch") -> Dict[str, Any]: return rpc("files.patch", {"path": path, "old": old, "new": new, "count": count, "label": label})
    def mkdir(self, path: str) -> Dict[str, Any]: return rpc("files.mkdir", {"path": path})
    def delete(self, path: str) -> Dict[str, Any]: return rpc("files.delete", {"path": path})
    def rename(self, old_path: str, new_path: str) -> Dict[str, Any]: return rpc("files.rename", {"old_path": old_path, "new_path": new_path})
    def open(self, path: str) -> Dict[str, Any]: return rpc("files.open", {"path": path})
    def sync_disk(self) -> Dict[str, Any]: return rpc("files.sync_disk")
    def revisions(self, path: str) -> List[Dict[str, Any]]: return rpc("files.revisions", {"path": path})
    def restore_revision(self, path: str, revision_id: int) -> Dict[str, Any]: return rpc("files.restore_revision", {"path": path, "revision_id": int(revision_id)})
    def snapshot(self, path: str, label: str = "AI Snapshot") -> Dict[str, Any]: return rpc("files.snapshot", {"path": path, "label": label})


class _Settings:
    def get(self) -> Dict[str, Any]: return rpc("settings.get")
    def set(self, **kwargs: Any) -> Dict[str, Any]: return rpc("settings.set", kwargs)


class _Manim:
    def detect_scenes(self, path: str) -> List[str]: return rpc("manim.detect_scenes", {"path": path})
    def render(self, path: str, scene: str = "", preset: str = "", output_format: str = "", render_mode: str = "", anim_range: str = "", disable_cache: Optional[bool] = None, auto_save: Optional[bool] = None) -> Dict[str, Any]:
        params: Dict[str, Any] = {"path": path, "scene": scene, "preset": preset, "output_format": output_format, "render_mode": render_mode, "anim_range": anim_range}
        if disable_cache is not None: params["disable_cache"] = bool(disable_cache)
        if auto_save is not None: params["auto_save"] = bool(auto_save)
        return rpc("manim.render", params, timeout=3600.0)
    def render_draft(self, path: str, scene: str = "") -> Dict[str, Any]: return self.render(path=path, scene=scene, render_mode="DRAFT")
    def render_still(self, path: str, scene: str = "") -> Dict[str, Any]: return self.render(path=path, scene=scene, render_mode="STILL")
    def render_range(self, path: str, scene: str, start: int, end: int) -> Dict[str, Any]: return self.render(path=path, scene=scene, render_mode="RANGE", anim_range=f"{start},{end}")
    def preview_formula(self, tex_expr: str) -> Dict[str, Any]: return _studio.preview_formula(tex_expr)


class _Voice:
    def models(self) -> List[Dict[str, Any]]: return rpc("voice.models")
    def asr_models(self) -> List[Dict[str, Any]]: return rpc("voice.asr_models")
    def install(self, model_id: str) -> Dict[str, Any]: return rpc("voice.install", {"model": model_id}, timeout=1800.0)
    def delete(self, model_id: str) -> Dict[str, Any]: return rpc("voice.delete", {"model": model_id})
    def normalize_math(self, text: str) -> str: return str(rpc("voice.normalize_math", {"text": text})["normalized"])
    def glossary_get(self) -> Dict[str, str]: return rpc("voice.glossary_get")
    def glossary_set(self, entries: Dict[str, str]) -> Dict[str, Any]: return rpc("voice.glossary_set", {"entries": entries})
    def say(self, text: str, model: str = "", sid: int = -1, speed: float = 1.0, normalize_math: bool = True, out_name: str = "", ref_wav: str = "", lang: str = "") -> Dict[str, Any]:
        return rpc("voice.say", {"text": text, "model": model, "sid": int(sid), "speed": float(speed), "normalize_math": bool(normalize_math), "out_name": out_name, "ref_wav": ref_wav, "lang": lang}, timeout=300.0)
    def clone_say(self, text: str, ref_wav: str = "", speed: float = 1.0, normalize_math: bool = True, out_name: str = "") -> Dict[str, Any]:
        return self.say(text=text, model="pocket_tts_en", speed=speed, normalize_math=normalize_math, out_name=out_name, ref_wav=ref_wav)
    def synthesize_script(self, lines: List[str], model: str = "", sid: int = -1, speed: float = 1.0, pause_sec: float = 0.28, normalize_math: bool = True, manifest_name: str = "timing_manifest.json", srt_name: str = "captions.srt", lang: str = "", ref_wav: str = "") -> Dict[str, Any]:
        return rpc("voice.synthesize_script", {"lines": list(lines), "model": model, "sid": int(sid), "speed": float(speed), "pause_sec": float(pause_sec), "normalize_math": bool(normalize_math), "manifest_name": manifest_name, "srt_name": srt_name, "lang": lang, "ref_wav": ref_wav}, timeout=1800.0)
    def align(self, wav_path: str, asr_model: str = "") -> Dict[str, Any]:
        return rpc("voice.align", {"wav_path": wav_path, "model": asr_model}, timeout=600.0)
    def denoise(self, in_wav: str, out_wav: str = "") -> Dict[str, Any]:
        if not out_wav:
            stem = os.path.splitext(os.path.basename(in_wav))[0]
            out_wav = f"/outputs/audio/{stem}_clean.wav"
        _tl_eng._run_ffmpeg(["ffmpeg", "-y", "-i", in_wav, "-af", "highpass=f=85,afftdn=nf=-25:tn=1,acompressor=threshold=-18dB:ratio=3,loudnorm=I=-16:TP=-1.5", "-ar", "24000", "-ac", "1", out_wav])
        return {"path": out_wav, "summary": f"Denoised {os.path.basename(out_wav)}"}
    def release(self) -> Dict[str, Any]: return rpc("voice.release")


class _Music:
    def styles(self) -> List[str]: return list(_music_dsp.STYLES)
    def compose(self, duration_sec: float = 45.0, style: str = "felt_piano", bpm: float = 84.0, key: str = "D", scale: str = "minor", seed: int = 42, cues: Optional[List[Dict[str, Any]]] = None, out_path: str = "") -> Dict[str, Any]:
        _emit_step("running", "music.compose", f"{style} {key} {scale} {int(duration_sec)}s")
        res = _music_dsp.compose_score(duration_sec=duration_sec, style=style, bpm=bpm, key=key, scale=scale, seed=seed, cues=cues, out_path=out_path)
        _emit_step("ok", "music.compose", res["summary"])
        return res
    def list_tracks(self) -> List[str]:
        d = "/outputs/audio/music"
        return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".wav")) if os.path.isdir(d) else []


class _Sfx:
    def presets(self) -> List[str]: return list(_music_dsp.SFX_PRESETS)
    def generate(self, preset: str = "soft_pop", duration_sec: float = 0.0, out_path: str = "") -> Dict[str, Any]:
        _emit_step("running", "sfx.generate", preset)
        res = _music_dsp.generate_sfx(preset=preset, duration_sec=duration_sec, out_path=out_path)
        _emit_step("ok", "sfx.generate", res["summary"])
        return res
    def list_sfx(self) -> List[str]:
        d = "/outputs/audio/sfx"
        return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".wav")) if os.path.isdir(d) else []


class _NeuralAudio:
    def status(self) -> Dict[str, Any]: return _studio.stable_audio_status()
    def install(self, variant: str = "sm-music", hf_token: str = "") -> Dict[str, Any]:
        _emit_step("running", "neural_audio.install", variant)
        res = _studio.install_stable_audio(variant=variant, hf_token=hf_token)
        _emit_step("ok", "neural_audio.install", res["summary"])
        return res
    def generate_music(self, prompt: str, seconds: float = 30.0, seed: int = 42, steps: int = 8, out_wav: str = "") -> Dict[str, Any]:
        _emit_step("running", "neural_audio.music", prompt[:50])
        res = _studio.generate_stable_audio(prompt=prompt, seconds=seconds, variant="sm-music", seed=seed, steps=steps, out_wav=out_wav)
        _emit_step("ok", "neural_audio.music", res["summary"])
        return res
    def generate_sfx(self, prompt: str, seconds: float = 4.0, seed: int = 42, steps: int = 8, out_wav: str = "") -> Dict[str, Any]:
        _emit_step("running", "neural_audio.sfx", prompt[:50])
        res = _studio.generate_stable_audio(prompt=prompt, seconds=seconds, variant="sm-sfx", seed=seed, steps=steps, out_wav=out_wav)
        _emit_step("ok", "neural_audio.sfx", res["summary"])
        return res


class _Audio:
    def master_mix(self, voice_clips: List[Dict[str, Any]], sfx_clips: List[Dict[str, Any]], music_clips: List[Dict[str, Any]], total_duration: float, out_wav: str, target_lufs: float = -14.0, duck_db: float = -13.0) -> str:
        return _tl_eng.master_audio_mix(voice_clips, sfx_clips, music_clips, total_duration, out_wav, target_lufs=target_lufs, duck_db=duck_db)
    def mux_video(self, video_path: str, voice_clips: Optional[List[Dict[str, Any]]] = None, sfx_clips: Optional[List[Dict[str, Any]]] = None, music_clips: Optional[List[Dict[str, Any]]] = None, out_mp4: str = "", keep_video_audio: bool = False, target_lufs: float = -14.0, duck_db: float = -13.0, publish_to_stage: bool = True) -> Dict[str, Any]:
        _emit_step("running", "audio.mux_video", os.path.basename(video_path))
        res = _tl_eng.mux_video_with_audio(video_path, voice_clips, sfx_clips, music_clips, out_mp4, keep_video_audio=keep_video_audio, target_lufs=target_lufs, duck_db=duck_db)
        if publish_to_stage and os.environ.get("ATELIER_BRIDGE_PORT"):
            try: rpc("stage.publish_video", {"guest_path": res["outputPath"], "title": os.path.basename(res["outputPath"])})
            except Exception: pass
        _emit_step("ok", "audio.mux_video", res["summary"])
        return res


class _Timeline:
    def list(self) -> List[Dict[str, Any]]: return _tl_eng.list_timelines()
    def create(self, name: str = "episode_01", preset: str = "youtube_1080p60") -> Dict[str, Any]:
        res = _tl_eng.create_timeline(name, preset)
        _emit_step("ok", "timeline.create", res["summary"])
        return res
    def load(self, name_or_path: str) -> Dict[str, Any]: return _tl_eng.load_timeline(name_or_path)
    def save(self, name_or_path: str, doc: Dict[str, Any]) -> str: return _tl_eng.save_timeline(name_or_path, doc)
    def add_video(self, name_or_path: str, video_path: str, trim_start: float = 0.0, trim_end: float = 0.0, speed: float = 1.0, freeze_tail_sec: float = 0.0, transition: str = "none", transition_dur: float = 0.45, zoom_start: float = 1.0, zoom_end: float = 1.0, pan_x: float = 0.0, pan_y: float = 0.0, label: str = "") -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["video_clips"].append({"path": video_path, "trim_start": float(trim_start), "trim_end": float(trim_end), "speed": float(speed), "freeze_tail_sec": float(freeze_tail_sec), "transition": transition, "transition_dur": float(transition_dur), "zoom_start": float(zoom_start), "zoom_end": float(zoom_end), "pan_x": float(pan_x), "pan_y": float(pan_y), "label": label or os.path.basename(video_path)})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def add_overlay(self, name_or_path: str, image_path: str, start: float = 0.0, end: float = 4.0, x: float = 0.5, y: float = 0.2, scale: float = 0.35, opacity: float = 1.0) -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["v2_overlays"].append({"path": image_path, "start": float(start), "end": float(end), "x": float(x), "y": float(y), "scale": float(scale), "opacity": float(opacity)})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def set_caption_style(self, name_or_path: str, style: str = "minimal_3b1b") -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["caption_style"] = style
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def add_voice(self, name_or_path: str, wav_path: str, start: float = 0.0, gain_db: float = 0.0, text: str = "", trim_silence: bool = False, audio_offset_sec: float = 0.0) -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["voice_clips"].append({"path": wav_path, "start": float(start), "gain_db": float(gain_db), "text": text, "trim_silence": bool(trim_silence), "audio_offset_sec": float(audio_offset_sec)})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def import_voice_manifest(self, name_or_path: str, manifest_path: str, offset_sec: float = 0.0) -> Dict[str, Any]:
        with open(manifest_path, "r", encoding="utf-8") as f: man = json.load(f)
        doc = _tl_eng.load_timeline(name_or_path)
        for cue in man.get("cues", []):
            st, en = float(cue["startSec"]) + float(offset_sec), float(cue["endSec"]) + float(offset_sec)
            doc["voice_clips"].append({"path": cue["wavPath"], "start": st, "gain_db": 0.0, "text": cue["text"]})
            doc["captions"].append({"start": st, "end": en, "text": cue["text"], "words": cue.get("words", [])})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def add_sfx(self, name_or_path: str, wav_path: str, start: float, gain_db: float = -5.0) -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["sfx_clips"].append({"path": wav_path, "start": float(start), "gain_db": float(gain_db)})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def add_music(self, name_or_path: str, wav_path: str, start: float = 0.0, duration: float = 0.0, gain_db: float = -11.0, fade_in: float = 1.2, fade_out: float = 2.2) -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["music_clips"].append({"path": wav_path, "start": float(start), "duration": float(duration), "gain_db": float(gain_db), "fade_in": float(fade_in), "fade_out": float(fade_out)})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def add_caption(self, name_or_path: str, start: float, end: float, text: str) -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["captions"].append({"start": float(start), "end": float(end), "text": str(text)})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc
    def add_chapter(self, name_or_path: str, time_sec: float, title: str) -> Dict[str, Any]:
        doc = _tl_eng.load_timeline(name_or_path)
        doc["chapters"].append({"time": float(time_sec), "title": str(title)})
        _tl_eng.save_timeline(name_or_path, doc)
        return doc


class _Export:
    def presets(self) -> List[str]: return list(_tl_eng.EXPORT_PRESETS.keys())
    def render_timeline(self, name_or_path: str, preset: str = "", out_mp4: str = "", target_lufs: float = -14.0, duck_db: float = -13.0, shorts_reframe: str = "blur_pillarbox", burn_captions: Optional[bool] = None, publish_to_stage: bool = True) -> Dict[str, Any]:
        _emit_step("running", "export.render_timeline", f"{name_or_path} ({preset or 'default'})")
        res = _tl_eng.render_timeline(name_or_path, preset=preset, out_mp4=out_mp4, target_lufs=target_lufs, duck_db=duck_db, shorts_reframe=shorts_reframe, burn_captions=burn_captions)
        if publish_to_stage and os.environ.get("ATELIER_BRIDGE_PORT"):
            try: rpc("stage.publish_video", {"guest_path": res["outputPath"], "title": os.path.basename(res["outputPath"])})
            except Exception: pass
        _emit_step("ok", "export.render_timeline", res["summary"])
        return res
    def publish_bundle(self, name_or_path: str, include_short: bool = True, master_preset: str = "youtube_1080p60", thumbnail_title: str = "", thumbnail_subtitle: str = "", thumbnail_math: str = "") -> Dict[str, Any]:
        _emit_step("running", "export.publish_bundle", name_or_path)
        res = _tl_eng.export_publish_bundle(name_or_path, include_short=include_short, master_preset=master_preset, thumbnail_title=thumbnail_title, thumbnail_subtitle=thumbnail_subtitle, thumbnail_math=thumbnail_math)
        if os.environ.get("ATELIER_BRIDGE_PORT"):
            try: rpc("stage.publish_video", {"guest_path": res["masterMp4"], "title": os.path.basename(res["masterMp4"])})
            except Exception: pass
        _emit_step("ok", "export.publish_bundle", res["summary"])
        return res
    def hardware_transcode(self, in_guest_path: str, out_guest_path: str, width: int = 1920, height: int = 1080, trim_start_ms: int = 0, trim_end_ms: int = 0, zoom: float = 1.0) -> Dict[str, Any]:
        return rpc("export.hardware_transcode", {"in_path": in_guest_path, "out_path": out_guest_path, "width": width, "height": height, "trim_start_ms": trim_start_ms, "trim_end_ms": trim_end_ms, "zoom": float(zoom)}, timeout=1800.0)


class _Thumbnail:
    def create(self, video_path: str = "", time_sec: float = 1.5, title: str = "", subtitle: str = "", math_tex: str = "", accent_hex: str = "", out_png: str = "") -> Dict[str, Any]:
        return _studio.render_thumbnail(video_path=video_path, time_sec=time_sec, title=title, subtitle=subtitle, math_tex=math_tex, accent_hex=accent_hex, out_png=out_png)


class _Pacing:
    def analyze(self, video_path: str, silence_min_sec: float = 1.1, static_min_sec: float = 6.0) -> Dict[str, Any]:
        return _studio.analyze_pacing(video_path, silence_min_sec=silence_min_sec, static_min_sec=static_min_sec)
    def auto_trim_dead_air(self, timeline_name: str) -> Dict[str, Any]:
        return _studio.auto_trim_dead_air(timeline_name)


class _Blueprint:
    def build(self, markdown_text: str, timeline_name: str = "blueprint_ep", voice_model: str = "", sid: int = -1, music_style: str = "felt_piano") -> Dict[str, Any]:
        return _studio.build_episode_blueprint(markdown_text, timeline_name=timeline_name, voice_model=voice_model, sid=sid, music_style=music_style)


class _Dub:
    def timeline(self, source_timeline: str, target_lang: str, translated_lines: List[str], voice_model: str = "", sid: int = -1, speed: float = 1.0, out_timeline: str = "") -> Dict[str, Any]:
        return _studio.dub_timeline(source_timeline, target_lang=target_lang, translated_lines=translated_lines, voice_model=voice_model, sid=sid, speed=speed, out_timeline=out_timeline)


class _Brand:
    def get(self) -> Dict[str, Any]: return rpc("brand.get")
    def set(self, **kwargs: Any) -> Dict[str, Any]: return rpc("brand.set", kwargs)
    def export_theme(self, path: str = "atelier_theme.py") -> Dict[str, Any]: return rpc("brand.export_theme", {"path": path})


class _Batch:
    def list(self) -> List[Dict[str, Any]]: return rpc("batch.list")
    def enqueue(self, kind: str, title: str, target: str, extra: str = "") -> Dict[str, Any]:
        return rpc("batch.enqueue", {"kind": kind, "title": title, "target": target, "extra": extra})
    def start(self) -> Dict[str, Any]: return rpc("batch.start")
    def pause(self) -> Dict[str, Any]: return rpc("batch.pause")
    def clear(self) -> Dict[str, Any]: return rpc("batch.clear")


class _Git:
    def init(self) -> Dict[str, Any]: return _studio.git_init_safe()
    def status(self) -> Dict[str, Any]: return _studio.git_exec(["status", "--short", "-b"])
    def diff(self) -> Dict[str, Any]: return _studio.git_exec(["diff"])
    def log(self, n: int = 12) -> Dict[str, Any]: return _studio.git_exec(["log", f"-n{int(n)}", "--oneline"])
    def commit_all(self, message: str) -> Dict[str, Any]:
        _studio.git_exec(["add", "-A"])
        return _studio.git_exec(["commit", "-m", message])
    def run(self, args: List[str]) -> Dict[str, Any]: return _studio.git_exec(list(args))


class _Latex:
    def compile(self, path: str, auto_save: Optional[bool] = None) -> Dict[str, Any]:
        params: Dict[str, Any] = {"path": path}
        if auto_save is not None: params["auto_save"] = bool(auto_save)
        return rpc("latex.compile", params, timeout=600.0)


class _Python:
    def run(self, path: str) -> Dict[str, Any]: return rpc("python.run", {"path": path}, timeout=1200.0)
    def compile(self, path: str, native: bool = False) -> Dict[str, Any]: return rpc("python.compile", {"path": path, "native": bool(native)}, timeout=600.0)


class _Pip:
    def install(self, packages: Any) -> Dict[str, Any]:
        spec = " ".join(str(p) for p in packages) if isinstance(packages, (list, tuple)) else str(packages)
        return rpc("pip.install", {"packages": spec}, timeout=1200.0)
    def list(self) -> Dict[str, Any]: return rpc("pip.list", timeout=120.0)


class _App:
    def switch_mode(self, mode: str) -> Dict[str, Any]: return rpc("app.switch_mode", {"mode": mode})
    def toast(self, message: str) -> Dict[str, Any]: return rpc("app.toast", {"message": str(message)})
    def notify(self, title: str, message: str, copy_text: str = "") -> Dict[str, Any]: return rpc("app.notify", {"title": str(title), "message": str(message), "copy_text": str(copy_text)})
    def clipboard_set(self, text: str) -> Dict[str, Any]: return rpc("app.clipboard_set", {"text": str(text)})
    def clipboard_get(self) -> str: return str(rpc("app.clipboard_get").get("text", ""))
    def export_artifact(self) -> Dict[str, Any]: return rpc("app.export_artifact")
    def macros_list(self) -> List[Dict[str, Any]]: return rpc("app.macros_list")
    def macros_save(self, name: str, code: str, description: str = "") -> Dict[str, Any]: return rpc("app.macros_save", {"name": name, "code": code, "description": description})
    def macros_delete(self, name_or_id: str) -> Dict[str, Any]: return rpc("app.macros_delete", {"id": name_or_id})


class _Tx:
    def begin(self, label: str = "AI Controller") -> Dict[str, Any]: return rpc("tx.begin", {"label": label})
    def rollback(self, tx_id: str = "") -> Dict[str, Any]: return rpc("tx.rollback", {"tx_id": tx_id})


inspect = _Inspect()
files = _Files()
settings = _Settings()
manim = _Manim()
voice = _Voice()
music = _Music()
sfx = _Sfx()
neural_audio = _NeuralAudio()
audio = _Audio()
timeline = _Timeline()
export = _Export()
thumbnail = _Thumbnail()
pacing = _Pacing()
blueprint = _Blueprint()
dub = _Dub()
brand = _Brand()
batch = _Batch()
git = _Git()
latex = _Latex()
python = _Python()
pip = _Pip()
app = _App()
tx = _Tx()

__all__ = [
    "AtelierError", "rpc", "inspect", "files", "settings", "manim",
    "voice", "music", "sfx", "neural_audio", "audio", "timeline", "export",
    "thumbnail", "pacing", "blueprint", "dub", "brand", "batch", "git",
    "latex", "python", "pip", "app", "tx",
]
