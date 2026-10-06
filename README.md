# Audiophile Player — Premium AI Vocal Remover (MDX-Net LiteRT)

This package replaces the previous HT-Demucs/ONNX neural backend with an on-device MDX-Net LiteRT backend while leaving the existing `PremiumVocalRemoverDSP` and stereo-widener DSP untouched.

## Runtime architecture

```text
Decoded stereo PCM
        |
        v
Source sample rate
        |
        v
44.1 kHz resampling (existing WindowedSincResampler)
        |
        v
MDX-Net 9482 LiteRT (default, ~30 MB)
        |
        v
Host STFT -> model -> iSTFT
        |
        v
Vocal stem @ original sample rate
        |
        +----------------------+
        |                      |
        v                      v
mix ------------------> PremiumVocalRemoverDSP
                            |
                            v
                     latency-compensated output
```

The default model is `UVR_MDXNET_9482.fp16acc.tflite`. It is a vocal-separation MDX-Net profile intended for a much smaller on-device footprint than the previous 166 MB HT-Demucs model. A higher-quality `UVR-MDX-NET-Voc_FT.fp16acc.tflite` profile is also wired into the backend for builds that prefer quality over download/storage size. The published MDX LiteRT variants are approximately 30 MB and 67 MB respectively; the source repository describes 9482 as the smaller/faster profile and Voc_FT as the vocal fine-tune. citeturn787695search0turn985663search0turn985663search1

The MDX models operate on 44.1 kHz audio with 256 time frames per inference chunk. The host performs STFT/iSTFT because the model graph itself does not perform the audio transform. The 9482 profile uses 4096-point FFT / 2048 frequency bins; Voc_FT uses 6144-point FFT / 3072 frequency bins. citeturn787695search0turn787695search1turn175423view1

## Why 9482 is the default

The goal of this integration is to keep the feature practical on Android without changing the premium post-processing DSP. The 9482 model cuts the neural download/storage footprint to roughly one-sixth of the previous 166 MB model. The `VOC_FT` profile remains available as the quality-focused option. For a premium shipping build, benchmark both on the target device and representative music before locking the default. citeturn959857search3turn787695search0

## Android dependencies

Add to the app module:

```kotlin
implementation("com.google.ai.edge.litert:litert:2.2.0")
implementation("com.github.wendykierp:JTransforms:3.1")
```

LiteRT 2.2.0 is the current documented Android artifact and the modern `CompiledModel` API supports CPU/GPU/NPU acceleration. citeturn902221search9turn959857search2

The host must also declare:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

The model is downloaded to app-private storage, written to a `.part` file, SHA-256 verified, and then atomically renamed into place. The default 9482 checksum is pinned in `MdxModelSpec.kt`:

```text
2a07e11db13a11ca4900a54b4a316ef67931e993a6a3d19444bccbbeb9b445ee
```

The higher-quality Voc_FT checksum is also pinned there:

```text
5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58
```

## Existing premium DSP was not changed

These production files are carried over unchanged from the repaired integration:

- `PremiumVocalRemoverDSP.h`
- `native_vocal_remover.cpp`
- `ProfessionalStereoWidenerDSP_v10.h`

The neural backend only supplies the vocal stem. `PremiumVocalRemoverDSP` still performs the existing depth, focus/transient protection, dry/wet, stem gain, output gain, ceiling, and latency-compensation work.

## Important integration fix

The UI no longer forces the pipeline to 44.1 kHz. It constructs the pipeline from the decoded track's actual sample rate. The MDX backend then resamples internally to 44.1 kHz and returns the separated stem to the source rate. This avoids a sample-rate mismatch on 48/96 kHz tracks.

## Testing completed in this environment

Passed:

- existing separator / OLA / chunk-edge tests
- streaming separator regression
- MDX production Kotlin source compile against API stubs
- 9482 and Voc_FT STFT/iSTFT numerical reference checks
- 10% crossfade identity check
- native CMake build
- JNI create/process/destroy test
- neural depth/gain regression
- full native DSP subtraction chain
- 44.1 / 48 / 96 kHz block/reset regression
- ASan + UBSan JNI regression
- ThreadSanitizer setter/process stress

Representative results:

```text
9482 STFT/iSTFT round-trip: 113.22 dB SNR
Voc_FT STFT/iSTFT round-trip: 115.24 dB SNR
MDX crossfade identity max error: 1.735e-18
Neural correlated-accompaniment relative error: 6.19e-8
Full native chain relative RMS error: 5.20e-8
ASan/UBSan: PASS
TSAN: PASS (exit 0)
```

## Remaining validation gap

The actual 30 MB model binary could not be executed in this sandbox because direct model download/runtime access was unavailable. A real-model smoke test is included in `.github/workflows/mdx-model-smoke.yml`; it downloads the pinned model, verifies the checksum, checks the LiteRT tensor contract, executes inference, and rejects non-finite/implausible output. LiteRT's Python package is officially available as `ai-edge-litert`. citeturn902221search0turn902221search1

The final production acceptance test should still be run on the target Android hardware, measuring real inference time, memory, thermal behavior, CPU/GPU utilization, vocal bleed, accompaniment damage, and long-track stability.
