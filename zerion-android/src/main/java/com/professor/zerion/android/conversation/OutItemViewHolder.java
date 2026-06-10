package com.professor.zerion.android.conversation;

import android.view.View;
import android.widget.ImageView;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
class OutItemViewHolder {

	private final ImageView status;

	OutItemViewHolder(View v) {
		status = v.findViewById(R.id.status);
	}

	void bind(ConversationItem item) {
		int res;
		int desc;
		if (item.isSeen()) {
			res = R.drawable.message_delivered;
			desc = R.string.message_status_delivered;
		} else if (item.isSent()) {
			res = R.drawable.message_sent;
			desc = R.string.message_status_sent;
		} else {
			res = R.drawable.message_stored;
			desc = R.string.message_status_pending;
		}
		status.setImageResource(res);
		status.setContentDescription(status.getContext().getString(desc));
	}

}
