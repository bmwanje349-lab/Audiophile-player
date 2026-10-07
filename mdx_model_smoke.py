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


def periodic_hann(n: int) -> np.ndarray:
    return (
        0.5
        - 0.5 * np.cos(2.0 * np.pi * np.arange(n, dtype=np.float64) / n)
    ).astype(np.float32)


def uvr_chunk_spectrogram(
    left: np.ndarray,
    right: np.ndarray,
    n_fft: int = 6144,
    hop: int = 1024,
    dim_f: int = 2048,
    dim_t: int = 256,
) -> np.ndarray:
    chunk_size = hop * (dim_t - 1)
    trim = n_fft // 2
    assert left.shape == (chunk_size,)
    assert right.shape == (chunk_size,)

    window = periodic_hann(n_fft)
    planes = []
    for channel in (left, right):
        # torch.stft(center=True, pad_mode="reflect")
        padded = np.pad(channel, (trim, trim), mode="reflect")
        frames = np.stack(
            [
                padded[t * hop : t * hop + n_fft] * window
                for t in range(dim_t)
            ],
            axis=0,
        )
        spectrum = np.fft.rfft(frames, n=n_fft, axis=1).astype(np.complex64)
        planes.extend(
            [
                spectrum.real[:, :dim_f].T,
                spectrum.imag[:, :dim_f].T,
            ]
        )

    return np.stack(planes, axis=0).astype(np.float32)[None, ...]


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

    chunk_size = 1024 * 255
    sample_rate = 44_100
    t = np.arange(chunk_size, dtype=np.float32) / sample_rate

    max_abs = 0.0
    min_output_rms = float("inf")

    for chunk_index in range(4):
        phase = np.float32(chunk_index * 0.31)
        left = (
            0.20 * np.sin(2 * np.pi * 440.0 * t + phase)
            + 0.05 * np.sin(2 * np.pi * 1_760.0 * t)
        ).astype(np.float32)
        right = (
            0.17 * np.sin(2 * np.pi * 550.0 * t + phase)
            + 0.04 * np.sin(2 * np.pi * 2_200.0 * t)
        ).astype(np.float32)

        x = uvr_chunk_spectrogram(left, right)
        assert list(x.shape) == EXPECTED, x.shape

        y = session.run([outputs[0].name], {inputs[0].name: x})[0]
        assert list(y.shape) == EXPECTED, y.shape
        assert y.dtype == np.float32, y.dtype
        assert np.isfinite(y).all(), "non-finite ONNX output"

        abs_max = float(np.max(np.abs(y)))
        output_rms = float(np.sqrt(np.mean(np.square(y), dtype=np.float64)))
        max_abs = max(max_abs, abs_max)
        min_output_rms = min(min_output_rms, output_rms)

        assert abs_max < 8.0, "implausibly large MDX output"
        assert output_rms > 1.0e-5, "model produced an effectively silent stem"

        print(
            f"real_model_uvr_chunk={chunk_index + 1} "
            f"shape={list(y.shape)} "
            f"rms={output_rms:.6f} "
            f"max_abs={abs_max:.6f}"
        )

    print("REAL UVR-COMPATIBLE MDX-NET ONNX SMOKE TEST PASSED")
    print(f"model_sha256={MODEL_SHA256}")
    print(f"input_shape={inputs[0].shape}")
    print(f"output_shape={outputs[0].shape}")
    print("chunk_inference_calls=4")
    print(f"min_output_rms={min_output_rms:.6f}")
    print(f"max_output_abs={max_abs:.6f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
