package dev.friday.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app update. CI publishes friday.apk and version.json to the site on every
 * push to main; the app compares version.json's build number with its own.
 */
object Updater {
    private const val BASE_URL = "https://friday-android.vercel.app"

    data class Update(val code: Long, val name: String, val sha256: String, val notes: String)

    /** Returns the published build if it is newer than the installed one, else null. */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        // The query string defeats any cache between the phone and the site.
        val conn = open("$BASE_URL/version.json?t=${System.currentTimeMillis()}")
        try {
            if (conn.responseCode != 200) throw IOException("version.json: HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val update = Update(
                code = json.getLong("code"),
                name = json.optString("name"),
                sha256 = json.optString("sha256"),
                notes = json.optString("notes"),
            )
            if (update.code > installedCode(context)) update else null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads the APK into the app's cache and verifies its SHA-256 against
     * version.json, so a corrupted or swapped file is never handed to the installer.
     */
    suspend fun download(context: Context, update: Update, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val out = File(dir, "friday.apk")
            val conn = open("$BASE_URL/friday.apk?v=${update.code}")
            try {
                if (conn.responseCode != 200) throw IOException("friday.apk: HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (update.sha256.isNotEmpty() && !update.sha256.equals(hex, ignoreCase = true)) {
                    out.delete()
                    throw IOException("Download ka checksum match nahi hua")
                }
                out
            } finally {
                conn.disconnect()
            }
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for this app. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
        )
    }

    /** Hands the APK to the system installer; the user still confirms there. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun installedCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }
}
