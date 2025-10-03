/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.interfaces

/**
 * Callback interface for video compression progress updates
 */
interface VideoCompressionProgressCallback {
    /**
     * Called when video compression starts
     */
    fun onCompressionStarted()

    /**
     * Called periodically during compression to report progress
     * @param progress Compression progress as percentage (0-100)
     * @param currentFrame Current frame being processed
     * @param totalFrames Total number of frames (estimated)
     * @param originalSizeBytes Original file size in bytes
     * @param currentSizeBytes Current compressed size in bytes (estimation)
     */
    fun onProgressUpdate(
        progress: Int,
        currentFrame: Int,
        totalFrames: Int,
        originalSizeBytes: Long,
        currentSizeBytes: Long
    )

    /**
     * Called when compression is completed successfully
     * @param originalSizeBytes Original file size in bytes
     * @param compressedSizeBytes Final compressed file size in bytes
     * @param compressionRatio Compression ratio as percentage
     */
    fun onCompressionCompleted(originalSizeBytes: Long, compressedSizeBytes: Long, compressionRatio: Int)

    /**
     * Called when compression fails
     * @param error Error message describing the failure
     * @param exception Optional exception that caused the failure
     */
    fun onCompressionFailed(error: String, exception: Throwable? = null)
}
