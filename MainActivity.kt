package com.save.me

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.messaging.FirebaseMessaging
import com.save.me.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var permissionsUiRefresh by mutableStateOf(0)
    private var showSetup by mutableStateOf(false)

    // Launchers for special permissions/settings
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionsUiRefresh++ }
    private val allFilesPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionsUiRefresh++ }
    private val batteryPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionsUiRefresh++ }

    // Launcher for a single dangerous permission
    private val singlePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionsUiRefresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Subscribe to FCM topic "all" and log FCM token for debugging
        FirebaseMessaging.getInstance().subscribeToTopic("all")
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("FCM", "Subscribed to 'all' topic")
                } else {
                    Log.e("FCM", "Failed to subscribe to 'all' topic", task.exception)
                }
            }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d("FCM", "Device FCM token: ${task.result}")
            } else {
                Log.e("FCM", "Failed to get FCM token", task.exception)
            }
        }

        showSetup = shouldShowSetup(this)
        setContent {
            AppTheme {
                val vm: MainViewModel = viewModel()
                val context = LocalContext.current

                // Force recomposition on permission change
                var localPermissionsUiRefresh by remember { mutableStateOf(0) }
                LaunchedEffect(permissionsUiRefresh) { localPermissionsUiRefresh++ }

                if (showSetup) {
                    SetupScreen(
                        onSetupComplete = {
                            showSetup = false
                            permissionsUiRefresh++
                        }
                    )
                } else {
                    PermissionAndMainScreen(
                        activity = this@MainActivity,
                        permissionsUiRefresh = permissionsUiRefresh,
                        onSetupClick = { showSetup = true },
                        vm = vm,
                        requestPermission = { permission ->
                            // Only block storage permissions on Android 11+ (handled by All Files Access intent)
                            val isStorageRead = permission == android.Manifest.permission.READ_EXTERNAL_STORAGE
                            val isStorageWrite = permission == android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (isStorageRead || isStorageWrite)) {
                                // Ignore, All Files Access is handled by a different intent
                            } else {
                                singlePermissionLauncher.launch(permission)
                            }
                        },
                        openAppSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        },
                        requestOverlayPermission = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            overlayPermissionLauncher.launch(intent)
                        },
                        requestAllFilesPermission = {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            allFilesPermissionLauncher.launch(intent)
                        },
                        requestBatteryPermission = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            batteryPermissionLauncher.launch(intent)
                        }
                    )
                }
            }
        }
    }

    private fun shouldShowSetup(context: Context): Boolean {
        val token = Preferences.getBotToken(context)
        val nickname = Preferences.getNickname(context)
        return token.isNullOrBlank() || nickname.isNullOrBlank()
    }
}