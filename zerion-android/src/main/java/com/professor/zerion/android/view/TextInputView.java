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

		LayoutInflater inflater = (LayoutInflater) requireNonNull(
				context.getSystemService(LAYOUT_INFLATER_SERVICE));
		inflater.inflate(getLayout(), this, true);

		TypedArray attributes = context.obtainStyledAttributes(attrs,
				R.styleable.TextInputView);
		String hint = attributes.getString(R.styleable.TextInputView_hint);
		boolean allowEmptyText = attributes
				.getBoolean(R.styleable.TextInputView_allowEmptyText, false);
		attributes.recycle();

		textInput = findViewById(R.id.emojiTextInput);
		textInput.setAllowEmptyText(allowEmptyText);
		if (hint != null) textInput.setHint(hint);
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
		hideReplyPreview();

		LayoutInflater inflater = LayoutInflater.from(getContext());
		replyPreview = inflater.inflate(R.layout.reply_preview, this, false);
		addView(replyPreview, 0);

		replyingToItem = item;

		TextView replyAuthor = replyPreview.findViewById(R.id.reply_author);
		TextView replyText = replyPreview.findViewById(R.id.reply_text);
		ImageButton cancelReply = replyPreview.findViewById(R.id.cancel_reply);

		String authorName = "You";
		if (item.isIncoming() && item.getContactName() != null &&
			item.getContactName().getValue() != null) {
			authorName = item.getContactName().getValue();
		}
		replyAuthor.setText(authorName);

		String displayText;
		String rawText = item.getText();
		if (rawText == null || rawText.isEmpty()) {
			displayText = getContext().getString(R.string.media);
		} else if (rawText.startsWith("[VOICE:")) {
			displayText = getContext().getString(R.string.voice_message);
		} else if (com.professor.zerion.android.conversation.voice
				.VoiceCallSignal.isSignal(rawText)
				|| rawText.startsWith("VOICE_CALL:")) {
			displayText = getContext().getString(R.string.voice_call);
		} else {
			displayText = rawText;
		}
		replyText.setText(displayText);

		cancelReply.setOnClickListener(v -> hideReplyPreview());

		replyPreview.setVisibility(View.VISIBLE);
	}

	public void hideReplyPreview() {
		if (replyPreview != null) {
			replyPreview.setVisibility(View.GONE);
			removeView(replyPreview);
			replyPreview = null;
		}
		replyingToItem = null;
	}

	@Nullable
	public ConversationItem getReplyingToItem() {
		return replyingToItem;
	}

}
