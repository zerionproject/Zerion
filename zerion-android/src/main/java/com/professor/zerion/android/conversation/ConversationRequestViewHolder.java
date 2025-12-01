package com.professor.zerion.android.conversation;

import android.view.View;
import android.widget.Button;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

@UiThread
@NotNullByDefault
class ConversationRequestViewHolder extends ConversationNoticeViewHolder {

	private final Button acceptButton;
	private final Button declineButton;

	ConversationRequestViewHolder(View v, ConversationListener listener,
			boolean isIncoming) {
		super(v, listener, isIncoming);
		acceptButton = v.findViewById(R.id.acceptButton);
		declineButton = v.findViewById(R.id.declineButton);
	}

	@Override
	void bind(ConversationItem item, boolean selected) {
		ConversationRequestItem request = (ConversationRequestItem) item;
		super.bind(request, selected);

		if (request.wasAnswered() && request.canBeOpened()) {
			acceptButton.setVisibility(VISIBLE);
			acceptButton.setText(R.string.open);
			acceptButton.setOnClickListener(
					v -> listener.openRequestedShareable(request));
			declineButton.setVisibility(GONE);
		} else if (request.wasAnswered()) {
			acceptButton.setVisibility(GONE);
			declineButton.setVisibility(GONE);
		} else {
			acceptButton.setVisibility(VISIBLE);
			acceptButton.setText(R.string.accept);
			acceptButton.setOnClickListener(v -> {
				acceptButton.setEnabled(false);
				declineButton.setEnabled(false);
				listener.respondToRequest(request, true);
			});
			declineButton.setVisibility(VISIBLE);
			declineButton.setOnClickListener(v -> {
				acceptButton.setEnabled(false);
				declineButton.setEnabled(false);
				listener.respondToRequest(request, false);
			});
		}
		acceptButton.setEnabled(true);
		declineButton.setEnabled(true);
	}

}
