package com.tony3974.bingeguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tony3974.bingeguard.permissions.PermissionStatus
import com.tony3974.bingeguard.ui.onboarding.OnboardingScreen

/** Screens, as strings so the back stack survives rotation and process death. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
}

@OptIn(ExperimentalMaterial3Api::class)
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

    when (stack.last()) {
        Routes.ONBOARDING -> OnboardingScreen(onDone = { pop() })
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Binge Guard") },
                    actions = { TextButton(onClick = { push(Routes.ONBOARDING) }) { Text("Permissions") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tracking comes in the next phase.")
            }
        }
    }
}
