package com.bmwanje.audiophile.vocalremover

/**
 * Canonical MDX-Net model profile used by the Android vocal-removal backend.
 */
enum class MdxModelSpec(
    val id: String,
    val fileName: String,
    val assetPath: String,
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
        assetPath = "models/mdx/UVR_MDXNET_9482.onnx",
        modelUrl = "https://huggingface.co/seanghay/uvr_models/resolve/main/UVR_MDXNET_9482.onnx?download=true",
        sha256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184",
        nFft = 4096,
        dimF = 2048,
        approxDownloadMb = 30,
    ),
}
