package com.follow.clash.plugins

import android.app.DownloadManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloaderTest {
    @Test
    fun `active downloads are reused rather than enqueued twice`() {
        for (status in listOf(
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED,
        )) {
            assertTrue(canReuseUpdateDownload(status, false))
        }
    }

    @Test
    fun `failed cancelled or removed downloads can be retried`() {
        assertFalse(canReuseUpdateDownload(DownloadManager.STATUS_FAILED, false))
        assertFalse(canReuseUpdateDownload(null, false))
        assertFalse(canReuseUpdateDownload(DownloadManager.STATUS_SUCCESSFUL, false))
    }

    @Test
    fun `completed downloads are reused while their file exists`() {
        assertTrue(canReuseUpdateDownload(DownloadManager.STATUS_SUCCESSFUL, true))
    }
}
