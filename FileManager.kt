package com.save.me

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.*

object FileManager {
    private val TAG = "FileManager"

    private fun getRootDir(context: Context): File {
        val dir = Environment.getExternalStorageDirectory()
        Log.d(TAG, "getRootDir: Using root dir at ${dir.absolutePath}")
        return dir
    }

    fun getFileFromPath(context: Context, relativePath: String): File {
        val root = getRootDir(context)
        val file = File(root, relativePath)
        Log.d(TAG, "getFileFromPath: fullPath=${file.absolutePath} (relativePath=$relativePath)")
        return file
    }

    data class FileListEntry(
        val path: String,
        val isDir: Boolean,
        val size: Long = 0L,
        val lastModified: Long = 0L
    )

    /**
     * List only the directories and files (one level) in the given relative path, or in root if blank/null.
     * Returns list of FileListEntry (path, isDir, size, lastModified), sorted as requested.
     * @param sort name|size|date|type
     * @param order desc|asc
     */
    fun listFiles(
        context: Context,
        relativePath: String? = null,
        sort: String = "date",
        order: String = "desc"
    ): List<FileListEntry> {
        val root = getRootDir(context)
        val baseDir = if (relativePath.isNullOrBlank()) root else File(root, relativePath)
        val rootPath = root.absolutePath.trimEnd('/') + "/"
        Log.d(TAG, "listFiles: baseDir=${baseDir.absolutePath}, relativePath=$relativePath")
        if (!baseDir.exists() || !baseDir.isDirectory) {
            Log.d(TAG, "listFiles: Directory not found or not a directory: ${baseDir.absolutePath}")
            return emptyList()
        }
        val results = mutableListOf<FileListEntry>()
        baseDir.listFiles()?.forEach { file ->
            val rel = file.absolutePath.removePrefix(rootPath)
            results.add(
                FileListEntry(
                    path = if (file.isDirectory) "$rel/" else rel,
                    isDir = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified()
                )
            )
        }
        Log.d(TAG, "listFiles: totalEntries=${results.size}")

        val sorted = when (sort.lowercase(Locale.ROOT)) {
            "name" -> results.sortedWith(compareBy({ !it.isDir }, { it.path.lowercase(Locale.ROOT) }))
            "size" -> results.sortedWith(compareBy({ !it.isDir }, { it.size }))
            "date" -> results.sortedWith(compareBy({ !it.isDir }, { it.lastModified }))
            "type" -> results.sortedWith(compareBy({ !it.isDir }, { if (it.isDir) "" else it.path.substringAfterLast('.', "") }))
            else -> results.sortedWith(compareBy({ !it.isDir }, { -it.lastModified })) // Default: date desc
        }.let { list ->
            if (order == "asc") list else list.asReversed()
        }

        return sorted
    }

    fun deleteFile(context: Context, relativePath: String): Boolean {
        val file = getFileFromPath(context, relativePath)
        Log.d(TAG, "deleteFile: Attempting to delete ${file.absolutePath}")
        return try {
            val result = file.exists() && file.delete()
            Log.d(TAG, "deleteFile: Deleted=${result}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "deleteFile: Exception while deleting file: ${e}")
            false
        }
    }

    /**
     * Finds a unique filename in the given directory, adding (1), (2), ... as needed.
     */
    private fun getUniqueFile(dir: File, baseName: String): File {
        var name = baseName
        var file = File(dir, name)
        if (!file.exists()) return file

        val dotIndex = baseName.lastIndexOf('.')
        val namePart = if (dotIndex != -1) baseName.substring(0, dotIndex) else baseName
        val extPart = if (dotIndex != -1) baseName.substring(dotIndex) else ""

        var index = 1
        while (file.exists()) {
            name = "$namePart($index)$extPart"
            file = File(dir, name)
            index++
        }
        return file
    }

    /**
     * Save file from URL to the given directory, using a unique filename if needed.
     */
    suspend fun saveFileFromUrl(
        context: Context,
        url: String,
        fileName: String,
        targetDir: String? = null
    ): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val root = getRootDir(context)
        val saveDir = if (targetDir.isNullOrBlank()) root else File(root, targetDir)
        if (!saveDir.exists()) {
            val created = saveDir.mkdirs()
            Log.d(TAG, "saveFileFromUrl: Created targetDir=${saveDir.absolutePath}, success=$created")
        }
        // Find unique name
        val outFile = getUniqueFile(saveDir, fileName)
        Log.d(TAG, "saveFileFromUrl: Downloading from $url to ${outFile.absolutePath}")

        try {
            val client = OkHttpClient()
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful || resp.body == null) {
                Log.e(TAG, "saveFileFromUrl: Failed HTTP response: ${resp.code}")
                return@withContext false
            }
            val sink = FileOutputStream(outFile)
            sink.use { out ->
                resp.body!!.byteStream().copyTo(out)
            }
            Log.d(TAG, "saveFileFromUrl: Success saving file to ${outFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveFileFromUrl: Exception while saving file: $e")
            false
        }
    }

    fun formatMono(text: String): String = "`$text`"
}