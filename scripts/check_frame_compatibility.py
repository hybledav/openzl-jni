#!/usr/bin/env python3
"""Exercise OpenZL 0.2.0/0.3.0 frame exchange using isolated JVMs and release JARs."""

import argparse
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for release in ("previous", "current"):
        parser.add_argument(f"--{release}-base", type=Path, required=True)
        parser.add_argument(f"--{release}-native", type=Path, required=True)
    args = vars(parser.parse_args())
    for jar in args.values():
        if not jar.is_file():
            parser.error(f"Missing release JAR: {jar}")
    probe = Path(__file__).resolve().parent / "compatibility/FrameCompatibility.java"
    with TemporaryDirectory(prefix="openzl-frames-") as temporary:
        def run(release, operation, directory):
            classpath = os.pathsep.join(str(args[f"{release}_{kind}"].resolve()) for kind in ("base", "native"))
            subprocess.run(["java", "-cp", classpath, str(probe), operation,
                            str(Path(temporary) / directory), release], check=True, timeout=120)

        run("previous", "write", "old")
        run("current", "read", "old")
        run("current", "write", "new")
        run("previous", "read", "new")
    print("Both releases exchanged format-24 frames; the previous release rejected format 27")


if __name__ == "__main__":
    main()
