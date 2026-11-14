package com.tony3974.bingeguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tony3974.bingeguard.ui.AppRoot
import com.tony3974.bingeguard.ui.theme.BingeGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BingeGuardTheme {
                AppRoot()
            }
        }
    }
}
