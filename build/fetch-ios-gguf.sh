#!/bin/bash
set -euo pipefail

# fetch-ios-gguf.sh
# Fetches the on-device RAG model into the folder the iOS app bundles (the engine's Ai/assets/, carried
# into the .app by ios/project.yml's folder reference) — PINNING iOS to the SAME upstream file Android
# downloads at first run, so both platforms generate byte-identically.
#
# Provenance is pinned to ONE upstream file. GGUF_URL below MUST stay identical to
# android/app/build.gradle.kts's GGUF_URL (bartowski Llama-3.2-1B-Instruct-GGUF, Q4_K_M). The engine
# resolves the model by EXACT name (EngineHost.BuildRagModel → "Llama-3.2-1B-Instruct-Q4_K_M.gguf"), and
# that name is also this upstream file's name, so no rename is needed.
#
# The *.gguf is Git-LFS-tracked but the ~0.77 GB bytes are NOT committed — a fresh checkout without the
# model degrades to the always-available extractive generator (the graceful fallback), so this is a
# build-host convenience step, not a hard build dependency. Idempotent: skips if the file is already there.
#
# Run once on the Mac build host BEFORE the app build (the framework build does not bundle the model):
#   bash build/fetch-ios-gguf.sh

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# KEEP IN SYNC with android/app/build.gradle.kts → GGUF_URL (byte-identical-generation parity).
GGUF_URL="https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
EXPECTED_BYTES=807694464   # bartowski Q4_K_M size; a mismatch means the upstream file moved — investigate, don't ship.

DEST_DIR="${PROJECT_DIR}/core/DotnetNativeInterop.Engine/Ai/assets"
DEST="${DEST_DIR}/Llama-3.2-1B-Instruct-Q4_K_M.gguf"

mkdir -p "${DEST_DIR}"

if [ -f "${DEST}" ]; then
  actual=$(wc -c < "${DEST}")
  if [ "${actual}" -eq "${EXPECTED_BYTES}" ]; then
    echo "GGUF already present and size-verified: ${DEST} (${actual} bytes)"
    exit 0
  fi
  echo "GGUF present but wrong size (${actual} != ${EXPECTED_BYTES}) — re-fetching."
  rm -f "${DEST}"
fi

echo "Fetching Llama-3.2-1B-Instruct Q4_K_M (~0.77 GB) from bartowski → ${DEST}"
curl -fSL --retry 3 --retry-delay 2 -o "${DEST}.partial" "${GGUF_URL}"

actual=$(wc -c < "${DEST}.partial")
if [ "${actual}" -ne "${EXPECTED_BYTES}" ]; then
  rm -f "${DEST}.partial"
  echo "ERROR: downloaded ${actual} bytes, expected ${EXPECTED_BYTES}. Upstream changed — not shipping." >&2
  exit 1
fi

mv "${DEST}.partial" "${DEST}"
echo "Done: ${DEST} (${actual} bytes)"
