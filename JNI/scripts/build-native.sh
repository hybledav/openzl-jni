#!/usr/bin/env sh
set -e

# compute repository root (two levels up from this script: JNI/scripts -> JNI -> repo root)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BUILD_TYPE="${BUILD_TYPE:-Release}"
ENABLE_FRAME_POINTERS="${ENABLE_FRAME_POINTERS:-ON}"

echo "Building native in repo root: $REPO_ROOT (CMAKE_BUILD_TYPE=$BUILD_TYPE, ENABLE_FRAME_POINTERS=$ENABLE_FRAME_POINTERS)"
mkdir -p "$REPO_ROOT/cmake_build"
cd "$REPO_ROOT/cmake_build"
cmake "$REPO_ROOT" \
  -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
  -DENABLE_FRAME_POINTERS="$ENABLE_FRAME_POINTERS" \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
make -j"$(nproc)"

# copy produced shared lib to module resources (create target dir)
SRC_SO="cli/libopenzl_jni.so"
RES_DIR="$REPO_ROOT/JNI/openzl-jni/src/main/resources/lib/linux_amd64"
CLS_DIR="$REPO_ROOT/JNI/openzl-jni/target/classes/lib/linux_amd64"

mkdir -p "$RES_DIR" "$CLS_DIR"
if [ -f "$SRC_SO" ]; then
	cp -f "$SRC_SO" "$RES_DIR/"
	cp -f "$SRC_SO" "$CLS_DIR/"
	echo "Copied $SRC_SO to:\n  $RES_DIR\n  $CLS_DIR"
else
	echo "Warning: expected $SRC_SO not found in build directory"
fi

exit 0
