package com.professor.zerion.android.conversation;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

@NotNullByDefault
class ConversationViewOnceViewHolder extends ConversationItemViewHolder {

	private final TextView title;
	private final TextView subtitle;
	private final ImageView icon;

	ConversationViewOnceViewHolder(View v, ConversationListener listener,
			boolean isIncoming) {
		super(v, listener, isIncoming);
		title = v.findViewById(R.id.secretTitle);
		subtitle = v.findViewById(R.id.secretSubtitle);
		icon = v.findViewById(R.id.lockIcon);
		v.findViewById(R.id.secretContent).setVisibility(GONE);
	}

	@Override
	void bind(ConversationItem item, boolean selected) {
		super.bind(item, selected);
		ConversationViewOnceItem once = (ConversationViewOnceItem) item;
		icon.setImageResource(once.isVideo() ? R.drawable.ic_video
				: R.drawable.ic_image);
		title.setText(once.isVideo() ? R.string.view_once_video
				: R.string.view_once_photo);
		if (once.isOpened()) {
			subtitle.setText(R.string.view_once_viewed);
			subtitle.setVisibility(VISIBLE);
		} else if (once.canOpen()) {
			subtitle.setText(R.string.view_once_tap);
			subtitle.setVisibility(VISIBLE);
		} else {
			subtitle.setText("");
			subtitle.setVisibility(GONE);
		}
		layout.setOnClickListener(v -> {
			if (isSelecting() || !once.canOpen()) {
				listener.onMessageClick(item);
			} else {
				listener.onViewOnceClicked(once);
			}
		});
	}

	@Override
	void bindBody(ConversationItem item) {
		text.setText("");
	}
}
