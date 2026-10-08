package com.bmwanje.audiophile.vocalremover

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Installs the pinned ONNX model from the APK asset only. No model network download is permitted. */
object MdxModelManager {
    private const val MODELS_DIR = "models/mdx"

    fun file(context: Context, spec: MdxModelSpec): File =
        File(File(context.applicationContext.filesDir, MODELS_DIR), spec.fileName)

    fun isInstalled(context: Context, spec: MdxModelSpec): Boolean {
        val f = file(context, spec)
        return f.isFile && sha256(f) == spec.sha256
    }

    fun hasBundledModel(context: Context, spec: MdxModelSpec): Boolean =
        runCatching {
            context.applicationContext.assets.open(spec.assetPath).use { true }
        }.getOrDefault(false)

    @Synchronized
    fun ensureInstalled(
        context: Context,
        spec: MdxModelSpec = MdxModelSpec.LIGHT_9482,
        progress: ((done: Long, total: Long) -> Unit)? = null,
    ): File {
        val appContext = context.applicationContext
        val target = file(appContext, spec)

        if (isInstalled(appContext, spec)) return target
        target.parentFile?.mkdirs()

        check(hasBundledModel(appContext, spec)) {
            "Bundled MDX model is missing from the APK: ${spec.assetPath}"
        }

        installBundledAsset(appContext, spec, target, progress)
        check(isInstalled(appContext, spec)) {
            "Bundled MDX model failed final checksum verification"
        }
        return target
    }

    private fun installBundledAsset(
        context: Context,
        spec: MdxModelSpec,
        target: File,
        progress: ((done: Long, total: Long) -> Unit)?,
    ) {
        val temp = File(target.parentFile, "${spec.fileName}.asset.part")

        try {
            context.assets.open(spec.assetPath).use { input ->
                val total =
                    runCatching {
                        context.assets.openFd(spec.assetPath).use { it.length }
                    }.getOrDefault(-1L)

                FileOutputStream(temp, false).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var done = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Model installation cancelled")
                        }

                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buffer, 0, n)
                        done += n
                        progress?.invoke(done, total)
                    }
                    output.fd.sync()
                }
            }

            require(temp.isFile && temp.length() > 1_000_000L) {
                "Bundled MDX model is unexpectedly small"
            }
            require(sha256(temp) == spec.sha256) {
                "Bundled MDX model SHA-256 mismatch for ${spec.id}"
            }

            if (!temp.renameTo(target)) {
                if (target.exists() && !target.delete()) {
                    error("Cannot replace existing MDX model")
                }
                require(temp.renameTo(target)) {
                    "Cannot finalize bundled MDX model"
                }
            }
        } catch (e: InterruptedException) {
            temp.delete()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: java.io.FileNotFoundException) {
            temp.delete()
            throw IllegalStateException(
                "Bundled MDX model is missing from the APK: ${spec.assetPath}",
                e,
            )
        } catch (e: java.io.IOException) {
            temp.delete()
            throw IllegalStateException("Failed to copy bundled MDX model", e)
        } catch (e: IllegalArgumentException) {
            temp.delete()
            throw IllegalStateException("Bundled MDX model is corrupt", e)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object ModelManager {
    fun isInstalled(context: Context): Boolean =
        MdxModelManager.isInstalled(context, MdxModelSpec.LIGHT_9482)

    fun ensureInstalled(
        context: Context,
        progress: ((Long, Long) -> Unit)? = null,
    ): File = MdxModelManager.ensureInstalled(context, MdxModelSpec.LIGHT_9482, progress)
}
