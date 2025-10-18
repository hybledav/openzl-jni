#!/usr/bin/env sh
set -e

# compute repository root (two levels up from this script: JNI/scripts -> JNI -> repo root)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "Building native in repo root: $REPO_ROOT"
mkdir -p "$REPO_ROOT/cmake_build"
cd "$REPO_ROOT/cmake_build"
cmake "$REPO_ROOT"
make -j"$(nproc)"

# copy produced shared lib to module resources (create target dir)
TARGET_DIR="$REPO_ROOT/JNI/openzl-jni/src/main/resources/lib/linux_amd64"
mkdir -p "$TARGET_DIR"
if [ -f cli/libopenzl_jni.so ]; then
	cp -f cli/libopenzl_jni.so "$TARGET_DIR/"
else
	echo "Warning: expected cli/libopenzl_jni.so not found in build directory"
fi

exit 0
