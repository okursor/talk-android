/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 NextcloudTalk Contributors  
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.upload

import android.os.Handler
import android.os.Looper
import com.nextcloud.talk.adapters.messages.TalkMessagesListAdapter
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.interfaces.VideoCompressionProgressCallback
import com.nextcloud.talk.models.UploadState

/**
 * Callback implementation that updates chat UI in real-time during video compression
 */
class ChatVideoCompressionCallback(
    private val messageId: String,
    private val adapter: TalkMessagesListAdapter<ChatMessage>,
    private val onUploadStart: ((String) -> Unit)? = null
) : VideoCompressionProgressCallback {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCompressionStarted() {
        mainHandler.post {
            updateMessage { message ->
                message.copy(
                    uploadState = UploadState.TRANSCODING,
                    transcodeProgress = 0,
                    compressionStartTime = System.currentTimeMillis()
                )
            }
        }
    }

    override fun onProgressUpdate(
        progress: Int,
        processedFrames: Int,
        totalFrames: Int,
        originalSize: Long,
        currentCompressedSize: Long
    ) {
        mainHandler.post {
            updateMessage { message ->
                val compressionRatio = if (originalSize > 0 && currentCompressedSize > 0) {
                    ((originalSize - currentCompressedSize) * 100 / originalSize).toInt()
                } else {
                    0
                }
                
                message.copy(
                    transcodeProgress = progress,
                    processedFrames = processedFrames,
                    totalFrames = totalFrames,
                    originalFileSize = if (message.originalFileSize == 0L) originalSize else message.originalFileSize,
                    currentCompressedSize = currentCompressedSize,
                    compressionRatio = compressionRatio
                )
            }
        }
    }

    override fun onCompressionCompleted(
        originalSize: Long,
        compressedSize: Long,
        compressionRatio: Int
    ) {
        mainHandler.post {
            updateMessage { message ->
                message.copy(
                    uploadState = UploadState.UPLOADING,
                    transcodeProgress = 100,
                    finalCompressedSize = compressedSize,
                    compressionRatio = compressionRatio,
                    uploadProgress = 0
                )
            }
            
            // Trigger upload start callback if provided
            onUploadStart?.invoke("file://${System.currentTimeMillis()}.mp4")
        }
    }

    override fun onCompressionFailed(errorMessage: String, throwable: Throwable?) {
        mainHandler.post {
            updateMessage { message ->
                message.copy(
                    uploadState = UploadState.FAILED,
                    uploadErrorMessage = errorMessage
                )
            }
        }
    }

    /**
     * Update message in adapter using transformation function
     */
    private fun updateMessage(transform: (ChatMessage) -> ChatMessage) {
        val items = adapter.items ?: return
        
        // Find message by ID
        val messageIndex = items.indexOfFirst { wrapper ->
            wrapper.item is ChatMessage && 
            (wrapper.item as ChatMessage).jsonMessageId.toString() == messageId
        }
        
        if (messageIndex != -1) {
            val wrapper = items[messageIndex]
            if (wrapper.item is ChatMessage) {
                val oldMessage = wrapper.item as ChatMessage
                val newMessage = transform(oldMessage)
                
                // Update the message in place
                wrapper.item = newMessage
                
                // Notify adapter with payload for efficient updates
                adapter.notifyItemChanged(messageIndex, "progress_update")
            }
        }
    }
}