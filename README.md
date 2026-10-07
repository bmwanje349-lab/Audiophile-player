# Audiophile Player — Premium AI Vocal Remover (MDX-Net ONNX Runtime)

This integration uses **ONNX Runtime Android** as the neural backend. LiteRT/TFLite is not part of the production path.

## Runtime architecture

Decoded stereo PCM
→ source-rate-aware pipeline
→ resample to 44.1 kHz
→ host STFT
→ **UVR_MDXNET_9482.onnx**
→ ONNX Runtime inference
→ iSTFT / overlap-add
→ restore source sample rate
→ existing PremiumVocalRemoverDSP
→ latency-compensated instrumental output

### Canonical model

- File: `UVR_MDXNET_9482.onnx`
- Runtime: ONNX Runtime Android 1.30.0
- Input/output contract: `float32 [1, 4, 2048, 256]`
- FFT: 4096
- Frequency bins: 2048
- Time frames: 256
- SHA-256: `f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184`

The same hash is used by the build pipeline and runtime installer. The APK build downloads the exact pinned ONNX file into `app/src/main/assets/models/mdx/`, verifies the checksum, and bundles it. At runtime the app copies the bundled asset into app-private storage and verifies it again. If an asset is unavailable, the manager downloads the same exact file and verifies the same SHA before installation.

## Android dependencies

The neural dependency is:

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
implementation("com.github.wendykierp:JTransforms:3.1")
```

No LiteRT/TFLite dependency is used.

The host performs the STFT/iSTFT because the MDX model contract is spectrogram-based. The ONNX runner validates the model metadata at startup and rejects a wrong input/output shape or non-float tensor rather than allowing a later crash during inference.

## Existing premium DSP

The ONNX neural layer is kept separate from the existing premium processing. The native premium vocal-removal DSP and stereo-widener implementation are not replaced by the model.

The pipeline applies the neural vocal estimate to the existing premium DSP, where the configured depth, focus/transient protection, dry/wet, stem gain, output gain, ceiling and latency compensation continue to operate.

## Stability repairs

The repaired pipeline also:

- creates a replacement ONNX session before closing the previous session;
- validates the exact model tensor contract before processing;
- preserves finite ONNX outputs instead of applying an arbitrary hard clamp;
- uses the decoded track's actual sample rate and resamples internally to/from the model's 44.1 kHz rate;
- avoids relying on a network request when the verified model is already bundled in the APK.

## Verification

GitHub Actions now performs, in order:

1. Download the exact ONNX model.
2. Verify its SHA-256.
3. Run a real ONNX Runtime CPU smoke test against the actual model binary.
4. Verify input/output tensor shapes and float32 types.
5. Run one real inference and reject non-finite or implausibly large output.
6. Build the Android debug APK.
7. Upload the APK and build log.

A successful CI run is therefore the acceptance gate for both the real model binary and the Android build.
