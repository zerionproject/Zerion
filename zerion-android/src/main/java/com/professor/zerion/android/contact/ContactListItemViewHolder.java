package com.professor.zerion.android.contact;

import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

import static com.professor.zerion.android.util.UiUtils.formatDate;

@UiThread
@NotNullByDefault
class ContactListItemViewHolder extends ContactItemViewHolder<ContactListItem> {

	private final TextView unread;
	private final TextView date;

	ContactListItemViewHolder(View v) {
		super(v);
		unread = v.findViewById(R.id.unreadCountView);
		date = v.findViewById(R.id.dateView);
	}

	@Override
	protected void bind(ContactListItem item, @Nullable
			OnContactClickListener<ContactListItem> listener) {
		super.bind(item, listener);

		int unreadCount = item.getUnreadCount();
		if (unreadCount > 0) {
			unread.setText(
					String.format(Locale.getDefault(), "%d", unreadCount));
			unread.setVisibility(View.VISIBLE);
		} else {
			unread.setVisibility(View.INVISIBLE);
		}

		if (item.isEmpty()) {
			date.setText(R.string.date_no_private_messages);
		} else {
			long timestamp = item.getTimestamp();
			date.setText(formatDate(date.getContext(), timestamp));
		}
	}

}
