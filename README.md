# Audiophile Player — Premium AI Vocal Remover (MDX-Net ONNX Runtime)

This integration uses **ONNX Runtime Android** as the neural backend. LiteRT/TFLite is not part of the production execution path.

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
- Input/output contract: `float32 [batch, 4, 2048, 256]` with batch `1` at runtime
- FFT: 4096
- Frequency bins: 2048
- Time frames: 256
- SHA-256: `f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184`

The production ONNX file is vendored at `app/src/main/assets/models/mdx/UVR_MDXNET_9482.onnx` and verified by SHA-256 before use. The Android app never downloads model bytes from the network. The model is copied from APK assets into app-private storage on first use and checksum-verified again before the ONNX Runtime session is created.

A one-time GitHub Actions vendor workflow exists only to bootstrap the exact pinned ONNX file into the repository if the asset is missing. The normal Android build never fetches the model from the network.

### Important format distinction

The supplied `UVR_MDXNET_9482.fp16acc.tflite` file is a **TFLite** model. It is not loaded by this ONNX Runtime implementation. It was inspected as a 9482 MDX-derived model with NCHW I/O metadata, but substituting it would require switching the production execution engine to LiteRT/TFLite, which is intentionally not done here.

## Android dependencies

The neural dependency is:

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
implementation("com.github.wendykierp:JTransforms:3.1")
```

No LiteRT/TFLite dependency is used.

The host performs the STFT/iSTFT because the MDX model contract is spectrogram-based. The ONNX runner validates model metadata at startup and accepts either a fixed batch dimension of 1 or a symbolic/dynamic batch dimension, while requiring dimensions `[4,2048,256]` and float32.

## Existing premium DSP

The ONNX neural layer is kept separate from the existing premium processing. The native premium vocal-removal DSP and stereo-widener implementation are not replaced by the model.

The pipeline applies the neural vocal estimate to the existing premium DSP, where the configured depth, focus/transient protection, dry/wet, stem gain, output gain, ceiling and latency compensation continue to operate.

## Stability repairs

The repaired pipeline also:

- creates a replacement ONNX session before closing the previous session;
- validates the model tensor contract before processing, including dynamic batch metadata;
- preserves finite ONNX outputs instead of applying an arbitrary hard clamp;
- uses the decoded track's actual sample rate and resamples internally to/from the model's 44.1 kHz rate;
- refuses to start when the vendored model asset is absent or has the wrong checksum;
- contains no runtime model-download fallback.

## Verification

GitHub Actions performs the following model/build gates:

1. Verify that the ONNX binary is present in the repository.
2. Verify the exact SHA-256.
3. Run a real ONNX Runtime CPU smoke test against the actual model binary.
4. Verify input/output rank, dimensions and float32 types.
5. Run one real inference and reject non-finite or implausibly large output.
6. Build the Android debug APK.
7. Upload the APK and build log.

A successful Android build therefore uses the exact model bytes that are checked into the repository, with no model download during the build.
