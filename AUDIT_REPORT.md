# Audiophile Player — MDX-Net ONNX Runtime Audit / Repair Report

## Executive result

The vocal-remover backend is coherently wired around **ONNX Runtime Android** and the pinned `UVR_MDXNET_9482.onnx` model.

LiteRT/TFLite is not used as the production execution engine.

The model path has now been tightened to an **offline, vendored asset model**: the Android application no longer contains a network fallback for downloading neural-model bytes.

## Exact production model

`UVR_MDXNET_9482.onnx`

SHA-256:

```
f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184
```

Tensor contract:

```
input  = float32 [batch, 4, 2048, 256]
output = float32 [batch, 4, 2048, 256]
batch  = 1 at runtime; symbolic/dynamic batch metadata is accepted
```

The ONNX asset is vendored at:

```
app/src/main/assets/models/mdx/UVR_MDXNET_9482.onnx
```

Runtime copies only this bundled asset to app-private storage and verifies the checksum before creating an ONNX Runtime session. There is no runtime URL and no HTTP download fallback.

## Main repairs

### 1. Backend consistency

The production neural path is ONNX Runtime only. LiteRT/TFLite is not reintroduced.

### 2. Compile/API failure fixed correctly

The failed build reported an unresolved tensor shape reference. The ONNX Runtime Java API exposes tensor shape through `TensorInfo.getShape()`. The runner now uses that API.

### 3. Dynamic-batch model metadata handled correctly

The real model exposes a symbolic/dynamic first dimension in its ONNX metadata. The runner no longer incorrectly requires the metadata to be literally `[1,4,2048,256]`; it accepts batch `-1` or `1` while enforcing `[4,2048,256]` for the remaining dimensions.

This is important because the previous exact-shape check could reject a valid model at app startup even though the actual runtime tensor was correctly created with batch 1.

### 4. Model installation is offline

The previous implementation had a verified network fallback. That path has been removed.

The application now:

1. checks whether a previously installed app-private copy already matches the expected SHA;
2. otherwise requires the model asset to exist inside the APK;
3. copies the bundled asset to app-private storage;
4. verifies size and SHA-256;
5. rejects missing/corrupt assets with an explicit error.

There is no HTTP client in the model manager.

### 5. Build-time model vendoring

A separate one-time GitHub Actions workflow can bootstrap the exact pinned ONNX binary into the repository when the asset is missing. The normal Android build workflow only verifies and uses the committed asset; it does not download the model.

### 6. Model format distinction

The file `UVR_MDXNET_9482.fp16acc.tflite` is a TFLite binary, not an ONNX binary. Its metadata identifies it as a 9482 MDX-derived model with NCHW I/O, but ONNX Runtime cannot execute a TFLite file directly. It therefore is not substituted into the ONNX execution path.

### 7. Reload safety

A new ONNX session is created before the previous runner is closed, so a failed replacement does not leave the pipeline holding a closed session.

### 8. Sample-rate handling

The player supplies the decoded track's real sample rate. The MDX layer converts to 44.1 kHz for inference and converts the vocal stem back to the source rate afterward.

## Preserved DSP

The existing native premium vocal-removal DSP and stereo-widener DSP remain outside the neural backend and are not replaced with a lower-level substitute.

## Verification status

The repository contains automated DSP/source tests plus a real-model GitHub Actions smoke test.

The smoke test validates the **actual ONNX binary used for the APK**, including checksum, tensor metadata, real CPU inference, output finiteness and output magnitude before the Android build runs.

The remaining real-device acceptance work is performance/quality testing on representative music and target hardware. The repository build gate now ensures the APK is assembled from the checked-in, verified model rather than a transient network download.
