package com.professor.zerion.android.conversation;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.MessageStates;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
class OutItemViewHolder {

	private final ImageView status;

	OutItemViewHolder(View v) {
		status = v.findViewById(R.id.status);
	}

	static MessageState stateOf(ConversationItem item) {
		if (item.isSeen()) return MessageState.DELIVERED;
		if (item.isSent()) return MessageState.SENT;
		return MessageState.WAITING;
	}

	void bind(ConversationItem item, TextView time, String timeText) {
		MessageStates.bind(time, status, timeText, stateOf(item));
	}

}
