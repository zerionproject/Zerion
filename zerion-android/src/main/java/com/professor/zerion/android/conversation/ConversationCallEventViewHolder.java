package com.professor.zerion.android.conversation;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@UiThread
@NotNullByDefault
class ConversationCallEventViewHolder extends ConversationItemViewHolder {

	private final ImageView callIcon;
	private final TextView callEventText;
	private final TextView timeView;

	private final SimpleDateFormat timeFormat =
			new SimpleDateFormat("HH:mm", Locale.getDefault());

	ConversationCallEventViewHolder(View v) {
		super(v, null, true);
		callIcon = v.findViewById(R.id.callIcon);
		callEventText = v.findViewById(R.id.callEventText);
		timeView = v.findViewById(R.id.time);
	}

	@Override
	void bind(ConversationItem item, boolean selected) {
		ConversationCallEventItem callEvent = (ConversationCallEventItem) item;

		String eventText = callEvent.getCallEventText();
		callEventText.setText(eventText);

		String formattedTime = timeFormat.format(new Date(callEvent.getTime()));
		timeView.setText(formattedTime);
	}
}
