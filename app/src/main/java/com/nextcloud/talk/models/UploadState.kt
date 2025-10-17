/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 NextcloudTalk Contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.models

/**
 * Represents the state of file upload with video compression
 */
enum class UploadState {
    /** Upload process hasn't started yet */
    NONE,
    
    /** Currently compressing/transcoding video */
    TRANSCODING,
    
    /** Currently uploading the file to server */
    UPLOADING,
    
    /** Upload completed successfully */
    COMPLETED,
    
    /** Upload failed (compression or upload error) */
    FAILED,
    
    /** Upload was cancelled by user */
    CANCELLED
}