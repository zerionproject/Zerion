package com.professor.zerion.android.conversation;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

@UiThread
@NotNullByDefault
class ConversationSecretNoteViewHolder extends ConversationItemViewHolder {

	private final TextView secretTitle;
	private final TextView secretSubtitle;
	private final TextView secretContent;
	private final View lockIcon;

	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private Runnable pendingStep;

	ConversationSecretNoteViewHolder(View v, ConversationListener listener,
			boolean isIncoming) {
		super(v, listener, isIncoming);
		secretTitle = v.findViewById(R.id.secretTitle);
		secretSubtitle = v.findViewById(R.id.secretSubtitle);
		secretContent = v.findViewById(R.id.secretContent);
		lockIcon = v.findViewById(R.id.lockIcon);
	}

	@Override
	void bind(ConversationItem item, boolean selected) {
		cancelCountdown();
		super.bind(item, selected);
		layout.setOnLongClickListener(null);
		layout.setLongClickable(false);

		ConversationSecretNoteItem secretItem = (ConversationSecretNoteItem) item;

		if (!item.isIncoming()) {
			bindOutgoing(secretItem);
		} else {
			bindIncoming(secretItem);
		}
	}

	private void bindOutgoing(ConversationSecretNoteItem item) {
		secretTitle.setText(R.string.secret_note_sent);
		if (item.isSeen()) {
			secretSubtitle.setText(R.string.secret_note_read);
		} else {
			secretSubtitle.setText(R.string.secret_note_waiting);
		}
		lockIcon.setVisibility(VISIBLE);
		secretContent.setVisibility(GONE);
		layout.setOnClickListener(null);
	}

	private void bindIncoming(ConversationSecretNoteItem item) {
		if (item.isRevealed()) {
			lockIcon.setVisibility(GONE);
			secretTitle.setText(R.string.secret_note_label);
			secretSubtitle.setText(R.string.secret_note_expired);
			secretContent.setVisibility(GONE);
			layout.setOnClickListener(null);
			return;
		}
		lockIcon.setVisibility(VISIBLE);
		secretTitle.setText(R.string.secret_note_label);
		secretSubtitle.setText(R.string.secret_note_tap_to_read);
		secretContent.setVisibility(GONE);
		layout.setOnClickListener(v -> onReveal(item));
	}

	private void onReveal(ConversationSecretNoteItem item) {
		if (item.isRevealed()) return;
		item.markRevealed();

		listener.onSecretNoteRevealing(true);

		String content = item.getSecretContent();
		if (content != null) {
			secretContent.setText(content);
			secretContent.setVisibility(VISIBLE);
		}
		lockIcon.setVisibility(GONE);
		layout.setOnClickListener(null);

		startCountdown(item, item.getCountdownSeconds());
	}

	private void startCountdown(ConversationSecretNoteItem item, int seconds) {
		if (seconds <= 0) {
			secretContent.setText("");
			secretContent.setVisibility(GONE);
			secretSubtitle.setText(R.string.secret_note_expired);
			item.clearSecretContent();

			listener.onSecretNoteRevealing(false);
			listener.onSecretNoteOpened(item.getId());
			return;
		}
		secretSubtitle.setText(secretSubtitle.getContext()
				.getString(R.string.secret_note_deletes_in, seconds));
		pendingStep = () -> startCountdown(item, seconds - 1);
		handler.postDelayed(pendingStep, 1000);
	}

	private void cancelCountdown() {
		if (pendingStep != null) {
			handler.removeCallbacks(pendingStep);
			pendingStep = null;
		}
	}

}
