/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import autodagger.AutoInjector
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.talk.R
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import java.text.DecimalFormat
import java.util.UUID
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class VideoCompressionProgressDialog : DialogFragment() {

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var frameText: TextView
    private lateinit var sizeText: TextView
    private lateinit var timeText: TextView
    private lateinit var fileName: String
    private lateinit var workerId: UUID

    private val decimalFormat = DecimalFormat("#.#")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedApplication!!.componentApplication.inject(this)

        fileName = arguments?.getString(KEY_FILE_NAME) ?: "Video"
        workerId = UUID.fromString(arguments?.getString(KEY_WORKER_ID) ?: "")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_video_compression_progress, null)

        initializeViews(view)
        setupProgressTracking()

        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.nc_video_compression_progress_title))
            .setView(view)
            .setNegativeButton(getString(R.string.nc_cancel)) { _, _ ->
                cancelCompression()
            }
            .setCancelable(false)

        viewThemeUtils.dialog.colorMaterialAlertDialogBackground(requireContext(), dialogBuilder)

        return dialogBuilder.create()
    }

    private fun initializeViews(view: View) {
        progressBar = view.findViewById(R.id.compression_progress_bar)
        progressText = view.findViewById(R.id.compression_progress_text)
        frameText = view.findViewById(R.id.compression_frame_text)
        sizeText = view.findViewById(R.id.compression_size_text)
        timeText = view.findViewById(R.id.compression_time_text)

        // Apply theme colors
        viewThemeUtils.platform.colorTextView(progressText)
        viewThemeUtils.platform.colorTextView(frameText)
        viewThemeUtils.platform.colorTextView(sizeText)
        viewThemeUtils.platform.colorTextView(timeText)

        // Initialize with default values
        progressText.text = getString(R.string.nc_video_compression_starting, fileName)
        frameText.text = getString(R.string.nc_video_compression_frames, 0, 0)
        sizeText.text = getString(R.string.nc_video_compression_size, "0 MB", "0 MB")
        timeText.text = getString(R.string.nc_video_compression_time_remaining, "Berechne...")
    }

    private fun setupProgressTracking() {
        WorkManager.getInstance(requireContext())
            .getWorkInfoByIdLiveData(workerId)
            .observe(this) { workInfo ->
                workInfo?.let { updateProgress(it) }
            }
    }

    private fun updateProgress(workInfo: WorkInfo) {
        val progress = workInfo.progress
        val status = progress.getString("status")

        when (status) {
            "compressing" -> {
                val percentage = progress.getInt("progress", 0)
                val phase = progress.getString("phase") ?: ""
                val processedFrames = progress.getInt("processedFrames", 0)
                val estimatedTotalFrames = progress.getInt("estimatedTotalFrames", 0)
                val currentSizeBytes = progress.getLong("currentSizeBytes", 0L)
                val originalSizeBytes = progress.getLong("originalSizeBytes", 0L)
                val timeLeftSeconds = progress.getLong("estimatedTimeLeftSeconds", 0L)

                // Update progress bar and percentage
                progressBar.progress = percentage
                progressText.text = "$phase ($percentage%)"

                // Update frame information
                frameText.text = getString(
                    R.string.nc_video_compression_frames,
                    processedFrames,
                    estimatedTotalFrames
                )

                // Update size information
                val currentSizeMB = currentSizeBytes / (1024f * 1024f)
                val originalSizeMB = originalSizeBytes / (1024f * 1024f)
                sizeText.text = getString(
                    R.string.nc_video_compression_size,
                    decimalFormat.format(currentSizeMB) + " MB",
                    decimalFormat.format(originalSizeMB) + " MB"
                )

                // Update time remaining
                if (timeLeftSeconds > 0) {
                    val timeRemaining = if (timeLeftSeconds < 60) {
                        "${timeLeftSeconds}s"
                    } else {
                        val minutes = timeLeftSeconds / 60
                        val seconds = timeLeftSeconds % 60
                        "${minutes}m ${seconds}s"
                    }
                    timeText.text = getString(R.string.nc_video_compression_time_remaining, timeRemaining)
                } else {
                    timeText.text = getString(R.string.nc_video_compression_time_remaining, "Berechne...")
                }
            }

            "compression_completed" -> {
                val originalSizeBytes = progress.getLong("originalSizeBytes", 0L)
                val compressedSizeBytes = progress.getLong("compressedSizeBytes", 0L)
                val compressionRatio = progress.getFloat("compressionRatio", 0f)

                progressBar.progress = 100
                progressText.text = getString(R.string.nc_video_compression_completed)

                val originalSizeMB = originalSizeBytes / (1024f * 1024f)
                val compressedSizeMB = compressedSizeBytes / (1024f * 1024f)
                val savings = (compressionRatio * 100).toInt()

                sizeText.text = getString(
                    R.string.nc_video_compression_final_size,
                    decimalFormat.format(originalSizeMB) + " MB",
                    decimalFormat.format(compressedSizeMB) + " MB",
                    savings
                )

                timeText.text = getString(R.string.nc_video_compression_completed_time)

                // Auto-dismiss after 2 seconds
                view?.postDelayed({
                    if (isAdded && !isDetached) {
                        dismiss()
                    }
                }, 2000)
            }

            "compression_failed" -> {
                val error = progress.getString("error") ?: "Unbekannter Fehler"
                progressText.text = getString(R.string.nc_video_compression_failed, error)
                timeText.text = getString(R.string.nc_video_compression_failed_time)

                // Auto-dismiss after 3 seconds
                view?.postDelayed({
                    if (isAdded && !isDetached) {
                        dismiss()
                    }
                }, 3000)
            }
        }
    }

    private fun cancelCompression() {
        WorkManager.getInstance(requireContext()).cancelWorkById(workerId)
        dismiss()
    }

    companion object {
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_WORKER_ID = "worker_id"

        fun newInstance(fileName: String, workerId: UUID): VideoCompressionProgressDialog {
            val dialog = VideoCompressionProgressDialog()
            val args = Bundle().apply {
                putString(KEY_FILE_NAME, fileName)
                putString(KEY_WORKER_ID, workerId.toString())
            }
            dialog.arguments = args
            return dialog
        }
    }
}
