#!/usr/bin/env python3
import argparse
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

ALLOWED_SYSTEM_LIBS = {
    "libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so", "libstdc++.so",
    "libandroid.so", "libOpenSLES.so", "libaaudio.so", "libjnigraphics.so", "libc++_shared.so"
}

def inspect_elf(path: str, bundled_names: set) -> list:
    errors = []
    with open(path, "rb") as f:
        hdr = f.read(64)
    if len(hdr) < 64 or hdr[:4] != b"\x7fELF":
        return [f"{path}: Not an ELF binary"]
    ei_class = hdr[4]
    e_machine = struct.unpack_from("<H", hdr, 18)[0]
    e_phoff = struct.unpack_from("<Q", hdr, 32)[0]
    e_phentsize = struct.unpack_from("<H", hdr, 54)[0]
    e_phnum = struct.unpack_from("<H", hdr, 56)[0]

    if ei_class != 2 or e_machine != 183:
        errors.append(f"{path}: Expected ELF64 AArch64, got class={ei_class}, machine={e_machine}")

    aligns = []
    with open(path, "rb") as f:
        for i in range(e_phnum):
            f.seek(e_phoff + i * e_phentsize)
            ph = f.read(56)
            p_type = struct.unpack_from("<I", ph, 0)[0]
            p_align = struct.unpack_from("<Q", ph, 48)[0]
            if p_type == 1:
                aligns.append(p_align)

    name = os.path.basename(path)
    print(f"  [ELF] {name}: AArch64 PT_LOAD aligns={[hex(a) for a in aligns]}")

    proc = subprocess.run(["readelf", "-d", path], capture_output=True, text=True)
    needed = []
    for line in proc.stdout.splitlines():
        if "(NEEDED)" in line and "[" in line and "]" in line:
            needed.append(line.split("[", 1)[1].split("]", 1)[0])
    print(f"        DT_NEEDED={needed}")
    for lib in needed:
        if lib not in ALLOWED_SYSTEM_LIBS and lib not in bundled_names:
            errors.append(f"{name}: Unresolved DT_NEEDED library '{lib}'")
    return errors

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir")
    ap.add_argument("--apk")
    args = ap.parse_args()

    errors = []
    if args.dir:
        so_files = sorted(os.path.join(args.dir, fn) for fn in os.listdir(args.dir) if fn.endswith(".so"))
        bundled = {os.path.basename(p) for p in so_files}
        for p in so_files:
            errors.extend(inspect_elf(p, bundled))
    elif args.apk:
        with tempfile.TemporaryDirectory() as td:
            with zipfile.ZipFile(args.apk, "r") as zf:
                arm_entries = [n for n in zf.namelist() if n.startswith("lib/arm64-v8a/") and n.endswith(".so")]
                if not arm_entries:
                    raise SystemExit("APK contains no lib/arm64-v8a/*.so files!")
                extracted = []
                for entry in sorted(arm_entries):
                    out = os.path.join(td, os.path.basename(entry))
                    with zf.open(entry) as src, open(out, "wb") as dst:
                        dst.write(src.read())
                    extracted.append(out)
                bundled = {os.path.basename(p) for p in extracted}
                required = {"libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"}
                missing = required - bundled
                if missing:
                    errors.append(f"APK missing required PRoot binaries: {missing}")
                for p in extracted:
                    errors.extend(inspect_elf(p, bundled))
    else:
        raise SystemExit("Pass --dir or --apk")

    if errors:
        for e in errors:
            print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
    print("ELF verification passed.")

if __name__ == "__main__":
    main()
