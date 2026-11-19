#!/opt/atelier-venv/bin/python3
import json, os, subprocess, sys

if len(sys.argv) < 2:
    sys.exit(1)
path = sys.argv[1]
cmd = [
    "ffprobe", "-v", "quiet", "-print_format", "json",
    "-show_format", "-show_streams", path
]
out = subprocess.check_output(cmd, text=True)
data = json.loads(out)
video = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), {})
rate = video.get("avg_frame_rate") or video.get("r_frame_rate") or "30/1"
try:
    num, den = rate.split("/")
    fps = float(num) / float(den) if float(den) != 0 else 30.0
except Exception:
    fps = 30.0
dur_s = float(data.get("format", {}).get("duration") or video.get("duration") or 0.0)
print(json.dumps({
    "width": int(video.get("width") or 0),
    "height": int(video.get("height") or 0),
    "fps": round(fps, 3),
    "durationMs": int(dur_s * 1000),
    "codec": str(video.get("codec_name") or "").upper(),
    "sizeBytes": os.path.getsize(path) if os.path.exists(path) else 0
}))
