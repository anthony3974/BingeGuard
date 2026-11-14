package com.tony3974.bingeguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tony3974.bingeguard.data.AppDatabase

class BingeGuardApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The ongoing notification while Binge Guard watches your apps"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGE, "Session nudges", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A heads-up when a session is about to hit its cap"
            },
        )
    }

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_NUDGE = "nudge"
    }
}

val Context.db: AppDatabase get() = (applicationContext as BingeGuardApp).db
