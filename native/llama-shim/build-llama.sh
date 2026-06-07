#!/bin/bash
set -euo pipefail
# Builds dni_llama + llama.cpp as a static lib for one platform.
# Usage: build-llama.sh <host|ios-arm64|iossimulator-arm64|android-arm64>
# Pinned llama.cpp tag (same tag across iOS + Android so the shim C-API + C# ABI stay in lockstep).
# b9542: the shim's modern-API calls (llama_model_load_from_file, llama_init_from_model,
# llama_get_memory/llama_memory_clear, llama_sampler_chain_*, llama_vocab_is_eog) compile clean here.
LLAMA_TAG="${LLAMA_TAG:-b9542}"
# CMake is overridable so the android-arm64 build can use the Android SDK's bundled cmake
# (export CMAKE=$ANDROID_HOME/cmake/3.22.1/bin/cmake) when no cmake is on PATH.
CMAKE="${CMAKE:-cmake}"
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
  android-arm64)
    : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME (NDK 27) for the android-arm64 build}"
    # Cross-compile via the NDK toolchain. GGML_NATIVE=OFF strips the host -march=native that would
    # otherwise be baked in for the build machine; GGML_OPENMP=OFF avoids a libomp runtime dependency.
    # Unix Makefiles keeps the build off the (PATH-absent) ninja; /usr/bin/make is always present.
    CMAKE_ARGS+=(-G "Unix Makefiles"
                 -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
                 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
                 -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF) ;;
  host) : ;;
esac
"$CMAKE" "${CMAKE_ARGS[@]}"
"$CMAKE" --build "$OUT" --config Release
# Collect the resulting .a files (libdni_llama.a, libllama.a, libggml*.a) into $OUT/lib for the .csproj.
mkdir -p "$OUT/lib"
find "$OUT" -name "*.a" -exec cp {} "$OUT/lib/" \;
echo "Built static libs in $OUT/lib"
ls -1 "$OUT/lib"
