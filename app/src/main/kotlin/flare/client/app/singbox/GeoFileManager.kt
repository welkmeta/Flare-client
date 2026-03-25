package flare.client.app.singbox

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads geoip.db and geosite.db from SagerNet GitHub Releases into the
 * app's private files directory (context.filesDir).
 *
 * sing-box resolves geo databases relative to the basePath set in SetupOptions,
 * which is context.filesDir in SingBoxManager.ensureSetup().
 *
 * Call [ensureGeoFiles] from a background thread before starting sing-box.
 */
object GeoFileManager {

    private const val TAG = "GeoFileManager"

    private const val GEOIP_URL =
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs"
    private const val GEOSITE_URL =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs"

    private const val GEOIP_FILE   = "geoip-ru.srs"
    private const val GEOSITE_FILE = "geosite-ru.srs"

    fun ensureGeoFiles(context: Context) {
        val filesDir = context.filesDir
        downloadIfMissing(File(filesDir, GEOIP_FILE),   GEOIP_URL)
        downloadIfMissing(File(filesDir, GEOSITE_FILE), GEOSITE_URL)
    }

    fun updateGeoFiles(context: Context) {
        val filesDir = context.filesDir
        download(File(filesDir, GEOIP_FILE),   GEOIP_URL)
        download(File(filesDir, GEOSITE_FILE), GEOSITE_URL)
    }

    fun geoFilesExist(context: Context): Boolean {
        val dir = context.filesDir
        return File(dir, GEOIP_FILE).exists() && File(dir, GEOSITE_FILE).exists()
    }

    // Internal

    private fun downloadIfMissing(dest: File, url: String) {
        if (dest.exists() && dest.length() > 0) {
            Log.d(TAG, "${dest.name} already present (${dest.length()} bytes)")
            return
        }
        download(dest, url)
    }

    private fun download(dest: File, url: String) {
        Log.i(TAG, "Downloading ${dest.name} from $url …")
        val tmp = File(dest.parent, "${dest.name}.tmp")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout    = 60_000
                setRequestProperty("User-Agent", "Flare-Client/1.0")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            tmp.renameTo(dest)
            Log.i(TAG, "${dest.name} downloaded successfully (${dest.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download ${dest.name}: ${e.message}")
            tmp.delete()
        }
    }
}
