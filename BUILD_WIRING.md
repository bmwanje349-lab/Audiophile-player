# MDX-LiteRT integration wiring

## Runtime

Use the MDX-LiteRT backend as the neural layer. The default model is `UVR_MDXNET_9482.fp16acc.tflite` (~30 MB). `UVR-MDX-NET-Voc_FT.fp16acc.tflite` (~67 MB) is available as the higher-quality option.

The app should use:

- `MdxModelSpec`
- `MdxModelManager`
- `MdxStft`
- `MdxLiteRtVocalModelRunner`
- `MdxSeparatorCore`
- existing `VocalRemoverPipeline`

## Gradle

```kotlin
implementation("com.google.ai.edge.litert:litert:2.2.0")
implementation("com.github.wendykierp:JTransforms:3.1")
```

LiteRT 2.x's current Android API is `CompiledModel`. It can target GPU and fall back to CPU; the supplied runner tries GPU+CPU first and then CPU-only.

## Project compatibility

LiteRT 2.2.0 is a modern dependency. Projects using an older Kotlin/AGP toolchain may need the Kotlin/AGP update required by LiteRT 2.x before the source compiles. Do not lower the model's STFT contract to work around a dependency mismatch.

## Model installation

The model is **not** embedded in the APK. `MdxModelManager` downloads it into app-private storage, resumes interrupted transfers, verifies SHA-256, then atomically renames the `.part` file.

Pinned models:

| Profile | File | SHA-256 | Approx. size |
|---|---|---|---:|
| `LIGHT_9482` | `UVR_MDXNET_9482.fp16acc.tflite` | `2a07e11db13a11ca4900a54b4a316ef67931e993a6a3d19444bccbbeb9b445ee` | 29.8 MB |
| `VOC_FT` | `UVR-MDX-NET-Voc_FT.fp16acc.tflite` | `5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58` | 66.8 MB |

## Processing path

```text
Decoded stereo PCM
      |
      v
44.1 kHz resample
      |
      v
Periodic-Hann STFT
      |
      v
MDX-Net LiteRT [1,4,dim_f,256]
      |
      v
inverse STFT -> vocal stem
      |
      v
sample-rate restore
      |
      v
Existing PremiumVocalRemoverDSP  <--- unchanged
      |
      v
latency-aligned instrumental copy
```

The MDX model predicts the vocal stem. The original mix is still passed to the existing native DSP; the source file is never overwritten.

## UI

Keep the AI Vocal Remover as a separate DSP screen/card after the existing Stereo Widener. Do not place MDX inside the normal live EQ/widener render chain.

The supplied UI copy now says `MDX-Net vocal separation` rather than `HT-Demucs`.
