package com.professor.zerion.android.conversation;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.selection.RowActions;
import org.zerionproject.core.api.sync.MessageId;
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

	private final SecretNoteTimers secretNotes;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable tick = this::showReveal;
	@Nullable
	private MessageId shownId;

	ConversationSecretNoteViewHolder(View v, ConversationListener listener,
			boolean isIncoming, SecretNoteTimers secretNotes) {
		super(v, listener, isIncoming);
		this.secretNotes = secretNotes;
		secretTitle = v.findViewById(R.id.secretTitle);
		secretSubtitle = v.findViewById(R.id.secretSubtitle);
		secretContent = v.findViewById(R.id.secretContent);
		lockIcon = v.findViewById(R.id.lockIcon);
	}

	@Override
	void bind(ConversationItem item, boolean selected) {
		stopTicking();
		shownId = null;
		secretContent.setText("");
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

	@Override
	void bindBody(ConversationItem item) {
		text.setText("");
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
		secretTitle.setText(R.string.secret_note_label);
		if (item.isRevealed() || secretNotes.isRevealed(item.getId())) {
			item.markRevealed();
			item.clearSecretContent();
			showRevealed(item);
			return;
		}
		lockIcon.setVisibility(VISIBLE);
		secretSubtitle.setText(R.string.secret_note_tap_to_read);
		secretContent.setVisibility(GONE);
		layout.setOnClickListener(v -> onReveal(item));
	}

	private void onReveal(ConversationSecretNoteItem item) {
		if (item.isRevealed() || secretNotes.isRevealed(item.getId())) return;
		String content = item.getSecretContent();
		int seconds = item.getCountdownSeconds();
		item.markRevealed();
		item.clearSecretContent();
		listener.onSecretNoteRevealed(item.getId(), seconds);
		secretNotes.reveal(item.getId(), content == null ? "" : content,
				seconds);
		showRevealed(item);
	}

	private void showRevealed(ConversationSecretNoteItem item) {
		lockIcon.setVisibility(GONE);
		layout.setOnClickListener(null);
		shownId = item.getId();
		showReveal();
	}

	private void showReveal() {
		stopTicking();
		MessageId id = shownId;
		String content = id == null ? null : secretNotes.content(id);
		if (id == null || content == null) {
			secretContent.setText("");
			secretContent.setVisibility(GONE);
			secretSubtitle.setText(R.string.secret_note_expired);
			return;
		}
		secretContent.setText(content);
		secretContent.setVisibility(VISIBLE);
		secretSubtitle.setText(secretSubtitle.getContext().getString(
				R.string.secret_note_deletes_in, secretNotes.secondsLeft(id)));
		handler.postDelayed(tick, secretNotes.millisToNextSecond(id));
	}

	void onSecretNoteGone(MessageId id) {
		if (id.equals(shownId)) showReveal();
	}

	private void stopTicking() {
		handler.removeCallbacks(tick);
	}

	@Override
	@Nullable
	RowActions.Row rowActions(ConversationItem item) {
		return null;
	}

	@Override
	void onRecycled() {
		stopTicking();
		shownId = null;
		secretContent.setText("");
	}

}
