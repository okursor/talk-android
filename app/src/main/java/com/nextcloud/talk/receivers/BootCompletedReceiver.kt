/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nextcloud.talk.services.PersistentConnectionService
import com.nextcloud.talk.utils.NotificationUtils

/**
 * Receiver for boot completed and package replaced events.
 * Starts the persistent connection service to ensure reliable call notifications.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == null) {
            return
        }

        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Boot completed - starting persistent connection service")
                handleBootCompleted(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Package replaced - restarting services")
                handlePackageReplaced(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        // Start persistent connection service
        PersistentConnectionService.startService(context)
    }

    private fun handlePackageReplaced(context: Context) {
        // Remove old notification channels
        NotificationUtils.removeOldNotificationChannels(context)
        
        // Restart persistent connection service
        PersistentConnectionService.startService(context)
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
