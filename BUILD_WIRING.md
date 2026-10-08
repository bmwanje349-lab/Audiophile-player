# MDX-Net ONNX Runtime integration wiring

## Runtime

The production offline and Live Karaoke neural layer is:

- `MdxModelSpec`
- `MdxModelManager`
- `MdxStft` / `LiveMdxStft`
- `MdxOnnxVocalModelRunner` / `LiveMdxOnnxVocalModelRunner`
- `MdxSeparatorCore.StreamingSeparator`
- `VocalRemoverPipeline`
- `LiveStreamingVocalRemover`

## Gradle

implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
implementation("com.github.wendykierp:JTransforms:3.1")

There is no LiteRT/TFLite dependency in the production Gradle module.

## Model packaging

The exact verified model is:

| Profile | File | SHA-256 | Size |
|---|---|---|---:|
| `LIGHT_9482` | `UVR_MDXNET_9482.onnx` | `f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184` | 29,704,436 bytes |

The model is committed at:

`app/src/main/assets/models/mdx/UVR_MDXNET_9482.onnx`

The Android build does not download model bytes. `MdxModelManager` copies the APK asset to app-private storage and verifies size/SHA-256 before loading it.

## Tensor contract

`input/output: float32 [batch, 4, 2048, 256]
runtime batch: 1`

The ONNX runner accepts either a fixed batch metadata value of 1 or a symbolic/dynamic batch dimension, while enforcing the remaining dimensions and float32 type.

## Offline processing path

Decoded stereo PCM -> 44.1 kHz model-rate conversion -> UVR-compatible periodic-Hann STFT -> ONNX Runtime [1,4,2048,256] -> inverse STFT -> central MDX generation region + compensation 1.035 -> source-rate restoration -> existing PremiumVocalRemoverDSP -> streaming PCM/WAV output

## Live processing path

The live path uses the same model contract but never materializes the complete song. MDX windows are processed from bounded input/output buffers and emitted into the existing premium DSP. AudioTrack consumes bounded PCM blocks.

A one-time throughput measurement determines the safe startup buffer. If sustained rate or thermal health becomes unsafe, the player switches to Fast Live and transfers pending source audio into the same playback timeline.

## Failure handling

Model installation is checksum-gated and asset-only. Session construction is metadata-gated. A missing, corrupted or wrong model cannot silently become the active runner.

## Preserved DSP

The existing native premium vocal-removal DSP and stereo-widener DSP remain separate. The MDX layer supplies the neural vocal estimate; it does not replace the PEQ or Stereo Widener path.

## Variant note

The current repository contains one production separator: MDX-Net 9482. There is no active HT-Demucs model/runtime in the current app module, so no non-functional separator selector is advertised.