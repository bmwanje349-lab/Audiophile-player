# Audiophile Player — MDX-Net ONNX Runtime Audit / Repair Report

## Executive result

The vocal-remover backend is now coherently wired around **ONNX Runtime Android** and the verified `UVR_MDXNET_9482.onnx` model.

LiteRT/TFLite was removed from the production build path.

## Exact production model

`UVR_MDXNET_9482.onnx`

SHA-256:

```
f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184
```

Tensor contract:

```
input  = float32 [1, 4, 2048, 256]
output = float32 [1, 4, 2048, 256]
```

The model URL and SHA are pinned in `MdxModelSpec`. The GitHub Actions build downloads the exact file, checks the SHA, and places it in the APK assets. Runtime installation checks the SHA again before creating an ONNX Runtime session.

## Main repairs

### 1. Backend consistency

The app previously mixed LiteRT and ONNX configuration. The repair makes ONNX Runtime the only neural dependency and only production execution path.

### 2. Compile failure fixed correctly

The failed build reported:

```
MdxLiteRtVocalModelRunner.kt:88:31
Unresolved reference: shape
```

The ONNX Runtime Java API exposes tensor shape through `TensorInfo.getShape()`. The runner now uses that API and validates the returned shape before inference.

### 3. Model identity fixed

The model specification now points to:

```
UVR_MDXNET_9482.onnx
```

rather than a TFLite/LiteRT file.

### 4. Model is actually packaged into production APKs

CI fetches the exact pinned ONNX model into:

```
app/src/main/assets/models/mdx/UVR_MDXNET_9482.onnx
```

The runtime manager copies this verified asset into app-private storage. This removes the previous ambiguity over whether the model used by the APK matches the intended model.

### 5. Runtime validation

The ONNX runner checks:

- exactly one input;
- exactly one output;
- float32 tensor type;
- exact `[1,4,2048,256]` shape.

A mismatched model is rejected immediately with an explicit error.

### 6. Reload safety

A new session is created before the previous runner is closed, so a failed replacement cannot leave the pipeline holding a closed session.

### 7. Sample-rate handling

The player supplies the decoded track's real sample rate. The MDX layer converts to 44.1 kHz for inference and converts the vocal stem back to the source rate afterward.

## Preserved DSP

The existing native premium vocal-removal DSP and stereo-widener DSP remain outside the neural backend and were not replaced with a lower-level substitute.

## Verification status

The repository contains automated local DSP/source tests plus a real-model GitHub Actions smoke test.

The CI smoke test validates the **actual ONNX binary** used for the APK, not merely a stub. It checks model hash, tensor metadata, real inference, output finiteness and output magnitude before the Android build runs.

The remaining real-device acceptance work is performance/quality testing on representative music and target hardware; the repository's build gate itself now validates the real model and APK together.
