package com.tony3974.bingeguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tony3974.bingeguard.monitor.MonitorService
import com.tony3974.bingeguard.permissions.PermissionStatus
import com.tony3974.bingeguard.ui.history.HistoryScreen
import com.tony3974.bingeguard.ui.home.HomeScreen
import com.tony3974.bingeguard.ui.onboarding.OnboardingScreen
import com.tony3974.bingeguard.ui.picker.PickerScreen
import com.tony3974.bingeguard.ui.settings.AppSettingsScreen

/** Screens, as strings so the back stack survives rotation and process death. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val PICKER = "picker"
    const val HISTORY = "history"
    const val APP_PREFIX = "app/"
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var stack by rememberSaveable {
        mutableStateOf(
            if (PermissionStatus.read(context).allGranted) listOf(Routes.HOME) else listOf(Routes.HOME, Routes.ONBOARDING),
        )
    }
    fun push(route: String) { stack = stack + route }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    // Start (or keep) the monitor whenever the app is opened with the permissions it needs.
    LifecycleResumeEffect(Unit) {
        if (PermissionStatus.read(context).canTrack) MonitorService.start(context)
        onPauseOrDispose { }
    }

    val route = stack.last()
    when {
        route == Routes.ONBOARDING -> OnboardingScreen(onDone = { pop() })
        route == Routes.PICKER -> PickerScreen(onBack = { pop() })
        route == Routes.HISTORY -> HistoryScreen(onBack = { pop() })
        route.startsWith(Routes.APP_PREFIX) ->
            AppSettingsScreen(packageName = route.removePrefix(Routes.APP_PREFIX), onBack = { pop() })
        else -> HomeScreen(
            onAddApps = { push(Routes.PICKER) },
            onOpenApp = { push(Routes.APP_PREFIX + it) },
            onHistory = { push(Routes.HISTORY) },
            onPermissions = { push(Routes.ONBOARDING) },
        )
    }
}
