/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.call

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Helper class to control the volume of STREAM_VOICE_CALL.
 * This is useful for smartwatch devices that lack hardware volume buttons.
 */
class CallVolumeController(context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val stream = AudioManager.STREAM_VOICE_CALL

    companion object {
        private const val TAG = "CallVolumeController"
    }

    /**
     * Get the maximum volume level for the voice call stream.
     */
    fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(stream)

    /**
     * Get the current volume level for the voice call stream.
     */
    fun getCurrentVolume(): Int = audioManager.getStreamVolume(stream)

    /**
     * Get the current volume as a percentage (0-100).
     */
    fun getVolumePercent(): Int =
        if (getMaxVolume() > 0) (getCurrentVolume() * 100) / getMaxVolume() else 0

    /**
     * Set the volume to a specific level (0 to maxVolume).
     * Returns true if successful, false if permission missing or other error.
     */
    fun setVolume(level: Int): Boolean {
        return try {
            val clamped = max(0, min(level, getMaxVolume()))
            audioManager.setStreamVolume(stream, clamped, 0)
            Log.d(TAG, "Volume set to $clamped / ${getMaxVolume()}")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing MODIFY_AUDIO_SETTINGS permission", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            false
        }
    }

    /**
     * Adjust the volume by a delta value (positive to increase, negative to decrease).
     */
    fun adjustVolume(delta: Int): Boolean = setVolume(getCurrentVolume() + delta)
}
