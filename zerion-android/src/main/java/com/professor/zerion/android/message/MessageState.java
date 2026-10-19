package com.professor.zerion.android.message;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

@NotNullByDefault
public enum MessageState {

	SENDING(R.string.message_state_sending,
			R.drawable.ic_message_state_sending),
	WAITING(R.string.message_state_waiting, R.drawable.message_stored),
	SENT(R.string.message_state_sent, R.drawable.message_sent),
	DELIVERED(R.string.message_state_delivered,
			R.drawable.message_delivered),
	NOT_SENT(R.string.message_state_not_sent,
			R.drawable.ic_message_state_not_sent);

	@StringRes
	private final int label;
	@DrawableRes
	private final int icon;

	MessageState(@StringRes int label, @DrawableRes int icon) {
		this.label = label;
		this.icon = icon;
	}

	@StringRes
	public int getLabel() {
		return label;
	}

	@DrawableRes
	public int getIcon() {
		return icon;
	}
}
