#!/bin/bash
set -euo pipefail
# build-android-so.sh
# Publishes DotnetNativeInterop.NativeBridge for Android (linux-bionic-arm64) and copies the
# resulting dni.so to the Gradle jniLibs dir as libdni.so. Produces the COMPLETE SP0 native gate:
# the bionic image hand-links llama/ggml (S3) and ships libe_sqlcipher.so alongside (S2).
#
# WITH_LLAMA defaults to 1 because the .csproj unconditionally hand-links the android-arm64 llama/ggml
# static libs for linux-bionic-arm64 — so the publish (link) requires them to exist. Set WITH_LLAMA=0
# only to skip the (incremental) llama rebuild when build/android-arm64/lib/*.a are already present.
# Building llama needs cmake on PATH (or CMAKE=<path>); see native/llama-shim/build-llama.sh.
#
# NativeAOT for Android is experimental in .NET 10 (XA1040); DisableUnsupportedError suppresses it.
# ILC must link with the NDK clang/lld: Apple Clang lacks a usable -fuse-ld=lld and cannot target
# bionic, so we pass the NDK's aarch64-linux-android29-clang (API matches android minSdk=29).

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CSPROJ="${PROJECT_DIR}/core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj"
PUBLISH_DIR="${PROJECT_DIR}/build/android-artifacts/linux-bionic-arm64"
JNILIB_DIR="${PROJECT_DIR}/android/app/src/main/jniLibs/arm64-v8a"
WITH_LLAMA="${WITH_LLAMA:-1}"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK 27 dir (e.g. ~/Library/Android/sdk/ndk/27.2.12479018)}"
# NDK prebuilt host dir: darwin-x86_64 on macOS (incl. Apple Silicon via Rosetta), linux-x86_64 on CI.
NDK_BIN="$(ls -d "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1)"
CLANG="${NDK_BIN}/aarch64-linux-android29-clang"
[ -x "${CLANG}" ] || { echo "ERROR: NDK clang not found at ${CLANG}" >&2; exit 1; }
export PATH="${NDK_BIN}:${PATH}"   # so the wrapper's -fuse-ld=lld resolves ld.lld

rm -rf "${PROJECT_DIR}/build/android-artifacts"
mkdir -p "${PUBLISH_DIR}" "${JNILIB_DIR}"

if [ "${WITH_LLAMA}" = "1" ]; then
  echo "[llama] building android-arm64 static libs"
  bash "${PROJECT_DIR}/native/llama-shim/build-llama.sh" android-arm64
fi

echo "[publish] linux-bionic-arm64 (WITH_LLAMA=${WITH_LLAMA})"
echo "          CppCompilerAndLinker=${CLANG}"
dotnet publish "${CSPROJ}" \
  -c Release \
  -r linux-bionic-arm64 \
  -o "${PUBLISH_DIR}" \
  -p:DisableUnsupportedError=true \
  -p:UseAppHost=false \
  -p:CppCompilerAndLinker="${CLANG}"

if [ ! -f "${PUBLISH_DIR}/dni.so" ]; then
  echo "ERROR: dni.so not produced in ${PUBLISH_DIR}" >&2
  exit 1
fi

cp "${PUBLISH_DIR}/dni.so" "${JNILIB_DIR}/libdni.so"
echo "OK: ${JNILIB_DIR}/libdni.so ($(du -h "${JNILIB_DIR}/libdni.so" | cut -f1))"

# S2: ship libe_sqlcipher.so so the bionic image's dynamic P/Invoke ("e_sqlcipher") resolves at runtime
# from the APK lib dir. The shared lib lives ONLY inside the SQLitePCLRaw.lib.e_sqlcipher.android .aar
# (the package has no loose .so). iOS static-links the .a in the .csproj instead; Android's loader
# resolves the .so as a sibling of libdni.so, so no static hand-link is needed here.
SQLCIPHER_AAR="$(ls "${HOME}"/.nuget/packages/sqlitepclraw.lib.e_sqlcipher.android/*/lib/*/SQLitePCLRaw.lib.e_sqlcipher.android.aar 2>/dev/null | sort -V | tail -1)"
[ -n "${SQLCIPHER_AAR}" ] || { echo "ERROR: SQLitePCLRaw.lib.e_sqlcipher.android .aar not found in nuget cache" >&2; exit 1; }
unzip -o -j "${SQLCIPHER_AAR}" "jni/arm64-v8a/libe_sqlcipher.so" -d "${JNILIB_DIR}" >/dev/null
[ -f "${JNILIB_DIR}/libe_sqlcipher.so" ] || { echo "ERROR: libe_sqlcipher.so not extracted from ${SQLCIPHER_AAR}" >&2; exit 1; }
echo "OK: ${JNILIB_DIR}/libe_sqlcipher.so ($(du -h "${JNILIB_DIR}/libe_sqlcipher.so" | cut -f1))"

# ONNX Runtime (semantic search + RAG retrieval): ship libonnxruntime.so so the bionic image's dynamic
# P/Invoke ("onnxruntime") resolves at runtime. The .so lives inside the ORT android .aar (no loose .so).
ORT_AAR="$(ls "${HOME}"/.nuget/packages/microsoft.ml.onnxruntime/*/runtimes/android/native/onnxruntime.aar 2>/dev/null | sort -V | tail -1)"
[ -n "${ORT_AAR}" ] || { echo "ERROR: onnxruntime.aar not found in nuget cache (Microsoft.ML.OnnxRuntime)" >&2; exit 1; }
unzip -o -j "${ORT_AAR}" "jni/arm64-v8a/libonnxruntime.so" -d "${JNILIB_DIR}" >/dev/null
[ -f "${JNILIB_DIR}/libonnxruntime.so" ] || { echo "ERROR: libonnxruntime.so not extracted from ${ORT_AAR}" >&2; exit 1; }
echo "OK: ${JNILIB_DIR}/libonnxruntime.so ($(du -h "${JNILIB_DIR}/libonnxruntime.so" | cut -f1))"
