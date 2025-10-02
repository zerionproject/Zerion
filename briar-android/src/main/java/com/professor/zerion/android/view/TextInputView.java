package com.professor.zerion.android.view;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.widget.ImageButton;

import com.professor.zerion.R;
import com.professor.zerion.android.conversation.ConversationItem;
import com.professor.zerion.android.view.EmojiTextInputView.OnKeyboardShownListener;
import com.professor.zerion.android.conversation.voice.VoiceMessageRecorder;
import java.io.File;
import java.util.concurrent.Executors;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static java.util.Objects.requireNonNull;

@UiThread
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TextInputView extends LinearLayout {

	@Nullable
	TextSendController textSendController;
	final EmojiTextInputView textInput;
	@Nullable
	private View replyPreview;
	@Nullable
	private ConversationItem replyingToItem;
	@Nullable
	private VoiceMessageRecorder voiceRecorder;
	@Nullable
	private VoiceMessageListener voiceMessageListener;
	@Nullable
	private ImageButton voiceButton;

	public interface VoiceMessageListener {
		void onVoiceMessageRecorded(File recording);
	}

	public TextInputView(Context context) {
		this(context, null);
	}

	public TextInputView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public TextInputView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setSaveEnabled(true);
		setOrientation(VERTICAL);
		setLayoutTransition(new LayoutTransition());

		// inflate layout
		LayoutInflater inflater = (LayoutInflater) requireNonNull(
				context.getSystemService(LAYOUT_INFLATER_SERVICE));
		inflater.inflate(getLayout(), this, true);

		// get attributes
		TypedArray attributes = context.obtainStyledAttributes(attrs,
				R.styleable.TextInputView);
		String hint = attributes.getString(R.styleable.TextInputView_hint);
		boolean allowEmptyText = attributes
				.getBoolean(R.styleable.TextInputView_allowEmptyText, false);
		attributes.recycle();

		textInput = findViewById(R.id.emojiTextInput);
		textInput.setAllowEmptyText(allowEmptyText);
		if (hint != null) textInput.setHint(hint);

		// Initialize voice message button
		voiceButton = findViewById(R.id.voiceMessageButton);
		if (voiceButton != null) {
			voiceButton.setVisibility(View.GONE); // Hidden by default until permission granted
			voiceRecorder = new VoiceMessageRecorder(context, Executors.newSingleThreadExecutor());
			voiceButton.setOnClickListener(v -> handleVoiceButtonClick());
		}
	}

	@LayoutRes
	protected int getLayout() {
		return R.layout.text_input_view;
	}

	@Nullable
	@Override
	protected Parcelable onSaveInstanceState() {
		Parcelable superState = super.onSaveInstanceState();
		if (textSendController != null) {
			superState = textSendController.onSaveInstanceState(superState);
		}
		return superState;
	}

	@Override
	protected void onRestoreInstanceState(Parcelable state) {
		if (textSendController != null) {
			Parcelable outState =
					textSendController.onRestoreInstanceState(state);
			super.onRestoreInstanceState(outState);
		} else {
			super.onRestoreInstanceState(state);
		}
	}

	/**
	 * Call this in onCreate() before any other methods of this class.
	 */
	public <T extends TextSendController> void setSendController(T controller) {
		textSendController = controller;
		textInput.setTextInputListener(textSendController);
	}

	@Override
	public void setEnabled(boolean enabled) {
		throw new RuntimeException("Use controllers to enable/disable");
	}

	public void setReady(boolean ready) {
		requireNonNull(textSendController).setReady(ready);
	}

	@Override
	public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
		return textInput.requestFocus(direction, previouslyFocusedRect);
	}

	EmojiTextInputView getEmojiTextInputView() {
		return textInput;
	}

	public void clearText() {
		textInput.clearText();
	}

	public void setHint(@StringRes int res) {
		textInput.setHint(getContext().getString(res));
	}

	public void setMaxTextLength(int maxLength) {
		textInput.setMaxLength(maxLength);
	}

	public boolean isKeyboardOpen() {
		return textInput.isKeyboardOpen();
	}

	public void showSoftKeyboard() {
		textInput.showSoftKeyboard();
	}

	public void hideSoftKeyboard() {
		textInput.hideSoftKeyboard();
	}

	public void setOnKeyboardShownListener(
			@Nullable OnKeyboardShownListener listener) {
		textInput.setOnKeyboardShownListener(listener);
	}

	public void showReplyPreview(ConversationItem item) {
		if (replyPreview == null) {
			// Inflate reply preview if not already present
			LayoutInflater inflater = LayoutInflater.from(getContext());
			replyPreview = inflater.inflate(R.layout.reply_preview, this, false);
			addView(replyPreview, 0); // Add at the top
		}

		replyingToItem = item;

		// Set up the reply preview UI
		TextView replyAuthor = replyPreview.findViewById(R.id.reply_author);
		TextView replyText = replyPreview.findViewById(R.id.reply_text);
		ImageButton cancelReply = replyPreview.findViewById(R.id.cancel_reply);

		replyAuthor.setText(item.isIncoming() ?
			item.getContactName().getValue() : "You");
		replyText.setText(item.getText() != null ?
			item.getText() : "[Image]");

		cancelReply.setOnClickListener(v -> hideReplyPreview());

		replyPreview.setVisibility(View.VISIBLE);
	}

	public void hideReplyPreview() {
		if (replyPreview != null) {
			replyPreview.setVisibility(View.GONE);
			replyingToItem = null;
		}
	}

	@Nullable
	public ConversationItem getReplyingToItem() {
		return replyingToItem;
	}

	public void setVoiceMessageListener(VoiceMessageListener listener) {
		this.voiceMessageListener = listener;
	}

	public void enableVoiceButton() {
		if (voiceButton != null) {
			voiceButton.setVisibility(View.VISIBLE);
		}
	}

	private void handleVoiceButtonClick() {
		if (voiceRecorder == null || voiceMessageListener == null) return;

		if (voiceRecorder.isRecording()) {
			// Stop recording
			voiceRecorder.stopRecording();
			File recording = voiceRecorder.getRecordingFile();
			if (recording != null && recording.exists()) {
				voiceMessageListener.onVoiceMessageRecorded(recording);
			}
			voiceButton.setImageResource(R.drawable.ic_mic_24dp);
		} else {
			// Start recording with callback
			voiceRecorder.startRecording(new VoiceMessageRecorder.RecordingCallback() {
				@Override
				public void onRecordingStarted() {
					// Recording started
				}
				@Override
				public void onRecordingProgress(int durationMs, int amplitudeDb) {
					// Update UI with progress if needed
				}
				@Override
				public void onRecordingCompleted(File audioFile, int durationMs) {
					// Will be handled when stop is clicked
				}
				@Override
				public void onRecordingError(String error) {
					// Handle error
				}
				@Override
				public void onRecordingCancelled() {
					// Handle cancellation
				}
			});
			voiceButton.setImageResource(android.R.drawable.ic_media_pause);
		}
	}

}
