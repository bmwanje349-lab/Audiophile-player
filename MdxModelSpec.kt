package com.bmwanje.audiophile.vocalremover

/**
 * Canonical MDX-Net model profile used by the Android vocal-removal backend.
 *
 * The model is vendored into the application assets and pinned by SHA-256.
 * Runtime never downloads model bytes from the network.
 */
enum class MdxModelSpec(
    val id: String,
    val fileName: String,
    val assetPath: String,
    val sha256: String,
    val nFft: Int,
    val dimF: Int,
    val hop: Int = 1024,
    val dimT: Int = 256,
    val compensation: Float = 1.0f,
) {
    LIGHT_9482(
        id = "9482",
        fileName = "UVR_MDXNET_9482.onnx",
        assetPath = "models/mdx/UVR_MDXNET_9482.onnx",
        sha256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184",
        nFft = 4096,
        dimF = 2048,
    ),
}
