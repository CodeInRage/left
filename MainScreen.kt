package com.save.me

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: Activity,
    onSetupClick: (() -> Unit)? = null,
    onRefreshClick: (() -> Unit)? = null,
    vm: MainViewModel = viewModel(),
    realPermissions: List<PermissionStatus>,
    requestPermission: (String) -> Unit,
    openAppSettings: () -> Unit,
    requestOverlayPermission: () -> Unit,
    requestAllFilesPermission: () -> Unit,
    requestBatteryPermission: () -> Unit,
    requestNotificationAccess: () -> Unit,
    permissionsUiRefresh: Int
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val nickname = vm.getCurrentNickname()
    val botToken = vm.getCurrentBotToken()
    val actionInProgress by vm.actionInProgress
    val actionError by vm.actionError

    var fcmStatus by remember { mutableStateOf(false) }
    var telegramStatus by remember { mutableStateOf(false) }

    // Device Admin
    val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = remember { ComponentName(context, DeviceAdminReceiver::class.java) }
    var deviceAdminEnabled by remember { mutableStateOf(devicePolicyManager.isAdminActive(adminComponent)) }

    // Refresh device admin status when returning to screen
    LaunchedEffect(permissionsUiRefresh) {
        deviceAdminEnabled = devicePolicyManager.isAdminActive(adminComponent)
    }
    LaunchedEffect(Unit) {
        deviceAdminEnabled = devicePolicyManager.isAdminActive(adminComponent)
    }

    // Connection status refresh logic
    val refreshConnectionStatuses = {
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    FirebaseMessaging.getInstance().token.await()
                }
                fcmStatus = token.isNotBlank()
            } catch (e: Exception) {
                fcmStatus = false
            }
            if (botToken.isNotBlank()) {
                val (ok, _) = checkTelegramBotApi(botToken)
                telegramStatus = ok
            } else {
                telegramStatus = false
            }
        }
    }

    LaunchedEffect(permissionsUiRefresh) {
        refreshConnectionStatuses()
    }
    LaunchedEffect(Unit) {
        refreshConnectionStatuses()
    }
    LaunchedEffect(actionError) {
        actionError?.let { errorMsg ->
            scope.launch {
                val res = snackbarHostState.showSnackbar(errorMsg, actionLabel = "Retry")
                if (res == SnackbarResult.ActionPerformed) {
                    actionInProgress?.type?.let { vm.startRemoteAction(it) }
                }
                vm.clearError()
            }
        }
    }

    var permissionsExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(0.dp)
    ) {
        // Device Administration Section
        Spacer(Modifier.height(20.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Device Administration",
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = deviceAdminEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                context.getString(R.string.device_admin_explanation)
                            )
                        }
                        context.startActivity(intent)
                    } else {
                        devicePolicyManager.removeActiveAdmin(adminComponent)
                        deviceAdminEnabled = false
                    }
                }
            )
        }
        Text(
            text = if (deviceAdminEnabled)
                stringResource(R.string.device_admin_enabled)
            else
                stringResource(R.string.device_admin_disabled),
            color = if (deviceAdminEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)
        )

        // Device Username Row (no edit button)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Username",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f)
            )
        }
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = nickname,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // Bot Token Row (no edit button)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Bot Token",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f)
            )
        }
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = botToken,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Connection Status:",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StatusDot(fcmStatus)
            Spacer(Modifier.width(8.dp))
            Text("FCM")
            Spacer(Modifier.width(8.dp))
            Text(": ${if (fcmStatus) "Connected" else "Not Connected"}")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StatusDot(telegramStatus)
            Spacer(Modifier.width(8.dp))
            Text("Telegram Bot API")
            Spacer(Modifier.width(8.dp))
            Text(": ${if (telegramStatus) "Connected" else "Not Connected"}")
        }
        Spacer(Modifier.height(20.dp))
        PermissionStatusExpandableTab(
            activity = activity,
            realPermissions = realPermissions,
            expanded = permissionsExpanded,
            onExpandToggle = { permissionsExpanded = !permissionsExpanded },
            requestPermission = requestPermission,
            openAppSettings = openAppSettings,
            requestOverlayPermission = requestOverlayPermission,
            requestAllFilesPermission = requestAllFilesPermission,
            requestBatteryPermission = requestBatteryPermission,
            requestNotificationAccess = requestNotificationAccess
        )
        Spacer(Modifier.height(20.dp))
        actionInProgress?.let { action ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                CircularProgressIndicator(
                    Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Running: ${action.type.replaceFirstChar { it.uppercase() }}",
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun PermissionStatusExpandableTab(
    activity: Activity,
    realPermissions: List<PermissionStatus>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    requestPermission: (String) -> Unit,
    openAppSettings: () -> Unit,
    requestOverlayPermission: () -> Unit,
    requestAllFilesPermission: () -> Unit,
    requestBatteryPermission: () -> Unit,
    requestNotificationAccess: () -> Unit
) {
    val sdkInt = Build.VERSION.SDK_INT

    // Storage permission labels
    val storageLabels = setOf("All Files Access", "Storage (Read)", "Storage (Write)")

    // Helper: Get permission status by label
    fun getStatus(label: String) = realPermissions.find { it.name == label }

    val permissionRows = mutableListOf<@Composable () -> Unit>()

    // --- Storage Section ---
    if (sdkInt >= Build.VERSION_CODES.R) {
        // Android 11+ (API 30+): ONLY show "All Files Access"
        getStatus("All Files Access")?.let { status ->
            permissionRows += {
                PermissionRow(
                    name = "All Files Access",
                    granted = status.granted,
                    onGrant = requestAllFilesPermission
                )
            }
        }
    } else {
        // Android 10 and below: combine Storage (Read & Write)
        val statusRead = getStatus("Storage (Read)")
        val statusWrite = getStatus("Storage (Write)")
        val readGranted = statusRead?.granted == true
        val writeGranted = statusWrite?.granted == true
        val granted = readGranted && writeGranted
        permissionRows += {
            PermissionRow(
                name = "Storage (Read & Write)",
                granted = granted,
                onGrant = {
                    val permRead = PermissionsAndOnboarding.getSystemPermissionFromLabel("Storage (Read)")
                    val permWrite = PermissionsAndOnboarding.getSystemPermissionFromLabel("Storage (Write)")
                    var requestedAny = false
                    if (!readGranted && permRead != null && PermissionsAndOnboarding.canRequestPermission(activity, permRead)) {
                        requestPermission(permRead)
                        requestedAny = true
                    }
                    if (!writeGranted && permWrite != null && PermissionsAndOnboarding.canRequestPermission(activity, permWrite)) {
                        requestPermission(permWrite)
                        requestedAny = true
                    }
                    if (!requestedAny) {
                        openAppSettings()
                    }
                }
            )
        }
    }

    // --- Non-storage permissions ---
    val filteredPermissions = realPermissions.filter { it.name !in storageLabels }

    // --- Add Call Log and Contacts permission rows ---
    fun addSpecialPermission(name: String) {
        getStatus(name)?.let { status ->
            permissionRows += {
                PermissionRow(
                    name = name,
                    granted = status.granted,
                    onGrant = {
                        val systemPermission = PermissionsAndOnboarding.getSystemPermissionFromLabel(name)
                        val canRequest = systemPermission != null && PermissionsAndOnboarding.canRequestPermission(activity, systemPermission)
                        if (canRequest && systemPermission != null) {
                            requestPermission(systemPermission)
                        } else {
                            openAppSettings()
                        }
                    }
                )
            }
        }
    }
    addSpecialPermission("Call Log")
    addSpecialPermission("Contacts")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandToggle() }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = if (expanded) R.drawable.ic_arrow_drop_down else R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                )
                Text(
                    "Permissions Status",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    permissionRows.forEach { it() }
                    filteredPermissions.filter { it.name != "Call Log" && it.name != "Contacts" }.forEach { status ->
                        PermissionRow(
                            name = status.name,
                            granted = status.granted,
                            onGrant = {
                                when (status.name) {
                                    "Overlay (Draw over apps)" -> requestOverlayPermission()
                                    "Ignore Battery Optimization" -> requestBatteryPermission()
                                    "Notification Access" -> requestNotificationAccess()
                                    else -> {
                                        val systemPermission = PermissionsAndOnboarding.getSystemPermissionFromLabel(status.name)
                                        val canRequest = systemPermission != null && PermissionsAndOnboarding.canRequestPermission(activity, systemPermission)
                                        if (canRequest && systemPermission != null) {
                                            requestPermission(systemPermission)
                                        } else {
                                            openAppSettings()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    name: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = if (granted) R.drawable.ic_check else R.drawable.ic_close),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(
                if (granted) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(name, modifier = Modifier.weight(1f))
        if (!granted) {
            TextButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
fun StatusDot(active: Boolean) {
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF4CAF50) else Color(0xFFF44336))
    )
}

suspend fun checkTelegramBotApi(token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("https://api.telegram.org/bot${token}/getMe")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        if (code == 200 && text.contains("\"ok\":true")) {
            return@withContext true to "OK"
        } else {
            return@withContext false to "API Error"
        }
    } catch (e: Exception) {
        return@withContext false to (e.localizedMessage ?: "Network error")
    }
}