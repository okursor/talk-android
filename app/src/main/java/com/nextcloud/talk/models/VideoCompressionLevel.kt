/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.models

/**
 * Enum representing different levels of video compression for file uploads.
 * Each level defines specific bitrate, resolution, and frame rate parameters.
 */
enum class VideoCompressionLevel(
    val key: String,
    // Video bitrate in kbps
    val videoBitrate: Int,
    // Audio bitrate in kbps
    val audioBitrate: Int,
    // Maximum width in pixels
    val maxWidth: Int,
    // Maximum height in pixels
    val maxHeight: Int,
    // Target frame rate
    val frameRate: Int
) {
    /**
     * No compression - original video is uploaded
     */
    NONE("none", Int.MAX_VALUE, 320, Int.MAX_VALUE, Int.MAX_VALUE, 60),

    /**
     * Light compression - high quality with moderate size reduction
     * Suitable for high-quality sharing while reducing file size
     */
    LIGHT("light", 2000, 128, 1280, 720, 30),

    /**
     * Medium compression - good balance between quality and file size
     * Similar to WhatsApp compression level
     */
    MEDIUM("medium", 1000, 96, 854, 480, 30),

    /**
     * Strong compression - aggressive compression for quick sharing
     * Best for data-conscious scenarios and quick uploads
     */
    STRONG("strong", 500, 64, 640, 360, 24);

    companion object {
        /**
         * Returns the compression level from its key string
         * @param key The key string of the compression level
         * @return The matching VideoCompressionLevel or NONE if not found
         */
        fun fromKey(key: String): VideoCompressionLevel = values().find { it.key == key } ?: NONE

        /**
         * Returns the default compression level
         */
        fun getDefault(): VideoCompressionLevel = NONE
    }

    /**
     * Returns true if compression should be applied (i.e., not NONE)
     */
    fun shouldCompress(): Boolean = this != NONE

    /**
     * Returns a human-readable description of the compression settings
     */
    fun getDescription(): String =
        when (this) {
            NONE -> "Original quality"
            LIGHT -> "HD 720p, ${videoBitrate}kbps"
            MEDIUM -> "SD 480p, ${videoBitrate}kbps"
            STRONG -> "Basic 360p, ${videoBitrate}kbps"
        }
}
