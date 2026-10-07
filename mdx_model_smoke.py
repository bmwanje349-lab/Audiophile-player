#!/usr/bin/env python3
"""Real ONNX Runtime smoke test for the pinned MDX-Net 9482 model."""
from __future__ import annotations

import hashlib
import os

import numpy as np
import onnxruntime as ort

MODEL_SHA256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184"
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


def main() -> int:
    model_path = os.environ.get("MDX_MODEL", ".cache/UVR_MDXNET_9482.onnx")
    actual_hash = sha256(model_path)
    assert actual_hash == MODEL_SHA256, (
        f"model SHA-256 mismatch: {actual_hash} != {MODEL_SHA256}"
    )

    session = ort.InferenceSession(
        model_path,
        providers=["CPUExecutionProvider"],
    )
    inputs = session.get_inputs()
    outputs = session.get_outputs()

    assert len(inputs) == 1, inputs
    assert len(outputs) == 1, outputs
    assert len(inputs[0].shape) == 4, inputs[0].shape
    assert len(outputs[0].shape) == 4, outputs[0].shape
    assert inputs[0].shape[1:] == EXPECTED[1:], inputs[0].shape
    assert outputs[0].shape[1:] == EXPECTED[1:], outputs[0].shape
    assert inputs[0].type == "tensor(float)", inputs[0].type
    assert outputs[0].type == "tensor(float)", outputs[0].type

    rng = np.random.default_rng(9482)
    max_abs = 0.0

    # Exercise the exact fixed tensor contract repeatedly. This mirrors the
    # Android streaming runner, which sends one padded [1,4,2048,256] tensor
    # per MDX audio chunk instead of one tensor for the entire song.
    for chunk_index in range(4):
        x = rng.normal(
            0.0,
            0.05,
            size=EXPECTED,
        ).astype(np.float32)
        y = session.run([outputs[0].name], {inputs[0].name: x})[0]

        assert list(y.shape) == EXPECTED, y.shape
        assert y.dtype == np.float32, y.dtype
        assert np.isfinite(y).all(), "non-finite ONNX output"
        max_abs = max(max_abs, float(np.max(np.abs(y))))
        assert np.max(np.abs(y)) < 8.0, "implausibly large MDX output"

        print(
            f"real_model_chunk={chunk_index + 1} "
            f"shape={list(y.shape)} "
            f"max_abs={np.max(np.abs(y)):.6f}"
        )

    print("REAL MDX-NET ONNX CHUNK INFERENCE SMOKE TEST PASSED")
    print(f"model_sha256={MODEL_SHA256}")
    print(f"input_shape={inputs[0].shape}")
    print(f"output_shape={outputs[0].shape}")
    print("chunk_inference_calls=4")
    print(f"max_output_abs={max_abs:.6f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
