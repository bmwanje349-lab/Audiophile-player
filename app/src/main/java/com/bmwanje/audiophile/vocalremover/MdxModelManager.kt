package com.bmwanje.audiophile.vocalremover

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** On-demand MDX model download with atomic install + SHA-256 verification. */
object MdxModelManager {
    private const val MODELS_DIR = "models/mdx"

    fun file(context: Context, spec: MdxModelSpec): File =
        File(File(context.filesDir, MODELS_DIR), spec.fileName)

    fun isInstalled(context: Context, spec: MdxModelSpec): Boolean {
        val f = file(context, spec)
        return f.isFile && sha256(f) == spec.sha256
    }

    @Synchronized
    fun ensureInstalled(
        context: Context,
        spec: MdxModelSpec = MdxModelSpec.LIGHT_9482,
        progress: ((done: Long, total: Long) -> Unit)? = null,
    ): File {
        val target = file(context, spec)
        if (isInstalled(context, spec)) return target

        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${spec.fileName}.part")
        var existing = if (temp.isFile) temp.length() else 0L
        var append = existing > 0L
        var connection: HttpURLConnection? = null

        try {
            fun openConnection(rangeStart: Long): HttpURLConnection =
                (URI(spec.modelUrl).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AudiophilePlayer/MDX-1")
                    if (rangeStart > 0L) setRequestProperty("Range", "bytes=$rangeStart-")
                }

            connection = openConnection(existing)
            connection.connect()
            var code = connection.responseCode

            if (code == 416 && append) {
                connection.disconnect()
                temp.delete()
                existing = 0L
                append = false
                connection = openConnection(0L)
                connection.connect()
                code = connection.responseCode
            }

            require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                "MDX model download HTTP $code"
            }

            if (append && code != HttpURLConnection.HTTP_PARTIAL) {
                append = false
                existing = 0L
                temp.delete()
            }

            val reported = connection.getHeaderFieldLong("Content-Length", -1L)
            val total = if (reported > 0L) existing + reported else -1L

            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var done = existing
                    while (true) {
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
                "Downloaded MDX model is unexpectedly small"
            }
            require(sha256(temp) == spec.sha256) {
                "MDX model SHA-256 mismatch for ${spec.id}"
            }

            if (!temp.renameTo(target)) {
                if (target.exists() && !target.delete()) error("Cannot replace old MDX model")
                require(temp.renameTo(target)) { "Cannot finalize MDX model" }
            }
            return target
        } finally {
            connection?.disconnect()
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

/** Backward-compatible facade for the existing UI patch. */
object ModelManager {
    fun isInstalled(context: Context): Boolean =
        MdxModelManager.isInstalled(context, MdxModelSpec.LIGHT_9482)

    fun ensureInstalled(
        context: Context,
        progress: ((Long, Long) -> Unit)? = null,
    ): File = MdxModelManager.ensureInstalled(context, MdxModelSpec.LIGHT_9482, progress)
}
