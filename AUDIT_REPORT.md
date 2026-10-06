# Audiophile Player — MDX-Net Vocal Remover Audit / Repair Report

Date: 2026-10-06

## Executive result

The neural backend has been moved from HT-Demucs/ONNX to MDX-Net LiteRT without changing the existing premium vocal-removal DSP.

The selected production default is:

`UVR_MDXNET_9482.fp16acc.tflite` — approximately 30 MB.

A second profile is included for quality-focused builds:

`UVR-MDX-NET-Voc_FT.fp16acc.tflite` — approximately 67 MB.

The current LiteRT MDX source publishes 9482 as the smaller/faster profile and Voc_FT as the vocal fine-tune. Both are designed for on-device source separation. citeturn787695search0turn985663search0turn985663search1

## What was deliberately not changed

The following premium DSP implementation was not edited:

- `PremiumVocalRemoverDSP.h`
- `native_vocal_remover.cpp`
- `ProfessionalStereoWidenerDSP_v10.h`

The replacement is isolated to the neural separator layer plus model management, STFT/iSTFT, LiteRT execution, and UI wiring.

## Neural backend design

```text
Decoded PCM
  -> source-rate-aware pipeline
  -> resample to 44.1 kHz
  -> MDX STFT
  -> LiteRT model
  -> vocal spectrogram
  -> MDX iSTFT
  -> overlap/add
  -> resample back to source rate
  -> existing PremiumVocalRemoverDSP
```

The 9482 profile uses 4096-point FFT / 2048 frequency bins and 256 time frames. Voc_FT uses 6144-point FFT / 3072 frequency bins and 256 time frames. The host performs the transform and the model predicts the vocal spectrogram; instrumental output remains the existing `mix - vocals` path supplied to the premium DSP. citeturn787695search0turn787695search1turn175423view1

## Correctness repairs inherited from the previous audit

The earlier integration issues were retained as fixed:

1. The neural stem gain heuristic that could over-subtract correlated accompaniment was removed.
2. `Depth` now actually controls neural subtraction strength.
3. Finite model values are no longer blindly clamped to `[-1, 1]`; only non-finite values are sanitized.
4. Model replacement remains transactional: a new runner is created before the old runner is closed.
5. The test harness path errors were removed.

The correlated-accompaniment regression remained at approximately `6.19e-8` relative error after the MDX integration.

## New integration correctness fix found during this pass

The UI previously constructed the pipeline as though every source track were 44.1 kHz. That would make 48 kHz and 96 kHz decoded tracks use the wrong rate metadata.

The UI now constructs `VocalRemoverPipeline` from `pcm.sampleRate` after decoding. The MDX separator then performs its own 44.1 kHz model conversion and returns the result to the original source rate.

## Local test results

### Host / Kotlin

- separator / OLA regression: PASS
- chunk/tail edge cases: PASS
- streaming separator: PASS
- MDX source compilation: PASS
- 9482 STFT/iSTFT: `113.22 dB SNR`
- Voc_FT STFT/iSTFT: `115.24 dB SNR`
- 10% crossfade identity: `1.735e-18` max error

### Native / JNI

- CMake build: PASS
- JNI create/process/destroy: PASS
- neural depth/gain regression: PASS
- full DSP subtraction chain: PASS
- 44.1/48/96 kHz regression: PASS
- block-size invariance: PASS
- ASan/UBSan: PASS
- TSAN: PASS, exit code 0

Representative post-repair values:

```text
neural correlated relative error = 6.1887388e-8
full-chain relative RMS error    = 5.2045800e-8
full-chain max sample error      = 1.4901161e-8
```

## Model integrity

The default model is pinned by SHA-256:

```text
9482  2a07e11db13a11ca4900a54b4a316ef67931e993a6a3d19444bccbbeb9b445ee
VocFT 5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58
```

The download manager uses a resumable `.part` file and only installs a model after the final checksum passes.

## Actual-model verification status

The local environment could not execute the real 9482 binary because the model host was not reachable from the sandbox. Therefore this audit does **not** claim that the exact production `.tflite` binary has been run here.

A real-model smoke test has been added to GitHub Actions. It uses the official LiteRT Python package, verifies the pinned hash, validates the expected tensor shape/dtype, performs one deterministic inference, and checks output finiteness and magnitude. LiteRT's current documentation recommends `ai-edge-litert` for Python and `CompiledModel` for new high-performance inference paths. citeturn902221search0turn902221search7

## Production risks still requiring a real Android device

1. **Long-track memory:** the end-to-end API still holds multiple whole-buffer representations. The existing streaming separator is not yet the player-facing streaming pipeline.
2. **Accelerator choice:** GPU versus CPU performance is device dependent. LiteRT supports multiple accelerators, but the production default should be selected from target-device measurements. citeturn902221search9turn959857search2
3. **Music-quality acceptance:** compare 9482 and Voc_FT on representative pop, hip-hop, EDM, acoustic, rock, and dense mixes for vocal leakage and accompaniment damage before declaring one universally superior.

## Recommendation

Ship the 9482 backend as the lightweight default for the Android build, but keep Voc_FT available as the premium-quality profile. Do not alter the existing premium post-DSP to compensate for a model choice until real music tests demonstrate a measurable need.
