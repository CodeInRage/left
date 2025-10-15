package com.save.me

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionAndMainScreen(
    activity: Activity,
    permissionsUiRefresh: Int,
    onSetupClick: () -> Unit,
    vm: MainViewModel,
    requestPermission: (String) -> Unit,
    openAppSettings: () -> Unit,
    requestOverlayPermission: () -> Unit,
    requestAllFilesPermission: () -> Unit,
    requestBatteryPermission: () -> Unit,
    forceSetupScreen: Boolean = false
) {
    val context = LocalContext.current
    var permissionStatuses by remember { mutableStateOf(PermissionsAndOnboarding.getAllPermissionStatuses(context)) }
    var refreshKey by remember { mutableStateOf(0) }
    var showSetupScreen by remember { mutableStateOf(forceSetupScreen) }

    val handleRefresh = {
        permissionStatuses = PermissionsAndOnboarding.getAllPermissionStatuses(context)
        refreshKey += 1
    }

    LaunchedEffect(permissionsUiRefresh) {
        handleRefresh()
    }

    if (showSetupScreen) {
        if (forceSetupScreen) {
            SetupScreen(
                onSetupComplete = { showSetupScreen = false },
                showTitle = true
            )
        } else {
            SetupScreenWithBack(
                onSetupComplete = { showSetupScreen = false }
            )
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("LostCodex") },
                    actions = {
                        var refreshAnimating by remember { mutableStateOf(false) }
                        var gearAnimating by remember { mutableStateOf(false) }
                        AnimatedRotateIconButton(
                            iconRes = R.drawable.ic_refresh,
                            contentDescription = "Refresh Status",
                            isRotating = refreshAnimating,
                            onClick = {
                                refreshAnimating = true
                                handleRefresh()
                            },
                            onAnimationEnd = { refreshAnimating = false }
                        )
                        AnimatedRotateIconButton(
                            iconRes = R.drawable.ic_settings,
                            contentDescription = "Setup Device",
                            isRotating = gearAnimating,
                            onClick = {
                                gearAnimating = true
                                showSetupScreen = true
                            },
                            onAnimationEnd = { gearAnimating = false }
                        )
                    }
                )
            }
        ) { innerPadding ->
            Column(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                MainScreen(
                    activity = activity,
                    onSetupClick = null,
                    onRefreshClick = handleRefresh,
                    vm = vm,
                    realPermissions = permissionStatuses,
                    requestPermission = requestPermission,
                    openAppSettings = openAppSettings,
                    requestOverlayPermission = requestOverlayPermission,
                    requestAllFilesPermission = requestAllFilesPermission,
                    requestBatteryPermission = requestBatteryPermission,
                    requestNotificationAccess = {
                        PermissionsAndOnboarding.launchNotificationAccess(activity)
                    },
                    permissionsUiRefresh = refreshKey
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreenWithBack(
    onSetupComplete: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Device") },
                navigationIcon = {
                    IconButton(onClick = onSetupComplete) {
                        // Tint the back arrow icon with the theme color
                        Image(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            SetupScreen(onSetupComplete = onSetupComplete, showTitle = false)
        }
    }
}

@Composable
fun AnimatedRotateIconButton(
    iconRes: Int,
    contentDescription: String,
    isRotating: Boolean,
    onClick: () -> Unit,
    onAnimationEnd: () -> Unit
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isRotating) {
        if (isRotating) {
            rotation.snapTo(0f)
            rotation.animateTo(
                360f,
                animationSpec = tween(600)
            )
            onAnimationEnd()
        }
    }
    IconButton(
        onClick = {
            if (!isRotating) onClick()
        }
    ) {
        // Tint the icon with the theme color
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    rotationZ = rotation.value
                },
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
        )
    }
}