# Atelier — Manim, Python & LaTeX Studio for Android

Native Android studio with offline CPython 3.12, Manim Community Edition, TeX Live (`MathTex`, `Tex`, standalone `.tex`), FFmpeg, Cython, and GCC/G++ via an unprivileged ARM64 PRoot runtime.

## Workflows
- `build-proot.yml`: Packages verified ARM64 PRoot, loader, talloc, and android-shmem binaries to GitHub Release `proot-v1`.
- `build-rootfs.yml`: Builds native ARM64 Ubuntu 24.04 + Python 3.12 + ManimCE + TeX Live + FFmpeg + GCC/Cython rootfs on `ubuntu-24.04-arm`, runs end-to-end Manim/LaTeX/Cython smoke tests, and publishes to Release `runtime-v1`.
- `build-apk.yml`: Pulls cached PRoot binaries, compiles the Jetpack Compose release APK, verifies ELF alignment, and publishes `Atelier-arm64-v8a.apk` to Release `app-latest`.
- `release-all.yml`: Runs `build-proot` and `build-rootfs` in parallel, then `build-apk`.
