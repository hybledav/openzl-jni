#!/usr/bin/env python3
"""Check native source notices and finished Maven archives before release."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
from zipfile import ZipFile
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import UUID

REPO = Path(__file__).resolve().parents[1]
RESOURCES = REPO / "JNI/openzl-jni/src/main/resources"
META = RESOURCES / "META-INF"
INVENTORY = json.loads((META / "native-licenses.json").read_text())
LIBRARIES = {
    "linux_amd64": "libopenzl_jni.so",
    "macos_arm64": "libopenzl_jni.dylib",
    "windows_amd64": "openzl_jni.dll",
}
RUNTIME_REQUIRED = {
    "gcc/COPYING3",
    "gcc/COPYING.RUNTIME",
    "mingw-w64/COPYING.MinGW-w64-runtime.txt",
    "mingw-w64/COPYING.MinGW-w64.txt",
    "winpthreads/COPYING",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def license_bytes(entry, source):
    data = source.read_bytes().replace(b"\r\n", b"\n")
    if entry.get("line_count"):
        return b"\n".join(data.split(b"\n")[:entry["line_count"]]) + b"\n"
    if entry.get("inner_comment"):
        start = data.index(b"/**", data.index(b"namespace xgboost"))
        return data[start:data.index(b"*/", start) + 2] + b"\n"
    if entry["header"] or entry.get("header_block"):
        end = data.index(b"*/") + 2
        if source.name == "pdqsort-inl.h":
            end = data.index(b"*/", end) + 2
        data = data[:end] + b"\n"
    return data


def verify_sources(build):
    deps = build / "_deps"
    roots = {
        "openzl": deps / "openzl-src",
        "protobuf": deps / "protobuf-src",
        "absl": deps / "absl-src",
        "xgboost": deps / "openzl-build/tools/ml_selector/xgboost_external-prefix/src/xgboost_external",
    }
    # Respect the source directories CMake actually used for local overrides.
    cache = build / "CMakeCache.txt"
    if cache.is_file():
        for line in cache.read_text().splitlines():
            for name in ("openzl", "protobuf", "absl"):
                prefix = f"FETCHCONTENT_SOURCE_DIR_{name.upper()}:PATH="
                if line.startswith(prefix) and line[len(prefix):]:
                    roots[name] = Path(line[len(prefix):])
    for name, repository in INVENTORY["repositories"].items():
        source = roots[repository["root"]] / repository["path"]
        revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
        if revision != repository["revision"]:
            raise ValueError(f"Review the license inventory for {name} at {revision}")
    for entry in INVENTORY["components"]:
        if entry["root"] == "external":
            continue
        source = roots[entry["root"]] / entry["source"]
        if digest(license_bytes(entry, source)) != entry["sha256"]:
            raise ValueError(f"Review and update the packaged notice for {source}")
    print("License texts match the native dependency sources")


def stage_runtime(compiler_file, destination):
    compiler = Path(compiler_file.read_text().strip())
    supplier = compiler.parent.parent / "licenses"
    for required in RUNTIME_REQUIRED:
        if not (supplier / required).is_file():
            raise ValueError(f"Missing runtime notice from the actual compiler distribution: {supplier / required}")
    output = destination / "licenses"
    files = {}
    for component in ("gcc", "mingw-w64", "winpthreads"):
        for source in sorted((supplier / component).rglob("*")):
            if source.is_file():
                relative = source.relative_to(supplier).as_posix()
                target = output / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                files[relative] = digest(source.read_bytes())
    version = subprocess.check_output([str(compiler), "--version"], text=True).splitlines()[0]
    manifest = {"compiler": version, "licenses": files}
    (destination / "runtime-licenses.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Packaged runtime notices from {version}")


def verify_jar(path, classifier=None):
    required = {
        f"META-INF/{name}": (META / name).read_bytes()
        for name in ("LICENSE", "NOTICE", "THIRD-PARTY", "native-licenses.json")
    }
    for entry in INVENTORY["components"]:
        data = (META / "licenses" / entry["file"]).read_bytes()
        if digest(data) != entry["sha256"]:
            raise ValueError(f"Packaged license has changed without inventory update: {entry['file']}")
        required[f"META-INF/licenses/{entry['file']}"] = data
    with ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate archive entries in {path}")
        for name, expected in required.items():
            if archive.read(name) != expected:
                raise ValueError(f"Incorrect or missing notice {name} in {path}")
        native_files = [
            name for name in names
            if name.endswith((".so", ".dll", ".dylib"))
        ]
        expected_native = [f"lib/{classifier}/{LIBRARIES[classifier]}"] if classifier else []
        if native_files != expected_native:
            raise ValueError(f"Unexpected native files in {path}: {native_files}")
        if classifier:
            info = archive.getinfo(expected_native[0])
            if stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError(f"Native library is a symbolic link in {path}")
            signatures = {
                "linux_amd64": (b"\x7fELF",),
                "windows_amd64": (b"MZ",),
                "macos_arm64": (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca"),
            }
            with archive.open(info) as native:
                header = native.read(4)
            if not any(header.startswith(signature) for signature in signatures[classifier]):
                raise ValueError(f"Invalid native binary for {classifier} in {path}")
        if classifier == "windows_amd64":
            root = "lib/windows_amd64/"
            runtime = json.loads(archive.read(root + "runtime-licenses.json"))
            if not RUNTIME_REQUIRED.issubset(runtime["licenses"]):
                raise ValueError(f"Incomplete Windows runtime notices in {path}")
            for relative, checksum in runtime["licenses"].items():
                if digest(archive.read(root + "licenses/" + relative)) != checksum:
                    raise ValueError(f"Windows runtime notice checksum mismatch: {relative}")
    print(f"Verified licenses and contents in {path.name}")


def verify_archives(target, version, classifier):
    upstream = version.rsplit("-", 1)[0]
    if upstream != INVENTORY["openzl_version"]:
        raise ValueError("Update the native license inventory for the selected OpenZL version")
    base = target / f"openzl-jni-{version}"
    for suffix in ("", "-sources", "-javadoc"):
        verify_jar(base.with_name(base.name + suffix + ".jar"))
    classifiers = [classifier] if classifier else LIBRARIES
    for platform in classifiers:
        verify_jar(base.with_name(base.name + "-" + platform + ".jar"), platform)



def drop_deployment(deployment, version):
    deployment = str(UUID(deployment))
    credentials = f"{os.environ['MAVEN_USERNAME']}:{os.environ['MAVEN_PASSWORD']}"
    token = base64.b64encode(credentials.encode()).decode()
    headers = {"Authorization": f"Bearer {token}"}
    base = "https://central.sonatype.com/api/v1/publisher"
    try:
        with urlopen(Request(f"{base}/status?id={deployment}", headers=headers, method="POST"), timeout=30) as response:
            status = json.load(response)
    except HTTPError as error:
        if error.code != 404:
            raise
        print(f"Superseded private deployment {deployment} is already absent")
        return
    expected = f"pkg:maven/io.github.hybledav/openzl-jni@{version}"
    if status["deploymentState"] != "VALIDATED" or expected not in status.get("purls", []):
        raise ValueError("The superseded deployment must be validated and contain this exact Maven version")
    with urlopen(Request(f"{base}/deployment/{deployment}", headers=headers, method="DELETE"), timeout=30):
        pass
    print(f"Dropped superseded private deployment {deployment}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    sources = commands.add_parser("sources")
    sources.add_argument("--build-dir", type=Path, default=REPO / "cmake_build")
    runtime = commands.add_parser("windows-runtime")
    runtime.add_argument("--compiler-file", type=Path, required=True)
    runtime.add_argument("--destination", type=Path, required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--target", type=Path, default=REPO / "JNI/openzl-jni/target")
    verify.add_argument("--version", required=True)
    verify.add_argument("--classifier", choices=LIBRARIES)
    drop = commands.add_parser("drop-staged")
    drop.add_argument("--deployment", required=True)
    drop.add_argument("--version", required=True)
    prepare = commands.add_parser("prepare-publish")
    prepare.add_argument("--version", required=True)
    args = parser.parse_args()
    if args.command == "sources":
        verify_sources(args.build_dir)
    elif args.command == "windows-runtime":
        stage_runtime(args.compiler_file, args.destination)
    elif args.command == "prepare-publish":
        verify_archives(REPO / "JNI/openzl-jni/target", args.version, None)
        previous = os.environ.get("REPLACE_DEPLOYMENT")
        if previous:
            drop_deployment(previous, args.version)
    elif args.command == "drop-staged":
        drop_deployment(args.deployment, args.version)
    else:
        verify_archives(args.target, args.version, args.classifier)


if __name__ == "__main__":
    main()
