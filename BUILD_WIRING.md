# MDX-Net ONNX Runtime integration wiring

## Runtime

The production neural layer is:

- `MdxModelSpec`
- `MdxModelManager`
- `MdxStft`
- `MdxOnnxVocalModelRunner`
- `MdxSeparatorCore`
- `VocalRemoverPipeline`

## Gradle

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
implementation("com.github.wendykierp:JTransforms:3.1")
```

There is no LiteRT/TFLite dependency.

## Model packaging

The exact verified model is:

| Profile | File | SHA-256 | Approx. size |
|---|---|---|---:|
| `LIGHT_9482` | `UVR_MDXNET_9482.onnx` | `f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184` | ~30 MB |

The model is committed in the repository and verified in CI before the Android build:

```
app/src/main/assets/models/mdx/UVR_MDXNET_9482.onnx
```

The normal Android build only verifies the committed file; it does not download the model.

The app then copies that asset to app-private storage and verifies the same checksum before loading it.

## Tensor contract

```
[batch, 4, 2048, 256] float32
```

The host-side STFT stores the four planes as left-real, left-imaginary, right-real, right-imaginary.

## Processing path

```
Decoded stereo PCM
      |
      v
44.1 kHz model-rate conversion
      |
      v
Periodic-Hann STFT
      |
      v
ONNX Runtime [1,4,2048,256]
      |
      v
inverse STFT
      |
      v
overlap/crossfade reconstruction
      |
      v
source-rate restoration
      |
      v
Existing PremiumVocalRemoverDSP
      |
      v
latency-aligned instrumental output
```

## Failure handling

Model installation is checksum-gated and asset-only. Session construction is metadata-gated. A missing, corrupted or wrong model cannot silently become the active runner.

## UI

The AI Vocal Remover remains an offline-processing feature. It does not replace the regular EQ or stereo-widener render chain.
