/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Parneet Singh <gurayaparneet@gmail.com>
 * SPDX-FileCopyrightText: 2021-2022 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.ImageCompressionLevel
import com.nextcloud.talk.models.VideoCompressionLevel
import com.nextcloud.talk.interfaces.VideoCompressionProgressCallback
import com.nextcloud.talk.upload.chunked.ChunkedFileUploader
import com.nextcloud.talk.upload.chunked.OnDataTransferProgressListener
import com.nextcloud.talk.upload.normal.FileUploader
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.CapabilitiesUtil
import com.nextcloud.talk.utils.FileUtils
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.RemoteFileUtils
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.database.user.CurrentUserProviderNew
import com.nextcloud.talk.utils.permissions.PlatformPermissionUtil
import com.nextcloud.talk.utils.preferences.AppPreferences
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.UUID
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class UploadAndShareFilesWorker(val context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters),
    OnDataTransferProgressListener {

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var currentUserProvider: CurrentUserProviderNew

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var platformPermissionUtil: PlatformPermissionUtil

    lateinit var fileName: String

    private var mNotifyManager: NotificationManager? = null
    private var mBuilder: NotificationCompat.Builder? = null
    private var notificationId: Int = 0

    lateinit var roomToken: String
    lateinit var conversationName: String
    lateinit var currentUser: User
    private var isChunkedUploading = false
    private var file: File? = null
    private var chunkedFileUploader: ChunkedFileUploader? = null

    @Suppress("Detekt.TooGenericExceptionCaught")
    override fun doWork(): Result {
        Log.d(TAG, "🚀 WORKER DOWORK: Starting doWork() method")
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)

        return try {
            Log.d(TAG, "🚀 WORKER DOWORK: Getting current user...")
            currentUser = currentUserProvider.currentUser.blockingGet()
            val sourceFile = inputData.getString(DEVICE_SOURCE_FILE)
            roomToken = inputData.getString(ROOM_TOKEN)!!
            conversationName = inputData.getString(CONVERSATION_NAME)!!
            val metaData = inputData.getString(META_DATA)

            Log.d(TAG, "📱 WORKER DOWORK: Input data - sourceFile: $sourceFile")
            Log.d(TAG, "📱 WORKER DOWORK: Input data - roomToken: $roomToken")
            Log.d(TAG, "📱 WORKER DOWORK: Input data - conversationName: $conversationName")
            Log.d(TAG, "📱 WORKER DOWORK: Current user: ${currentUser.userId}")

            checkNotNull(currentUser)
            checkNotNull(sourceFile)
            require(sourceFile.isNotEmpty())
            checkNotNull(roomToken)

            val sourceFileUri = sourceFile.toUri()
            Log.d(TAG, "📁 WORKER DOWORK: Source URI: $sourceFileUri")
            Log.d(TAG, "📁 WORKER DOWORK: URI scheme: ${sourceFileUri.scheme}")

            fileName = FileUtils.getFileName(sourceFileUri, context)
            Log.d(TAG, "📝 WORKER DOWORK: File name: $fileName")

            file = FileUtils.getFileFromUri(context, sourceFileUri)
            Log.d(TAG, "📂 WORKER DOWORK: File object: $file")

            if (file == null) {
                Log.e(TAG, "❌ WORKER DOWORK: File is null after getFileFromUri!")
                showFailedToUploadNotification()
                return Result.failure()
            }

            Log.d(TAG, "📊 WORKER DOWORK: File exists: ${file!!.exists()}, size: ${file!!.length()} bytes")

            // Apply image compression if enabled and file is an image
            Log.d(TAG, "🖼️ WORKER DOWORK: Starting image compression check...")
            val originalFile = file
            file = applyImageCompressionIfNeeded(file, sourceFileUri)
            Log.d(TAG, "🖼️ WORKER DOWORK: Image compression completed")

            // Apply video compression if enabled and file is a video
            Log.d(TAG, "🎥 WORKER DOWORK: Starting video compression check...")
            Log.d(
                TAG,
                "🎥 WORKER DOWORK: File before video compression: ${file?.absolutePath}, size: ${file?.length()} bytes"
            )
            file = applyVideoCompressionIfNeeded(file ?: originalFile, sourceFileUri)
            Log.d(TAG, "🎥 WORKER DOWORK: Video compression completed")
            Log.d(
                TAG,
                "🎥 WORKER DOWORK: File after video compression: ${file?.absolutePath}, size: ${file?.length()} bytes"
            )

            // If compression was applied, update the URI to point to compressed file
            val finalUri = if (file != originalFile && file != null) {
                Log.d(TAG, "Using compressed file for upload: ${file!!.name}")
                Uri.fromFile(file!!)
            } else {
                Log.d(TAG, "Using original file for upload")
                sourceFileUri
            }

            val remotePath = getRemotePath(currentUser)

            initNotificationSetup()
            file?.let { isChunkedUploading = it.length() > CHUNK_UPLOAD_THRESHOLD_SIZE }
            val uploadSuccess: Boolean = uploadFile(finalUri, metaData, remotePath)

            if (uploadSuccess) {
                cancelNotification()
                return Result.success()
            } else if (isStopped) {
                // since work is cancelled the result would be ignored anyways
                return Result.failure()
            }

            Log.e(TAG, "❌ WORKER DOWORK: Something went wrong when trying to upload file")
            showFailedToUploadNotification()
            return Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "❌ WORKER DOWORK: Exception occurred in doWork()", e)
            Log.e(TAG, "❌ WORKER DOWORK: Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "❌ WORKER DOWORK: Exception message: ${e.message}")
            e.printStackTrace()
            showFailedToUploadNotification()
            return Result.failure()
        }
    }

    private fun uploadFile(fileUri: Uri, metaData: String?, remotePath: String): Boolean =
        if (file == null) {
            false
        } else if (isChunkedUploading) {
            Log.d(TAG, "starting chunked upload because size is " + file!!.length())

            initNotificationWithPercentage()
            val mimeType = context.contentResolver.getType(fileUri)?.toMediaTypeOrNull()

            chunkedFileUploader = ChunkedFileUploader(okHttpClient, currentUser, roomToken, metaData, this)
            chunkedFileUploader!!.upload(file!!, mimeType, remotePath)
        } else {
            Log.d(TAG, "starting normal upload (not chunked) of $fileName")

            FileUploader(okHttpClient, context, currentUser, roomToken, ncApi, file!!)
                .upload(fileUri, fileName, remotePath, metaData)
                .blockingFirst()
        }

    private fun getRemotePath(currentUser: User): String {
        val remotePath = CapabilitiesUtil.getAttachmentFolder(
            currentUser.capabilities!!.spreedCapability!!
        ) + "/" + fileName
        return RemoteFileUtils.getNewPathIfFileExists(ncApi, currentUser, remotePath)
    }

    override fun onTransferProgress(percentage: Int) {
        val progressUpdateNotification = mBuilder!!
            .setProgress(HUNDRED_PERCENT, percentage, false)
            .setContentText(getNotificationContentText(percentage))
            .build()

        mNotifyManager!!.notify(notificationId, progressUpdateNotification)
    }

    override fun onStopped() {
        if (file != null && isChunkedUploading) {
            chunkedFileUploader?.abortUpload {
                mNotifyManager?.cancel(notificationId)
            }
        }
        super.onStopped()
    }

    private fun initNotificationSetup() {
        mNotifyManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mBuilder = NotificationCompat.Builder(
            context,
            NotificationUtils.NotificationChannels
                .NOTIFICATION_CHANNEL_UPLOADS.name
        )
    }

    private fun initNotificationWithPercentage() {
        val initNotification = mBuilder!!
            .setContentTitle(context.resources.getString(R.string.nc_upload_in_progess))
            .setContentText(getNotificationContentText(ZERO_PERCENT))
            .setSmallIcon(R.drawable.upload_white)
            .setOngoing(true)
            .setProgress(HUNDRED_PERCENT, ZERO_PERCENT, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(NotificationUtils.KEY_UPLOAD_GROUP)
            .setContentIntent(getIntentToOpenConversation())
            .addAction(
                R.drawable.ic_cancel_white_24dp,
                getResourceString(context, R.string.nc_cancel),
                getCancelUploadIntent()
            )
            .build()

        notificationId = SystemClock.uptimeMillis().toInt()
        mNotifyManager!!.notify(notificationId, initNotification)
        // only need one summary notification but multiple upload worker can call it more than once but it is safe
        // because of the same notification object config and id.
        makeSummaryNotification()
    }

    private fun makeSummaryNotification() {
        // summary notification encapsulating the group of notifications
        val summaryNotification = NotificationCompat.Builder(
            context,
            NotificationUtils.NotificationChannels
                .NOTIFICATION_CHANNEL_UPLOADS.name
        ).setSmallIcon(R.drawable.upload_white)
            .setGroup(NotificationUtils.KEY_UPLOAD_GROUP)
            .setGroupSummary(true)
            .build()

        mNotifyManager?.notify(NotificationUtils.GROUP_SUMMARY_NOTIFICATION_ID, summaryNotification)
    }

    private fun getActiveUploadNotifications(): Int? {
        // filter out active notifications that are upload notifications using group
        return mNotifyManager?.activeNotifications?.filter {
            it.notification.group == NotificationUtils
                .KEY_UPLOAD_GROUP
        }?.size
    }

    private fun cancelNotification() {
        mNotifyManager?.cancel(notificationId)
        // summary notification would not get dismissed automatically
        // if child notifications are cancelled programmatically
        // so check if only 1 notification left if yes
        // then cancel it (which is summary notification)
        if (getActiveUploadNotifications() == 1) {
            mNotifyManager?.cancel(NotificationUtils.GROUP_SUMMARY_NOTIFICATION_ID)
        }
    }

    private fun getNotificationContentText(percentage: Int): String =
        String.format(
            getResourceString(context, R.string.nc_upload_notification_text),
            getShortenedFileName(),
            conversationName,
            percentage
        )

    private fun getShortenedFileName(): String =
        if (fileName.length > NOTIFICATION_FILE_NAME_MAX_LENGTH) {
            THREE_DOTS + fileName.takeLast(NOTIFICATION_FILE_NAME_MAX_LENGTH)
        } else {
            fileName
        }

    private fun getCancelUploadIntent(): PendingIntent =
        WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

    private fun getIntentToOpenConversation(): PendingIntent? {
        val bundle = Bundle()
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK

        bundle.putString(KEY_ROOM_TOKEN, roomToken)
        bundle.putLong(KEY_INTERNAL_USER_ID, currentUser.id!!)

        intent.putExtras(bundle)

        val requestCode = System.currentTimeMillis().toInt()
        val intentFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getActivity(context, requestCode, intent, intentFlag)
    }

    private fun showFailedToUploadNotification() {
        val failureTitle = getResourceString(context, R.string.nc_upload_failed_notification_title)
        val failureText = String.format(
            getResourceString(context, R.string.nc_upload_failed_notification_text),
            fileName
        )
        val failureNotification = NotificationCompat.Builder(
            context,
            NotificationUtils.NotificationChannels
                .NOTIFICATION_CHANNEL_UPLOADS.name
        )
            .setContentTitle(failureTitle)
            .setContentText(failureText)
            .setSmallIcon(R.drawable.baseline_error_24)
            .setGroup(NotificationUtils.KEY_UPLOAD_GROUP)
            .setOngoing(false)
            .build()

        mNotifyManager?.cancel(notificationId)
        // update current notification with failure info
        mNotifyManager!!.notify(SystemClock.uptimeMillis().toInt(), failureNotification)
    }

    private fun getResourceString(context: Context, resourceId: Int): String = context.resources.getString(resourceId)

    /**
     * Applies image compression if enabled in settings and the file is an image
     * @param originalFile The original file to potentially compress
     * @param sourceFileUri The source URI for MIME type checking
     * @return The compressed file if compression was applied and successful, otherwise the original file
     */
    private fun applyImageCompressionIfNeeded(originalFile: File?, sourceFileUri: Uri): File? {
        if (originalFile == null) return null

        // Get the compression level from preferences
        val compressionLevelKey = appPreferences.imageCompressionLevel
        val compressionLevel = ImageCompressionLevel.fromKey(compressionLevelKey)

        if (!compressionLevel.shouldCompress()) {
            Log.d(TAG, "Image compression is disabled or set to NONE")
            return originalFile
        }

        // Check if the file is an image (using both file and URI checks for reliability)
        val isImageFromFile = FileUtils.isImageFile(originalFile)
        val isImageFromUri = FileUtils.isImageFile(context, sourceFileUri)
        val isImage = isImageFromFile || isImageFromUri

        if (!isImage) {
            Log.d(TAG, "File is not an image, skipping compression")
            return originalFile
        }

        Log.d(
            TAG,
            "Starting image compression for file: ${originalFile.name} with level: ${compressionLevel.name}"
        )

        return try {
            // Create a compressed file in cache directory
            val compressedFileName = "compressed_${compressionLevel.key}_" +
                "${System.currentTimeMillis()}_${originalFile.name}"
            val compressedFile = File(context.cacheDir, compressedFileName)

            // Apply compression using the specified level
            val compressionSuccess = FileUtils.compressImageFile(
                inputFile = originalFile,
                outputFile = compressedFile,
                compressionLevel = compressionLevel
            )

            if (compressionSuccess && compressedFile.exists() && compressedFile.length() > 0) {
                val originalSize = originalFile.length()
                val compressedSize = compressedFile.length()
                val compressionRatio = if (originalSize > 0) {
                    ((originalSize - compressedSize) * 100 / originalSize).toInt()
                } else {
                    0
                }

                Log.d(
                    TAG,
                    "Image compression successful with ${compressionLevel.name}. " +
                        "Original: ${originalSize / 1024}KB, " +
                        "Compressed: ${compressedSize / 1024}KB, " +
                        "Saved: $compressionRatio%"
                )

                // Debug: Show compression result in notification for testing
                if (compressionRatio > 5) {
                    Log.i(TAG, "✅ COMPRESSION SUCCESSFUL: $compressionRatio% reduction")
                } else {
                    Log.w(TAG, "⚠️ COMPRESSION MINIMAL: Only $compressionRatio% reduction - check settings")
                }

                // Don't change fileName - keep original name for upload
                // The compressed file is now used, but uploaded with original name

                compressedFile
            } else {
                Log.w(TAG, "Image compression failed or resulted in empty file, using original")
                originalFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during image compression, using original file", e)
            originalFile
        }
    }

    /**
     * Applies video compression if enabled in settings and the file is a video
     * @param originalFile The original file to potentially compress
     * @param sourceFileUri The source URI for MIME type checking
     * @return The compressed file if compression was applied and successful, otherwise the original file
     */
    private fun applyVideoCompressionIfNeeded(originalFile: File?, sourceFileUri: Uri): File? {
        Log.d(TAG, "VIDEO_COMPRESSION: Method entry - originalFile: ${originalFile?.name}")
        if (originalFile == null) {
            Log.d(TAG, "VIDEO_COMPRESSION: originalFile is null, returning null")
            return null
        }

        Log.d(TAG, "VIDEO_COMPRESSION: Getting compression level from preferences...")
        // Get the compression level from preferences
        val compressionLevelKey = appPreferences.videoCompressionLevel
        Log.d(TAG, "VIDEO_COMPRESSION: Got compression level key: $compressionLevelKey")
        val compressionLevel = VideoCompressionLevel.fromKey(compressionLevelKey)
        Log.d(TAG, "VIDEO_COMPRESSION: Parsed compression level: ${compressionLevel.name}")

        if (!compressionLevel.shouldCompress()) {
            Log.d(TAG, "VIDEO_COMPRESSION: Compression level shouldCompress() returned false")
            Log.d(TAG, "Video compression is disabled or set to NONE")
            return originalFile
        }

        Log.d(TAG, "VIDEO_COMPRESSION: Compression level should compress, checking if file is video...")
        // Check if the file is a video (using both file and URI checks for reliability)
        Log.d(TAG, "VIDEO_COMPRESSION: Calling FileUtils.isVideoFile(originalFile)...")
        val isVideoFromFile = FileUtils.isVideoFile(originalFile)
        Log.d(TAG, "VIDEO_COMPRESSION: isVideoFromFile result: $isVideoFromFile")

        Log.d(TAG, "VIDEO_COMPRESSION: Calling FileUtils.isVideoFile(context, sourceFileUri)...")
        val isVideoFromUri = FileUtils.isVideoFile(context, sourceFileUri)
        Log.d(TAG, "VIDEO_COMPRESSION: isVideoFromUri result: $isVideoFromUri")

        val isVideo = isVideoFromFile || isVideoFromUri
        Log.d(TAG, "VIDEO_COMPRESSION: Final isVideo result: $isVideo")

        if (!isVideo) {
            Log.d(TAG, "File is not a video, skipping compression")
            return originalFile
        }

        Log.d(
            TAG,
            "VIDEO_COMPRESSION: Starting video compression for file: ${originalFile.name} with level: ${compressionLevel.name}"
        )

        return try {
            Log.d(TAG, "VIDEO_COMPRESSION: Entering try block for video compression")
            // Create a compressed file in cache directory
            val compressedFileName = "compressed_video_${compressionLevel.key}_" +
                "${System.currentTimeMillis()}_${originalFile.name}"
            Log.d(TAG, "VIDEO_COMPRESSION: Created compressed file name: $compressedFileName")
            val compressedFile = File(context.cacheDir, compressedFileName)
            Log.d(TAG, "VIDEO_COMPRESSION: Created compressed file object: ${compressedFile.absolutePath}")

            // Apply compression using the specified level with progress tracking
            val startTime = SystemClock.elapsedRealtime()
            val progressCallback = object : VideoCompressionProgressCallback {
                override fun onCompressionStarted() {
                    Log.d(TAG, "Video compression started for ${originalFile.name}")

                    // Update notification to show compression phase
                    mBuilder?.let { builder ->
                        val notification = builder
                            .setContentTitle(
                                context.resources.getString(R.string.nc_video_compression_notification_title)
                            )
                            .setContentText(
                                context.resources.getString(
                                    R.string.nc_video_compression_notification_starting,
                                    originalFile.name
                                )
                            )
                            .setSmallIcon(R.drawable.upload_white)
                            .setProgress(100, 0, false)
                            .setOngoing(true)
                            .build()

                        mNotifyManager?.notify(notificationId, notification)
                    }

                    // Update WorkManager progress
                    setProgressAsync(
                        Data.Builder()
                            .putString("status", "compressing")
                            .putString("fileName", originalFile.name)
                            .putInt("progress", 0)
                            .putString("phase", "Starte Video-Komprimierung...")
                            .build()
                    )
                }

                override fun onProgressUpdate(
                    progress: Int,
                    currentFrame: Int,
                    totalFrames: Int,
                    originalSizeBytes: Long,
                    currentSizeBytes: Long
                ) {
                    Log.v(
                        TAG,
                        "Video compression progress: $progress% ($currentFrame/$totalFrames frames)"
                    )

                    // Update notification with compression progress
                    mBuilder?.let { builder ->
                        val notification = builder
                            .setContentTitle(
                                context.resources.getString(R.string.nc_video_compression_notification_title)
                            )
                            .setContentText(
                                context.resources.getString(
                                    R.string.nc_video_compression_notification_progress,
                                    originalFile.name,
                                    progress
                                )
                            )
                            .setProgress(100, progress, false)
                            .setOngoing(true)
                            .build()

                        mNotifyManager?.notify(notificationId, notification)
                    }

                    // Update WorkManager progress with detailed information
                    val estimatedTimeLeft = if (progress > 0) {
                        val timePerPercent = (SystemClock.elapsedRealtime() - startTime) / progress
                        val remainingPercent = 100 - progress
                        (timePerPercent * remainingPercent) / 1000 // in seconds
                    } else {
                        0L
                    }

                    setProgressAsync(
                        Data.Builder()
                            .putString("status", "compressing")
                            .putString("fileName", originalFile.name)
                            .putInt("progress", progress)
                            .putString("phase", "Komprimiere Video: Frame $currentFrame von $totalFrames")
                            .putInt("processedFrames", currentFrame)
                            .putInt("estimatedTotalFrames", totalFrames)
                            .putLong("currentSizeBytes", currentSizeBytes)
                            .putLong("originalSizeBytes", originalSizeBytes)
                            .putLong("estimatedTimeLeftSeconds", estimatedTimeLeft)
                            .build()
                    )
                }

                override fun onCompressionCompleted(
                    originalSizeBytes: Long,
                    compressedSizeBytes: Long,
                    compressionRatio: Int
                ) {
                    Log.d(TAG, "Video compression completed: $compressionRatio% size reduction")

                    // Update notification to show compression completion
                    mBuilder?.let { builder ->
                        val sizeMB = compressedSizeBytes / (1024f * 1024f)
                        val reductionPercent = compressionRatio

                        val notification = builder
                            .setContentTitle(
                                context.resources.getString(R.string.nc_video_compression_notification_completed)
                            )
                            .setContentText(
                                context.resources.getString(
                                    R.string.nc_video_compression_notification_completed_text,
                                    originalFile.name,
                                    String.format("%.1f MB", sizeMB),
                                    reductionPercent
                                )
                            )
                            .setProgress(0, 0, false) // Remove progress bar
                            .setOngoing(false)
                            .build()

                        mNotifyManager?.notify(notificationId, notification)
                    }

                    setProgressAsync(
                        Data.Builder()
                            .putString("status", "compression_completed")
                            .putString("fileName", originalFile.name)
                            .putInt("progress", 100)
                            .putString("phase", "Video-Komprimierung abgeschlossen")
                            .putLong("originalSizeBytes", originalSizeBytes)
                            .putLong("compressedSizeBytes", compressedSizeBytes)
                            .putFloat("compressionRatio", compressionRatio.toFloat())
                            .build()
                    )
                }

                override fun onCompressionFailed(error: String, exception: Throwable?) {
                    Log.e(TAG, "Video compression failed: $error", exception)

                    // Update notification to show compression failure
                    mBuilder?.let { builder ->
                        val notification = builder
                            .setContentTitle(
                                context.resources.getString(R.string.nc_video_compression_notification_failed)
                            )
                            .setContentText(
                                context.resources.getString(
                                    R.string.nc_video_compression_notification_failed_text,
                                    originalFile.name
                                )
                            )
                            .setProgress(0, 0, false) // Remove progress bar
                            .setOngoing(false)
                            .build()

                        mNotifyManager?.notify(notificationId, notification)
                    }

                    setProgressAsync(
                        Data.Builder()
                            .putString("status", "compression_failed")
                            .putString("fileName", originalFile.name)
                            .putString("error", error)
                            .putString("phase", "Video-Komprimierung fehlgeschlagen")
                            .build()
                    )
                }
            }

            Log.d(TAG, "VIDEO_COMPRESSION: About to call FileUtils.compressVideoFile()")
            Log.d(TAG, "VIDEO_COMPRESSION: Input file: ${originalFile.absolutePath}, size: ${originalFile.length()}")
            Log.d(TAG, "VIDEO_COMPRESSION: Output file: ${compressedFile.absolutePath}")
            Log.d(TAG, "VIDEO_COMPRESSION: Compression level: ${compressionLevel.name}")

            val compressionSuccess = FileUtils.compressVideoFile(
                inputFile = originalFile,
                outputFile = compressedFile,
                compressionLevel = compressionLevel,
                progressCallback = progressCallback
            )

            Log.d(TAG, "VIDEO_COMPRESSION: FileUtils.compressVideoFile() returned: $compressionSuccess")

            if (compressionSuccess && compressedFile.exists() && compressedFile.length() > 0) {
                val originalSize = originalFile.length()
                val compressedSize = compressedFile.length()
                val compressionRatio = if (originalSize > 0) {
                    ((originalSize - compressedSize) * 100 / originalSize).toInt()
                } else {
                    0
                }

                Log.d(
                    TAG,
                    "Video compression completed with ${compressionLevel.name}. " +
                        "Original: ${originalSize / 1024}KB, " +
                        "Result: ${compressedSize / 1024}KB, " +
                        "Change: $compressionRatio%"
                )

                // Note: For now, compression just copies the file (MediaCodec implementation pending)
                Log.i(TAG, "📹 VIDEO PROCESSING: Using ${compressionLevel.getDescription()}")

                // Don't change fileName - keep original name for upload
                // The compressed file is now used, but uploaded with original name

                compressedFile
            } else {
                Log.w(TAG, "Video compression failed or resulted in empty file, using original")
                originalFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during video compression, using original file", e)
            originalFile
        }
    }

    companion object {
        private val TAG = UploadAndShareFilesWorker::class.simpleName
        private const val DEVICE_SOURCE_FILE = "DEVICE_SOURCE_FILE"
        private const val ROOM_TOKEN = "ROOM_TOKEN"
        private const val CONVERSATION_NAME = "CONVERSATION_NAME"
        private const val META_DATA = "META_DATA"
        private const val CHUNK_UPLOAD_THRESHOLD_SIZE: Long = 1024000
        private const val NOTIFICATION_FILE_NAME_MAX_LENGTH = 20
        private const val THREE_DOTS = "…"
        private const val HUNDRED_PERCENT = 100
        private const val ZERO_PERCENT = 0
        const val REQUEST_PERMISSION = 3123

        fun requestStoragePermission(activity: Activity) {
            when {
                Build.VERSION
                    .SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO
                        ),
                        REQUEST_PERMISSION
                    )
                }

                Build.VERSION.SDK_INT > Build.VERSION_CODES.Q -> {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ),
                        REQUEST_PERMISSION
                    )
                }

                else -> {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        REQUEST_PERMISSION
                    )
                }
            }
        }

        fun upload(fileUri: String, roomToken: String, conversationName: String, metaData: String?) {
            Log.d(TAG, "🚀 WORKER SETUP: Starting upload with WorkManager")
            Log.d(TAG, "🚀 WORKER SETUP: fileUri: $fileUri")
            Log.d(TAG, "🚀 WORKER SETUP: roomToken: $roomToken")
            Log.d(TAG, "🚀 WORKER SETUP: conversationName: $conversationName")
            Log.d(TAG, "🚀 WORKER SETUP: metaData: $metaData")

            val data: Data = Data.Builder()
                .putString(DEVICE_SOURCE_FILE, fileUri)
                .putString(ROOM_TOKEN, roomToken)
                .putString(CONVERSATION_NAME, conversationName)
                .putString(META_DATA, metaData)
                .build()
            val uploadWorker: OneTimeWorkRequest = OneTimeWorkRequest.Builder(UploadAndShareFilesWorker::class.java)
                .setInputData(data)
                .build()

            Log.d(TAG, "🚀 WORKER SETUP: Enqueueing work with ID: ${uploadWorker.id}")
            try {
                WorkManager.getInstance().enqueueUniqueWork(fileUri, ExistingWorkPolicy.KEEP, uploadWorker)
                Log.d(TAG, "🚀 WORKER SETUP: Work enqueued successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ WORKER SETUP: Failed to enqueue work", e)
                throw e
            }
        }

        fun upload(context: Context, fileUri: String, roomToken: String, conversationName: String, metaData: String?) {
            Log.d(TAG, "🚀 WORKER SETUP: Starting upload with WorkManager (with Context)")
            Log.d(TAG, "🚀 WORKER SETUP: fileUri: $fileUri")
            Log.d(TAG, "🚀 WORKER SETUP: roomToken: $roomToken")
            Log.d(TAG, "🚀 WORKER SETUP: conversationName: $conversationName")
            Log.d(TAG, "🚀 WORKER SETUP: metaData: $metaData")

            val data: Data = Data.Builder()
                .putString(DEVICE_SOURCE_FILE, fileUri)
                .putString(ROOM_TOKEN, roomToken)
                .putString(CONVERSATION_NAME, conversationName)
                .putString(META_DATA, metaData)
                .build()
            val uploadWorker: OneTimeWorkRequest = OneTimeWorkRequest.Builder(UploadAndShareFilesWorker::class.java)
                .setInputData(data)
                .build()

            Log.d(TAG, "🚀 WORKER SETUP: Enqueueing work with ID: ${uploadWorker.id}")
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(fileUri, ExistingWorkPolicy.KEEP, uploadWorker)
                Log.d(TAG, "🚀 WORKER SETUP: Work enqueued successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ WORKER SETUP: Failed to enqueue work", e)
                throw e
            }
        }

        /**
         * Upload files with progress tracking for video compression
         * @param fileUri The URI of the file to upload
         * @param roomToken The room token where the file should be shared
         * @param conversationName The name of the conversation
         * @param metaData Optional metadata
         * @param context Context for showing progress dialog (optional)
         * @param fileName File name for progress display (optional)
         * @return WorkRequest ID for progress tracking
         */
        fun uploadWithProgress(
            fileUri: String,
            roomToken: String,
            conversationName: String,
            metaData: String?,
            context: Context? = null,
            fileName: String? = null
        ): UUID {
            val data: Data = Data.Builder()
                .putString(DEVICE_SOURCE_FILE, fileUri)
                .putString(ROOM_TOKEN, roomToken)
                .putString(CONVERSATION_NAME, conversationName)
                .putString(META_DATA, metaData)
                .build()
            val uploadWorker: OneTimeWorkRequest = OneTimeWorkRequest.Builder(UploadAndShareFilesWorker::class.java)
                .setInputData(data)
                .build()

            WorkManager.getInstance().enqueueUniqueWork(fileUri, ExistingWorkPolicy.KEEP, uploadWorker)

            // Show progress dialog for video files if context and fileName are provided
            if (context != null &&
                fileName != null &&
                (
                    fileName.lowercase().endsWith(".mp4") ||
                        fileName.lowercase().endsWith(".mov") ||
                        fileName.lowercase().endsWith(".avi") ||
                        fileName.lowercase().endsWith(".mkv")
                    )
            ) {
                // Show progress dialog for video compression
                try {
                    val activity = context as? androidx.fragment.app.FragmentActivity
                    activity?.let {
                        val progressDialog = com.nextcloud.talk.ui.dialog.VideoCompressionProgressDialog
                            .newInstance(fileName, uploadWorker.id)
                        progressDialog.show(it.supportFragmentManager, "video_compression_progress")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not show progress dialog", e)
                }
            }

            return uploadWorker.id
        }
    }
}
