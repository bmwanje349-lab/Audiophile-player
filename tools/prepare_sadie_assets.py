#!/usr/bin/env python3
"""Build compact Android runtime assets from the official SADIE II D1 SOFA archives.

The user-supplied archives match the Zenodo v2-1 files by MD5. Only the 48 kHz
measurements are selected. All 8,802 HRIR measurement positions are preserved;
the FIR coefficients are quantized to int16. BRIR room tails remain float32.

Dataset: SADIE II, University of York AudioLab, subject D1 (KU100).
https://doi.org/10.5281/zenodo.10886409
Licensed under Apache License 2.0; preserve the attribution notice emitted below.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import struct
import urllib.request
import zipfile
from pathlib import Path

import h5py
import numpy as np

HRIR_URL = "https://zenodo.org/records/10886409/files/D1_HRIR_SOFA.zip?download=1"
BRIR_URL = "https://zenodo.org/records/10886409/files/D1_BRIR_SOFA.zip?download=1"
HRIR_MD5 = "4850c1eb8e63e2d4f605edcdb4d5c883"
BRIR_MD5 = "7725508069e99fb8f519d452c156fdb3"
HEADER = struct.Struct("<8sIIIII")
SAMPLE_RATE = 48000


def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def read_archive(path: str | None, url: str, expected_md5: str) -> bytes:
    if path:
        data = Path(path).read_bytes()
    else:
        request = urllib.request.Request(
            url, headers={"User-Agent": "AudiophilePlayer-SADIE-asset-builder/1.0"}
        )
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read()
    actual = md5(data)
    if actual != expected_md5:
        raise RuntimeError(f"Archive checksum mismatch: expected {expected_md5}, got {actual}")
    return data


def sofa_bytes(archive_bytes: bytes, expected_suffix: str) -> bytes:
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        candidates = [name for name in archive.namelist() if name.endswith(expected_suffix)]
        if len(candidates) != 1:
            raise RuntimeError(f"Expected exactly one {expected_suffix}, found {candidates}")
        return archive.read(candidates[0])


def f32le(values: np.ndarray) -> bytes:
    return np.asarray(values, dtype="<f4", order="C").tobytes(order="C")


def generate(hrir_zip: str | None, brir_zip: str | None, outdir: str) -> None:
    output_dir = Path(outdir)
    output_dir.mkdir(parents=True, exist_ok=True)
    hrir_archive = read_archive(hrir_zip, HRIR_URL, HRIR_MD5)
    brir_archive = read_archive(brir_zip, BRIR_URL, BRIR_MD5)

    # Full D1 HRIR field: 8,802 measurements, two ears, 256 taps at 48 kHz.
    hrir_sofa = sofa_bytes(hrir_archive, "D1_48K_24bit_256tap_FIR_SOFA.sofa")
    with h5py.File(io.BytesIO(hrir_sofa), "r") as sofa:
        rate = int(round(float(np.asarray(sofa["Data.SamplingRate"]).reshape(-1)[0])))
        source = np.asarray(sofa["SourcePosition"], dtype=np.float32)
        impulse = np.asarray(sofa["Data.IR"], dtype=np.float32)
    if rate != SAMPLE_RATE or source.shape != (8802, 3) or impulse.shape != (8802, 2, 256):
        raise RuntimeError(f"Unexpected HRIR shape/rate: source={source.shape}, IR={impulse.shape}, rate={rate}")
    if not np.isfinite(impulse).all() or np.max(np.abs(impulse)) > 1.001:
        raise RuntimeError("HRIR contains non-finite or out-of-range coefficients")
    positions = source[:, :2]
    hrir_i16 = np.rint(np.clip(impulse, -1.0, 1.0) * 32767.0).astype("<i2")
    hrir_header = HEADER.pack(b"D1HRIR1\0", 1, SAMPLE_RATE, source.shape[0], 2, 256)
    hrir_path = output_dir / "hrir_d1_48k_256.bin"
    hrir_path.write_bytes(hrir_header + f32le(positions) + hrir_i16.tobytes(order="C"))

    # Measured D1 BRIRs at +45/-45 degrees, elevation 0. The first 256 taps
    # (5.33 ms) are removed so this convolution adds reflections without counting
    # the direct path twice alongside the separate HRIR spatializer.
    brir_sofa = sofa_bytes(brir_archive, "D1_48K_24bit_0.3s_FIR_SOFA.sofa")
    with h5py.File(io.BytesIO(brir_sofa), "r") as sofa:
        rate = int(round(float(np.asarray(sofa["Data.SamplingRate"]).reshape(-1)[0])))
        source = np.asarray(sofa["SourcePosition"], dtype=np.float32)
        impulse = np.asarray(sofa["Data.IR"], dtype=np.float32)
    if rate != SAMPLE_RATE or source.shape != (50, 3) or impulse.shape != (50, 2, 16384):
        raise RuntimeError(f"Unexpected BRIR shape/rate: source={source.shape}, IR={impulse.shape}, rate={rate}")
    selected = []
    for azimuth in (45.0, 315.0):
        matches = np.where(np.isclose(source[:, 0], azimuth) & np.isclose(source[:, 1], 0.0))[0]
        if len(matches) != 1:
            raise RuntimeError(f"Cannot find unique BRIR measurement at azimuth {azimuth}, elevation 0")
        selected.append(int(matches[0]))
    tail = np.asarray(impulse[selected], dtype=np.float32)
    tail[:, :, :256] = 0.0
    if not np.isfinite(tail).all():
        raise RuntimeError("BRIR tail contains non-finite coefficients")
    brir_header = HEADER.pack(b"D1BRIR1\0", 1, SAMPLE_RATE, 2, 2, 16384)
    brir_metadata = f32le(source[selected, :2])
    brir_path = output_dir / "brir_d1_48k_16384.bin"
    brir_path.write_bytes(brir_header + brir_metadata + f32le(tail))

    attribution = output_dir / "SADIE_II_ATTRIBUTION.txt"
    attribution.write_text(
        "SADIE II measured spatial-audio data notice\n"
        "Original dataset: SADIE II Database, AudioLab, Department of Electronic Engineering, University of York, UK.\n"
        "Subject D1 (KU100). Source record: https://doi.org/10.5281/zenodo.10886409\n"
        "Academic reference: https://doi.org/10.3390/app8112029\n"
        "Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0\n"
        "The original dataset must be referenced whenever used in original or modified form.\n"
        "Modified subset in this app: all 8,802 48 kHz HRIR directions are converted to signed 16-bit FIR coefficients; D1 48 kHz BRIR measurements at +45 and -45 degrees are retained as float32, with the first 256 taps zeroed to isolate room reflections alongside the separate HRIR direct path.\n",
        encoding="utf-8",
    )
    print(f"HRIR: {hrir_path} ({hrir_path.stat().st_size:,} bytes), 8,802 directions")
    print(f"BRIR: {brir_path} ({brir_path.stat().st_size:,} bytes), measured +/-45-degree room tails")
    print("Verified archive MD5s:", HRIR_MD5, BRIR_MD5)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--hrir-zip")
    parser.add_argument("--brir-zip")
    parser.add_argument("--outdir", default="app/src/main/assets/immersion")
    args = parser.parse_args()
    generate(args.hrir_zip, args.brir_zip, args.outdir)


if __name__ == "__main__":
    main()
