#!/opt/atelier-venv/bin/python3
import io, json, os, sys, time, traceback

os.environ.setdefault("MPLBACKEND", "Agg")
plot_dir = "/outputs/plots"
os.makedirs(plot_dir, exist_ok=True)
last_image = [None]

try:
    import matplotlib
    matplotlib.use("Agg", force=True)
    import matplotlib.pyplot as plt
    _idx = [0]

    def _repl_show(*args, **kwargs):
        for num in plt.get_fignums():
            _idx[0] += 1
            p = os.path.join(plot_dir, f"repl_{int(time.time() * 1000)}_{_idx[0]}.png")
            plt.figure(num).savefig(p, dpi=180, bbox_inches="tight")
            last_image[0] = p
        plt.close("all")

    plt.show = _repl_show
except Exception:
    pass

ns = {"__name__": "__main__"}
sys.stdout.write(json.dumps({"ready": True, "version": sys.version.split()[0]}) + "\n")
sys.stdout.flush()

for raw in sys.stdin:
    raw = raw.strip()
    if not raw:
        continue
    try:
        req = json.loads(raw)
    except Exception:
        continue
    if req.get("cmd") == "reset":
        ns.clear()
        ns["__name__"] = "__main__"
        sys.stdout.write(json.dumps({"ok": True, "stdout": "Session reset.", "stderr": "", "vars": [], "latex": "", "image": ""}) + "\n")
        sys.stdout.flush()
        continue

    snippet = req.get("code", "")
    buf_out, buf_err = io.StringIO(), io.StringIO()
    last_image[0] = None
    latex_str = ""
    ok = True
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = buf_out, buf_err
    try:
        try:
            compiled = compile(snippet, "<repl>", "eval")
            val = eval(compiled, ns)
            if val is not None:
                ns["_"] = val
                print(repr(val))
                try:
                    import sympy
                    if isinstance(val, sympy.Basic):
                        latex_str = sympy.latex(val)
                except Exception:
                    pass
        except SyntaxError:
            exec(compile(snippet, "<repl>", "exec"), ns)
    except Exception:
        ok = False
        traceback.print_exc(file=buf_err)
    finally:
        sys.stdout, sys.stderr = old_out, old_err

    var_list = []
    for k, v in sorted(ns.items()):
        if k.startswith("_") or (callable(v) and getattr(v, "__module__", "") != "__main__"):
            continue
        if isinstance(v, type(sys)):
            continue
        r = repr(v)
        if len(r) > 80:
            r = r[:77] + "..."
        var_list.append({"name": k, "type": type(v).__name__, "repr": r})

    resp = {
        "ok": ok,
        "stdout": buf_out.getvalue(),
        "stderr": buf_err.getvalue(),
        "vars": var_list[:40],
        "latex": latex_str,
        "image": last_image[0] or "",
    }
    sys.stdout.write(json.dumps(resp) + "\n")
    sys.stdout.flush()
