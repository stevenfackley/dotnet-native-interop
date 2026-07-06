#!/usr/bin/env python
"""INT8 dynamic quantization of all-MiniLM-L6-v2 (the sentence encoder shared by the in-engine
NativeAOT path and the EVS index publisher).

This is a build-time step, not something that runs in .NET. Reproduce:

    uv venv --python 3.12 .venv
    uv pip install --python .venv onnxruntime==1.27.0 onnx==1.22.0 numpy==2.5.1
    .venv\\Scripts\\python.exe quantize.py <in.onnx> <out.int8.onnx>

Uses onnxruntime.quantization.quantize_dynamic with QInt8 weight type (dynamic quantization needs
no calibration data -- activations are quantized on the fly at inference time, only weights are
converted ahead of time). See docs/int8-minilm-quant-findings.md for the measured tradeoff.
"""

import argparse
import os
import sys

from onnxruntime.quantization import QuantType, quantize_dynamic


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model_in", help="path to FP32 model.onnx")
    parser.add_argument("model_out", help="path to write the INT8 model")
    args = parser.parse_args()

    if not os.path.isfile(args.model_in):
        print(f"ERROR: input model not found: {args.model_in}", file=sys.stderr)
        return 1

    fp32_size = os.path.getsize(args.model_in)
    print(f"quantizing {args.model_in} ({fp32_size:,} bytes) -> {args.model_out}")

    quantize_dynamic(
        model_input=args.model_in,
        model_output=args.model_out,
        weight_type=QuantType.QInt8,
    )

    int8_size = os.path.getsize(args.model_out)
    ratio = fp32_size / int8_size if int8_size else float("nan")
    print(f"done: {int8_size:,} bytes ({ratio:.2f}x smaller)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
