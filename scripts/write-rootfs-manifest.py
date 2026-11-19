#!/usr/bin/env python3
import json
import os

repo = os.environ["GITHUB_REPOSITORY"]
manifest = {
    "version": "1.0.0",
    "arch": "arm64-v8a",
    "sha256": os.environ["SHA256"],
    "compressedBytes": int(os.environ["COMP_BYTES"]),
    "estimatedExtractedBytes": int(os.environ["EXTRACTED_BYTES"]),
    "downloadUrl": f"https://github.com/{repo}/releases/download/runtime-v1/rootfs-arm64.tar.zst",
    "pythonVersion": os.environ["PY_VER"],
    "manimVersion": os.environ["MANIM_VER"],
    "gccVersion": os.environ["GCC_VER"],
    "texVersion": os.environ["TEX_VER"],
}
with open("rootfs-manifest.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2)
print(json.dumps(manifest, indent=2))
