package com.fastbrowser.gologin

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.fastbrowser.gologin.data.LegacyCryptoManager
import com.fastbrowser.gologin.data.legacyDataStore
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native one-off jobs behind the `fastbrowser/proxy` and
 * `fastbrowser/native` channels:
 *
 * - bindProxy: the AndroidX ProxyController override (Flutter has no API).
 *   Fail-closed: when PROXY_OVERRIDE is unsupported and the kill-switch is
 *   on, the result is 'unsupported' and Dart shows the blocked screen.
 * - readLegacyStore: one-time upgrade from the v1.0.7 Kotlin app — reads
 *   its DataStore (fastbrowser_settings) and decrypts stored passwords
 *   with its Tink keyset (server id as AAD), returning one JSON document
 *   with plaintext passwords. Dart re-encrypts them with its own key.
 * - saveToDownloads / openDownload / deleteDownload: public Downloads via
 *   MediaStore (the system downloader is never used — it leaks the IP).
 * - deleteProfileData / clearAllWebData: per-profile and global wipes.
 *   v2 change: a per-profile wipe touches ONLY that profile's store
 *   (v1.0.7 also cleared the global cookie jar and WebStorage).
 */
class NativeChannels(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directExecutor = Executor { runnable -> runnable.run() }

    fun handleProxy(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "bindProxy") {
            result.notImplemented()
            return
        }
        val port = call.argument<Int>("port") ?: 0
        val killSwitch = call.argument<Boolean>("killSwitch") ?: true
        if (port == 0) {
            result.success("unsupported")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            result.success(if (killSwitch) "unsupported" else "direct")
            return
        }
        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:$port")
            .build()
        ProxyController.getInstance().setProxyOverride(proxyConfig, directExecutor) {
            result.success("applied")
        }
    }

    fun handleNative(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "readLegacyStore" -> scope.launch {
                result.success(readLegacyStore())
            }
            "saveToDownloads" -> scope.launch {
                val tempPath = call.argument<String>("tempPath") ?: ""
                val fileName = call.argument<String>("fileName") ?: "download"
                val mimeType = call.argument<String>("mimeType")
                    ?: "application/octet-stream"
                result.success(saveToDownloads(tempPath, fileName, mimeType))
            }
            "openDownload" -> {
                val fileName = call.argument<String>("fileName") ?: ""
                result.success(openDownload(fileName))
            }
            "deleteDownload" -> {
                val fileName = call.argument<String>("fileName") ?: ""
                result.success(deleteDownload(fileName))
            }
            "deleteProfileData" -> scope.launch {
                val profileId = call.argument<String>("profileId") ?: ""
                deleteProfileData(profileId)
                result.success(null)
            }
            "clearAllWebData" -> scope.launch {
                @Suppress("UNCHECKED_CAST")
                val profileIds =
                    (call.argument<List<*>>("profileIds") ?: emptyList<String>())
                        .mapNotNull { it as? String }
                clearAllWebData(profileIds)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    // ----- legacy migration -------------------------------------------------

    private val serversKey = stringPreferencesKey("servers_json")
    private val profilesKey = stringPreferencesKey("profiles_json")
    private val settingsKey = stringPreferencesKey("settings_json")
    private val recentUrlsKey = stringPreferencesKey("recent_urls_json")
    private val bookmarksKey = stringPreferencesKey("bookmarks_json")

    private suspend fun readLegacyStore(): String? {
        return try {
            val prefs = context.legacyDataStore.data.first()
            val serversRaw = prefs[serversKey] ?: return null
            val crypto = LegacyCryptoManager(context)
            val servers = JSONArray(serversRaw)
            for (i in 0 until servers.length()) {
                val node = servers.getJSONObject(i)
                val stored = node.optString("password", "")
                if (stored.isNotEmpty()) {
                    val id = node.optString("id", "")
                    val plain = try {
                        crypto.decrypt(stored, id.toByteArray(Charsets.UTF_8))
                    } catch (_: Exception) {
                        ""
                    }
                    node.put("password", plain)
                }
            }
            JSONObject().apply {
                put("servers", servers)
                put(
                    "profiles",
                    prefs[profilesKey]?.let { JSONArray(it) } ?: JSONArray(),
                )
                put(
                    "settings",
                    prefs[settingsKey]?.let { JSONObject(it) } ?: JSONObject(),
                )
                put(
                    "recentUrls",
                    prefs[recentUrlsKey]?.let { JSONArray(it) } ?: JSONArray(),
                )
                put(
                    "bookmarks",
                    prefs[bookmarksKey]?.let { JSONArray(it) } ?: JSONArray(),
                )
            }.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ----- downloads (MediaStore) --------------------------------------------

    private fun saveToDownloads(
        tempPath: String,
        fileName: String,
        mimeType: String,
    ): String {
        val tempFile = File(tempPath)
        if (!tempFile.exists()) return fileName
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return fileName
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input -> input.copyTo(out) }
            }
        } catch (_: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            return fileName
        }
        // MediaStore appends " (1)" on a name collision — return the real name.
        return try {
            resolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0) ?: fileName
                } else fileName
            } ?: fileName
        } catch (_: Exception) {
            fileName
        }
    }

    private fun findDownloadUri(fileName: String): Uri? {
        val resolver = context.contentResolver
        return try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString(),
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openDownload(fileName: String): Boolean {
        val uri = findDownloadUri(fileName) ?: return false
        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        } ?: "*/*"
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteDownload(fileName: String): Boolean {
        val uri = findDownloadUri(fileName) ?: return false
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    // ----- profile / global wipes --------------------------------------------

    private fun deleteProfileData(profileId: String) {
        if (profileId.isEmpty()) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().getProfile(profileId)
                    ?.cookieManager?.removeAllCookies(null)
            } catch (_: Exception) {
            }
            try {
                ProfileStore.getInstance().deleteProfile(profileId)
            } catch (_: Exception) {
            }
        }
    }

    private fun clearAllWebData(profileIds: List<String>) {
        for (id in profileIds) deleteProfileData(id)
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) {
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }
    }
}
