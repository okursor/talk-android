/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 KO
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.Context
import com.nextcloud.talk.utils.preferences.AppPreferencesImpl

object SmartwatchModeHelper {
    /**
     * Get the effective font scale to use.
     * Returns the user's font scale preference if smartwatch mode is enabled,
     * otherwise returns 1.0f (no scaling, master behavior).
     */
    fun getEffectiveFontScale(context: Context): Float {
        return try {
            val prefs = AppPreferencesImpl(context)
            val smartwatchMode = prefs.getSmartwatchModeEnabled()
            if (smartwatchMode) {
                prefs.getFontScale()
            } else {
                1.0f // master behavior - no scaling
            }
        } catch (e: Exception) {
            1.0f // fallback to no scaling on error
        }
    }

    /**
     * Check if smartwatch mode is enabled.
     */
    fun isSmartwatchModeEnabled(context: Context): Boolean {
        return try {
            AppPreferencesImpl(context).getSmartwatchModeEnabled()
        } catch (e: Exception) {
            false
        }
    }
}
