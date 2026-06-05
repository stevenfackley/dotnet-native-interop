#!/bin/bash
set -euo pipefail

# capture-screenshots.sh
# Captures one upright-landscape screenshot of every app screen into ./screenshots/.
# macOS only. Runs the ScreenshotTests XCUITest on an iPad Simulator, then exports, names (via the
# .xcresult manifest), and rotates the captures (XCUITest stores landscape frames sideways, so each is
# rotated 270° to upright).
#
#   bash build/capture-screenshots.sh                 # default iPad Pro 13-inch sim
#   SIM_NAME="iPad Air 11-inch (M4)" bash build/capture-screenshots.sh

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIM_NAME="${SIM_NAME:-iPad Pro 13-inch (M5)}"
RESULT="${TMPDIR:-/tmp}/dni-shots.xcresult"
OUT="${PROJECT_DIR}/screenshots"

cd "${PROJECT_DIR}"
echo "[shots] building framework (device + simulator slices)"
bash build/build-ios-framework.sh

cd ios
echo "[shots] generating Xcode project"
xcodegen generate

echo "[shots] running screenshot UITest on '${SIM_NAME}'"
rm -rf "${RESULT}"
# The UITest never asserts, so a non-zero exit only means some navigation was skipped — still export.
xcodebuild test \
  -project DotnetNativeInteropApp.xcodeproj \
  -scheme DotnetNativeInteropUnified \
  -destination "platform=iOS Simulator,name=${SIM_NAME}" \
  -resultBundlePath "${RESULT}" \
  CODE_SIGNING_ALLOWED=NO || true

echo "[shots] exporting, naming, and rotating attachments → ${OUT}"
TMP="$(mktemp -d)"
xcrun xcresulttool export attachments --path "${RESULT}" --output-path "${TMP}"
mkdir -p "${OUT}"
python3 - "${TMP}" "${OUT}" <<'PY'
import json, os, shutil, subprocess, sys
src, out = sys.argv[1], sys.argv[2]

def strip_exif_chunk(path):
    # `sips -r` rotates the pixels upright but writes a PNG eXIf orientation tag that EXIF-honoring
    # viewers (Preview, GitHub) re-apply, double-rotating the image. Drop the eXIf chunk; IDAT is untouched.
    data = open(path, "rb").read()
    fixed = bytearray(data[:8])  # PNG signature
    i = 8
    while i < len(data):
        length = int.from_bytes(data[i:i + 4], "big")
        ctype = data[i + 4:i + 8]
        if ctype != b"eXIf":
            fixed += data[i:i + 12 + length]  # length(4) + type(4) + data + crc(4)
        i += 12 + length
    open(path, "wb").write(fixed)

manifest = json.load(open(os.path.join(src, "manifest.json")))
for entry in manifest:
    for a in entry.get("attachments", []):
        name = a["suggestedHumanReadableName"].split("_0_")[0] + ".png"
        dst = os.path.join(out, name)
        shutil.copy(os.path.join(src, a["exportedFileName"]), dst)
        # XCUITest stores landscape frames in the device's native (portrait) buffer; rotate to upright.
        subprocess.run(["sips", "-r", "270", dst],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        strip_exif_chunk(dst)
PY

echo "=== screenshots written to ${OUT} ==="
ls -1 "${OUT}"
