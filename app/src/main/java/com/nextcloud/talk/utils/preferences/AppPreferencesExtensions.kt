/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.preferences

/**
 * Extension properties for AppPreferences to provide convenient access to image compression settings
 */
var AppPreferences.imageCompressionLevel: String
    get() = getImageCompressionLevel()
    set(value) = setImageCompressionLevel(value)

/**
 * Extension properties for AppPreferences to provide convenient access to video compression settings
 */
var AppPreferences.videoCompressionLevel: String
    get() = getVideoCompressionLevel()
    set(value) = setVideoCompressionLevel(value)
