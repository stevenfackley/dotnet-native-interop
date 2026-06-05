#!/bin/bash
set -euo pipefail

# build-ios-framework-device.sh
# FAST device-only variant of build-ios-framework.sh: publishes DotnetNativeInterop.NativeBridge for the
# physical iOS device RID (ios-arm64) ONLY and assembles a single-slice XCFramework. Skipping the
# simulator slice roughly halves framework build time when deploying to a real iPad.
#
# Use build-ios-framework.sh (device + simulator) when you also need to run in the iOS Simulator.

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CSPROJ_PATH="${PROJECT_DIR}/core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj"
HEADER_PATH="${PROJECT_DIR}/core/DotnetNativeInterop.NativeBridge/abi/dni.h"

BUILD_DIR="${PROJECT_DIR}/build/ios-artifacts"
DEVICE_PUBLISH="${BUILD_DIR}/ios-arm64"
FRAMEWORK_DIR="${PROJECT_DIR}/ios/Frameworks"
FRAMEWORK_NAME="dni"
XCFRAMEWORK_PATH="${FRAMEWORK_DIR}/${FRAMEWORK_NAME}.xcframework"

mkdir -p "${BUILD_DIR}" "${FRAMEWORK_DIR}"

echo "[1/3] Publishing for iOS device (arm64) only..."
dotnet publish "${CSPROJ_PATH}" -c Release -r ios-arm64 -o "${DEVICE_PUBLISH}" -p:UseAppHost=false
[ -f "${DEVICE_PUBLISH}/dni.dylib" ] || { echo "ERROR: dni.dylib not found"; exit 1; }

echo "[2/3] Fixing dylib install name..."
install_name_tool -id "@rpath/${FRAMEWORK_NAME}.framework/${FRAMEWORK_NAME}" "${DEVICE_PUBLISH}/dni.dylib"

echo "[3/3] Assembling device-only XCFramework..."
DEVICE_FRAMEWORK="${BUILD_DIR}/device/${FRAMEWORK_NAME}.framework"
rm -rf "${BUILD_DIR}/device"
mkdir -p "${DEVICE_FRAMEWORK}/Headers"
cp "${DEVICE_PUBLISH}/dni.dylib" "${DEVICE_FRAMEWORK}/${FRAMEWORK_NAME}"
cp "${HEADER_PATH}" "${DEVICE_FRAMEWORK}/Headers/"
cat > "${DEVICE_FRAMEWORK}/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleExecutable</key><string>dni</string>
  <key>CFBundleIdentifier</key><string>com.dotnetnativeinterop</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleName</key><string>DotnetNativeInterop</string>
  <key>CFBundlePackageType</key><string>FMWK</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>NSPrincipalClass</key><string></string>
</dict>
</plist>
EOF

rm -rf "${XCFRAMEWORK_PATH}"
xcodebuild -create-xcframework -framework "${DEVICE_FRAMEWORK}" -output "${XCFRAMEWORK_PATH}"
[ -d "${XCFRAMEWORK_PATH}" ] || { echo "ERROR: XCFramework creation failed"; exit 1; }

echo "=== Device-only XCFramework built: ${XCFRAMEWORK_PATH} ==="
