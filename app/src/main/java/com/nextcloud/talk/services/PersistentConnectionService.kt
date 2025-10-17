/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.jobs.PushRegistrationWorker
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.preferences.AppPreferences
import javax.inject.Inject

/**
 * Persistent foreground service to keep the app alive for reliable call notifications.
 * This service ensures that incoming calls are properly received even when the app is not in the background.
 */
@AutoInjector(NextcloudTalkApplication::class)
class PersistentConnectionService : Service() {

    @Inject
    lateinit var appPreferences: AppPreferences

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceHandler = Handler(Looper.getMainLooper())

    // Heartbeat to keep connection alive
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Heartbeat ping - keeping service alive")
            
            // Refresh push token periodically
            refreshPushToken()
            
            // Schedule next heartbeat (every 15 minutes)
            serviceHandler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
        Log.d(TAG, "PersistentConnectionService created")
        
        // Acquire partial wake lock to keep service alive
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NextcloudTalk:PersistentConnectionWakeLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PersistentConnectionService starting")
        
        // Create notification channel if needed
        createNotificationChannel()
        
        // Create foreground notification
        val notification = buildForegroundNotification()
        
        // Start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Acquire wake lock with timeout
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(WAKELOCK_TIMEOUT)
        }
        
        // Start heartbeat
        serviceHandler.removeCallbacks(heartbeatRunnable)
        serviceHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL)
        
        // Return START_STICKY to automatically restart if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "PersistentConnectionService destroyed")
        
        // Release wake lock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        // Remove heartbeat callbacks
        serviceHandler.removeCallbacks(heartbeatRunnable)
        
        super.onDestroy()
        
        // Attempt to restart the service
        restartService()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = CHANNEL_ID
            val channelName = getString(R.string.nc_persistent_connection_channel_name)
            val channelDescription = getString(R.string.nc_persistent_connection_channel_description)
            
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = channelDescription
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            pendingIntentFlags
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.nc_persistent_connection_notification_title))
            .setContentText(getString(R.string.nc_persistent_connection_notification_text))
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun refreshPushToken() {
        // This will be handled by the WorkManager
        Log.d(TAG, "Push token refresh will be handled by WorkManager")
    }

    private fun restartService() {
        Log.d(TAG, "Attempting to restart service")
        val intent = Intent(applicationContext, PersistentConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    companion object {
        private const val TAG = "PersistentConnectionService"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "persistent_connection_channel"
        private const val HEARTBEAT_INTERVAL = 15 * 60 * 1000L // 15 minutes
        private const val WAKELOCK_TIMEOUT = 60 * 60 * 1000L // 1 hour

        /**
         * Start the persistent connection service
         */
        fun startService(context: Context) {
            val intent = Intent(context, PersistentConnectionService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stop the persistent connection service
         */
        fun stopService(context: Context) {
            val intent = Intent(context, PersistentConnectionService::class.java)
            context.stopService(intent)
        }

        /**
         * Check if the service is running
         */
        fun isServiceRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (PersistentConnectionService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }
}
