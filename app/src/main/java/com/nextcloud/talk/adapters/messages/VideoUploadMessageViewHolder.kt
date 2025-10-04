/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 NextcloudTalk Contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.adapters.messages

import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.models.UploadState
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.utils.ThumbnailCache
import com.stfalcon.chatkit.messages.MessageHolders

class VideoUploadMessageViewHolder(
    itemView: View
) : MessageHolders.OutcomingTextMessageViewHolder<ChatMessage>(itemView) {

    companion object {
        private const val TAG = "VideoUploadMessageViewHolder"
    }

    private val thumbnailView: ImageView = itemView.findViewById(R.id.videoThumbnail)
    private val progressOverlay: View = itemView.findViewById(R.id.progressOverlay)
    private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
    private val statusText: TextView = itemView.findViewById(R.id.statusText)
    private val fileSizeText: TextView = itemView.findViewById(R.id.fileSizeText)
    private val cancelButton: ImageButton = itemView.findViewById(R.id.cancelButton)
    private val timeText: TextView = itemView.findViewById(R.id.messageTime)
    private val authorText: TextView = itemView.findViewById(R.id.messageAuthor)
    private val messageText: TextView = itemView.findViewById(R.id.messageText)
    
    private var commonMessageInterface: CommonMessageInterface? = null

    /**
     * Called when the ViewHolder is recycled.
     * No async work to cancel since we only read from cache now.
     */
    fun onRecycled() {
        // Nothing to cancel - we only read from ThumbnailCache synchronously
    }

    fun assignCommonMessageInterface(commonMessageInterface: CommonMessageInterface) {
        this.commonMessageInterface = commonMessageInterface
    }

    fun adjustIfNoteToSelf(currentConversation: ConversationModel?) {
        // Note to self adjustments can be implemented here if needed
        // For now, we keep the default behavior
    }

    override fun onBind(message: ChatMessage) {
        super.onBind(message)
        
        // Safety check - if any critical views are null, don't bind
        try {
            bindVideoUpload(message)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error binding VideoUploadMessageViewHolder", e)
        }
    }

    // Handle partial updates for progress changes
    fun onBind(message: ChatMessage, payload: Any?) {
        if (payload == "progress_update") {
            // Only update progress, don't rebind everything
            updateProgressDisplay(message)
        } else {
            // Full bind
            onBind(message)
        }
    }

    private fun bindVideoUpload(message: ChatMessage) {
        // Zeit anzeigen
        timeText.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.timestamp))
        
        // Benutzername anzeigen
        authorText.text = message.actorDisplayName ?: "Unbekannt"
        
        // Nachricht anzeigen 
        messageText.text = message.message ?: "Video Upload..."
        messageText.visibility = View.VISIBLE
        
        // Progress live updaten basierend auf Upload-Status
        updateProgressDisplay(message)
        
        // Cancel Button
        cancelButton.setOnClickListener {
            (itemView.context as? ChatActivity)?.let { chatActivity ->
                chatActivity.cancelVideoUpload(message.jsonMessageId.toString())
            }
        }

        // Load thumbnail from ThumbnailCache (prefetched in ChatActivity before transcoding)
        val localUri = message.localVideoUri
        if (localUri != null) {
            val uriString = localUri.toString()
            
            // Check if thumbnail is already cached (prefetched before transcoding started)
            val cachedThumbnail = ThumbnailCache.get(uriString)
            if (cachedThumbnail != null) {
                thumbnailView.setImageBitmap(cachedThumbnail)
                thumbnailView.visibility = View.VISIBLE
                android.util.Log.d(TAG, "✅ Displaying cached thumbnail for $uriString")
            } else {
                // Fallback: show video icon placeholder if thumbnail not yet available
                thumbnailView.setImageResource(R.drawable.ic_mimetype_video)
                thumbnailView.visibility = View.VISIBLE
                android.util.Log.d(TAG, "⏳ Waiting for thumbnail prefetch for $uriString")
            }
        } else {
            // No video URI, show placeholder
            thumbnailView.setImageResource(R.drawable.ic_mimetype_video)
            thumbnailView.visibility = View.VISIBLE
        }
    }
    
    private fun updateProgressDisplay(message: ChatMessage) {
        when (message.uploadState) {
            UploadState.TRANSCODING -> {
                progressOverlay.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = message.transcodeProgress
                statusText.text = "Komprimierung... ${message.transcodeProgress}%"
                statusText.setTextColor(Color.GRAY)
                
                // Live file size updates mit Komprimierungsrate
                val originalSize = formatFileSize(message.originalFileSize)
                val currentSize = if (message.currentCompressedSize > 0) {
                    formatFileSize(message.currentCompressedSize)
                } else {
                    "..."
                }
                
                val compressionInfo = if (message.compressionRatio > 0) {
                    " (${message.compressionRatio}% kleiner)"
                } else {
                    ""
                }
                
                fileSizeText.text = "$originalSize → $currentSize$compressionInfo"
                
                // Geschätzte verbleibende Zeit
                val estimatedTimeText = getEstimatedTimeText(message)
                if (estimatedTimeText.isNotEmpty()) {
                    statusText.text = "${statusText.text} $estimatedTimeText"
                }
                
                cancelButton.visibility = View.VISIBLE
            }
            
            UploadState.UPLOADING -> {
                progressBar.isIndeterminate = false
                progressBar.progress = message.uploadProgress
                statusText.text = "Upload... ${message.uploadProgress}%"
                statusText.setTextColor(Color.BLUE)
                fileSizeText.text = "Größe: ${formatFileSize(message.finalCompressedSize)}"
                cancelButton.visibility = View.VISIBLE
            }
            
            UploadState.FAILED -> {
                progressOverlay.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                statusText.text = "Fehler: ${message.uploadErrorMessage ?: "Unbekannter Fehler"}"
                statusText.setTextColor(Color.RED)
                fileSizeText.text = ""
                cancelButton.visibility = View.GONE
            }
            
            UploadState.CANCELLED -> {
                progressOverlay.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                statusText.text = "Upload abgebrochen"
                statusText.setTextColor(Color.GRAY)
                fileSizeText.text = ""
                cancelButton.visibility = View.GONE
            }
            
            UploadState.COMPLETED -> {
                progressOverlay.visibility = View.GONE
                statusText.text = ""
                fileSizeText.text = ""
                cancelButton.visibility = View.GONE
            }
            
            UploadState.NONE -> {
                progressOverlay.visibility = View.GONE
                statusText.text = ""
                fileSizeText.text = ""
                cancelButton.visibility = View.GONE
            }
        }
    }
    
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
    
    private fun getEstimatedTimeText(message: ChatMessage): String {
        if (message.transcodeProgress <= 0 || message.compressionStartTime <= 0) return ""
        
        val elapsed = System.currentTimeMillis() - message.compressionStartTime
        if (elapsed < 2000) return "" // Warte mindestens 2 Sekunden für genauere Schätzung
        
        val remainingPercent = 100 - message.transcodeProgress
        if (remainingPercent <= 0) return ""
        
        val estimatedTotalTime = (elapsed * 100) / message.transcodeProgress
        val remainingTime = estimatedTotalTime - elapsed
        
        return when {
            remainingTime < 10000 -> "(weniger als 10s)"
            remainingTime < 60000 -> "(~${remainingTime / 1000}s)"
            else -> "(~${remainingTime / 60000}min)"
        }
    }
}
