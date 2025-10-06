/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2021 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2021 Stefan Niedermann <info@niedermann.it>
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import com.nextcloud.talk.models.ImageCompressionLevel
import com.nextcloud.talk.models.VideoCompressionLevel
import com.nextcloud.talk.interfaces.VideoCompressionProgressCallback
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.min

object FileUtils {
    private val TAG = FileUtils::class.java.simpleName
    private const val RADIX: Int = 16
    private const val MD5_LENGTH: Int = 32
    private const val TIMEOUT_USEC: Long = 100000 // 100ms - increased from 10ms

    /**
     * Creates a new [File]
     */
    @Suppress("ThrowsCount")
    @JvmStatic
    fun getTempCacheFile(context: Context, fileName: String): File {
        val cacheFile = File(context.applicationContext.filesDir.absolutePath + "/" + fileName)
        Log.v(TAG, "Full path for new cache file:" + cacheFile.absolutePath)
        val tempDir = cacheFile.parentFile ?: throw FileNotFoundException("could not cacheFile.getParentFile()")
        if (!tempDir.exists()) {
            Log.v(
                TAG,
                "The folder in which the new file should be created does not exist yet. Trying to create it…"
            )
            if (tempDir.mkdirs()) {
                Log.v(TAG, "Creation successful")
            } else {
                throw IOException("Directory for temporary file does not exist and could not be created.")
            }
        }
        Log.v(TAG, "- Try to create actual cache file")
        if (cacheFile.createNewFile()) {
            Log.v(TAG, "Successfully created cache file")
        } else {
            throw IOException("Failed to create cacheFile")
        }
        return cacheFile
    }

    /**
     * Creates a new [File]
     */
    fun removeTempCacheFile(context: Context, fileName: String) {
        val cacheFile = File(context.applicationContext.filesDir.absolutePath + "/" + fileName)
        Log.v(TAG, "Full path for new cache file:" + cacheFile.absolutePath)
        if (cacheFile.exists()) {
            if (cacheFile.delete()) {
                Log.v(TAG, "Deletion successful")
            } else {
                throw IOException("Directory for temporary file does not exist and could not be created.")
            }
        }
    }

    @Suppress("ThrowsCount")
    fun getFileFromUri(context: Context, sourceFileUri: Uri): File? {
        val fileName = getFileName(sourceFileUri, context)
        val scheme = sourceFileUri.scheme

        val file = if (scheme == null) {
            Log.d(TAG, "relative uri: " + sourceFileUri.path)
            throw IllegalArgumentException("relative paths are not supported")
        } else if (ContentResolver.SCHEME_CONTENT == scheme) {
            copyFileToCache(context, sourceFileUri, fileName)
        } else if (ContentResolver.SCHEME_FILE == scheme) {
            if (sourceFileUri.path != null) {
                sourceFileUri.path?.let { File(it) }
            } else {
                throw IllegalArgumentException("uri does not contain path")
            }
        } else {
            throw IllegalArgumentException("unsupported scheme: " + sourceFileUri.path)
        }
        return file
    }

    @Suppress("NestedBlockDepth")
    fun copyFileToCache(context: Context, sourceFileUri: Uri, filename: String): File? {
        val cachedFile = File(context.cacheDir, filename)

        if (!cachedFile.toPath().normalize().startsWith(context.cacheDir.toPath())) {
            Log.w(TAG, "cachedFile was not created in cacheDir. Aborting for security reasons.")
            cachedFile.delete()
            return null
        }

        if (cachedFile.exists()) {
            Log.d(TAG, "file is already in cache")
        } else {
            val outputStream = FileOutputStream(cachedFile)
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(sourceFileUri)
                inputStream?.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                outputStream.flush()
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "failed to copy file to cache", e)
            }
        }
        return cachedFile
    }

    fun getFileName(uri: Uri, context: Context?): String {
        var filename: String? = null
        if (uri.scheme == "content" && context != null) {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val displayNameColumnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameColumnIndex != -1) {
                        filename = cursor.getString(displayNameColumnIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        // if it was no content uri, read filename from path
        if (filename == null) {
            filename = uri.path
        }

        val lastIndexOfSlash = filename!!.lastIndexOf('/')
        if (lastIndexOfSlash != -1) {
            filename = filename.substring(lastIndexOfSlash + 1)
        }

        return filename
    }

    @JvmStatic
    fun md5Sum(file: File): String {
        val temp = file.name + file.lastModified() + file.length()
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(temp.toByteArray())
        val digest = messageDigest.digest()
        val md5String = StringBuilder(BigInteger(1, digest).toString(RADIX))
        while (md5String.length < MD5_LENGTH) {
            md5String.insert(0, "0")
        }
        return md5String.toString()
    }

    /**
     * Determines if the given file is an image based on its MIME type
     */
    @JvmStatic
    fun isImageFile(file: File): Boolean {
        val mimeType = getMimeType(file)
        return mimeType?.startsWith("image/") == true
    }

    /**
     * Determines if the given URI is an image based on its MIME type
     */
    @JvmStatic
    fun isImageFile(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)
        return mimeType?.startsWith("image/") == true
    }

    /**
     * Determines if the given file is a video based on its MIME type
     */
    @JvmStatic
    fun isVideoFile(file: File): Boolean {
        val mimeType = getMimeType(file)
        return mimeType?.startsWith("video/") == true
    }

    /**
     * Determines if the given URI is a video based on its MIME type
     */
    @JvmStatic
    fun isVideoFile(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)
        return mimeType?.startsWith("video/") == true
    }

    /**
     * Gets the MIME type of a file
     */
    @JvmStatic
    fun getMimeType(file: File): String? {
        val extension = file.extension.lowercase()
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        } else {
            null
        }
    }

    /**
     * Compresses an image file with specified quality and maximum dimensions
     * @param inputFile The original image file
     * @param outputFile The file where the compressed image will be saved
     * @param quality JPEG compression quality (0-100)
     * @param maxWidth Maximum width in pixels
     * @param maxHeight Maximum height in pixels
     * @return true if compression was successful, false otherwise
     */
    @JvmStatic
    fun compressImageFile(
        inputFile: File,
        outputFile: File,
        quality: Int = 80,
        maxWidth: Int = 1280,
        maxHeight: Int = 1280
    ): Boolean = compressImageFileInternal(inputFile, outputFile, quality, maxWidth, maxHeight)

    /**
     * Compresses an image file using the specified compression level
     * @param inputFile The original image file
     * @param outputFile The file where the compressed image will be saved
     * @param compressionLevel The compression level to apply
     * @return true if compression was successful, false otherwise
     */
    @JvmStatic
    fun compressImageFile(inputFile: File, outputFile: File, compressionLevel: ImageCompressionLevel): Boolean =
        if (compressionLevel == ImageCompressionLevel.NONE) {
            // No compression - just copy the file
            try {
                inputFile.copyTo(outputFile, overwrite = true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy file for no compression", e)
                false
            }
        } else {
            compressImageFileInternal(
                inputFile,
                outputFile,
                compressionLevel.quality,
                compressionLevel.maxWidth,
                compressionLevel.maxHeight
            )
        }

    /**
     * Internal implementation of image compression
     */
    private fun compressImageFileInternal(
        inputFile: File,
        outputFile: File,
        quality: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Boolean {
        return try {
            Log.d(TAG, "Starting image compression:")
            Log.d(TAG, "  Input file: ${inputFile.name} (${inputFile.length()} bytes)")
            Log.d(TAG, "  Target quality: $quality")
            Log.d(TAG, "  Max dimensions: ${maxWidth}x$maxHeight")

            // Decode the image to get its dimensions first
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(inputFile.absolutePath, options)

            Log.d(TAG, "  Original dimensions: ${options.outWidth}x${options.outHeight}")
            Log.d(TAG, "  Original MIME type: ${options.outMimeType}")

            // Calculate sample size to reduce memory usage
            val sampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
            Log.d(TAG, "  Calculated sample size: $sampleSize")

            // Decode the actual bitmap with sample size
            val decodingOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inJustDecodeBounds = false
            }

            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, decodingOptions)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from file: ${inputFile.absolutePath}")
                return false
            }

            Log.d(TAG, "  Decoded bitmap: ${bitmap.width}x${bitmap.height}, config: ${bitmap.config}")

            // Calculate final dimensions maintaining aspect ratio
            val (finalWidth, finalHeight) = calculateFinalDimensions(
                bitmap.width,
                bitmap.height,
                maxWidth,
                maxHeight
            )

            Log.d(TAG, "  Target final dimensions: ${finalWidth}x$finalHeight")

            // Scale the bitmap if necessary
            val scaledBitmap = if (bitmap.width != finalWidth || bitmap.height != finalHeight) {
                Log.d(TAG, "  Scaling bitmap from ${bitmap.width}x${bitmap.height} to ${finalWidth}x$finalHeight")
                Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
            } else {
                Log.d(TAG, "  No scaling needed, but will still apply quality compression")
                bitmap
            }

            // EMERGENCY TEST: Force a tiny test image to verify compression works
            Log.d(TAG, "EMERGENCY TEST: Creating tiny test image for compression verification")
            try {
                val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.RGB_565)
                testBitmap.eraseColor(android.graphics.Color.RED)

                val testFile = File(outputFile.parent, "test_compression.jpg")
                FileOutputStream(testFile).use { fos ->
                    val testSuccess = testBitmap.compress(Bitmap.CompressFormat.JPEG, 50, fos)
                    Log.d(TAG, "Test compression success: $testSuccess, file size: ${testFile.length()}")
                }
                testBitmap.recycle()
                testFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Test compression failed", e)
            }

            // Always determine compression format for quality reduction
            // For compression, we prefer JPEG format for better size reduction
            val format = when (inputFile.extension.lowercase()) {
                "png" -> {
                    // For PNG files, only keep PNG format if transparency is needed and quality is high
                    if (hasTransparency(scaledBitmap) && quality >= 90) {
                        Log.d(TAG, "  Keeping PNG format due to transparency")
                        Bitmap.CompressFormat.PNG
                    } else {
                        // Convert to JPEG for better compression
                        Log.d(TAG, "  Converting PNG to JPEG for better compression")
                        Bitmap.CompressFormat.JPEG
                    }
                }
                "webp" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Log.d(TAG, "  Using WEBP_LOSSY format")
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Log.d(TAG, "  Using legacy WEBP format")
                    Bitmap.CompressFormat.WEBP
                }
                else -> {
                    Log.d(TAG, "  Using JPEG format")
                    Bitmap.CompressFormat.JPEG
                }
            }

            Log.d(
                TAG,
                "Compressing image: ${inputFile.name} (${inputFile.length()} bytes) with format: $format, quality: $quality"
            )

            // Save the compressed image - FORCE JPEG for maximum compression
            FileOutputStream(outputFile).use { fos ->
                // ALWAYS use JPEG for maximum compression (except PNG with transparency)
                val finalFormat = if (format == Bitmap.CompressFormat.PNG && hasTransparency(scaledBitmap)) {
                    Log.d(TAG, "  Keeping PNG due to transparency")
                    Bitmap.CompressFormat.PNG
                } else {
                    Log.d(TAG, "  FORCING JPEG compression for maximum size reduction")
                    Bitmap.CompressFormat.JPEG
                }

                val compressionQuality = if (finalFormat == Bitmap.CompressFormat.PNG) 100 else quality
                Log.d(TAG, "  Applying compression: format=$finalFormat, quality=$compressionQuality")

                val success = scaledBitmap.compress(finalFormat, compressionQuality, fos)
                fos.flush()

                if (!success) {
                    Log.e(TAG, "Failed to compress bitmap to output stream")
                    return false
                }

                Log.d(TAG, "  Compression to stream successful")
            }

            // Verify the output file was created and has content
            if (!outputFile.exists()) {
                Log.e(TAG, "Output file was not created")
                return false
            }

            if (outputFile.length() == 0L) {
                Log.e(TAG, "Output file is empty")
                return false
            }

            val originalSize = inputFile.length()
            val compressedSize = outputFile.length()

            // Force verification: If file sizes are identical, something went wrong
            if (originalSize == compressedSize && quality < 95) {
                Log.w(TAG, "WARNING: Compressed file has identical size to original despite quality < 95%")
                Log.w(TAG, "This suggests compression didn't work properly")

                // Force a VERY aggressive re-compression attempt
                Log.d(TAG, "Attempting VERY aggressive forced re-compression...")

                try {
                    FileOutputStream(outputFile).use { fos ->
                        val forcedQuality = 15 // Very low quality
                        val recompressSuccess = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, forcedQuality, fos)
                        fos.flush()

                        if (recompressSuccess) {
                            Log.d(TAG, "Forced re-compression completed with quality: $forcedQuality")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed forced re-compression", e)
                }
            }

            val finalCompressedSize = outputFile.length()
            val compressionRatio = if (originalSize > 0) {
                ((originalSize - finalCompressedSize) * 100 / originalSize).toInt()
            } else {
                0
            }
            val compressedWidth = scaledBitmap.width
            val compressedHeight = scaledBitmap.height

            // Clean up bitmaps
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            Log.d(TAG, "Image compression completed successfully:")
            Log.d(TAG, "  Original: ${originalSize / 1024}KB ($originalSize bytes)")
            Log.d(TAG, "  Compressed: ${finalCompressedSize / 1024}KB ($finalCompressedSize bytes)")
            Log.d(TAG, "  Compression ratio: $compressionRatio%")
            Log.d(TAG, "  Final dimensions: ${compressedWidth}x$compressedHeight")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
            false
        }
    }

    /**
     * Calculates the appropriate sample size for bitmap loading to reduce memory usage
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Calculates final dimensions while maintaining aspect ratio
     */
    private fun calculateFinalDimensions(
        originalWidth: Int,
        originalHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Int, Int> {
        // Always apply some reduction for compression, even if within limits
        val targetMaxWidth = kotlin.math.min(maxWidth, (originalWidth * 0.9).toInt())
        val targetMaxHeight = kotlin.math.min(maxHeight, (originalHeight * 0.9).toInt())

        if (originalWidth <= targetMaxWidth && originalHeight <= targetMaxHeight) {
            return Pair(originalWidth, originalHeight)
        }

        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()

        return if (originalWidth > originalHeight) {
            val width = kotlin.math.min(originalWidth, targetMaxWidth)
            val height = (width / aspectRatio).toInt()
            Pair(width, height)
        } else {
            val height = kotlin.math.min(originalHeight, targetMaxHeight)
            val width = (height * aspectRatio).toInt()
            Pair(width, height)
        }
    }

    /**
     * Checks if a bitmap has transparency
     */
    private fun hasTransparency(bitmap: Bitmap): Boolean = bitmap.hasAlpha() && bitmap.config == Bitmap.Config.ARGB_8888

    /**
     * Compresses a video file with specified parameters
     * @param inputFile The original video file
     * @param outputFile The file where the compressed video will be saved
     * @param videoBitrate Video bitrate in kbps
     * @param audioBitrate Audio bitrate in kbps
     * @param maxWidth Maximum width in pixels
     * @param maxHeight Maximum height in pixels
     * @param frameRate Target frame rate
     * @param progressCallback Optional callback for progress updates
     * @return true if compression was successful, false otherwise
     */
    @JvmStatic
    fun compressVideoFile(
        inputFile: File,
        outputFile: File,
        videoBitrate: Int,
        audioBitrate: Int,
        maxWidth: Int,
        maxHeight: Int,
        frameRate: Int,
        progressCallback: VideoCompressionProgressCallback? = null
    ): Boolean =
        try {
            Log.d(TAG, "compressVideoFile: Starting compression with parameters")
            Log.d(TAG, "compressVideoFile: videoBitrate=$videoBitrate, audioBitrate=$audioBitrate")
            Log.d(TAG, "compressVideoFile: maxWidth=$maxWidth, maxHeight=$maxHeight, frameRate=$frameRate")

            try {
                Log.d(TAG, "compressVideoFile: About to call compressVideoWithMediaCodec")
                compressVideoWithMediaCodec(
                    inputFile.absolutePath,
                    outputFile.absolutePath,
                    videoBitrate,
                    audioBitrate,
                    maxWidth,
                    maxHeight,
                    frameRate,
                    progressCallback
                )
            } catch (e: Exception) {
                Log.e(TAG, "compressVideoFile: INNER EXCEPTION in compressVideoWithMediaCodec call", e)
                Log.e(TAG, "compressVideoFile: INNER Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "compressVideoFile: INNER Exception message: ${e.message}")
                throw e // Re-throw to be caught by outer catch
            }
        } catch (e: Exception) {
            Log.e(TAG, "compressVideoFile: Exception caught", e)
            Log.e(TAG, "compressVideoFile: Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "compressVideoFile: Exception message: ${e.message}")
            e.printStackTrace()
            progressCallback?.onCompressionFailed("Video compression failed: ${e.message}", e)
            false
        }

    /**
     * Compresses a video file using MediaCodec and MediaMuxer
     */
    @Suppress("LongParameterList")
    private fun compressVideoWithMediaCodec(
        inputPath: String,
        outputPath: String,
        targetVideoBitrate: Int,
        targetAudioBitrate: Int,
        maxWidth: Int,
        maxHeight: Int,
        frameRate: Int,
        progressCallback: VideoCompressionProgressCallback? = null
    ): Boolean {
        Log.d(TAG, "compressVideoWithMediaCodec: Starting compression")
        Log.d(TAG, "compressVideoWithMediaCodec: Input: $inputPath")
        Log.d(TAG, "compressVideoWithMediaCodec: Output: $outputPath")
        Log.d(TAG, "compressVideoWithMediaCodec: Target bitrate: ${targetVideoBitrate}kbps")

        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var codecInputSurface: CodecInputSurface? = null
        var surfaceRenderer: SurfaceTextureRenderer? = null
        var muxerStarted = false

        try {
            // Get original file size for progress tracking
            val originalFile = File(inputPath)
            val originalSizeBytes = originalFile.length()
            Log.d(TAG, "compressVideoWithMediaCodec: Original file size: $originalSizeBytes bytes")

            progressCallback?.onCompressionStarted()

            // Initialize MediaExtractor
            Log.d(TAG, "compressVideoWithMediaCodec: Initializing MediaExtractor")
            extractor = MediaExtractor()
            
            try {
                extractor.setDataSource(inputPath)
                Log.d(TAG, "compressVideoWithMediaCodec: MediaExtractor initialized, track count: ${extractor.trackCount}")
            } catch (e: Exception) {
                Log.e(TAG, "compressVideoWithMediaCodec: Failed to set data source", e)
                progressCallback?.onCompressionFailed("Failed to initialize video decoder", e)
                return false
            }

            // Find video and audio tracks
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mimeType = format.getString(MediaFormat.KEY_MIME) ?: continue

                when {
                    mimeType.startsWith("video/") && videoTrackIndex == -1 -> {
                        videoTrackIndex = i
                        videoFormat = format
                    }
                    mimeType.startsWith("audio/") && audioTrackIndex == -1 -> {
                        audioTrackIndex = i
                        audioFormat = format
                    }
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "compressVideoWithMediaCodec: No video track found")
                progressCallback?.onCompressionFailed("No video track found", null)
                return false
            }

            Log.d(TAG, "compressVideoWithMediaCodec: Found video track at index $videoTrackIndex")
            if (audioTrackIndex != -1) {
                Log.d(TAG, "compressVideoWithMediaCodec: Found audio track at index $audioTrackIndex")
            } else {
                Log.d(TAG, "compressVideoWithMediaCodec: No audio track found")
            }

            // Get original video dimensions
            val originalWidth = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
            val originalHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            // Estimate total frames for progress tracking
            val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val videoFrameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else {
                30 // Default assumption
            }
            val estimatedTotalFrames = if (videoDurationUs > 0) {
                (videoDurationUs * videoFrameRate / 1_000_000).toInt()
            } else {
                1000 // Fallback estimate
            }

            // Detect video rotation from metadata
            val videoRotation = try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputPath)
                val rotationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val rotation = rotationString?.toIntOrNull() ?: 0
                retriever.release()
                Log.d(TAG, "compressVideoWithMediaCodec: Detected video rotation: $rotation degrees")
                rotation
            } catch (e: Exception) {
                Log.w(TAG, "compressVideoWithMediaCodec: Failed to extract rotation, assuming 0°", e)
                0
            }

            // Also check MediaFormat for rotation as fallback
            val formatRotation = if (videoFormat!!.containsKey(MediaFormat.KEY_ROTATION)) {
                videoFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }

            // Use the detected rotation (prefer MediaMetadataRetriever)
            val finalRotation = if (videoRotation != 0) videoRotation else formatRotation
            Log.d(TAG, "compressVideoWithMediaCodec: Using final rotation: $finalRotation degrees")

            // Adjust source dimensions based on rotation (swap width/height for 90°/270°)
            val (sourceWidth, sourceHeight) = if (finalRotation == 90 || finalRotation == 270) {
                Log.d(TAG, "compressVideoWithMediaCodec: Swapping dimensions due to rotation")
                Pair(originalHeight, originalWidth) // Swap for rotated video
            } else {
                Pair(originalWidth, originalHeight)
            }

            // Calculate new dimensions maintaining aspect ratio with corrected source dimensions
            val (newWidth, newHeight) = calculateNewDimensions(sourceWidth, sourceHeight, maxWidth, maxHeight)

            Log.d(TAG, "Original: ${originalWidth}x$originalHeight, Adjusted source: ${sourceWidth}x$sourceHeight")
            Log.d(TAG, "Target: ${newWidth}x$newHeight, Rotation: $finalRotation°")
            Log.d(TAG, "Estimated frames: $estimatedTotalFrames, Duration: ${videoDurationUs / 1_000_000}s")

            // Create output format for video
            val outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, newWidth, newHeight)
            outputVideoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetVideoBitrate * 1000) // Convert to bps
            outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            outputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            // Create MediaMuxer - DON'T add any tracks yet!
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Setup video encoder
            Log.d(TAG, "compressVideoWithMediaCodec: Creating video encoder")
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            Log.d(TAG, "compressVideoWithMediaCodec: Configuring video encoder")
            videoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.d(TAG, "compressVideoWithMediaCodec: Creating input surface")
            val encoderInputSurface = videoEncoder.createInputSurface()
            Log.d(TAG, "compressVideoWithMediaCodec: Starting video encoder")
            videoEncoder.start()

            // Setup audio encoder and decoder if audio track exists
            var audioDecoder: MediaCodec? = null
            var audioTrackIndexOutput = -1
            var audioExtractor: MediaExtractor? = null
            var hasAudio = false // Track whether audio is actually being processed
            
            // Enable audio processing with proper synchronization
            if (audioFormat != null) {
                Log.d(TAG, "compressVideoWithMediaCodec: Setting up audio processing")
                hasAudio = true // Mark that audio processing is active
                
                try {
                    // Create separate extractor for audio
                    audioExtractor = MediaExtractor()
                    audioExtractor.setDataSource(inputPath)
                    audioExtractor.selectTrack(audioTrackIndex)
                    
                    // Get original audio properties
                    val originalSampleRate = audioFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val originalChannels = audioFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    Log.d(TAG, "Original audio: ${originalSampleRate}Hz, $originalChannels channels")
                    
                    // Create audio output format (AAC, 64kbps) using original properties
                    val outputAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, originalSampleRate, originalChannels)
                    outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                    outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    
                    // Create audio encoder
                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    audioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    audioEncoder.start()
                    
                    // Create audio decoder
                    audioDecoder = MediaCodec.createDecoderByType(audioFormat!!.getString(MediaFormat.KEY_MIME)!!)
                    audioDecoder!!.configure(audioFormat, null, null, 0)
                    audioDecoder!!.start()
                    
                    Log.d(TAG, "compressVideoWithMediaCodec: Audio encoder and decoder started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to setup audio processing, continuing with video only", e)
                    hasAudio = false // Disable audio if setup fails
                    audioEncoder = null
                    audioDecoder = null
                    audioExtractor?.release()
                    audioExtractor = null
                }
            } else {
                Log.d(TAG, "compressVideoWithMediaCodec: No audio track found, processing video only")
            }

            // Create CodecInputSurface wrapper for encoder surface with OpenGL context
            val codecInputSurface = CodecInputSurface(encoderInputSurface)
            codecInputSurface.makeCurrent()

            // Setup SurfaceTextureRenderer for transformation
            val surfaceRenderer = SurfaceTextureRenderer()
            val decoderOutputTexture = surfaceRenderer.setup()

            // Configure viewport for scaling transformation with corrected dimensions
            surfaceRenderer.setViewport(newWidth, newHeight, sourceWidth, sourceHeight)
            
            // Configure rotation transformation
            surfaceRenderer.setRotation(finalRotation)

            Log.d(TAG, "compressVideoWithMediaCodec: OpenGL transformation setup complete with rotation: $finalRotation°")

            // Setup video decoder with SurfaceTexture as output
            Log.d(
                TAG,
                "compressVideoWithMediaCodec: Creating video decoder for ${videoFormat.getString(MediaFormat.KEY_MIME)}"
            )
            videoDecoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
            Log.d(TAG, "compressVideoWithMediaCodec: Configuring video decoder with SurfaceTexture")
            videoDecoder.configure(videoFormat, Surface(decoderOutputTexture), null, 0)
            Log.d(TAG, "compressVideoWithMediaCodec: Starting video decoder")
            videoDecoder.start()
            Log.d(TAG, "compressVideoWithMediaCodec: Video decoder started successfully")

            // Select video track initially - we'll switch between video and audio as needed
            extractor.selectTrack(videoTrackIndex)

            // Process video - Track will be added when encoder sends OUTPUT_FORMAT_CHANGED
            var videoTrackIndexOutput = -1
            var videoEncoderFormatReceived = false
            var audioEncoderFormatReceived = false

            // Audio processing variables - separate flags for each pipeline stage
            var audioExtractorDone = false  // Extractor has read all samples
            var audioDecoderOutputDone = false  // Decoder has output EOS
            var audioEncoderOutputDone = false  // Encoder has output EOS
            var audioIdleIterations = 0  // Count iterations where ALL audio steps timeout
            val audioDecoderBufferInfo = MediaCodec.BufferInfo()
            val audioEncoderBufferInfo = MediaCodec.BufferInfo()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var videoOutputDone = false
            var processedFrames = 0
            var lastProgressUpdate = System.currentTimeMillis()
            val progressUpdateInterval = 500 // Update every 500ms

            // Add timeout protection
            val startTime = System.currentTimeMillis()
            val maxProcessingTimeMs = 300_000L // 5 minutes maximum processing time
            var consecutiveTimeouts = 0
            val maxConsecutiveTimeouts = 200 // Allow up to 200 consecutive timeouts (20s at 100ms each)

            // Main processing loop - continue until both video AND audio are done
            while (!videoOutputDone || (hasAudio && !audioEncoderOutputDone)) {
                // Check for overall timeout
                if (System.currentTimeMillis() - startTime > maxProcessingTimeMs) {
                    Log.e(TAG, "Video compression timeout after ${maxProcessingTimeMs / 1000}s")
                    throw RuntimeException("Video compression timeout")
                }

                if (!videoOutputDone) {
                    // CRITICAL: Process decoder output first (feeds encoder via OpenGL)
                    val decoderStatus = videoDecoder.dequeueOutputBuffer(bufferInfo, 100L)
                    if (decoderStatus >= 0) {
                        Log.d(TAG, "compressVideoWithMediaCodec: Decoder output frame ready")

                        // Instead of rendering to surface directly, we render to SurfaceTexture
                        // which will be processed by OpenGL and then sent to encoder
                        videoDecoder.releaseOutputBuffer(decoderStatus, true) // true = render to SurfaceTexture

                        // Process the frame through OpenGL transformation
                        codecInputSurface.makeCurrent()
                        surfaceRenderer.drawFrame()

                        // Set presentation time for encoder
                        val presentationTimeUs = surfaceRenderer.getTimestamp() / 1000 // Convert ns to us
                        codecInputSurface.setPresentationTime(presentationTimeUs * 1000) // Convert back to ns

                        // Swap buffers to send frame to encoder
                        codecInputSurface.swapBuffers()

                        processedFrames++

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "compressVideoWithMediaCodec: Decoder EOS reached")
                            videoEncoder.signalEndOfInputStream()
                        }
                    } else if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // No decoder output yet
                    }

                    // Feed input to decoder
                    if (!inputDone) {
                        val inputBufferIndex = videoDecoder.dequeueInputBuffer(100L)
                        if (inputBufferIndex >= 0) {
                            consecutiveTimeouts = 0 // Reset timeout counter on successful dequeue
                            Log.d(TAG, "compressVideoWithMediaCodec: Got input buffer $inputBufferIndex, processing frame")
                            val inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize >= 0) {
                                videoDecoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            } else {
                                videoDecoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                                Log.d(TAG, "compressVideoWithMediaCodec: Input EOS sent to decoder")
                            }
                        }
                    }

                    // NOW check encoder output (AFTER decoder processes frames)
                    val encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, 100L)
                    when (encoderStatus) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "compressVideoWithMediaCodec: Encoder output format changed")
                            if (muxerStarted) {
                                throw RuntimeException("Format changed twice")
                            }
                            val newFormat = videoEncoder.outputFormat
                            Log.d(TAG, "compressVideoWithMediaCodec: Adding video track to muxer: $newFormat")
                            videoTrackIndexOutput = muxer.addTrack(newFormat)
                        
                            // Start muxer immediately when video format is ready
                            // Audio track will be added later if audio processing succeeds
                            Log.d(TAG, "compressVideoWithMediaCodec: Starting muxer with video track (audio will be added dynamically if available)")
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "compressVideoWithMediaCodec: Muxer started successfully")
                            consecutiveTimeouts = 0 // Reset on success
                        }

                        in 0..Int.MAX_VALUE -> {
                            consecutiveTimeouts = 0 // Reset timeout counter on successful dequeue
                            Log.d(TAG, "compressVideoWithMediaCodec: Got encoder output buffer $encoderStatus")
                        
                            // If muxer is not started yet, try to start it with a dummy format
                            if (!muxerStarted && videoTrackIndexOutput == -1) {
                                Log.w(TAG, "compressVideoWithMediaCodec: Encoder producing data but no format changed event. Creating track from current format.")
                                val currentFormat = videoEncoder.outputFormat
                                videoTrackIndexOutput = muxer.addTrack(currentFormat)
                                muxer.start()
                                muxerStarted = true
                                Log.d(TAG, "compressVideoWithMediaCodec: Muxer started with current encoder format")
                            }
                        
                            val outputBuffer = videoEncoder.getOutputBuffer(encoderStatus)!!

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d(TAG, "compressVideoWithMediaCodec: Skipping codec config buffer")
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size != 0 && muxerStarted) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                Log.d(TAG, "Writing video sample: ${bufferInfo.size} bytes, PTS: ${bufferInfo.presentationTimeUs}")
                                muxer.writeSampleData(videoTrackIndexOutput, outputBuffer, bufferInfo)
                                Log.v(TAG, "Video sample written successfully")

                                // Update progress tracking
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastProgressUpdate >= progressUpdateInterval) {
                                    val progress = if (estimatedTotalFrames > 0) {
                                        kotlin.math.min(99, (processedFrames * 100 / estimatedTotalFrames))
                                    } else {
                                        kotlin.math.min(99, processedFrames % 100)
                                    }

                                    // Estimate current compressed size
                                    val outputFile = File(outputPath)
                                    val currentCompressedSize = if (outputFile.exists()) outputFile.length() else 0L

                                    progressCallback?.onProgressUpdate(
                                        progress,
                                        processedFrames,
                                        estimatedTotalFrames,
                                        originalSizeBytes,
                                        currentCompressedSize
                                    )

                                    lastProgressUpdate = currentTime
                                    Log.d(TAG, "Progress: $progress% ($processedFrames/$estimatedTotalFrames frames)")
                                }
                            } else if (!muxerStarted && bufferInfo.size != 0) {
                                Log.w(TAG, "compressVideoWithMediaCodec: Encoder data available but muxer not started yet")
                            }

                            videoEncoder.releaseOutputBuffer(encoderStatus, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "compressVideoWithMediaCodec: Encoder EOS reached")
                                videoOutputDone = true
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No encoder output available
                            consecutiveTimeouts++
                            if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
                                Log.e(TAG, "Too many consecutive encoder timeouts ($consecutiveTimeouts)")
                                throw RuntimeException("Video compression stuck - encoder not producing output")
                            }
                        }

                        else -> {
                            Log.w(TAG, "compressVideoWithMediaCodec: Unexpected encoder status: $encoderStatus")
                        }
                    }


            } // End of video processing block (if !videoOutputDone)

            // Process audio if available (continues even after video is done)
            // Audio pipeline: Extractor → Decoder → Encoder → Muxer
            if (hasAudio && !audioEncoderOutputDone) {
                Log.d(TAG, "DEBUG: Audio block reached - hasAudio=$hasAudio, audioEncoderOutputDone=$audioEncoderOutputDone")
                
                var anyProgress = false  // Track if ANY audio operation succeeds this iteration
                
                audioEncoder?.let { encoder ->
                    audioDecoder?.let { decoder ->
                        // STEP 1: Feed input to audio decoder from extractor
                        if (!audioExtractorDone) {
                            val inputBufferIndex = decoder.dequeueInputBuffer(100L)
                            Log.d(TAG, "DEBUG STEP 1: dequeueInputBuffer returned: $inputBufferIndex")
                            
                            if (inputBufferIndex >= 0) {
                                anyProgress = true
                                
                                // CRITICAL: Check getSampleTrackIndex() FIRST!
                                val trackIndex = audioExtractor!!.getSampleTrackIndex()
                                
                                if (trackIndex < 0) {
                                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    audioExtractorDone = true
                                    Log.d(TAG, "Audio extractor finished (getSampleTrackIndex=-1) - EOS sent to decoder")
                                } else {
                                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                                    val sampleSize = audioExtractor.readSampleData(inputBuffer!!, 0)
                                    
                                    if (sampleSize > 0) {
                                        val presentationTimeUs = audioExtractor.sampleTime
                                        decoder.queueInputBuffer(
                                            inputBufferIndex,
                                            0,
                                            sampleSize,
                                            presentationTimeUs,
                                            audioExtractor.sampleFlags
                                        )
                                        audioExtractor.advance()
                                        Log.d(TAG, "Audio extractor→decoder: $sampleSize bytes, PTS: $presentationTimeUs")
                                    } else {
                                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        audioExtractorDone = true
                                        Log.d(TAG, "Audio extractor finished (sampleSize=$sampleSize) - EOS sent to decoder")
                                    }
                                }
                            } else {
                                Log.d(TAG, "DEBUG STEP 1: No input buffer available (timeout)")
                            }
                        }

                        // STEP 2: Process audio decoder output → encoder input
                        if (!audioDecoderOutputDone) {
                            val audioDecoderStatus = decoder.dequeueOutputBuffer(audioDecoderBufferInfo, 100L)
                            Log.d(TAG, "DEBUG STEP 2: dequeueOutputBuffer returned: $audioDecoderStatus")
                            
                            if (audioDecoderStatus >= 0) {
                                anyProgress = true
                                val decodedData = decoder.getOutputBuffer(audioDecoderStatus)
                                val hasEosFlag = (audioDecoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                
                                if (decodedData != null) {
                                    val encoderInputIndex = encoder.dequeueInputBuffer(100L)
                                    if (encoderInputIndex >= 0) {
                                        val encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex)
                                        encoderInputBuffer?.clear()
                                        
                                        if (audioDecoderBufferInfo.size > 0) {
                                            decodedData.position(audioDecoderBufferInfo.offset)
                                            decodedData.limit(audioDecoderBufferInfo.offset + audioDecoderBufferInfo.size)
                                            encoderInputBuffer?.put(decodedData)
                                            Log.d(TAG, "Audio decoder→encoder: ${audioDecoderBufferInfo.size} bytes, PTS: ${audioDecoderBufferInfo.presentationTimeUs}")
                                        }
                                        
                                        encoder.queueInputBuffer(
                                            encoderInputIndex,
                                            0,
                                            audioDecoderBufferInfo.size,
                                            audioDecoderBufferInfo.presentationTimeUs,
                                            audioDecoderBufferInfo.flags
                                        )
                                        
                                        if (hasEosFlag) {
                                            Log.d(TAG, "Audio decoder EOS propagated to encoder via queueInputBuffer")
                                        }
                                    } else {
                                        Log.w(TAG, "Audio encoder input buffer not available (timeout)")
                                    }
                                }
                                
                                decoder.releaseOutputBuffer(audioDecoderStatus, false)
                                
                                if (hasEosFlag) {
                                    audioDecoderOutputDone = true
                                    Log.d(TAG, "Audio decoder EOS detected - decoder stage complete")
                                }
                            } else {
                                Log.d(TAG, "DEBUG STEP 2: No output available (status: $audioDecoderStatus)")
                            }
                        }
                    } // End of audioDecoder?.let

                    // STEP 3: Process audio encoder output LAST
                    val audioEncoderStatus = encoder.dequeueOutputBuffer(audioEncoderBufferInfo, 100L)
                    Log.d(TAG, "DEBUG STEP 3: dequeueOutputBuffer returned: $audioEncoderStatus")
                    when (audioEncoderStatus) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            anyProgress = true
                            Log.d(TAG, "compressVideoWithMediaCodec: Audio encoder output format changed")
                            audioTrackIndexOutput = muxer!!.addTrack(encoder.outputFormat)
                            Log.d(TAG, "Audio track added to muxer: $audioTrackIndexOutput")
                            
                            if (videoTrackIndexOutput >= 0 && !muxerStarted) {
                                Log.d(TAG, "compressVideoWithMediaCodec: Starting muxer (both tracks ready)")
                                muxer.start()
                                muxerStarted = true
                                Log.d(TAG, "compressVideoWithMediaCodec: Muxer started successfully")
                            }
                        }
                        
                        in 0..Int.MAX_VALUE -> {
                            anyProgress = true
                            if (muxerStarted && audioTrackIndexOutput >= 0) {
                                if (audioEncoderBufferInfo.size > 0) {
                                    val encodedData = encoder.getOutputBuffer(audioEncoderStatus)
                                    encodedData?.let { buffer ->
                                        buffer.position(audioEncoderBufferInfo.offset)
                                        buffer.limit(audioEncoderBufferInfo.offset + audioEncoderBufferInfo.size)
                                        muxer!!.writeSampleData(audioTrackIndexOutput, buffer, audioEncoderBufferInfo)
                                        Log.d(TAG, "Audio sample written: ${audioEncoderBufferInfo.size} bytes, PTS: ${audioEncoderBufferInfo.presentationTimeUs}")
                                        
                                        // Update progress during audio processing
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastProgressUpdate >= progressUpdateInterval) {
                                            val outputFile = File(outputPath)
                                            val currentCompressedSize = if (outputFile.exists()) outputFile.length() else 0L
                                            
                                            // When video is done and we're only processing audio, show near-completion progress
                                            val progress = if (videoOutputDone) {
                                                // Video done, audio still processing: 95-99%
                                                kotlin.math.min(99, 95 + ((currentTime - startTime) % 4000) / 1000).toInt()
                                            } else {
                                                // Normal progress calculation based on frames
                                                if (estimatedTotalFrames > 0) {
                                                    kotlin.math.min(99, (processedFrames * 100 / estimatedTotalFrames))
                                                } else {
                                                    kotlin.math.min(99, processedFrames % 100)
                                                }
                                            }
                                            
                                            progressCallback?.onProgressUpdate(
                                                progress,
                                                processedFrames,
                                                estimatedTotalFrames,
                                                originalSizeBytes,
                                                currentCompressedSize
                                            )
                                            
                                            lastProgressUpdate = currentTime
                                            Log.d(TAG, "Audio progress: $progress%")
                                        }
                                    }
                                }
                            }
                            encoder.releaseOutputBuffer(audioEncoderStatus, false)
                            
                            if (audioEncoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                audioEncoderOutputDone = true
                                Log.d(TAG, "Audio encoder EOS reached - audio pipeline complete")
                            }
                        }
                        
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            Log.d(TAG, "DEBUG STEP 3: No output available (timeout)")
                        }
                    }
                } // End of audioEncoder?.let
                
                // CRITICAL: Idle detection
                if (anyProgress) {
                    audioIdleIterations = 0
                } else {
                    audioIdleIterations++
                    Log.d(TAG, "Audio pipeline idle (iteration $audioIdleIterations)")
                    
                    if (audioIdleIterations >= 30) {
                        Log.w(TAG, "Audio pipeline stuck after $audioIdleIterations idle iterations - forcing completion")
                        audioExtractorDone = true
                        audioDecoderOutputDone = true
                        audioEncoderOutputDone = true
                    }
                }
            } // End of if (hasAudio)
            }

            Log.d(TAG, "Video compression completed successfully")
            Log.d(TAG, "Muxer started: $muxerStarted, Video track: $videoTrackIndexOutput, Audio track: $audioTrackIndexOutput")

            // Send final 100% progress update before completion callback
            val outputFile = File(outputPath)
            val finalCompressedSize = if (outputFile.exists()) outputFile.length() else 0L
            
            progressCallback?.onProgressUpdate(
                100,
                estimatedTotalFrames,
                estimatedTotalFrames,
                originalSizeBytes,
                finalCompressedSize
            )
            Log.d(TAG, "Final progress update: 100%")

            // Report final completion with actual file sizes
            Log.d(TAG, "Output file exists: ${outputFile.exists()}, size: ${outputFile.length()}")
            val compressionRatio = if (originalSizeBytes > 0) {
                ((originalSizeBytes - finalCompressedSize) * 100 / originalSizeBytes).toInt()
            } else {
                0
            }

            progressCallback?.onCompressionCompleted(
                originalSizeBytes,
                finalCompressedSize,
                compressionRatio
            )

            return true
        } catch (e: Exception) {
            Log.e(TAG, "compressVideoWithMediaCodec: Error during video compression", e)
            Log.e(TAG, "compressVideoWithMediaCodec: Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "compressVideoWithMediaCodec: Exception message: ${e.message}")
            e.printStackTrace()
            progressCallback?.onCompressionFailed("Compression failed: ${e.message}", e)
            return false
        } finally {
            // Clean up resources
            try {
                extractor?.release()
                audioExtractor?.release()
                
                // Only stop muxer if it was started
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
                
                videoDecoder?.stop()
                videoDecoder?.release()
                videoEncoder?.stop()
                videoEncoder?.release()
                audioDecoder?.stop()
                audioDecoder?.release()
                audioEncoder?.stop()
                audioEncoder?.release()

                // Clean up OpenGL resources
                surfaceRenderer?.release()
                codecInputSurface?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up resources", e)
            }
        }
    }

    /**
     * Calculates new video dimensions while maintaining aspect ratio
     */
    private fun calculateNewDimensions(
        originalWidth: Int,
        originalHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Int, Int> {
        if (originalWidth <= maxWidth && originalHeight <= maxHeight) {
            return Pair(originalWidth, originalHeight)
        }

        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()

        val newWidth: Int
        val newHeight: Int

        if (aspectRatio > 1.0f) { // Landscape
            newWidth = minOf(originalWidth, maxWidth)
            newHeight = (newWidth / aspectRatio).toInt()
        } else { // Portrait or square
            newHeight = minOf(originalHeight, maxHeight)
            newWidth = (newHeight * aspectRatio).toInt()
        }

        // Ensure dimensions are even (required by many encoders)
        return Pair(newWidth and 0xFFFFFFFE.toInt(), newHeight and 0xFFFFFFFE.toInt())
    }

    /**
     * Compresses a video file using the specified compression level
     * @param inputFile The original video file
     * @param outputFile The file where the compressed video will be saved
     * @param compressionLevel The compression level to apply
     * @param progressCallback Optional callback for progress updates
     * @return true if compression was successful, false otherwise
     */
    @JvmStatic
    fun compressVideoFile(
        inputFile: File,
        outputFile: File,
        compressionLevel: VideoCompressionLevel,
        progressCallback: VideoCompressionProgressCallback? = null
    ): Boolean =
        if (compressionLevel == VideoCompressionLevel.NONE) {
            // No compression - just copy the file
            progressCallback?.onCompressionStarted()
            try {
                inputFile.copyTo(outputFile, overwrite = true)
                progressCallback?.onCompressionCompleted(
                    inputFile.length(),
                    outputFile.length(),
                    0 // No compression ratio for copy operation
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy video file for no compression", e)
                progressCallback?.onCompressionFailed("Failed to copy file: ${e.message}", e)
                false
            }
        } else {
            Log.d(TAG, "compressVideoFile: Using compression level ${compressionLevel.name}")
             Log.d(TAG, "compressVideoFile: compressionLevel.videoBitrate=${compressionLevel.videoBitrate}")
            Log.d(TAG, "compressVideoFile: compressionLevel.audioBitrate=${compressionLevel.audioBitrate}")
            Log.d(TAG, "compressVideoFile: compressionLevel.maxWidth=${compressionLevel.maxWidth}")
            Log.d(TAG, "compressVideoFile: compressionLevel.maxHeight=${compressionLevel.maxHeight}")
            Log.d(TAG, "compressVideoFile: compressionLevel.frameRate=${compressionLevel.frameRate}")

            compressVideoFile(
                inputFile,
                outputFile,
                compressionLevel.videoBitrate,
                compressionLevel.audioBitrate,
                compressionLevel.maxWidth,
                compressionLevel.maxHeight,
                compressionLevel.frameRate,
                progressCallback
            )
        }
}
