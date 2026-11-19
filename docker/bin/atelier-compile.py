#!/opt/atelier-venv/bin/python3
import ast, dis, os, py_compile, subprocess, sys, sysconfig

def main():
    args = sys.argv[1:]
    native_mode = "--native" in args
    files = [a for a in args if not a.startswith("--")]
    if not files:
        print("Usage: atelier-compile.py [--native] <file>", file=sys.stderr)
        sys.exit(2)
    path = files[0]
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()

    if native_mode or path.endswith(".pyx"):
        print(f"=== Native Cython / GCC Compilation: {os.path.basename(path)} ===")
        out_dir = "/outputs/native"
        os.makedirs(out_dir, exist_ok=True)
        base = os.path.splitext(os.path.basename(path))[0]
        c_file = os.path.join(out_dir, f"{base}.c")
        so_file = os.path.join(out_dir, f"{base}{sysconfig.get_config_var('EXT_SUFFIX') or '.so'}")
        inc = sysconfig.get_path("include")
        subprocess.run(["cython", "-3", "-o", c_file, path], check=True)
        subprocess.run(["gcc", "-O3", "-shared", "-fPIC", f"-I{inc}", c_file, "-o", so_file], check=True)
        size_kb = os.path.getsize(so_file) / 1024.0
        print(f"Compiled native extension: {so_file} ({size_kb:.1f} KB)")
        return

    print(f"=== AST & Syntax Verification: {os.path.basename(path)} ===")
    tree = ast.parse(src, filename=path)
    classes = [n.name for n in ast.walk(tree) if isinstance(n, ast.ClassDef)]
    funcs = [n.name for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)]
    imports = []
    for n in ast.walk(tree):
        if isinstance(n, ast.Import):
            imports.extend(alias.name for alias in n.names)
        elif isinstance(n, ast.ImportFrom) and n.module:
            imports.append(n.module)

    print(f"Syntax: OK ({len(src.splitlines())} lines)")
    print(f"Imports ({len(set(imports))}): {', '.join(sorted(set(imports))) or 'none'}")
    print(f"Classes ({len(classes)}): {', '.join(classes) or 'none'}")
    print(f"Functions ({len(funcs)}): {', '.join(funcs) or 'none'}")

    pyc_out = os.path.join("/outputs", os.path.basename(path) + "c")
    os.makedirs("/outputs", exist_ok=True)
    py_compile.compile(path, cfile=pyc_out, doraise=True)
    print(f"Bytecode compiled: {pyc_out} ({os.path.getsize(pyc_out)} bytes)")
    print("\n=== CPython Bytecode Disassembly ===")
    code_obj = compile(src, path, "exec")
    dis.dis(code_obj)

if __name__ == "__main__":
    main()
