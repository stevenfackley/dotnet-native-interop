#!/bin/bash
set -euo pipefail

# build-android-so.sh
# Publishes OnDeviceLlm.NativeBridge for Android (linux-bionic-arm64),
# and copies the .so to the Gradle jniLibs directory with the 'lib' prefix.
#
# IMPORTANT: NativeAOT for Android is experimental in .NET 10 (warning XA1040).
# The DisableUnsupportedError flag is required to suppress the preview warning.
#
# SHARP EDGE: Microsoft.Data.Sqlite links e_sqlite3, which must be located
# alongside libondevicellm.so on the device. Comment below shows placement.

# ============================================================================
# Configuration
# ============================================================================

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CSPROJ_PATH="${PROJECT_DIR}/core/OnDeviceLlm.NativeBridge/OnDeviceLlm.NativeBridge.csproj"

# Build artifact location
BUILD_DIR="${PROJECT_DIR}/build/android-artifacts"
PUBLISH_DIR="${BUILD_DIR}/linux-bionic-arm64"

# Android Gradle jniLibs target
JNILIB_DIR="${PROJECT_DIR}/android/app/src/main/jniLibs/arm64-v8a"

# Cleanup and create directories
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}" "${JNILIB_DIR}"

echo "=== OnDeviceLlm Android .so Build ==="
echo "Project: ${CSPROJ_PATH}"
echo "RID: linux-bionic-arm64"
echo "Output: ${JNILIB_DIR}/libondevicellm.so"
echo ""

# ============================================================================
# Publish for Android (linux-bionic-arm64)
# ============================================================================

echo "[1/2] Publishing for Android (linux-bionic-arm64)..."
echo "      (NativeAOT on Android is experimental in .NET 10; XA1040 suppressed)"

dotnet publish \
  "${CSPROJ_PATH}" \
  -c Release \
  -r linux-bionic-arm64 \
  -o "${PUBLISH_DIR}" \
  -p:DisableUnsupportedError=true \
  -p:UseAppHost=false

if [ ! -f "${PUBLISH_DIR}/ondevicellm.so" ]; then
  echo "ERROR: ondevicellm.so not found in ${PUBLISH_DIR}"
  exit 1
fi

echo "   Generated: ${PUBLISH_DIR}/ondevicellm.so"

# ============================================================================
# Copy to Android jniLibs with 'lib' prefix
# ============================================================================

echo "[2/2] Installing to Android jniLibs..."

# Android JNI convention: System.loadLibrary("ondevicellm") -> libondevicellm.so
# The 'lib' prefix and .so extension are added automatically by the loader.
# Copy the raw .so to jniLibs/arm64-v8a/libondevicellm.so
cp "${PUBLISH_DIR}/ondevicellm.so" "${JNILIB_DIR}/libondevicellm.so"

echo "   Installed: ${JNILIB_DIR}/libondevicellm.so"
echo ""

# ============================================================================
# SHARP EDGE: Microsoft.Data.Sqlite native dependency
# ============================================================================

echo "=== IMPORTANT: SQLite Native Dependency ==="
echo ""
echo "Microsoft.Data.Sqlite links e_sqlite3.so, which must be present on the device."
echo "Add the e_sqlite3.so from the Microsoft.Data.Sqlite package:"
echo ""
echo "  1. Locate e_sqlite3.so in your .NET 10 SDK or NuGet cache"
echo "     (typically ~/.nuget/packages/microsoft.data.sqlite.core/10.0.0/runtimes/linux-bionic-arm64/native/)"
echo ""
echo "  2. Copy e_sqlite3.so to the same jniLibs directory:"
echo "     ${JNILIB_DIR}/libe_sqlite3.so"
echo ""
echo "  3. The Gradle build will bundle both libraries into the APK."
echo ""

# Optional: if you want to auto-locate e_sqlite3, uncomment below
# However, this assumes a standard .NET SDK installation path.
#
# SQLITE_PATH="${HOME}/.nuget/packages/microsoft.data.sqlite.core/10.0.0/runtimes/linux-bionic-arm64/native/e_sqlite3.so"
# if [ -f "${SQLITE_PATH}" ]; then
#   cp "${SQLITE_PATH}" "${JNILIB_DIR}/libe_sqlite3.so"
#   echo "Auto-copied: ${JNILIB_DIR}/libe_sqlite3.so"
# else
#   echo "WARNING: e_sqlite3.so not found at expected path: ${SQLITE_PATH}"
#   echo "         You will need to manually copy it to ${JNILIB_DIR}/"
# fi

echo ""
echo "=== Build Complete ==="
echo "Android .so: ${JNILIB_DIR}/libondevicellm.so"
echo ""
