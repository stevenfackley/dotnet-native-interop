#!/bin/bash
set -euo pipefail

# gen-ios-proto.sh
# Regenerates ios/Shared/Pb/dni_frame.pb.swift from the shared wire contract (proto/dni_frame.proto)
# using protoc + protoc-gen-swift. The generated file is committed (like the .xcodeproj) so a checkout
# builds without codegen tooling; re-run this whenever proto/dni_frame.proto changes, then commit.
#
# The SwiftProtobuf RUNTIME is an SPM dependency in ios/project.yml, pinned to the same 1.38.x line as
# the plugin below. Run on the Mac build host:
#   brew install swift-protobuf   # one-time, provides protoc-gen-swift
#   bash build/gen-ios-proto.sh

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/opt/homebrew/bin:$PATH"

command -v protoc >/dev/null || { echo "protoc missing — brew install protobuf" >&2; exit 1; }
command -v protoc-gen-swift >/dev/null || { echo "protoc-gen-swift missing — brew install swift-protobuf" >&2; exit 1; }

OUT="${PROJECT_DIR}/ios/Shared/Pb"
mkdir -p "${OUT}"
protoc --swift_out="${OUT}" --proto_path="${PROJECT_DIR}/proto" "${PROJECT_DIR}/proto/dni_frame.proto"
echo "Generated ${OUT}/dni_frame.pb.swift ($(wc -l < "${OUT}/dni_frame.pb.swift") lines)"
