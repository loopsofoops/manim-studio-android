"""Atelier Studio Director SDK (Phase 1B).

Programmatic control over Atelier files, settings, rendering, introspection,
clipboard, notifications, macros, and atomic rollback via localhost bridge.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional


class AtelierError(RuntimeError):
    """Raised when an Atelier bridge RPC call fails."""


def _emit_step(status: str, method: str, detail: str = "") -> None:
    if os.environ.get("ATELIER_CONTROLLER_MODE") == "1":
        payload = json.dumps(
            {"status": status, "method": method, "detail": detail[:220]},
            ensure_ascii=False,
        )
        sys.stdout.write(f"__ATELIER_CTRL_STEP__:{payload}\n")
        sys.stdout.flush()


def rpc(method: str, params: Optional[Dict[str, Any]] = None, timeout: float = 1800.0) -> Any:
    port = os.environ.get("ATELIER_BRIDGE_PORT", "").strip()
    token = os.environ.get("ATELIER_BRIDGE_TOKEN", "").strip()
    if not port or not token:
        raise AtelierError("Atelier bridge env vars (ATELIER_BRIDGE_PORT / ATELIER_BRIDGE_TOKEN) missing.")
    url = f"http://127.0.0.1:{port}/rpc"
    body = json.dumps({"method": method, "params": params or {}}, ensure_ascii=False).encode("utf-8")
    detail = ""
    if params:
        for k in ("path", "scene", "preset", "mode", "name", "packages", "label", "format"):
            if k in params and params[k] not in (None, ""):
                detail = f"{k}={params[k]}"
                break
    _emit_step("running", method, detail)
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-Atelier-Token": token,
        },
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
        if result.get("summary"):
            ok_detail = str(result["summary"])
        elif result.get("outputPath"):
            ok_detail = os.path.basename(str(result["outputPath"]))
        elif result.get("path"):
            ok_detail = str(result["path"])
    _emit_step("ok", method, ok_detail)
    return result


class _Inspect:
    def full_snapshot(self, format: str = "markdown") -> Any:
        return rpc("inspect.snapshot", {"format": format})

    def status(self) -> Dict[str, Any]:
        return rpc("inspect.status")

    def runtime(self) -> Dict[str, Any]:
        return rpc("inspect.runtime")

    def settings(self) -> Dict[str, Any]:
        return rpc("inspect.settings")

    def file_tree(self, include_scenes: bool = True) -> Dict[str, Any]:
        return rpc("inspect.file_tree", {"include_scenes": include_scenes})

    def active_file(self, include_content: bool = True) -> Dict[str, Any]:
        return rpc("inspect.active_file", {"include_content": include_content})

    def execution(self) -> Dict[str, Any]:
        return rpc("inspect.execution")

    def console_log(self, tail: int = 200) -> str:
        return str(rpc("inspect.console_log", {"tail": tail}))

    def crash_reports(self, limit: int = 5, include_body: bool = True) -> Dict[str, Any]:
        return rpc("inspect.crash_reports", {"limit": limit, "include_body": include_body})

    def render_history(self, limit: int = 20) -> List[Dict[str, Any]]:
        return rpc("inspect.render_history", {"limit": limit})

    def sdk_schema(self) -> str:
        return str(rpc("inspect.sdk_schema"))


class _Files:
    def list(self, folder: str = "") -> List[Dict[str, Any]]:
        return rpc("files.list", {"folder": folder})

    def read(self, path: str) -> str:
        return str(rpc("files.read", {"path": path})["content"])

    def write(self, path: str, content: str, label: str = "AI Controller", language: str = "AUTO") -> Dict[str, Any]:
        return rpc("files.write", {"path": path, "content": content, "label": label, "language": language})

    def create(self, path: str, content: str = "", language: str = "AUTO") -> Dict[str, Any]:
        return rpc("files.create", {"path": path, "content": content, "language": language})

    def patch(self, path: str, old: str, new: str, count: int = 1, label: str = "AI Patch") -> Dict[str, Any]:
        return rpc("files.patch", {"path": path, "old": old, "new": new, "count": count, "label": label})

    def mkdir(self, path: str) -> Dict[str, Any]:
        return rpc("files.mkdir", {"path": path})

    def delete(self, path: str) -> Dict[str, Any]:
        return rpc("files.delete", {"path": path})

    def rename(self, old_path: str, new_path: str) -> Dict[str, Any]:
        return rpc("files.rename", {"old_path": old_path, "new_path": new_path})

    def open(self, path: str) -> Dict[str, Any]:
        return rpc("files.open", {"path": path})

    def sync_disk(self) -> Dict[str, Any]:
        return rpc("files.sync_disk")

    def revisions(self, path: str) -> List[Dict[str, Any]]:
        return rpc("files.revisions", {"path": path})

    def restore_revision(self, path: str, revision_id: int) -> Dict[str, Any]:
        return rpc("files.restore_revision", {"path": path, "revision_id": int(revision_id)})

    def snapshot(self, path: str, label: str = "AI Snapshot") -> Dict[str, Any]:
        return rpc("files.snapshot", {"path": path, "label": label})


class _Settings:
    def get(self) -> Dict[str, Any]:
        return rpc("settings.get")

    def set(self, **kwargs: Any) -> Dict[str, Any]:
        return rpc("settings.set", kwargs)


class _Manim:
    def detect_scenes(self, path: str) -> List[str]:
        return rpc("manim.detect_scenes", {"path": path})

    def render(
        self,
        path: str,
        scene: str = "",
        preset: str = "",
        output_format: str = "",
        render_mode: str = "",
        anim_range: str = "",
        disable_cache: Optional[bool] = None,
        auto_save: Optional[bool] = None,
    ) -> Dict[str, Any]:
        params: Dict[str, Any] = {
            "path": path,
            "scene": scene,
            "preset": preset,
            "output_format": output_format,
            "render_mode": render_mode,
            "anim_range": anim_range,
        }
        if disable_cache is not None:
            params["disable_cache"] = bool(disable_cache)
        if auto_save is not None:
            params["auto_save"] = bool(auto_save)
        return rpc("manim.render", params, timeout=3600.0)

    def render_draft(self, path: str, scene: str = "") -> Dict[str, Any]:
        return self.render(path=path, scene=scene, render_mode="DRAFT")

    def render_still(self, path: str, scene: str = "") -> Dict[str, Any]:
        return self.render(path=path, scene=scene, render_mode="STILL")

    def render_range(self, path: str, scene: str, start: int, end: int) -> Dict[str, Any]:
        return self.render(path=path, scene=scene, render_mode="RANGE", anim_range=f"{start},{end}")


class _Latex:
    def compile(self, path: str, auto_save: Optional[bool] = None) -> Dict[str, Any]:
        params: Dict[str, Any] = {"path": path}
        if auto_save is not None:
            params["auto_save"] = bool(auto_save)
        return rpc("latex.compile", params, timeout=600.0)


class _Python:
    def run(self, path: str) -> Dict[str, Any]:
        return rpc("python.run", {"path": path}, timeout=1200.0)

    def compile(self, path: str, native: bool = False) -> Dict[str, Any]:
        return rpc("python.compile", {"path": path, "native": bool(native)}, timeout=600.0)


class _Pip:
    def install(self, packages: Any) -> Dict[str, Any]:
        spec = " ".join(str(p) for p in packages) if isinstance(packages, (list, tuple)) else str(packages)
        return rpc("pip.install", {"packages": spec}, timeout=1200.0)

    def list(self) -> Dict[str, Any]:
        return rpc("pip.list", timeout=120.0)


class _App:
    def switch_mode(self, mode: str) -> Dict[str, Any]:
        return rpc("app.switch_mode", {"mode": mode})

    def toast(self, message: str) -> Dict[str, Any]:
        return rpc("app.toast", {"message": str(message)})

    def notify(self, title: str, message: str, copy_text: str = "") -> Dict[str, Any]:
        return rpc("app.notify", {"title": str(title), "message": str(message), "copy_text": str(copy_text)})

    def clipboard_set(self, text: str) -> Dict[str, Any]:
        return rpc("app.clipboard_set", {"text": str(text)})

    def clipboard_get(self) -> str:
        return str(rpc("app.clipboard_get").get("text", ""))

    def export_artifact(self) -> Dict[str, Any]:
        return rpc("app.export_artifact")

    def macros_list(self) -> List[Dict[str, Any]]:
        return rpc("app.macros_list")

    def macros_save(self, name: str, code: str, description: str = "") -> Dict[str, Any]:
        return rpc("app.macros_save", {"name": name, "code": code, "description": description})

    def macros_delete(self, name_or_id: str) -> Dict[str, Any]:
        return rpc("app.macros_delete", {"id": name_or_id})


class _Tx:
    def begin(self, label: str = "AI Controller") -> Dict[str, Any]:
        return rpc("tx.begin", {"label": label})

    def rollback(self, tx_id: str = "") -> Dict[str, Any]:
        return rpc("tx.rollback", {"tx_id": tx_id})


inspect = _Inspect()
files = _Files()
settings = _Settings()
manim = _Manim()
latex = _Latex()
python = _Python()
pip = _Pip()
app = _App()
tx = _Tx()

__all__ = [
    "AtelierError", "rpc", "inspect", "files", "settings",
    "manim", "latex", "python", "pip", "app", "tx",
]
