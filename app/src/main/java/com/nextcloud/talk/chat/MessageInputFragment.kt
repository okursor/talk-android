/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Julius Linus juliuslinus1@gmail.com
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.Animation.AnimationListener
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.SeekBar
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.emoji2.widget.EmojiTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import autodagger.AutoInjector
import coil.Coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.target.Target
import coil.transform.CircleCropTransformation
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.android.common.ui.theme.utils.ColorRole
import com.nextcloud.talk.R
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.callbacks.MentionAutocompleteCallback
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.chat.viewmodels.ChatViewModel
import com.nextcloud.talk.chat.viewmodels.MessageInputViewModel
import com.nextcloud.talk.data.network.NetworkMonitor
import com.nextcloud.talk.databinding.FragmentMessageInputBinding
import com.nextcloud.talk.utils.preferences.AppPreferencesImpl
import com.nextcloud.talk.jobs.UploadAndShareFilesWorker
import com.nextcloud.talk.models.json.chat.ChatUtils
import com.nextcloud.talk.models.json.mention.Mention
import com.nextcloud.talk.models.json.signaling.NCSignalingMessage
import com.nextcloud.talk.presenters.MentionAutocompletePresenter
import com.nextcloud.talk.ui.MicInputCloud
import com.nextcloud.talk.ui.dialog.AttachmentDialog
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.CapabilitiesUtil
import com.nextcloud.talk.utils.CharPolicy
import com.nextcloud.talk.utils.EmojiTextInputEditText
import com.nextcloud.talk.utils.ImageEmojiEditText
import com.nextcloud.talk.utils.SpreedFeatures
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.database.user.CurrentUserProviderNew
import com.nextcloud.talk.utils.message.MessageUtils
import com.nextcloud.talk.utils.text.Spans
import com.otaliastudios.autocomplete.Autocomplete
import com.vanniktech.emoji.EmojiPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Objects
import javax.inject.Inject

@Suppress("LongParameterList", "TooManyFunctions", "LargeClass", "LongMethod")
@AutoInjector(NextcloudTalkApplication::class)
class MessageInputFragment : Fragment() {

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var currentUserProvider: CurrentUserProviderNew

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var messageUtils: MessageUtils

    lateinit var binding: FragmentMessageInputBinding
    private lateinit var conversationInternalId: String
    private var typedWhileTypingTimerIsRunning: Boolean = false
    private var typingTimer: CountDownTimer? = null
    private lateinit var chatActivity: ChatActivity
    private var emojiPopup: EmojiPopup? = null
    private var mentionAutocomplete: Autocomplete<*>? = null
    private var xcounter = 0f
    private var ycounter = 0f
    private var collapsed = false
    // (removed persistent listener flag) layout listeners are now one-shot to avoid races

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedApplication!!.componentApplication.inject(this)
        conversationInternalId = arguments?.getString(ChatActivity.CONVERSATION_INTERNAL_ID).orEmpty()
        chatActivity = requireActivity() as ChatActivity
        val sharedText = arguments?.getString(BundleKeys.KEY_SHARED_TEXT).orEmpty()
        if (sharedText.isNotEmpty()) {
            chatActivity.chatViewModel.messageDraft.messageText = sharedText
            chatActivity.chatViewModel.saveMessageDraft()
        }
        if (conversationInternalId.isEmpty()) {
            Log.e(TAG, "internalId for conversation passed to MessageInputFragment is empty")
        }
    }

    // Helper: recompute vertical padding and baseline nudge for the inputEditText.
    // Call this when text changes (including switching between empty and non-empty)
    // to prevent the hint from jumping and being clipped.
    private fun recenterInputField() {
        try {
            val input = binding.fragmentMessageInputView.inputEditText ?: return
            // Only recenter when the input is empty (i.e., hint is visible).
            val currentText = try { input.text } catch (_: Throwable) { null }
            if (currentText != null && currentText.isNotEmpty()) return

            // Determine a reliable reference button height to center against:
            // prefer the measured attachment button or smiley button if available (reflects actual layout),
            // fallback to base 48dp otherwise.
            val density = resources.displayMetrics.density
            val fallbackButtonPx = (48f * density).toInt()
            val measuredBtn = try { binding.fragmentMessageInputView.attachmentButton.measuredHeight.takeIf { it > 0 } } catch (_: Throwable) { null }
            val measuredSmiley = try { binding.fragmentMessageInputView.smileyButton.measuredHeight.takeIf { it > 0 } } catch (_: Throwable) { null }
            val buttonPx = measuredBtn ?: measuredSmiley ?: fallbackButtonPx

            val paint = input.paint
            val fm = paint.fontMetrics
            val textHeightPx = kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt()
            val verticalPad = kotlin.math.max(0, (buttonPx - textHeightPx) / 2)

            try {
                val curTop = input.paddingTop
                val curBottom = input.paddingBottom
                if (kotlin.math.abs(curTop - verticalPad) >= 2 || kotlin.math.abs(curBottom - verticalPad) >= 2) {
                    input.setPadding(input.paddingLeft, verticalPad, input.paddingRight, verticalPad)
                }
            } catch (_: Throwable) {}

            input.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            input.includeFontPadding = false

            // If layout is already available, apply an immediate baseline nudge to align visual center.
            val layout = try { (input as android.widget.TextView).layout } catch (_: Throwable) { null }
            if (layout != null && input.height > 0) {
                try {
                    val baseline = layout.getLineBaseline(0)
                    val fm2 = input.paint.fontMetrics
                    val textCenter = baseline + (fm2.ascent + fm2.descent) / 2f
                    val viewCenter = input.height / 2f
                    val delta = kotlin.math.round(viewCenter - textCenter).toInt()
                    if (kotlin.math.abs(delta) >= 2) {
                        val minExtra = kotlin.math.max(2, kotlin.math.ceil(kotlin.math.abs(fm2.ascent) * 0.10).toInt())
                        val half = delta / 2
                        val remainder = delta - half
                        val desiredTop = (input.paddingTop + half).coerceAtLeast(minExtra)
                        val desiredBottom = (input.paddingBottom - remainder).coerceAtLeast(minExtra)
                        if (kotlin.math.abs(input.paddingTop - desiredTop) >= 2 || kotlin.math.abs(input.paddingBottom - desiredBottom) >= 2) {
                            input.setPadding(input.paddingLeft, desiredTop, input.paddingRight, desiredBottom)
                        }
                    }
                } catch (_: Throwable) {}
            } else {
                // Otherwise attach a one-shot layout listener to run immediately after the next layout pass.
                try {
                    val oneShot = object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View?, left: Int, top: Int, right: Int, bottom: Int,
                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                        ) {
                            try {
                                val edtView = v as? android.widget.TextView ?: return
                                val layout2 = edtView.layout
                                if (layout2 != null && edtView.height > 0) {
                                    val baseline = layout2.getLineBaseline(0)
                                    val fm2 = edtView.paint.fontMetrics
                                    val textCenter = baseline + (fm2.ascent + fm2.descent) / 2f
                                    val viewCenter = edtView.height / 2f
                                    val delta = kotlin.math.round(viewCenter - textCenter).toInt()
                                    if (kotlin.math.abs(delta) >= 2) {
                                        val minExtra = kotlin.math.max(2, kotlin.math.ceil(kotlin.math.abs(fm2.ascent) * 0.10).toInt())
                                        val half = delta / 2
                                        val remainder = delta - half
                                        val desiredTop = (edtView.paddingTop + half).coerceAtLeast(minExtra)
                                        val desiredBottom = (edtView.paddingBottom - remainder).coerceAtLeast(minExtra)
                                        if (kotlin.math.abs(edtView.paddingTop - desiredTop) >= 2 || kotlin.math.abs(edtView.paddingBottom - desiredBottom) >= 2) {
                                            edtView.setPadding(edtView.paddingLeft, desiredTop, edtView.paddingRight, desiredBottom)
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                            v?.removeOnLayoutChangeListener(this)
                        }
                    }
                    input.addOnLayoutChangeListener(oneShot)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentMessageInputBinding.inflate(inflater)
        themeMessageInputView()
        initMessageInputView()
        initSmileyKeyboardToggler()
        setupMentionAutocomplete()
        initVoiceRecordButton()
        initThreadHandling()
        restoreState()
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (mentionAutocomplete != null && mentionAutocomplete!!.isPopupShowing) {
            mentionAutocomplete?.dismissPopup()
        }
        clearEditUI()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initObservers()

        binding.fragmentCreateThreadView.createThreadView.findViewById<EmojiTextInputEditText>(
            R.id
                .createThreadInput
        ).doAfterTextChanged { text ->
            val threadTitle = text.toString()
            chatActivity.chatViewModel.messageDraft.threadTitle = threadTitle
        }

        // Apply input scaling only to the internal buttons (do not touch the whole view)
        try {
                val scale = com.nextcloud.talk.utils.preferences.AppPreferencesImpl(requireContext()).getFontScale()
                applyMessageInputButtonsScaling(scale)
        } catch (_: Throwable) {}
    }

    override fun onStart() {
        super.onStart()
        // Re-apply scaling only to buttons in case layout/insets changed while fragment was paused
        try {
            val scale = try {
                com.nextcloud.talk.utils.preferences.AppPreferencesImpl(requireContext()).getFontScale()
            } catch (_: Throwable) {
                resources.configuration.fontScale
            }
            applyMessageInputButtonsScaling(scale)
        } catch (_: Throwable) {}
    }

    // Adjust only the ImageButtons inside the MessageInput so the parent view size is untouched
    private fun applyMessageInputButtonsScaling(fontScale: Float) {
        try {
            if (fontScale <= 1.05f) return

            // base sizes (dp) — compute final size from user preference multiplier
            val baseButtonDp = 48f
            val baseIconDp = 24f
            val minIconDp = 16f
            val maxIconDp = 32f

            // Use the numeric fontScale preference (passed in) as multiplier.
            // Clamp to reasonable bounds to avoid extreme sizes (1.0..2.0)
            val sizeMultiplier = kotlin.math.max(1.0f, kotlin.math.min(fontScale, 2.0f))

            val targetButtonDp = baseButtonDp * sizeMultiplier
            val targetIconDp = baseIconDp * sizeMultiplier

            val targetIconClampedDp = kotlin.math.max(minIconDp * sizeMultiplier, kotlin.math.min(targetIconDp, maxIconDp * sizeMultiplier))
            val density = resources.displayMetrics.density
            val buttonPx = (targetButtonDp * density).toInt()
            val iconPx = (targetIconClampedDp * density).toInt()
            val pad = kotlin.math.max(0, (buttonPx - iconPx) / 2)

            val btns = listOfNotNull(
                runCatching { binding.fragmentMessageInputView.attachmentButton }.getOrNull(),
                runCatching { binding.fragmentMessageInputView.smileyButton }.getOrNull(),
                runCatching { binding.fragmentMessageInputView.messageSendButton }.getOrNull(),
                runCatching { binding.fragmentMessageInputView.recordAudioButton }.getOrNull(),
                runCatching { binding.fragmentMessageInputView.submitThreadButton }.getOrNull(),
                runCatching { binding.fragmentMessageInputView.editMessageButton }.getOrNull()
            )

            Log.d("MessageInputFrag", "applyMessageInputButtonsScaling: multiplier=$sizeMultiplier fontScale=$fontScale, buttonPx=$buttonPx, iconPx=$iconPx, pad=$pad")
            btns.forEach { btn ->
                try {
                    if (btn is ImageView) {
                        btn.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        btn.setPadding(pad, pad, pad, pad)
                        try {
                            val iconDrawable = btn.drawable
                            if (iconDrawable != null) {
                                // replace with a bitmap of the desired icon size so intrinsic size increases
                                val bmp = iconDrawable.toBitmap(iconPx, iconPx)
                                btn.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp))
                            }
                        } catch (_: Throwable) {}
                        // Ensure the ImageView does not resize its bounds to the drawable
                        try {
                            btn.adjustViewBounds = false
                            val lp = btn.layoutParams
                            if (lp != null) {
                                lp.width = buttonPx
                                lp.height = buttonPx
                                btn.layoutParams = lp
                                // also set minimums to avoid future measure increases
                                try { btn.minimumWidth = buttonPx; btn.minimumHeight = buttonPx } catch (_: Throwable) {}
                                Log.d("MessageInputFrag", "applied lp to btn desc='${btn.contentDescription}' resId='${btn.id}' -> width=${lp.width} height=${lp.height}")
                                // post a runnable to log actual measured size after layout
                                btn.post {
                                    try {
                                        Log.d("MessageInputFrag", "btn measured: desc='${btn.contentDescription}' id=${btn.id} measuredW=${btn.measuredWidth} measuredH=${btn.measuredHeight} layoutW=${btn.width} layoutH=${btn.height}")
                                    } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    } else {
                        try { btn.setPadding(pad, pad, pad, pad) } catch (_: Throwable) {}
                    }
                    // do not change layoutParams of parent; keep parent measured size
                } catch (_: Throwable) {}
            }
            // after applying sizes, log parent and key children measured/layout sizes
            try {
                binding.fragmentMessageInputView.post {
                    try {
                        val parent = binding.fragmentMessageInputView
                        Log.d("MessageInputFrag", "parent measured: measuredW=${parent.measuredWidth} measuredH=${parent.measuredHeight} layoutW=${parent.width} layoutH=${parent.height}")
                        val children = listOf(
                            binding.fragmentMessageInputView.attachmentButton,
                            binding.fragmentMessageInputView.smileyButton,
                            binding.fragmentMessageInputView.messageSendButton,
                            binding.fragmentMessageInputView.recordAudioButton,
                            binding.fragmentMessageInputView.submitThreadButton,
                            binding.fragmentMessageInputView.editMessageButton,
                            binding.fragmentMessageInputView.inputEditText
                        )
                        children.forEach { c ->
                            try {
                                val resName = try { resources.getResourceEntryName(c.id) } catch (_: Throwable) { "no-name" }
                                Log.d("MessageInputFrag", "child id=${c.id} name=$resName vis=${c.visibility} measuredW=${c.measuredWidth} measuredH=${c.measuredHeight} layoutW=${c.width} layoutH=${c.height}")
                            } catch (_: Throwable) {}
                        }
                        // Scale the EditText together with buttons: adjust text size, minHeight and height
                        try {
                            val input = binding.fragmentMessageInputView.inputEditText
                            // Scale the text moderately: we only apply a fraction of the button multiplier
                            // so the text grows but not as aggressively as the full fontScale.
                            val baseTextSpId = R.dimen.chat_text_size
                            val baseTextPx = resources.getDimension(baseTextSpId) // px using scaledDensity
                            val baseSp = baseTextPx / resources.displayMetrics.scaledDensity
                            // textScale: linear interpolation between 1.0 and 1.0 + 0.5*(sizeMultiplier-1)
                            val textScale = 1.0f + (sizeMultiplier - 1.0f) * 0.5f
                            val desiredSp = baseSp * textScale
                            val desiredPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, desiredSp, resources.displayMetrics)
                            input.setTextSize(TypedValue.COMPLEX_UNIT_PX, desiredPx)

                            // ensure minimum and single-line height matches button size; allow wrap for multiline
                            try { input.minHeight = buttonPx } catch (_: Throwable) {}
                            try {
                                val lp = input.layoutParams
                                if (lp != null) {
                                    val lineCount = try { input.lineCount } catch (_: Throwable) { 1 }
                                    if (lineCount <= 1) {
                                        lp.height = buttonPx
                                        try { input.setLines(1); input.maxLines = 1 } catch (_: Throwable) {}
                                        try { input.setHeight(buttonPx); input.minHeight = buttonPx } catch (_: Throwable) {}
                                        Log.d("MessageInputFrag", "input single-line: enforcing height=$buttonPx via lp+setHeight+maxLines")
                                    } else {
                                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                        try { input.maxLines = Integer.MAX_VALUE } catch (_: Throwable) {}
                                        Log.d("MessageInputFrag", "input multiline (lines=$lineCount): allow wrap_content")
                                    }
                                    input.layoutParams = lp
                                }
                            } catch (_: Throwable) {}

                            // center text vertically: compute font metrics and add symmetric padding so the
                                // center text and hint vertically inside the target button height
                                try {
                                    // compute font metrics-based text height
                                    val paint = input.paint
                                    val fm = paint.fontMetrics
                                    val textHeightPx = kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt()
                                    // compute vertical padding needed to center text within buttonPx
                                    val verticalPad = kotlin.math.max(0, (buttonPx - textHeightPx) / 2)
                                    // apply symmetric vertical padding so both text and hint align
                                    input.setPadding(input.paddingLeft, verticalPad, input.paddingRight, verticalPad)
                                    input.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                                    input.includeFontPadding = false
                                    Log.d("MessageInputFrag", "input centering: textH=$textHeightPx buttonH=$buttonPx pad=$verticalPad")
                            } catch (_: Throwable) {}

                            // Keep background as-is to preserve drawable insets which prevent glyph clipping

                            // force a layout pass so the changes take effect immediately
                            try {
                                input.invalidate(); input.requestLayout()
                                try { binding.fragmentMessageInputView.invalidate(); binding.fragmentMessageInputView.requestLayout() } catch (_: Throwable) {}
                            } catch (_: Throwable) {}

                            // Post-check after the next layout pass using a one-shot listener (deterministic).
                            try {
                                val oneShotLayout = object : View.OnLayoutChangeListener {
                                    override fun onLayoutChange(
                                        v: View?, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                                    ) {
                                        try {
                                            try {
                                                Log.d("MessageInputFrag", "post-check input padding top=${input.paddingTop} bottom=${input.paddingBottom} measuredH=${input.measuredHeight} layoutH=${input.height} lineCount=${input.lineCount} textPx=${desiredPx}")
                                            } catch (_: Throwable) {}

                                            try {
                                                val edt = input
                                                val layout2 = (edt as android.widget.TextView).layout
                                                if (layout2 != null && edt.height > 0) {
                                                    val baseline = layout2.getLineBaseline(0)
                                                    val fm2 = edt.paint.fontMetrics
                                                    val textCenter = baseline + (fm2.ascent + fm2.descent) / 2f
                                                    val viewCenter = edt.height / 2f
                                                    val delta = kotlin.math.round(viewCenter - textCenter).toInt()
                                                    if (kotlin.math.abs(delta) >= 2) {
                                                        val minExtra = kotlin.math.max(2, kotlin.math.ceil(kotlin.math.abs(fm2.ascent) * 0.10).toInt())
                                                        val half = delta / 2
                                                        val remainder = delta - half
                                                        val newTop = (edt.paddingTop + half).coerceAtLeast(minExtra)
                                                        val newBottom = (edt.paddingBottom - remainder).coerceAtLeast(minExtra)
                                                        if (kotlin.math.abs(edt.paddingTop - newTop) >= 2 || kotlin.math.abs(edt.paddingBottom - newBottom) >= 2) {
                                                            edt.setPadding(edt.paddingLeft, newTop, edt.paddingRight, newBottom)
                                                            edt.invalidate(); edt.requestLayout()
                                                        }
                                                        Log.d("MessageInputFrag", "baseline adjust: baseline=$baseline textCenter=$textCenter viewCenter=$viewCenter delta=$delta newPadTop=${newTop} newPadBottom=${newBottom} minExtra=${minExtra}")
                                                    }
                                                }
                                            } catch (_: Throwable) {}

                                            try {
                                                val parent = binding.fragmentMessageInputView
                                                Log.d("MessageInputFrag", "post-check parent measured: measuredW=${parent.measuredWidth} measuredH=${parent.measuredHeight} layoutW=${parent.width} layoutH=${parent.height}")
                                                val children = listOf(
                                                    binding.fragmentMessageInputView.attachmentButton,
                                                    binding.fragmentMessageInputView.smileyButton,
                                                    binding.fragmentMessageInputView.messageSendButton,
                                                    binding.fragmentMessageInputView.recordAudioButton,
                                                    binding.fragmentMessageInputView.submitThreadButton,
                                                    binding.fragmentMessageInputView.editMessageButton,
                                                    binding.fragmentMessageInputView.inputEditText
                                                )
                                                children.forEach { c ->
                                                    try {
                                                        val resName = try { resources.getResourceEntryName(c.id) } catch (_: Throwable) { "no-name" }
                                                        Log.d("MessageInputFrag", "post-check child id=${c.id} name=$resName vis=${c.visibility} measuredW=${c.measuredWidth} measuredH=${c.measuredHeight} layoutW=${c.width} layoutH=${c.height}")
                                                    } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {}
                                        } catch (_: Throwable) {}
                                        v?.removeOnLayoutChangeListener(this)
                                    }
                                }
                                input.addOnLayoutChangeListener(oneShotLayout)
                            } catch (_: Throwable) {}

                            Log.d("MessageInputFrag", "scaled input text: baseSp=$baseSp textScale=$textScale desiredSp=$desiredSp desiredPx=$desiredPx enforcedH=$buttonPx minH=${input.minHeight}")
                        } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun initObservers() {
        Log.d(TAG, "LifeCyclerOwner is: ${viewLifecycleOwner.lifecycle}")
        chatActivity.messageInputViewModel.getReplyChatMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                chatActivity.chatViewModel.messageDraft.quotedMessageText = message.text
                chatActivity.chatViewModel.messageDraft.quotedDisplayName = message.actorDisplayName
                chatActivity.chatViewModel.messageDraft.quotedImageUrl = message.imageUrl
                chatActivity.chatViewModel.messageDraft.quotedJsonId = message.jsonMessageId
                replyToMessage(
                    message.text,
                    message.actorDisplayName,
                    message.imageUrl
                )
            } ?: clearReplyUi()
        }

        chatActivity.messageInputViewModel.getEditChatMessage.observe(viewLifecycleOwner) { message ->
            message?.let { setEditUI(it as ChatMessage) }
        }

        chatActivity.messageInputViewModel.createThreadViewState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is MessageInputViewModel.CreateThreadStartState ->
                    binding.fragmentCreateThreadView.createThreadView.visibility = View.GONE

                is MessageInputViewModel.CreateThreadEditState -> {
                    binding.fragmentCreateThreadView.createThreadView.visibility = View.VISIBLE
                    binding.fragmentCreateThreadView.createThreadView
                        .findViewById<EmojiTextInputEditText>(R.id.createThreadInput)?.setText(
                            chatActivity.chatViewModel.messageDraft.threadTitle
                        )
                }

                else -> {}
            }
            initVoiceRecordButton()
        }

        chatActivity.chatViewModel.leaveRoomViewState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ChatViewModel.LeaveRoomSuccessState -> sendStopTypingMessage()
                else -> {}
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            var wasOnline: Boolean
            networkMonitor.isOnline
                .onEach { isOnline ->
                    wasOnline = !binding.fragmentConnectionLost.isShown
                    val connectionGained = (!wasOnline && isOnline)
                    Log.d(TAG, "isOnline: $isOnline\nwasOnline: $wasOnline\nconnectionGained: $connectionGained")
                    if (connectionGained) {
                        chatActivity.messageInputViewModel.sendUnsentMessages(
                            chatActivity.conversationUser!!.getCredentials(),
                            ApiUtils.getUrlForChat(
                                chatActivity.chatApiVersion,
                                chatActivity.conversationUser!!.baseUrl!!,
                                chatActivity.roomToken
                            )
                        )
                    }
                    handleUI(isOnline, connectionGained)
                }.collect()
        }

        chatActivity.messageInputViewModel.callStartedFlow.observe(viewLifecycleOwner) {
            val (message, show) = it
            if (show) {
                binding.fragmentCallStarted.callAuthorChip.text = message.actorDisplayName
                binding.fragmentCallStarted.callAuthorChipSecondary.text = message.actorDisplayName
                val user = currentUserProvider.currentUser.blockingGet()
                val url: String = if (message.actorType == "guests" || message.actorType == "guest") {
                    ApiUtils.getUrlForGuestAvatar(user!!.baseUrl!!, message.actorDisplayName, true)
                } else {
                    ApiUtils.getUrlForAvatar(user!!.baseUrl!!, message.actorId, false)
                }

                val imageRequest: ImageRequest = ImageRequest.Builder(requireContext())
                    .data(url)
                    .crossfade(true)
                    .transformations(CircleCropTransformation())
                    .target(object : Target {
                        override fun onStart(placeholder: Drawable?) {
                            // unused atm
                        }

                        override fun onError(error: Drawable?) {
                            // unused atm
                        }

                        override fun onSuccess(result: Drawable) {
                            binding.fragmentCallStarted.callAuthorChip.chipIcon = result
                            binding.fragmentCallStarted.callAuthorChipSecondary.chipIcon = result
                        }
                    })
                    .build()

                imageLoader(requireContext()).enqueue(imageRequest)
                binding.fragmentCallStarted.root.visibility = View.VISIBLE
            } else {
                binding.fragmentCallStarted.root.visibility = View.GONE
            }
        }
    }

    private fun handleUI(isOnline: Boolean, connectionGained: Boolean) {
        if (isOnline) {
            if (connectionGained) {
                val animation: Animation = AlphaAnimation(FULLY_OPAQUE, FULLY_TRANSPARENT)
                animation.duration = CONNECTION_ESTABLISHED_ANIM_DURATION
                animation.interpolator = LinearInterpolator()
                binding.fragmentConnectionLost.setBackgroundColor(resources.getColor(R.color.hwSecurityGreen))
                binding.fragmentConnectionLost.text = getString(R.string.connection_established)
                binding.fragmentConnectionLost.startAnimation(animation)
                binding.fragmentConnectionLost.animation.setAnimationListener(object : AnimationListener {
                    override fun onAnimationStart(animation: Animation?) {
                        // unused atm
                    }

                    override fun onAnimationEnd(animation: Animation?) {
                        binding.fragmentConnectionLost.visibility = View.GONE
                        binding.fragmentConnectionLost.setBackgroundColor(resources.getColor(R.color.hwSecurityRed))
                        binding.fragmentConnectionLost.text =
                            getString(R.string.connection_lost_sent_messages_are_queued)
                    }

                    override fun onAnimationRepeat(animation: Animation?) {
                        // unused atm
                    }
                })
            }

            binding.fragmentMessageInputView.attachmentButton.visibility = View.VISIBLE
            binding.fragmentMessageInputView.recordAudioButton.visibility =
                if (binding.fragmentMessageInputView.inputEditText.text.isEmpty()) View.VISIBLE else View.GONE
        } else {
            binding.fragmentMessageInputView.attachmentButton.visibility = View.INVISIBLE
            binding.fragmentMessageInputView.recordAudioButton.visibility = View.INVISIBLE
            binding.fragmentConnectionLost.clearAnimation()
            binding.fragmentConnectionLost.visibility = View.GONE
            binding.fragmentConnectionLost.setBackgroundColor(resources.getColor(R.color.hwSecurityRed))
            binding.fragmentConnectionLost.visibility = View.VISIBLE
        }
    }

    private fun restoreState() {
        CoroutineScope(Dispatchers.IO).launch {
            chatActivity.chatViewModel.updateMessageDraft()

            withContext(Dispatchers.Main) {
                val draft = chatActivity.chatViewModel.messageDraft
                binding.fragmentMessageInputView.messageInput.setText(draft.messageText)
                binding.fragmentMessageInputView.messageInput.setSelection(draft.messageCursor)

                if (draft.threadTitle?.isNotEmpty() == true) {
                    chatActivity.messageInputViewModel.startThreadCreation()
                }

                if (draft.messageText != "") {
                    binding.fragmentMessageInputView.messageInput.requestFocus()
                }

                if (isInReplyState()) {
                    replyToMessage(
                        chatActivity.chatViewModel.messageDraft.quotedMessageText,
                        chatActivity.chatViewModel.messageDraft.quotedDisplayName,
                        chatActivity.chatViewModel.messageDraft.quotedImageUrl
                    )
                }
            }
        }
    }

    private fun initMessageInputView() {
        if (!chatActivity.active) return

        val filters = arrayOfNulls<InputFilter>(1)
        val lengthFilter = CapabilitiesUtil.getMessageMaxLength(chatActivity.spreedCapabilities)

        binding.fragmentEditView.editMessageView.visibility = View.GONE
        binding.fragmentMessageInputView.setPadding(0, 0, 0, 0)

        filters[0] = InputFilter.LengthFilter(lengthFilter)
        binding.fragmentMessageInputView.inputEditText?.filters = filters

        binding.fragmentMessageInputView.inputEditText?.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                // unused atm
            }

            @Suppress("Detekt.TooGenericExceptionCaught")
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                updateOwnTypingStatus(s)

                if (s.length >= lengthFilter) {
                    binding.fragmentMessageInputView.inputEditText?.error = String.format(
                        Objects.requireNonNull<Resources>(resources).getString(R.string.nc_limit_hit),
                        lengthFilter.toString()
                    )
                } else {
                    binding.fragmentMessageInputView.inputEditText?.error = null
                }

                val editable = binding.fragmentMessageInputView.inputEditText?.editableText

                if (editable != null && binding.fragmentMessageInputView.inputEditText != null) {
                    val mentionSpans = editable.getSpans(
                        0,
                        binding.fragmentMessageInputView.inputEditText!!.length(),
                        Spans.MentionChipSpan::class.java
                    )
                    var mentionSpan: Spans.MentionChipSpan
                    for (i in mentionSpans.indices) {
                        mentionSpan = mentionSpans[i]
                        if (start >= editable.getSpanStart(mentionSpan) &&
                            start < editable.getSpanEnd(mentionSpan)
                        ) {
                            if (editable.subSequence(
                                    editable.getSpanStart(mentionSpan),
                                    editable.getSpanEnd(mentionSpan)
                                ).toString().trim() != mentionSpan.label
                            ) {
                                editable.removeSpan(mentionSpan)
                            }
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable) {
                val cursor = binding.fragmentMessageInputView.messageInput.selectionStart
                val text = binding.fragmentMessageInputView.messageInput.text.toString()
                chatActivity.chatViewModel.messageDraft.messageCursor = cursor
                chatActivity.chatViewModel.messageDraft.messageText = text
                handleButtonsVisibility()
                // Recenter the input field every time text changes to avoid hint jumping
                try {
                    recenterInputField()
                } catch (_: Throwable) {}
            }
        })

        // Image keyboard support
        // See: https://developer.android.com/guide/topics/text/image-keyboard

        (binding.fragmentMessageInputView.inputEditText as ImageEmojiEditText).onCommitContentListener = {
            chatActivity.chatViewModel.uploadFile(
                fileUri = it.toString(),
                isVoiceMessage = false,
                caption = "",
                roomToken = chatActivity.roomToken,
                replyToMessageId = chatActivity.getReplyToMessageId(),
                displayName = chatActivity.currentConversation?.displayName!!
            )
        }

        binding.fragmentMessageInputView.setAttachmentsListener {
            AttachmentDialog(requireActivity(), requireActivity() as ChatActivity).show()
        }

        binding.fragmentMessageInputView.attachmentButton.setOnLongClickListener {
            chatActivity.showGalleryPicker()
            true
        }

        binding.fragmentMessageInputView.button?.setOnClickListener {
            submitMessage(false)
        }

        binding.fragmentMessageInputView.editMessageButton.setOnClickListener {
            val editable = binding.fragmentMessageInputView.inputEditText!!.editableText
            replaceMentionChipSpans(editable)
            val inputEditText = editable.toString()

            val message = chatActivity.messageInputViewModel.getEditChatMessage.value as ChatMessage
            if (message.message!!.trim() != inputEditText.trim()) {
                if (message.messageParameters != null) {
                    val editedMessage = messageUtils.processEditMessageParameters(
                        message.messageParameters!!,
                        message,
                        inputEditText
                    )
                    editMessageAPI(message, editedMessage.toString())
                } else {
                    editMessageAPI(message, inputEditText.toString())
                }
            }
            clearEditUI()
        }
        binding.fragmentEditView.clearEdit.setOnClickListener {
            clearEditUI()
        }
        binding.fragmentCreateThreadView.abortCreateThread.setOnClickListener {
            cancelCreateThread()
        }

        if (CapabilitiesUtil.hasSpreedFeatureCapability(chatActivity.spreedCapabilities, SpreedFeatures.SILENT_SEND)) {
            binding.fragmentMessageInputView.button?.setOnLongClickListener {
                showSendButtonMenu()
                true
            }
        }

        binding.fragmentMessageInputView.button?.contentDescription =
            resources.getString(R.string.nc_description_send_message_button)

        binding.fragmentCallStarted.joinAudioCall.setOnClickListener {
            chatActivity.joinAudioCall()
        }

        binding.fragmentCallStarted.joinVideoCall.setOnClickListener {
            chatActivity.joinVideoCall()
        }

        binding.fragmentCallStarted.callStartedCloseBtn.setOnClickListener {
            collapsed = !collapsed
            binding.fragmentCallStarted.callAuthorLayout.visibility = if (collapsed) View.GONE else View.VISIBLE
            binding.fragmentCallStarted.callBtnLayout.visibility = if (collapsed) View.GONE else View.VISIBLE
            binding.fragmentCallStarted.callAuthorChipSecondary.visibility = if (collapsed) View.VISIBLE else View.GONE
            binding.fragmentCallStarted.callStartedSecondaryText.visibility = if (collapsed) View.VISIBLE else View.GONE
            setDropDown(collapsed)
        }

        binding.fragmentMessageInputView.findViewById<ImageButton>(R.id.cancelReplyButton)?.setOnClickListener {
            cancelReply()
        }
    }

    private fun setDropDown(collapsed: Boolean) {
        val drawable = if (collapsed) {
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_up)
        } else {
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_down)
        }

        binding.fragmentCallStarted.callStartedCloseBtn.setImageDrawable(drawable)
    }

    @Suppress("ClickableViewAccessibility", "CyclomaticComplexMethod", "LongMethod")
    private fun initVoiceRecordButton() {
        handleButtonsVisibility()

        var prevDx = 0f
        var voiceRecordStartTime = 0L
        var voiceRecordEndTime: Long
        binding.fragmentMessageInputView.recordAudioButton.setOnTouchListener { v, event ->
            v?.performClick()
            when (event?.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!chatActivity.isRecordAudioPermissionGranted()) {
                        chatActivity.requestRecordAudioPermissions()
                        return@setOnTouchListener true
                    }
                    if (!chatActivity.permissionUtil.isFilesPermissionGranted()) {
                        UploadAndShareFilesWorker.requestStoragePermission(chatActivity)
                        return@setOnTouchListener true
                    }

                    val base = SystemClock.elapsedRealtime()
                    voiceRecordStartTime = System.currentTimeMillis()
                    binding.fragmentMessageInputView.audioRecordDuration.base = base
                    chatActivity.messageInputViewModel.setRecordingTime(base)
                    binding.fragmentMessageInputView.audioRecordDuration.start()
                    chatActivity.chatViewModel.startAudioRecording(requireContext(), chatActivity.currentConversation!!)
                    showRecordAudioUi(true)
                }

                MotionEvent.ACTION_CANCEL -> {
                    Log.d(TAG, "ACTION_CANCEL")
                    if (chatActivity.chatViewModel.getVoiceRecordingInProgress.value == false ||
                        !chatActivity.isRecordAudioPermissionGranted()
                    ) {
                        return@setOnTouchListener true
                    }

                    showRecordAudioUi(false)
                    if (chatActivity.chatViewModel.getVoiceRecordingLocked.value != true) { // can also be null
                        chatActivity.chatViewModel.stopAndDiscardAudioRecording()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "ACTION_UP")
                    if (chatActivity.chatViewModel.getVoiceRecordingInProgress.value == false ||
                        chatActivity.chatViewModel.getVoiceRecordingLocked.value == true ||
                        !chatActivity.isRecordAudioPermissionGranted()
                    ) {
                        return@setOnTouchListener false
                    }
                    showRecordAudioUi(false)

                    voiceRecordEndTime = System.currentTimeMillis()
                    val voiceRecordDuration = voiceRecordEndTime - voiceRecordStartTime
                    if (voiceRecordDuration < MINIMUM_VOICE_RECORD_DURATION) {
                        Snackbar.make(
                            binding.root,
                            requireContext().getString(R.string.nc_voice_message_hold_to_record_info),
                            Snackbar.LENGTH_SHORT
                        ).show()
                        chatActivity.chatViewModel.stopAndDiscardAudioRecording()
                        return@setOnTouchListener false
                    } else {
                        chatActivity.chatViewModel.stopAndSendAudioRecording(
                            roomToken = chatActivity.roomToken,
                            replyToMessageId = chatActivity.getReplyToMessageId(),
                            displayName = chatActivity.currentConversation!!.displayName
                        )
                    }
                    resetSlider()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (chatActivity.chatViewModel.getVoiceRecordingInProgress.value == false ||
                        !chatActivity.isRecordAudioPermissionGranted()
                    ) {
                        return@setOnTouchListener false
                    }

                    if (event.x < VOICE_RECORD_CANCEL_SLIDER_X) {
                        chatActivity.chatViewModel.stopAndDiscardAudioRecording()
                        showRecordAudioUi(false)
                        resetSlider()
                        return@setOnTouchListener true
                    }
                    if (event.x < 0f) {
                        val dX = event.x
                        if (dX < prevDx) { // left
                            binding.fragmentMessageInputView.slideToCancelDescription.x -= INCREMENT
                            xcounter += INCREMENT
                        } else { // right
                            binding.fragmentMessageInputView.slideToCancelDescription.x += INCREMENT
                            xcounter -= INCREMENT
                        }

                        prevDx = dX
                    }

                    if (event.y < 0f) {
                        chatActivity.chatViewModel.postToRecordTouchObserver(INCREMENT)
                        ycounter += INCREMENT
                    }

                    if (ycounter >= VOICE_RECORD_LOCK_THRESHOLD) {
                        resetSlider()
                        binding.fragmentMessageInputView.recordAudioButton.isEnabled = false
                        chatActivity.chatViewModel.setVoiceRecordingLocked(true)
                        binding.fragmentMessageInputView.recordAudioButton.isEnabled = true
                    }
                }
            }
            v?.onTouchEvent(event) != false
        }
    }

    private fun initThreadHandling() {
        binding.fragmentMessageInputView.submitThreadButton.setOnClickListener {
            submitMessage(false)
        }

        binding.fragmentCreateThreadView.createThreadInput.doAfterTextChanged {
            handleButtonsVisibility()
        }
    }

    private fun handleButtonsVisibility() {
        fun View.setVisible(isVisible: Boolean) {
            visibility = if (isVisible) View.VISIBLE else View.GONE
        }

        val isEditModeActive = binding.fragmentEditView.editMessageView.isVisible
        val isThreadCreateModeActive = binding.fragmentCreateThreadView.createThreadView.isVisible
        val inputContainsText = binding.fragmentMessageInputView.messageInput.text.isNotEmpty()
        val threadTitleContainsText = binding.fragmentCreateThreadView.createThreadInput.text?.isNotEmpty() ?: false

        binding.fragmentMessageInputView.apply {
            try {
                // Work with the actual EditText view (avoid private/internal names on MessageInput wrapper)
                val input = inputEditText
                // determine a reasonable button height to center against: prefer measured attachment button height
                val buttonPx = try {
                    attachmentButton.measuredHeight.takeIf { it > 0 } ?: (48 * resources.displayMetrics.density).toInt()
                } catch (_: Throwable) {
                    (48 * resources.displayMetrics.density).toInt()
                }

                // remove extra font padding so FontMetrics are consistent for text and hint
                input?.let { edt ->
                    edt.includeFontPadding = false
                    // compute font metrics-based text height using ascent/descent to match hint/typed text
                    val paint = edt.paint
                    val fm = paint.fontMetrics
                    val textHeightPx = kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt()
                    // compute vertical padding needed to center text/hint within buttonPx
                    val verticalPad = kotlin.math.max(0, (buttonPx - textHeightPx) / 2)
                    // apply symmetric vertical padding so both text and hint align
                    edt.setPadding(edt.paddingLeft, verticalPad, edt.paddingRight, verticalPad)
                    edt.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    Log.d("MessageInputFrag", "computed input vPad=$verticalPad textH=$textHeightPx buttonH=$buttonPx fm.top=${fm.top} fm.bottom=${fm.bottom} fm.ascent=${fm.ascent} fm.descent=${fm.descent}")
                }
                try { input?.setLineSpacing(0f, 1.0f) } catch (_: Throwable) {}
                try { input?.invalidate(); input?.requestLayout() } catch (_: Throwable) {}
            } catch (_: Throwable) {}

            // control visibility of buttons depending on current mode/state
            when {
                isEditModeActive -> {
                    messageSendButton.setVisible(false)
                    recordAudioButton.setVisible(false)
                    submitThreadButton.setVisible(false)
                    attachmentButton.setVisible(true)
                }

                isThreadCreateModeActive -> {
                    messageSendButton.setVisible(false)
                    recordAudioButton.setVisible(false)
                    attachmentButton.setVisible(false)
                    submitThreadButton.setVisible(true)
                }

                inputContainsText && threadTitleContainsText -> {
                    submitThreadButton.isEnabled = true
                    submitThreadButton.alpha = FULLY_OPAQUE
                }

                inputContainsText -> {
                    recordAudioButton.setVisible(false)
                    submitThreadButton.setVisible(false)
                    messageSendButton.setVisible(true)
                    attachmentButton.setVisible(true)
                }

                else -> {
                    messageSendButton.setVisible(false)
                    submitThreadButton.setVisible(false)
                    recordAudioButton.setVisible(true)
                    attachmentButton.setVisible(true)
                }
            }
        }
    }

    private fun resetSlider() {
        binding.fragmentMessageInputView.audioRecordDuration.stop()
        binding.fragmentMessageInputView.audioRecordDuration.clearAnimation()
        binding.fragmentMessageInputView.slideToCancelDescription.x += xcounter
        chatActivity.chatViewModel.postToRecordTouchObserver(-ycounter)
        xcounter = 0f
        ycounter = 0f
    }

    private fun setupMentionAutocomplete() {
        val elevation = MENTION_AUTO_COMPLETE_ELEVATION
        resources.let {
            val backgroundDrawable = it.getColor(R.color.bg_default, null).toDrawable()
            val presenter = MentionAutocompletePresenter(
                requireContext(),
                chatActivity.roomToken,
                chatActivity.chatApiVersion
            )
            val callback = MentionAutocompleteCallback(
                requireContext(),
                chatActivity.conversationUser!!,
                binding.fragmentMessageInputView.inputEditText,
                viewThemeUtils
            )

            if (mentionAutocomplete == null && binding.fragmentMessageInputView.inputEditText != null) {
                mentionAutocomplete =
                    Autocomplete.on<Mention>(binding.fragmentMessageInputView.inputEditText)
                        .with(elevation)
                        .with(backgroundDrawable)
                        .with(CharPolicy('@'))
                        .with(presenter)
                        .with(callback)
                        .build()
            }
        }
    }

    private fun showRecordAudioUi(show: Boolean) {
        if (show) {
            val animation: Animation = AlphaAnimation(FULLY_OPAQUE, FULLY_TRANSPARENT)
            animation.duration = ANIMATION_DURATION
            animation.interpolator = LinearInterpolator()
            animation.repeatCount = Animation.INFINITE
            animation.repeatMode = Animation.REVERSE
            binding.fragmentMessageInputView.microphoneEnabledInfo.startAnimation(animation)

            binding.fragmentMessageInputView.microphoneEnabledInfo.visibility = View.VISIBLE
            binding.fragmentMessageInputView.microphoneEnabledInfoBackground.visibility = View.VISIBLE
            binding.fragmentMessageInputView.audioRecordDuration.visibility = View.VISIBLE
            binding.fragmentMessageInputView.slideToCancelDescription.visibility = View.VISIBLE
            binding.fragmentMessageInputView.attachmentButton.visibility = View.GONE
            binding.fragmentMessageInputView.smileyButton.visibility = View.GONE
            binding.fragmentMessageInputView.messageInput.visibility = View.GONE
            binding.fragmentMessageInputView.messageInput.hint = ""
        } else {
            binding.fragmentMessageInputView.microphoneEnabledInfo.clearAnimation()

            binding.fragmentMessageInputView.microphoneEnabledInfo.visibility = View.GONE
            binding.fragmentMessageInputView.microphoneEnabledInfoBackground.visibility = View.GONE
            binding.fragmentMessageInputView.audioRecordDuration.visibility = View.GONE
            binding.fragmentMessageInputView.slideToCancelDescription.visibility = View.GONE
            binding.fragmentMessageInputView.attachmentButton.visibility = View.VISIBLE
            binding.fragmentMessageInputView.smileyButton.visibility = View.VISIBLE
            binding.fragmentMessageInputView.messageInput.visibility = View.VISIBLE
            binding.fragmentMessageInputView.messageInput.hint =
                requireContext().resources?.getString(R.string.nc_hint_enter_a_message)
        }
    }

    private fun initSmileyKeyboardToggler() {
        val smileyButton = binding.fragmentMessageInputView.findViewById<ImageButton>(R.id.smileyButton)

        emojiPopup = binding.fragmentMessageInputView.inputEditText?.let {
            EmojiPopup(
                rootView = binding.root,
                editText = it,
                onEmojiPopupShownListener = {
                    smileyButton?.setImageDrawable(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_keyboard_24)
                    )
                },
                onEmojiPopupDismissListener = {
                    smileyButton?.setImageDrawable(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_insert_emoticon_black_24dp)
                    )
                },
                onEmojiClickListener = {
                    binding.fragmentMessageInputView.inputEditText?.editableText?.append(" ")
                }
            )
        }

        smileyButton?.setOnClickListener {
            emojiPopup?.toggle()
        }
    }

    private fun replyToMessage(quotedMessageText: String?, quotedActorDisplayName: String?, quotedImageUrl: String?) {
        Log.d(TAG, "Reply")
        val view = binding.fragmentMessageInputView
        view.findViewById<ImageButton>(R.id.cancelReplyButton)?.visibility =
            View.VISIBLE

        val quotedMessage = view.findViewById<EmojiTextView>(R.id.quotedMessage)

        quotedMessage?.maxLines = 2
        quotedMessage?.ellipsize = TextUtils.TruncateAt.END
        quotedMessage?.text = quotedMessageText
        view.findViewById<EmojiTextView>(R.id.quotedMessageAuthor)?.text =
            quotedActorDisplayName ?: requireContext().getText(R.string.nc_nick_guest)

        chatActivity.conversationUser?.let {
            val quotedMessageImage = view.findViewById<ImageView>(R.id.quotedMessageImage)
            quotedImageUrl?.let { previewImageUrl ->
                quotedMessageImage?.visibility = View.VISIBLE

                val px = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    QUOTED_MESSAGE_IMAGE_MAX_HEIGHT,
                    resources.displayMetrics
                )

                quotedMessageImage?.maxHeight = px.toInt()
                val layoutParams = quotedMessageImage?.layoutParams as FlexboxLayout.LayoutParams
                layoutParams.flexGrow = 0f
                quotedMessageImage.layoutParams = layoutParams
                quotedMessageImage.load(previewImageUrl) {
                    addHeader("Authorization", chatActivity.credentials!!)
                }
            } ?: run {
                view.findViewById<ImageView>(R.id.quotedMessageImage)?.visibility = View.GONE
            }
        }

        val quotedChatMessageView = view.findViewById<RelativeLayout>(R.id.quotedChatMessageView)
        quotedChatMessageView?.visibility = View.VISIBLE
    }

    fun updateOwnTypingStatus(typedText: CharSequence) {
        fun sendStartTypingSignalingMessage() {
            val concurrentSafeHashMap = chatActivity.webSocketInstance?.getUserMap()
            if (concurrentSafeHashMap != null) {
                for ((sessionId, _) in concurrentSafeHashMap) {
                    val ncSignalingMessage = NCSignalingMessage()
                    ncSignalingMessage.to = sessionId
                    ncSignalingMessage.type = TYPING_STARTED_SIGNALING_MESSAGE_TYPE
                    chatActivity.signalingMessageSender!!.send(ncSignalingMessage)
                }
            }
        }

        if (isTypingStatusEnabled()) {
            if (typedText.isEmpty()) {
                sendStopTypingMessage()
            } else if (typingTimer == null) {
                sendStartTypingSignalingMessage()

                typingTimer = object : CountDownTimer(
                    TYPING_DURATION_TO_SEND_NEXT_TYPING_MESSAGE,
                    TYPING_INTERVAL_TO_SEND_NEXT_TYPING_MESSAGE
                ) {
                    override fun onTick(millisUntilFinished: Long) {
                        // unused
                    }

                    override fun onFinish() {
                        if (typedWhileTypingTimerIsRunning) {
                            sendStartTypingSignalingMessage()
                            cancel()
                            start()
                            typedWhileTypingTimerIsRunning = false
                        } else {
                            sendStopTypingMessage()
                        }
                    }
                }.start()
            } else {
                typedWhileTypingTimerIsRunning = true
            }
        }
    }

    private fun sendStopTypingMessage() {
        if (isTypingStatusEnabled()) {
            typingTimer = null
            typedWhileTypingTimerIsRunning = false

            val concurrentSafeHashMap = chatActivity.webSocketInstance?.getUserMap()
            if (concurrentSafeHashMap != null) {
                for ((sessionId, _) in concurrentSafeHashMap) {
                    val ncSignalingMessage = NCSignalingMessage()
                    ncSignalingMessage.to = sessionId
                    ncSignalingMessage.type = TYPING_STOPPED_SIGNALING_MESSAGE_TYPE
                    chatActivity.signalingMessageSender?.send(ncSignalingMessage)
                }
            }
        }
    }

    private fun isTypingStatusEnabled(): Boolean =
        !CapabilitiesUtil.isTypingStatusPrivate(chatActivity.conversationUser!!)

    private fun submitMessage(sendWithoutNotification: Boolean) {
        if (binding.fragmentMessageInputView.inputEditText != null) {
            val editable = binding.fragmentMessageInputView.inputEditText!!.editableText
            replaceMentionChipSpans(editable)
            binding.fragmentMessageInputView.inputEditText?.setText("")
            sendStopTypingMessage()
            sendMessage(
                editable.toString(),
                sendWithoutNotification
            )
            cancelReply()
            cancelCreateThread()
        }
    }

    private fun sendMessage(message: String, sendWithoutNotification: Boolean) {
        chatActivity.messageInputViewModel.sendChatMessage(
            credentials = chatActivity.conversationUser!!.getCredentials(),
            url = ApiUtils.getUrlForChat(
                chatActivity.chatApiVersion,
                chatActivity.conversationUser!!.baseUrl!!,
                chatActivity.roomToken
            ),
            message = message,
            displayName = chatActivity.conversationUser!!.displayName ?: "",
            replyTo = chatActivity.getReplyToMessageId(),
            sendWithoutNotification = sendWithoutNotification,
            threadTitle = chatActivity.chatViewModel.messageDraft.threadTitle
        )
    }

    private fun replaceMentionChipSpans(editable: Editable) {
        val mentionSpans = editable.getSpans(
            0,
            editable.length,
            Spans.MentionChipSpan::class.java
        )
        for (mentionSpan in mentionSpans) {
            var mentionId = mentionSpan.id
            val shouldQuote = mentionId.contains(" ") ||
                mentionId.contains("@") ||
                mentionId.startsWith("guest/") ||
                mentionId.startsWith("group/") ||
                mentionId.startsWith("email/") ||
                mentionId.startsWith("team/")
            if (shouldQuote) {
                mentionId = "\"$mentionId\""
            }
            editable.replace(
                editable.getSpanStart(mentionSpan),
                editable.getSpanEnd(mentionSpan),
                "@$mentionId"
            )
        }
    }

    private fun showSendButtonMenu() {
        val popupMenu = PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ChatSendButtonMenu),
            binding.fragmentMessageInputView.button,
            Gravity.END
        )
        popupMenu.inflate(R.menu.chat_send_menu)

        popupMenu.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.send_without_notification -> submitMessage(true)
            }
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popupMenu.setForceShowIcon(true)
        }
        popupMenu.show()
    }

    private fun editMessageAPI(message: ChatMessage, editedMessageText: String) {
        // FIXME Fix API checking with guests?
        val apiVersion: Int = ApiUtils.getChatApiVersion(chatActivity.spreedCapabilities, intArrayOf(1))

        if (message.isTemporary) {
            chatActivity.messageInputViewModel.editTempChatMessage(
                message,
                editedMessageText
            )
        } else {
            chatActivity.messageInputViewModel.editChatMessage(
                chatActivity.credentials!!,
                ApiUtils.getUrlForChatMessage(
                    apiVersion,
                    chatActivity.conversationUser!!.baseUrl!!,
                    chatActivity.roomToken,
                    message.id
                ),
                editedMessageText
            )
        }
    }

    private fun setEditUI(message: ChatMessage) {
        val editedMessage = ChatUtils.getParsedMessage(message.message, message.messageParameters)
        binding.fragmentEditView.editMessage.text = editedMessage
        binding.fragmentMessageInputView.inputEditText.setText(editedMessage)
        if (mentionAutocomplete != null && mentionAutocomplete!!.isPopupShowing) {
            mentionAutocomplete?.dismissPopup()
        }
        val end = binding.fragmentMessageInputView.inputEditText.text.length
        binding.fragmentMessageInputView.inputEditText.setSelection(end)
        binding.fragmentMessageInputView.messageSendButton.visibility = View.GONE
        binding.fragmentMessageInputView.recordAudioButton.visibility = View.GONE
        binding.fragmentMessageInputView.submitThreadButton.visibility = View.GONE
        binding.fragmentMessageInputView.editMessageButton.visibility = View.VISIBLE
        binding.fragmentEditView.editMessageView.visibility = View.VISIBLE
        binding.fragmentMessageInputView.attachmentButton.visibility = View.GONE
    }

    private fun clearEditUI() {
        binding.fragmentMessageInputView.editMessageButton.visibility = View.GONE
        binding.fragmentMessageInputView.inputEditText.setText("")
        binding.fragmentEditView.editMessageView.visibility = View.GONE
        binding.fragmentMessageInputView.attachmentButton.visibility = View.VISIBLE
        chatActivity.messageInputViewModel.edit(null)
        handleButtonsVisibility()
        // After programmatic clears and visibility changes, ensure the input is recentered once
        // after the layout stabilizes. Use post + a one-shot layout listener to be robust across devices.
        try {
            val inputView = binding.fragmentMessageInputView.inputEditText
            inputView?.post {
                try { recenterInputField() } catch (_: Throwable) {}
            }
            // one-shot guard: ensure recenter after the next layout pass (covers visibility/measure changes)
            try {
                val oneShot = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                        try { recenterInputField() } catch (_: Throwable) {}
                        v?.removeOnLayoutChangeListener(this)
                    }
                }
                binding.fragmentMessageInputView.addOnLayoutChangeListener(oneShot)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun themeMessageInputView() {
        binding.fragmentMessageInputView.button?.let { viewThemeUtils.platform.colorImageView(it, ColorRole.PRIMARY) }

        binding.fragmentMessageInputView.findViewById<ImageButton>(R.id.cancelReplyButton)?.let {
            viewThemeUtils.platform
                .themeImageButton(it)
        }

        binding.fragmentMessageInputView.findViewById<MaterialButton>(R.id.playPauseBtn)?.let {
            viewThemeUtils.material.colorMaterialButtonText(it)
        }

        binding.fragmentMessageInputView.findViewById<SeekBar>(R.id.seekbar)?.let {
            viewThemeUtils.platform.themeHorizontalSeekBar(it)
        }

        binding.fragmentMessageInputView.findViewById<ImageView>(R.id.deleteVoiceRecording)?.let {
            viewThemeUtils.platform.colorImageView(it, ColorRole.PRIMARY)
        }
        binding.fragmentMessageInputView.findViewById<ImageView>(R.id.sendVoiceRecording)?.let {
            viewThemeUtils.platform.colorImageView(it, ColorRole.PRIMARY)
        }

        binding.fragmentMessageInputView.findViewById<ImageView>(R.id.microphoneEnabledInfo)?.let {
            viewThemeUtils.platform.colorImageView(it, ColorRole.PRIMARY)
        }

        binding.fragmentMessageInputView.findViewById<LinearLayout>(R.id.voice_preview_container)?.let {
            viewThemeUtils.talk.themeOutgoingMessageBubble(it, true, false)
        }

        binding.fragmentMessageInputView.findViewById<MicInputCloud>(R.id.micInputCloud)?.let {
            viewThemeUtils.talk.themeMicInputCloud(it)
        }
        binding.fragmentMessageInputView.findViewById<ImageView>(R.id.editMessageButton)?.let {
            viewThemeUtils.platform.colorImageView(it, ColorRole.PRIMARY)
        }
        binding.fragmentEditView.clearEdit.let {
            viewThemeUtils.platform.colorImageView(it, ColorRole.PRIMARY)
        }
        binding.fragmentCreateThreadView.abortCreateThread.let {
            viewThemeUtils.platform.colorImageView(it, ColorRole.PRIMARY)
        }

        binding.fragmentCallStarted.callStartedBackground.apply {
            viewThemeUtils.talk.themeOutgoingMessageBubble(this, grouped = true, false)
        }

        binding.fragmentCallStarted.callAuthorChip.apply {
            viewThemeUtils.material.colorChipBackground(this)
        }

        binding.fragmentCallStarted.callAuthorChipSecondary.apply {
            viewThemeUtils.material.colorChipBackground(this)
        }

        binding.fragmentCallStarted.callStartedCloseBtn.apply {
            viewThemeUtils.platform.colorImageView(this, ColorRole.PRIMARY)
        }

        binding.fragmentMessageInputView.submitThreadButton.apply {
            viewThemeUtils.platform.colorImageView(this, ColorRole.SECONDARY)
        }

        binding.fragmentCreateThreadView.createThreadInput.apply {
            viewThemeUtils.platform.colorEditText(this)
        }
    }

    private fun cancelCreateThread() {
        chatActivity.cancelCreateThread()
        chatActivity.messageInputViewModel.stopThreadCreation()
        binding.fragmentCreateThreadView.createThreadView.visibility = View.GONE
    }

    private fun cancelReply() {
        chatActivity.cancelReply()
        clearReplyUi()
    }

    private fun clearReplyUi() {
        val quote = binding.fragmentMessageInputView.findViewById<RelativeLayout>(R.id.quotedChatMessageView)
        quote.visibility = View.GONE
        binding.fragmentMessageInputView.findViewById<ImageButton>(R.id.attachmentButton)?.visibility = View.VISIBLE
    }

    private fun isInReplyState(): Boolean {
        val jsonId = chatActivity.chatViewModel.messageDraft.quotedJsonId
        return jsonId != null
    }

    companion object {
        fun newInstance() = MessageInputFragment()
        private val TAG: String = MessageInputFragment::class.java.simpleName
        private const val TYPING_DURATION_TO_SEND_NEXT_TYPING_MESSAGE = 10000L
        private const val TYPING_INTERVAL_TO_SEND_NEXT_TYPING_MESSAGE = 1000L
        private const val TYPING_STARTED_SIGNALING_MESSAGE_TYPE = "startedTyping"
        private const val TYPING_STOPPED_SIGNALING_MESSAGE_TYPE = "stoppedTyping"
        private const val QUOTED_MESSAGE_IMAGE_MAX_HEIGHT = 96f
        private const val MENTION_AUTO_COMPLETE_ELEVATION = 6f
        private const val MINIMUM_VOICE_RECORD_DURATION: Int = 1000
        private const val ANIMATION_DURATION: Long = 750
        private const val VOICE_RECORD_CANCEL_SLIDER_X: Int = -150
        private const val VOICE_RECORD_LOCK_THRESHOLD: Float = 100f
        private const val INCREMENT = 8f
        private const val CURSOR_KEY = "_cursor"
        private const val CONNECTION_ESTABLISHED_ANIM_DURATION: Long = 3000
        private const val FULLY_OPAQUE: Float = 1.0f
        private const val FULLY_TRANSPARENT: Float = 0.0f
        private const val OPACITY_DISABLED = 0.7f
    }
}
