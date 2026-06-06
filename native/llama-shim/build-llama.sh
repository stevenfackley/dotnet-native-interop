#!/bin/bash
set -euo pipefail
# Builds dni_llama + llama.cpp as a static lib for one platform.
# Usage: build-llama.sh <host|ios-arm64|iossimulator-arm64>
# Pinned llama.cpp tag:
LLAMA_TAG="${LLAMA_TAG:-b4585}"   # <-- set to the tag confirmed in Task 3 Step 1
# Confirm/bump this tag and the C-API names (Task 3 Step 1) before the gate build.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/.llama-src"
PLATFORM="${1:-host}"
OUT="$HERE/build/$PLATFORM"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$SRC"
fi

CMAKE_ARGS=(-S "$HERE" -B "$OUT" -DLLAMA_CPP_DIR="$SRC" -DCMAKE_BUILD_TYPE=Release
            -DGGML_METAL=OFF -DGGML_BLAS=OFF -DGGML_ACCELERATE=OFF
            -DLLAMA_CURL=OFF -DLLAMA_BUILD_TOOLS=OFF)
case "$PLATFORM" in
  ios-arm64)
    CMAKE_ARGS+=(-G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64
                 -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DGGML_METAL=OFF) ;;
  iossimulator-arm64)
    CMAKE_ARGS+=(-G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64
                 -DCMAKE_OSX_SYSROOT=iphonesimulator -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DGGML_METAL=OFF) ;;
  host) : ;;
esac
cmake "${CMAKE_ARGS[@]}"
cmake --build "$OUT" --config Release
# Collect the resulting .a files (libdni_llama.a, libllama.a, libggml*.a) into $OUT/lib for the .csproj.
mkdir -p "$OUT/lib"
find "$OUT" -name "*.a" -exec cp {} "$OUT/lib/" \;
echo "Built static libs in $OUT/lib"
ls -1 "$OUT/lib"
