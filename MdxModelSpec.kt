package com.bmwanje.audiophile.vocalremover

/**
 * MDX-Net model profiles used by the Android vocal-removal backend.
 *
 * 9482 is the lightweight default. Voc_FT is the higher-quality alternative.
 * Both are vocal-predicting models; instrumental is reconstructed as mix-vocals.
 */
enum class MdxModelSpec(
    val id: String,
    val fileName: String,
    val modelUrl: String,
    val sha256: String,
    val nFft: Int,
    val dimF: Int,
    val hop: Int = 1024,
    val dimT: Int = 256,
    val compensation: Float = 1.0f,
    val approxDownloadMb: Int,
) {
    LIGHT_9482(
        id = "9482",
        fileName = "UVR_MDXNET_9482.onnx",
        modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR_MDXNET_9482.onnx",
        sha256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184",
        nFft = 4096,
        dimF = 2048,
        approxDownloadMb = 30,
    ),
    VOC_FT(
        id = "voc_ft",
        fileName = "UVR-MDX-NET-Voc_FT.fp16acc.tflite",
        modelUrl = "https://huggingface.co/gyoom-sa/UVR-MDX-LiteRT/resolve/main/UVR-MDX-NET-Voc_FT.fp16acc.tflite",
        sha256 = "5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58",
        nFft = 6144,
        dimF = 3072,
        approxDownloadMb = 67,
    ),
}
