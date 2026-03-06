package com.example.qwenchat.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

object ModelDownloader {
    private const val TAG = "ModelDownloader"
    const val MODEL_URL =
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q8_0.gguf"
    const val MODEL_FILENAME = "Qwen3.5-2B-Q8_0.gguf"

    suspend fun download(
        destDir: File,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val destFile = File(destDir, MODEL_FILENAME)
        val partFile = File(destDir, "$MODEL_FILENAME.part")

        if (destFile.exists()) {
            Log.i(TAG, "Model already downloaded")
            return@withContext destFile
        }

        if (!destDir.exists()) destDir.mkdirs()

        var downloaded = if (partFile.exists()) partFile.length() else 0L

        val url = URL(MODEL_URL)
        val conn = url.openConnection() as HttpURLConnection
        try {
            if (downloaded > 0) {
                conn.setRequestProperty("Range", "bytes=$downloaded-")
            }
            conn.connect()

            val responseCode = conn.responseCode
            val totalBytes: Long

            if (responseCode == 206) {
                // Partial content - resume
                totalBytes = downloaded + conn.contentLengthLong
            } else if (responseCode == 200) {
                // Full content - start from beginning
                downloaded = 0
                totalBytes = conn.contentLengthLong
            } else {
                throw Exception("HTTP $responseCode: ${conn.responseMessage}")
            }

            Log.i(TAG, "Downloading: $downloaded / $totalBytes bytes")

            val raf = RandomAccessFile(partFile, "rw")
            raf.seek(downloaded)

            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    raf.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress(DownloadProgress(downloaded, totalBytes))
                }
            }
            raf.close()

            // Rename .part to final filename
            partFile.renameTo(destFile)
            Log.i(TAG, "Download complete: ${destFile.absolutePath}")
            destFile
        } finally {
            conn.disconnect()
        }
    }
}
