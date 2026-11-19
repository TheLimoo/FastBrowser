package com.fastbrowser.gologin.browser

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object ProxyDownloader {

    suspend fun download(
        context: Context,
        url: String,
        userAgent: String,
        fileName: String,
        mimeType: String,
        localProxyPort: Int,
        profileCookieManager: CookieManager
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localProxyPort))
            val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                profileCookieManager.getCookie(url)?.let { cookie ->
                    setRequestProperty("Cookie", cookie)
                }
            }

            try {
                if (conn.responseCode !in 200..299) {
                    error("HTTP ${conn.responseCode} ${conn.responseMessage}")
                }
                conn.inputStream.use { input ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values
                        ) ?: error("Failed to create MediaStore entry")
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            input.copyTo(out)
                        } ?: error("Failed to open output stream")
                        fileName
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        if (!dir.exists()) dir.mkdirs()
                        val target = File(dir, fileName)
                        FileOutputStream(target).use { out -> input.copyTo(out) }
                        target.absolutePath
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
