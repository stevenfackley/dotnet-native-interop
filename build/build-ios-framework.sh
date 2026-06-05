#!/bin/bash
set -euo pipefail

# build-ios-framework.sh
# Publishes OnDeviceLlm.NativeBridge for iOS (device + simulator), applies dylib name fixes,
# and assembles a universal XCFramework.
#
# This script runs on macOS and uses Apple toolchains (install_name_tool, lipo, xcodebuild).
# Reference: Microsoft "Custom frameworks for iOS" packaging flow.

# ============================================================================
# Configuration
# ============================================================================

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CSPROJ_PATH="${PROJECT_DIR}/core/OnDeviceLlm.NativeBridge/OnDeviceLlm.NativeBridge.csproj"
HEADER_PATH="${PROJECT_DIR}/core/OnDeviceLlm.NativeBridge/abi/ondevicellm.h"

# Build artifact locations
BUILD_DIR="${PROJECT_DIR}/build/ios-artifacts"
DEVICE_PUBLISH="${BUILD_DIR}/ios-arm64"
SIMULATOR_PUBLISH="${BUILD_DIR}/iossimulator-arm64"

# Framework output
FRAMEWORK_DIR="${PROJECT_DIR}/ios/Frameworks"
FRAMEWORK_NAME="ondevicellm"
XCFRAMEWORK_PATH="${FRAMEWORK_DIR}/${FRAMEWORK_NAME}.xcframework"

# Cleanup and create build directory
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}" "${FRAMEWORK_DIR}"

echo "=== OnDeviceLlm iOS Framework Build ==="
echo "Project: ${CSPROJ_PATH}"
echo "Output: ${XCFRAMEWORK_PATH}"
echo ""

# ============================================================================
# Publish for iOS device (arm64)
# ============================================================================

echo "[1/4] Publishing for iOS device (arm64)..."
dotnet publish \
  "${CSPROJ_PATH}" \
  -c Release \
  -r ios-arm64 \
  -o "${DEVICE_PUBLISH}" \
  -p:UseAppHost=false

if [ ! -f "${DEVICE_PUBLISH}/ondevicellm.dylib" ]; then
  echo "ERROR: ondevicellm.dylib not found in ${DEVICE_PUBLISH}"
  exit 1
fi

# ============================================================================
# Publish for iOS Simulator (arm64)
# ============================================================================

echo "[2/4] Publishing for iOS Simulator (arm64)..."
dotnet publish \
  "${CSPROJ_PATH}" \
  -c Release \
  -r iossimulator-arm64 \
  -o "${SIMULATOR_PUBLISH}" \
  -p:UseAppHost=false

if [ ! -f "${SIMULATOR_PUBLISH}/ondevicellm.dylib" ]; then
  echo "ERROR: ondevicellm.dylib not found in ${SIMULATOR_PUBLISH}"
  exit 1
fi

# ============================================================================
# Apply install_name_tool fixes to dylib files
# (macOS: Rewrite embedded library ID to use @rpath for framework search)
# ============================================================================

echo "[3/4] Fixing dylib install names..."

# Device dylib: set the framework-relative load name
# install_name_tool -id @rpath/ondevicellm.framework/ondevicellm <dylib>
# This tells the iOS runtime to find the dylib at @rpath/ondevicellm.framework/ondevicellm
install_name_tool -id "@rpath/${FRAMEWORK_NAME}.framework/${FRAMEWORK_NAME}" \
  "${DEVICE_PUBLISH}/ondevicellm.dylib"

# Simulator dylib: same fix
install_name_tool -id "@rpath/${FRAMEWORK_NAME}.framework/${FRAMEWORK_NAME}" \
  "${SIMULATOR_PUBLISH}/ondevicellm.dylib"

echo "   Device dylib: install_name set to @rpath/${FRAMEWORK_NAME}.framework/${FRAMEWORK_NAME}"
echo "   Simulator dylib: install_name set to @rpath/${FRAMEWORK_NAME}.framework/${FRAMEWORK_NAME}"

# ============================================================================
# Create .framework bundles for each architecture
# (macOS/Xcode: Standard framework directory structure)
# ============================================================================

echo "[4/4] Assembling frameworks..."

# Create device framework bundle.
# The bundle dir name must equal "<FRAMEWORK_NAME>.framework" and contain a binary named
# "<FRAMEWORK_NAME>", or xcodebuild -create-xcframework can't find the executable. Device and
# simulator therefore live in separate parent dirs so both can be "ondevicellm.framework".
DEVICE_FRAMEWORK="${BUILD_DIR}/device/${FRAMEWORK_NAME}.framework"
mkdir -p "${DEVICE_FRAMEWORK}/Headers"
cp "${DEVICE_PUBLISH}/ondevicellm.dylib" "${DEVICE_FRAMEWORK}/${FRAMEWORK_NAME}"
cp "${HEADER_PATH}" "${DEVICE_FRAMEWORK}/Headers/"

# Generate minimal Info.plist for device framework
cat > "${DEVICE_FRAMEWORK}/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>en</string>
  <key>CFBundleExecutable</key>
  <string>ondevicellm</string>
  <key>CFBundleIdentifier</key>
  <string>com.ondevicellm</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>OnDeviceLlm</string>
  <key>CFBundlePackageType</key>
  <string>FMWK</string>
  <key>CFBundleShortVersionString</key>
  <string>1.0</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>NSPrincipalClass</key>
  <string></string>
</dict>
</plist>
EOF

# Create simulator framework bundle (same naming rule as device).
SIMULATOR_FRAMEWORK="${BUILD_DIR}/simulator/${FRAMEWORK_NAME}.framework"
mkdir -p "${SIMULATOR_FRAMEWORK}/Headers"
cp "${SIMULATOR_PUBLISH}/ondevicellm.dylib" "${SIMULATOR_FRAMEWORK}/${FRAMEWORK_NAME}"
cp "${HEADER_PATH}" "${SIMULATOR_FRAMEWORK}/Headers/"

# Copy Info.plist to simulator framework
cp "${DEVICE_FRAMEWORK}/Info.plist" "${SIMULATOR_FRAMEWORK}/Info.plist"

echo "   Device framework: ${DEVICE_FRAMEWORK}"
echo "   Simulator framework: ${SIMULATOR_FRAMEWORK}"

# ============================================================================
# Create XCFramework (macOS: xcodebuild combines device + simulator)
# (xcodebuild -create-xcframework combines multiple platform variants into a
#  universal .xcframework bundle that Xcode can select the right variant from)
# ============================================================================

echo "   Creating XCFramework with xcodebuild..."

# Remove existing xcframework if present
if [ -d "${XCFRAMEWORK_PATH}" ]; then
  rm -rf "${XCFRAMEWORK_PATH}"
fi

# xcodebuild -create-xcframework:
# - Takes one -framework per platform/variant
# - Creates a .xcframework bundle (directory) containing all variants
# - Xcode automatically selects the correct variant at link time
xcodebuild -create-xcframework \
  -framework "${DEVICE_FRAMEWORK}" \
  -framework "${SIMULATOR_FRAMEWORK}" \
  -output "${XCFRAMEWORK_PATH}"

if [ ! -d "${XCFRAMEWORK_PATH}" ]; then
  echo "ERROR: XCFramework creation failed"
  exit 1
fi

echo ""
echo "=== Build Complete ==="
echo "XCFramework: ${XCFRAMEWORK_PATH}"
echo ""
echo "To use in Xcode:"
echo "  1. Drag ${XCFRAMEWORK_PATH} into Xcode project"
echo "  2. Select target and add to 'Frameworks and Libraries'"
echo "  3. Link with '#import <ondevicellm/ondevicellm.h>'"
