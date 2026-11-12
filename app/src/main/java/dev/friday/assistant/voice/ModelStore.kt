package dev.friday.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The speech model the wake word listener runs on. It is about 36 MB, so it is downloaded once
 * on the phone instead of being packed into the app, which would be re-uploaded with every update.
 * Indian-English small model from the Vosk project (alphacephei.com).
 */
object ModelStore {
    private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

    fun dir(context: Context) = File(context.filesDir, "vosk-model")

    fun isReady(context: Context) = File(dir(context), "am").isDirectory && File(dir(context), "conf").isDirectory

    fun sizeMb(context: Context) = dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)

    suspend fun download(context: Context, onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val staging = File(context.filesDir, "vosk-staging")
        val zip = File(context.cacheDir, "vosk-model.zip")
        try {
            staging.deleteRecursively()
            staging.mkdirs()

            val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != 200) throw IOException("Server ne HTTP ${conn.responseCode} diya")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    zip.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != last) {
                                last = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            unzip(zip, staging)
            // The archive wraps the model in a folder whose name carries the version, so find it by what it holds.
            val root = staging.walkTopDown().firstOrNull {
                it.isDirectory && File(it, "am").isDirectory && File(it, "conf").isDirectory
            } ?: throw IOException("Download mein model ka folder nahi mila")
            dir(context).deleteRecursively()
            if (!root.renameTo(dir(context))) throw IOException("Model ko jagah par rakh nahi paya")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun unzip(zip: File, into: File) {
        val base = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(into, entry.name)
                // An archive entry named like "../x" must not be allowed to write outside the folder.
                if (!out.canonicalPath.startsWith(base)) throw IOException("Archive mein galat path: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}
