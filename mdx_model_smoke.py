#!/usr/bin/env python3
"""Real MDX-Net LiteRT smoke test for the pinned default model."""
from __future__ import annotations

import hashlib
import os
import urllib.request

import numpy as np
from ai_edge_litert.interpreter import Interpreter

MODEL_URL = "https://huggingface.co/gyoom-sa/UVR-MDX-LiteRT/resolve/main/UVR_MDXNET_9482.fp16acc.tflite"
MODEL_SHA256 = "2a07e11db13a11ca4900a54b4a316ef67931e993a6a3d19444bccbbeb9b445ee"
EXPECTED = [1, 4, 2048, 256]


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def download(path: str) -> None:
    tmp = path + ".part"
    with urllib.request.urlopen(MODEL_URL, timeout=120) as src, open(tmp, "wb") as dst:
        while True:
            b = src.read(1024 * 1024)
            if not b:
                break
            dst.write(b)
    os.replace(tmp, path)


def main() -> int:
    model_path = os.environ.get("MDX_MODEL", ".cache/UVR_MDXNET_9482.fp16acc.tflite")
    os.makedirs(os.path.dirname(model_path) or ".", exist_ok=True)
    if not os.path.isfile(model_path) or sha256(model_path) != MODEL_SHA256:
        print("Downloading pinned MDX-Net 9482 LiteRT model...")
        download(model_path)
    assert sha256(model_path) == MODEL_SHA256, "model SHA-256 mismatch"

    interpreter = Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    assert len(inputs) == 1, inputs
    assert len(outputs) == 1, outputs
    assert list(inputs[0]["shape"]) == EXPECTED, inputs[0]["shape"]
    assert list(outputs[0]["shape"]) == EXPECTED, outputs[0]["shape"]
    assert inputs[0]["dtype"] == np.float32, inputs[0]["dtype"]
    assert outputs[0]["dtype"] == np.float32, outputs[0]["dtype"]

    rng = np.random.default_rng(9482)
    x = rng.normal(0.0, 0.05, size=EXPECTED).astype(np.float32)
    interpreter.set_tensor(inputs[0]["index"], x)
    interpreter.invoke()
    y = interpreter.get_tensor(outputs[0]["index"])
    assert y.shape == tuple(EXPECTED), y.shape
    assert np.isfinite(y).all(), "non-finite LiteRT output"
    assert np.max(np.abs(y)) < 8.0, "implausibly large MDX output"

    print("REAL MDX-NET LITERT MODEL SMOKE TEST PASSED")
    print(f"model_sha256={MODEL_SHA256}")
    print(f"input_shape={EXPECTED}")
    print(f"output_max_abs={np.max(np.abs(y)):.6f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
