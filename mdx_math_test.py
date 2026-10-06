#!/usr/bin/env python3
import math
import numpy as np

SR = 44100
HOP = 1024
DIM_T = 256
CROSSFADE = 0.10


def hann_periodic(n):
    k = np.arange(n, dtype=np.float64)
    return (0.5 * (1.0 - np.cos(2.0 * np.pi * k / n))).astype(np.float32)


def reflect_pad(x, pad):
    if len(x) < 2:
        raise ValueError('reflect padding requires at least two input samples')
    return np.pad(x, (pad, pad), mode='reflect')


def stft_chunk(x, n_fft, dim_f):
    chunk = HOP * (DIM_T - 1)
    assert len(x) == chunk
    pad = n_fft // 2
    win = hann_periodic(n_fft)
    p = reflect_pad(x, pad)
    frames = []
    for t in range(DIM_T):
        frame = p[t * HOP:t * HOP + n_fft] * win
        spec = np.fft.rfft(frame, n=n_fft)
        frames.append(spec[:dim_f])
    spec = np.stack(frames, axis=1)
    out = np.empty((4, dim_f, DIM_T), dtype=np.float32)
    out[0] = spec.real.astype(np.float32)
    out[1] = spec.imag.astype(np.float32)
    out[2] = out[0]
    out[3] = out[1]
    return out


def istft_channel(spec, n_fft, dim_f):
    chunk = HOP * (DIM_T - 1)
    pad = n_fft // 2
    win = hann_periodic(n_fft)
    time_len = chunk + n_fft
    acc = np.zeros(time_len, dtype=np.float64)
    env = np.zeros(time_len, dtype=np.float64)
    for t in range(DIM_T):
        full = np.zeros(n_fft, dtype=np.complex64)
        full[:dim_f] = spec[:, t]
        for k in range(1, dim_f):
            full[n_fft-k] = np.conj(full[k])
        frame = np.fft.ifft(full).real.astype(np.float32)
        frame *= win
        start = t * HOP
        acc[start:start+n_fft] += frame
        env[start:start+n_fft] += win.astype(np.float64) ** 2
    out = acc[pad:pad+chunk]
    den = env[pad:pad+chunk]
    return np.where(den > 1e-8, out / den, 0).astype(np.float32)


def quality_db(a, b):
    err = np.sqrt(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    rms = np.sqrt(np.mean(a.astype(np.float64) ** 2))
    return 20 * np.log10(max(rms, 1e-20) / max(err, 1e-20))


def run_case(n_fft, dim_f):
    chunk = HOP * (DIM_T - 1)
    t = np.arange(chunk, dtype=np.float64) / SR
    x = (
        0.45 * np.sin(2*np.pi*220*t) +
        0.19 * np.sin(2*np.pi*997*t) +
        0.08 * np.sin(2*np.pi*4011*t)
    ).astype(np.float32)
    spec = stft_chunk(x, n_fft, dim_f)[0] + 1j * stft_chunk(x, n_fft, dim_f)[1]
    y = istft_channel(spec, n_fft, dim_f)
    db = quality_db(x, y)
    assert db > 85.0, (n_fft, db)
    return db


def test_crossfade_identity():
    # Two consecutive identical model outputs must reconstruct exactly through
    # MDX's 10% weighted overlap. This validates chunk weights independently of
    # any neural model quality.
    chunk = HOP * (DIM_T - 1)
    overlap = int(chunk * CROSSFADE)
    stride = chunk - overlap
    total = chunk + stride
    x = np.linspace(-0.25, 0.25, total, dtype=np.float32)
    out = np.zeros_like(x, dtype=np.float64)
    wsum = np.zeros_like(x, dtype=np.float64)
    for index, start in enumerate([0, stride]):
        actual = min(chunk, total-start)
        first = index == 0
        last = index == 1
        for i in range(actual):
            w = 1.0
            if not first and i < overlap:
                w *= i / overlap
            if not last and i >= chunk-overlap:
                w *= (chunk-i) / overlap
            out[start+i] += float(x[start+i]) * w
            wsum[start+i] += w
    y = out / np.maximum(wsum, 1e-12)
    assert np.max(np.abs(y-x)) < 1e-6
    return float(np.max(np.abs(y-x)))


def test_tensor_contract():
    for n_fft, dim_f in [(4096, 2048), (6144, 3072)]:
        x = np.zeros(HOP*(DIM_T-1), dtype=np.float32)
        spec = stft_chunk(x, n_fft, dim_f)
        assert spec.shape == (4, dim_f, DIM_T)
        assert spec.dtype == np.float32


if __name__ == '__main__':
    d9482 = run_case(4096, 2048)
    dft = run_case(6144, 3072)
    test_tensor_contract()
    cross = test_crossfade_identity()
    print(f'MDX 9482 STFT/iSTFT round-trip: {d9482:.2f} dB SNR')
    print(f'MDX Voc_FT STFT/iSTFT round-trip: {dft:.2f} dB SNR')
    print(f'10% crossfade identity max error: {cross:.3e}')
    print('MDX host-DSP reference tests: PASS')
