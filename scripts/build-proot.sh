#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-dist/arm64-v8a}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$OUT_DIR"

MIRRORS=(
  "https://packages-cf.termux.dev/apt/termux-main"
  "https://packages.termux.dev/apt/termux-main"
  "https://mirrors.kernel.org/termux/apt/termux-main"
)

SELECTED_MIRROR=""
for M in "${MIRRORS[@]}"; do
  echo "Probing Termux mirror: $M"
  if curl -fsSL --retry 3 --connect-timeout 15 "$M/dists/stable/main/binary-aarch64/Packages.gz" -o "$WORK_DIR/Packages.gz"; then
    SELECTED_MIRROR="$M"
    break
  fi
done

if [[ -z "$SELECTED_MIRROR" ]]; then
  echo "ERROR: Could not fetch Termux aarch64 Packages.gz from any mirror." >&2
  exit 1
fi

gzip -dc "$WORK_DIR/Packages.gz" > "$WORK_DIR/Packages"

python3 - "$WORK_DIR/Packages" "$SELECTED_MIRROR" "$WORK_DIR" <<'PYEOF'
import hashlib, sys, urllib.request

pkg_file, mirror, work_dir = sys.argv[1], sys.argv[2], sys.argv[3]
wanted = {"proot", "libtalloc", "libandroid-shmem"}
found = {}

current = {}
with open(pkg_file, "r", encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.rstrip("\n")
        if not line:
            name = current.get("Package")
            if name in wanted and name not in found:
                found[name] = dict(current)
            current = {}
            continue
        if ":" in line and not line.startswith(" "):
            k, v = line.split(":", 1)
            current[k.strip()] = v.strip()
    name = current.get("Package")
    if name in wanted and name not in found:
        found[name] = dict(current)

missing = wanted - set(found.keys())
if missing:
    raise SystemExit(f"Missing packages in Termux index: {missing}")

for name in sorted(wanted):
    info = found[name]
    rel = info["Filename"]
    expected_sha = info["SHA256"].lower()
    url = f"{mirror}/{rel}"
    dest = f"{work_dir}/{name}.deb"
    print(f"Downloading {name} ({info.get('Version', '?')}): {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "Atelier-CI/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp, open(dest, "wb") as out:
        h = hashlib.sha256()
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            out.write(chunk)
            h.update(chunk)
    actual_sha = h.hexdigest().lower()
    if actual_sha != expected_sha:
        raise SystemExit(f"SHA256 mismatch for {name}: expected {expected_sha}, got {actual_sha}")
    print(f"Verified {name}.deb SHA256={actual_sha[:16]}...")
PYEOF

mkdir -p "$WORK_DIR/extract"
for pkg in proot libtalloc libandroid-shmem; do
  dpkg-deb -x "$WORK_DIR/${pkg}.deb" "$WORK_DIR/extract"
done

PROOT_BIN="$(find "$WORK_DIR/extract" -path '*/bin/proot' | head -n 1)"
LOADER_BIN="$(find "$WORK_DIR/extract" -path '*/libexec/proot/loader' | head -n 1)"
TALLOC_LIB="$(find "$WORK_DIR/extract" -name 'libtalloc.so.2*' \( -type f -o -type l \) | head -n 1)"
SHMEM_LIB="$(find "$WORK_DIR/extract" -name 'libandroid-shmem.so*' \( -type f -o -type l \) | head -n 1)"

for item in "$PROOT_BIN" "$LOADER_BIN" "$TALLOC_LIB" "$SHMEM_LIB"; do
  if [[ -z "$item" || ! -e "$item" ]]; then
    echo "ERROR: Missing expected extracted binary: $item" >&2
    find "$WORK_DIR/extract" -maxdepth 6
    exit 1
  fi
done

cp -L "$PROOT_BIN" "$OUT_DIR/libproot.so"
cp -L "$LOADER_BIN" "$OUT_DIR/libproot-loader.so"
cp -L "$TALLOC_LIB" "$OUT_DIR/libtalloc.so"
cp -L "$SHMEM_LIB" "$OUT_DIR/libandroid-shmem.so"
chmod 0755 "$OUT_DIR"/*.so

patchelf --page-size 16384 --set-soname libtalloc.so "$OUT_DIR/libtalloc.so"
patchelf --page-size 16384 --set-rpath '$ORIGIN' "$OUT_DIR/libtalloc.so"
patchelf --page-size 16384 --set-rpath '$ORIGIN' "$OUT_DIR/libandroid-shmem.so"

NEEDED_TALLOC="$(readelf -d "$OUT_DIR/libproot.so" | sed -n 's/.*Shared library: \[\(libtalloc\.so[^]]*\)\].*/\1/p' | head -n 1 || true)"
if [[ -n "$NEEDED_TALLOC" && "$NEEDED_TALLOC" != "libtalloc.so" ]]; then
  echo "Patching DT_NEEDED $NEEDED_TALLOC -> libtalloc.so in libproot.so"
  patchelf --page-size 16384 --replace-needed "$NEEDED_TALLOC" libtalloc.so "$OUT_DIR/libproot.so"
fi
patchelf --page-size 16384 --set-rpath '$ORIGIN' "$OUT_DIR/libproot.so"

ls -lh "$OUT_DIR"
