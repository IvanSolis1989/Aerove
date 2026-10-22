package com.follow.clash.plugins

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri

internal class UpdateDownloader(private val context: Context) {
    private val preferences = context.getSharedPreferences("update_download", Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(url: String, fileName: String): Long {
        val uri = url.toUri()
        require(uri.scheme == "https" && uri.host == "github.com")
        require(uri.path.orEmpty().contains("/releases/download/"))
        require(fileName.endsWith(".apk") && !fileName.contains('/') && !fileName.contains('\\'))
        require(uri.lastPathSegment == fileName)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val previousId = preferences.getLong("id", -1)
        if (preferences.getString("url", null) == url && previousId > 0) {
            manager.query(DownloadManager.Query().setFilterById(previousId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val fileAvailable = status == DownloadManager.STATUS_SUCCESSFUL && runCatching {
                        manager.openDownloadedFile(previousId)?.use { true } ?: false
                    }.getOrDefault(false)
                    if (canReuseUpdateDownload(status, fileAvailable)) return previousId
                }
            }
        }
        val destination = "${System.currentTimeMillis()}-$fileName"
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destination)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, destination)
        }
        val id = manager.enqueue(request)
        check(id > 0)
        preferences.edit().putString("url", url).putLong("id", id).apply()
        return id
    }
}

internal fun canReuseUpdateDownload(status: Int?, fileAvailable: Boolean): Boolean = when (status) {
    DownloadManager.STATUS_PENDING,
    DownloadManager.STATUS_RUNNING,
    DownloadManager.STATUS_PAUSED -> true
    DownloadManager.STATUS_SUCCESSFUL -> fileAvailable
    else -> false
}
