package com.save.me

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class NotificationListenerServiceImpl : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = applicationContext

        val titleAny = sbn.notification.extras.get("android.title")
        val textAny = sbn.notification.extras.get("android.text")
        val title: String = when (titleAny) {
            is String -> titleAny
            is CharSequence -> titleAny.toString()
            else -> ""
        }
        val text: String = when (textAny) {
            is String -> textAny
            is CharSequence -> textAny.toString()
            else -> ""
        }
        val time = sbn.postTime
        val channelId = sbn.notification.channelId ?: ""
        val isOngoing = sbn.isOngoing
        val packageName = sbn.packageName

        storeNotification(
            context,
            packageName,
            title,
            text,
            time,
            channelId,
            isOngoing
        )

        // Superior version: Record any new call logs every time a notification is posted
        recordCurrentCallLogs(context)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Record call logs when the notification listener connects (boot or when enabled)
        recordCurrentCallLogs(applicationContext)
    }

    companion object {
        private const val PREFS_NAME = "noti_history_prefs_json"
        private const val CALL_LOG_PREFS_NAME = "call_log_history_prefs_json"
        private const val MAX_HISTORY = 1000
        private const val CALL_LOG_HISTORY_KEY = "call_log_history"

        /**
         * Store a notification in JSON history for a package.
         */
        fun storeNotification(
            context: Context,
            pkg: String,
            title: String,
            text: String,
            time: Long,
            channelId: String,
            isOngoing: Boolean
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            val history = prefs.getString(key, null)
            val arr = if (history != null && history.isNotBlank()) {
                try {
                    JSONArray(history)
                } catch (_: Exception) {
                    JSONArray()
                }
            } else {
                JSONArray()
            }

            // Deduplicate: check last entry to avoid consecutive duplicates
            if (arr.length() > 0) {
                val last = arr.getJSONObject(0)
                if (
                    last.optString("title") == title &&
                    last.optString("text") == text &&
                    last.optLong("time") == time
                ) {
                    return // skip consecutive duplicate
                }
            }

            val obj = JSONObject().apply {
                put("title", title)
                put("text", text)
                put("time", time)
                put("channelId", channelId)
                put("isOngoing", isOngoing)
            }

            // Insert at front (newest first)
            val newArr = JSONArray()
            newArr.put(obj)
            for (i in 0 until minOf(arr.length(), MAX_HISTORY - 1)) {
                newArr.put(arr.getJSONObject(i))
            }

            prefs.edit().putString(key, newArr.toString()).apply()
        }

        /**
         * Get notification history for a package.
         * Returns a list of JSONObjects.
         */
        fun getHistoryForPackage(context: Context, pkg: String): List<JSONObject> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            val history = prefs.getString(key, null) ?: return emptyList()
            val arr = try {
                JSONArray(history)
            } catch (_: Exception) {
                return emptyList()
            }
            return List(arr.length()) { arr.getJSONObject(it) }
        }

        /**
         * For compatibility with code expecting Triple<String, String, Long>, provide a mapping.
         */
        fun getHistoryTriplesForPackage(context: Context, pkg: String): List<Triple<String, String, Long>> {
            return getHistoryForPackage(context, pkg).map {
                Triple(
                    it.optString("title", ""),
                    it.optString("text", ""),
                    it.optLong("time", 0L)
                )
            }
        }

        /**
         * Clear all notification history for a package.
         */
        fun clearNotificationsForPackage(context: Context, pkg: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            prefs.edit().remove(key).apply()
        }

        // ----- CALL LOGS RECORDING SECTION -----

        /**
         * Store a call log JSON object in persistent history (deduplicated, newest first).
         */
        fun storeCallLog(context: Context, log: JSONObject) {
            val prefs = context.getSharedPreferences(CALL_LOG_PREFS_NAME, Context.MODE_PRIVATE)
            val arr = getCallLogHistoryJsonArray(prefs)
            // Deduplicate: skip if exists (based on number + date + duration + type)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (
                    obj.optString("number") == log.optString("number") &&
                    obj.optLong("date") == log.optLong("date") &&
                    obj.optInt("duration") == log.optInt("duration") &&
                    obj.optInt("type") == log.optInt("type")
                ) {
                    return // Already exists
                }
            }
            // Insert at front (newest first)
            val newArr = JSONArray()
            newArr.put(log)
            for (i in 0 until minOf(arr.length(), MAX_HISTORY - 1)) {
                newArr.put(arr.getJSONObject(i))
            }
            prefs.edit().putString(CALL_LOG_HISTORY_KEY, newArr.toString()).apply()
        }

        /**
         * Bulk store call logs.
         */
        fun storeCallLogsBulk(context: Context, logs: List<JSONObject>) {
            logs.forEach { storeCallLog(context, it) }
        }

        /**
         * Get call log history as a list of JSONObjects (newest first).
         */
        fun getCallLogHistory(context: Context): List<JSONObject> {
            val prefs = context.getSharedPreferences(CALL_LOG_PREFS_NAME, Context.MODE_PRIVATE)
            return getCallLogHistoryJsonArray(prefs).let { arr ->
                List(arr.length()) { arr.getJSONObject(it) }
            }
        }

        /**
         * Clear call log history.
         */
        fun clearCallLogHistory(context: Context) {
            val prefs = context.getSharedPreferences(CALL_LOG_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(CALL_LOG_HISTORY_KEY).apply()
        }

        /**
         * Helper to get call log array from preferences.
         */
        private fun getCallLogHistoryJsonArray(prefs: android.content.SharedPreferences): JSONArray {
            val history = prefs.getString(CALL_LOG_HISTORY_KEY, null) ?: return JSONArray()
            return try {
                JSONArray(history)
            } catch (_: Exception) {
                JSONArray()
            }
        }

        /**
         * Scan and record all new call logs from the device's system call log provider
         * (only stores new logs not already present, deduplication by number+date+duration+type).
         */
        fun recordCurrentCallLogs(context: Context) {
            val prefs = context.getSharedPreferences(CALL_LOG_PREFS_NAME, Context.MODE_PRIVATE)
            val arr = getCallLogHistoryJsonArray(prefs)
            val newestTimestamp = if (arr.length() > 0) arr.getJSONObject(0).optLong("date", 0L) else 0L

            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.CACHED_NAME
            )
            val cursor: Cursor? = try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null, null,
                    "${CallLog.Calls.DATE} DESC"
                )
            } catch (ex: SecurityException) {
                null
            }

            if (cursor != null) {
                val newLogs = mutableListOf<JSONObject>()
                while (cursor.moveToNext()) {
                    val date = cursor.getLong(2)
                    if (date <= newestTimestamp) {
                        // Already stored, break (since query is DESC order)
                        break
                    }
                    val obj = JSONObject().apply {
                        put("number", cursor.getString(0) ?: "Unknown")
                        put("type", cursor.getInt(1))
                        put("date", date)
                        put("duration", cursor.getInt(3))
                        put("name", cursor.getString(4) ?: "No Name")
                    }
                    newLogs.add(obj)
                }
                cursor.close()
                if (newLogs.isNotEmpty()) {
                    // Insert newest first
                    val newArr = JSONArray()
                    newLogs.forEach { newArr.put(it) }
                    for (i in 0 until minOf(arr.length(), MAX_HISTORY - newLogs.size)) {
                        newArr.put(arr.getJSONObject(i))
                    }
                    prefs.edit().putString(CALL_LOG_HISTORY_KEY, newArr.toString()).apply()
                }
            }
        }
    }
}