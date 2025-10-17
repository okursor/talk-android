/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nextcloud.talk.services.PersistentConnectionService

/**
 * Worker that checks if the persistent connection service is running
 * and restarts it if necessary.
 */
class ServiceRestartWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        Log.d(TAG, "Checking persistent connection service status")

        if (!PersistentConnectionService.isServiceRunning(applicationContext)) {
            Log.d(TAG, "Service not running - restarting")
            PersistentConnectionService.startService(applicationContext)
        } else {
            Log.d(TAG, "Service is running")
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "ServiceRestartWorker"
    }
}
