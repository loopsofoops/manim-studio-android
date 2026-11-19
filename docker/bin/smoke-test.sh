#!/usr/bin/env bash
set -euo pipefail

echo "[Smoke 1/4] Verifying Python scientific & Manim imports..."
python3 -c "import manim, numpy, scipy, sympy, matplotlib, PIL, networkx, Cython; print('PY_STACK_OK', manim.__version__)"

echo "[Smoke 2/4] Rendering Manim MathTex animation to MP4..."
cat <<'EOF' > /tmp/smoke_scene.py
from manim import *

class SmokeScene(Scene):
    def construct(self):
        eq = MathTex(r"e^{i\pi} + 1 = 0")
        self.play(Write(eq), run_time=0.5)
        self.wait(0.2)
EOF
manim render -ql --disable_caching --media_dir /tmp/smoke_media /tmp/smoke_scene.py SmokeScene
MP4_OUT="$(find /tmp/smoke_media -name 'SmokeScene.mp4' | head -n 1)"
test -s "$MP4_OUT"
/usr/local/bin/atelier-probe.py "$MP4_OUT"

echo "[Smoke 3/4] Compiling standalone LaTeX PDF..."
cat <<'EOF' > /tmp/smoke.tex
\documentclass{article}
\usepackage{amsmath}
\begin{document}
\[\int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}\]
\end{document}
EOF
pdflatex -interaction=nonstopmode -output-directory=/tmp /tmp/smoke.tex
test -s /tmp/smoke.pdf

echo "[Smoke 4/4] Compiling native ARM64 Cython + GCC extension..."
cat <<'EOF' > /tmp/smoke_cy.pyx
def fast_sum(int n):
    cdef long s = 0
    cdef int i
    for i in range(n):
        s += i
    return s
EOF
/usr/local/bin/atelier-compile.py --native /tmp/smoke_cy.pyx
PYTHONPATH=/outputs/native python3 -c "import smoke_cy; assert smoke_cy.fast_sum(100) == 4950; print('CYTHON_OK')"

rm -rf /tmp/smoke* /outputs/native /root/.cache/pip /root/.cache/manim
echo "ALL 4 SMOKE TESTS PASSED."
