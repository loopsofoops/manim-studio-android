#!/opt/atelier-venv/bin/python3
import os, runpy, sys, time

os.environ.setdefault("MPLBACKEND", "Agg")
plot_dir = "/outputs/plots"
os.makedirs(plot_dir, exist_ok=True)

try:
    import matplotlib
    matplotlib.use("Agg", force=True)
    import matplotlib.pyplot as plt
    _plot_counter = [0]

    def _atelier_show(*args, **kwargs):
        figs = [plt.figure(num) for num in plt.get_fignums()]
        for fig in figs:
            _plot_counter[0] += 1
            out_path = os.path.join(plot_dir, f"plot_{int(time.time() * 1000)}_{_plot_counter[0]}.png")
            fig.savefig(out_path, dpi=180, bbox_inches="tight")
            print(f"__ATELIER_ARTIFACT__:IMAGE:{out_path}", flush=True)
        plt.close("all")

    plt.show = _atelier_show
except Exception:
    pass

if len(sys.argv) < 2:
    print("Usage: atelier-run.py <script.py>", file=sys.stderr)
    sys.exit(2)

target = sys.argv[1]
sys.argv = sys.argv[1:]
sys.path.insert(0, os.path.dirname(os.path.abspath(target)))
runpy.run_path(target, run_name="__main__")
