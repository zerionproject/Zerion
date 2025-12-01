package com.professor.zerion.android.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.professor.zerion.R;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static java.util.Objects.requireNonNull;

public class CompositeSendButton extends LinearLayout {

	private final AppCompatImageButton sendButton, attachmentButton, voiceButton;
	@Nullable
	private final ImageView bombBadge;
	private final ProgressBar progressBar;

	private boolean hasImageSupport = false;

	public CompositeSendButton(@NonNull Context context,
			@Nullable AttributeSet attrs) {
		super(context, attrs);
		LayoutInflater inflater = (LayoutInflater) requireNonNull(
				context.getSystemService(LAYOUT_INFLATER_SERVICE));
		inflater.inflate(R.layout.view_composite_send_button, this, true);

		sendButton = findViewById(R.id.sendButton);
		attachmentButton = findViewById(R.id.attachmentButton);
		voiceButton = findViewById(R.id.voiceButton);
		bombBadge = null;
		progressBar = findViewById(R.id.progressBar);

		hasImageSupport = true;
		attachmentButton.setEnabled(true);
		attachmentButton.setVisibility(VISIBLE);
	}

	@Override
	public void setEnabled(boolean enabled) {
		setSendEnabled(enabled);
	}

	@Override
	public void setOnClickListener(@Nullable View.OnClickListener l) {
		setOnSendClickListener(l);
	}

	public void setOnSendClickListener(@Nullable OnClickListener l) {
		sendButton.setOnClickListener(l);
	}

	public void setSendEnabled(boolean enabled) {
		if (enabled) {
			sendButton.setVisibility(VISIBLE);
			sendButton.setEnabled(true);
			sendButton.setAlpha(1.0f);
			voiceButton.setVisibility(INVISIBLE);
			voiceButton.setEnabled(false);
		} else {
			sendButton.setVisibility(INVISIBLE);
			sendButton.setEnabled(false);
			voiceButton.setVisibility(VISIBLE);
			voiceButton.setEnabled(true);
		}
	}

	public void setOnAttachmentClickListener(@Nullable OnClickListener l) {
		attachmentButton.setOnClickListener(l);
	}

	public void setOnVoiceClickListener(@Nullable OnClickListener l) {
		voiceButton.setOnClickListener(l);
	}

	public void setOnVoiceLongClickListener(@Nullable OnLongClickListener l) {
		voiceButton.setOnLongClickListener(l);
	}

	public void setImagesSupported() {
		hasImageSupport = true;
		attachmentButton.setEnabled(true);
	}

	public boolean hasImageSupport() {
		return hasImageSupport;
	}

	public void setBombVisible(boolean visible) {
		if (bombBadge != null) {
			bombBadge.setVisibility(visible ? VISIBLE : INVISIBLE);
		}
	}

	public void showImageButton(boolean showImageButton, boolean sendEnabled) {
		attachmentButton.setVisibility(VISIBLE);
		attachmentButton.setEnabled(hasImageSupport);

		if (sendEnabled) {
			sendButton.setVisibility(VISIBLE);
			sendButton.setEnabled(true);
			sendButton.setAlpha(1.0f);
			voiceButton.setVisibility(INVISIBLE);
			voiceButton.setEnabled(false);
		} else {
			sendButton.setVisibility(INVISIBLE);
			sendButton.setEnabled(false);
			voiceButton.setVisibility(VISIBLE);
			voiceButton.setEnabled(true);
		}
	}

	public void showProgress(boolean show) {
		if (show) {
			sendButton.setVisibility(INVISIBLE);
			voiceButton.setVisibility(INVISIBLE);
			attachmentButton.setVisibility(INVISIBLE);
			progressBar.setVisibility(VISIBLE);
		} else {
			attachmentButton.setVisibility(VISIBLE);
			progressBar.setVisibility(INVISIBLE);
		}
	}

}
