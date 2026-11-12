#!/usr/bin/env python3
"""Run bounded, deterministic JNI stress phases in isolated JVMs; retain logs and JSON results."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-jar", type=Path, required=True)
    native = parser.add_mutually_exclusive_group(required=True)
    native.add_argument("--native-jar", type=Path)
    native.add_argument("--native-dir", type=Path)
    parser.add_argument("--java", type=Path, default=Path(shutil.which("java") or "/usr/bin/java"))
    parser.add_argument("--asan-runtime", type=Path)
    parser.add_argument("--cxx-runtime", type=Path, help="Preload libstdc++ with ASan for HotSpot exception interception")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=310024)
    parser.add_argument("--count", type=int, default=2000)
    parser.add_argument("--modes", nargs="+", choices=["boundaries", "roundtrip", "numeric", "malformed", "trained"],
                        default=["boundaries", "roundtrip", "numeric", "malformed", "trained"])
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    if args.count <= 0 or args.timeout <= 0:
        parser.error("count and timeout must be positive")
    args.output.mkdir(parents=True, exist_ok=True)
    java = args.java.resolve()
    javac = java.with_name("javac.exe" if java.suffix.lower() == ".exe" else "javac")
    source = Path(__file__).resolve().parent / "validation/NativeStress.java"
    summary = {"java": subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT, text=True),
               "base_jar": str(args.base_jar.resolve()), "seed": args.seed, "count": args.count,
               "native": str((args.native_jar or args.native_dir).resolve()),
               "sanitized": bool(args.asan_runtime), "phases": []}
    summary["code_commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source.parents[2], text=True).strip()
    major = int(re.search(r'version "(\d+)', summary["java"]).group(1))
    with tempfile.TemporaryDirectory(prefix="openzl-validation-") as temporary:
        subprocess.run([str(javac), "--release", "11", "-cp", str(args.base_jar.resolve()),
                        "-d", temporary, str(source)], check=True)
        classpath = [temporary, str(args.base_jar.resolve())]
        if args.native_jar:
            classpath.append(str(args.native_jar.resolve()))
        env = os.environ.copy()
        if args.asan_runtime:
            libraries = [args.asan_runtime.resolve()]
            if args.cxx_runtime:
                libraries.append(args.cxx_runtime.resolve())
            env["LD_PRELOAD"] = ":".join(str(library) for library in libraries)
            # HotSpot uses SIGSEGV and custom stack mappings. LeakSanitizer cannot
            # distinguish JVM lifetime allocations; do not claim leak coverage.
            env["ASAN_OPTIONS"] = "detect_leaks=0:handle_segv=0:use_sigaltstack=0:abort_on_error=1:allocator_may_return_null=1"
            env["UBSAN_OPTIONS"] = "print_stacktrace=1:halt_on_error=1"
        for mode in args.modes:
            command = [str(java)] + (["--enable-native-access=ALL-UNNAMED"] if major >= 17 else [])
            command += ["-Xcheck:jni", "-Xms32m", "-Xmx384m",
                       "-XX:MaxDirectMemorySize=128m", "-XX:ReservedCodeCacheSize=128m"]
            if args.native_dir:
                command.append(f"-Djava.library.path={args.native_dir.resolve()}")
            command.extend(["-cp", os.pathsep.join(classpath), "NativeStress", mode, str(args.count), str(args.seed)])
            log = args.output / f"{mode}.log"
            started = time.monotonic()
            with log.open("w") as output:
                try:
                    result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, env=env, timeout=args.timeout)
                    code = result.returncode
                except subprocess.TimeoutExpired:
                    code = "timeout"
            contents = log.read_text(errors="replace")
            diagnostics = bool(re.search(r"ERROR: AddressSanitizer|runtime error:|WARNING in native method|Warning: Calling other JNI|FATAL ERROR|AddressSanitizer:DEADLYSIGNAL", contents))
            passed = code == 0 and f"VALIDATION_OK mode={mode}" in contents and not diagnostics
            phase = {"mode": mode, "passed": passed, "exit_code": code,
                     "iterations": 1 if mode == "boundaries" else args.count,
                     "seconds": round(time.monotonic() - started, 2), "log": str(log.resolve())}
            summary["phases"].append(phase)
            (args.output / "results.json").write_text(json.dumps(summary, indent=2) + "\n")
            print(json.dumps(phase), flush=True)
    raise SystemExit(0 if all(phase["passed"] for phase in summary["phases"]) else 1)


if __name__ == "__main__":
    main()
