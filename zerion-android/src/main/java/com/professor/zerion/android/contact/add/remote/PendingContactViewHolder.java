package com.professor.zerion.android.contact.add.remote;

import android.view.View;
import android.widget.TextView;

import org.briarproject.bramble.api.contact.PendingContact;
import com.professor.zerion.R;
import com.professor.zerion.android.view.TextAvatarView;
import org.briarproject.nullsafety.NotNullByDefault;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import static com.professor.zerion.android.util.UiUtils.formatDate;

@NotNullByDefault
class PendingContactViewHolder extends ViewHolder {

	private final PendingContactListener listener;
	private final TextAvatarView avatar;
	private final TextView name;
	private final TextView time;
	private final TextView status;
	private final MaterialButton removeButton;
	private final View statusIndicator;
	private final CircularProgressIndicator connectingIndicator;

	PendingContactViewHolder(View v, PendingContactListener listener) {
		super(v);
		avatar = v.findViewById(R.id.avatar);
		name = v.findViewById(R.id.name);
		time = v.findViewById(R.id.time);
		status = v.findViewById(R.id.status);
		removeButton = v.findViewById(R.id.removeButton);
		statusIndicator = v.findViewById(R.id.status_indicator);
		connectingIndicator = v.findViewById(R.id.connecting_indicator);
		this.listener = listener;
	}

	public void bind(PendingContactItem item) {
		PendingContact p = item.getPendingContact();
		String alias = p.getAlias();

		// Set avatar with first letter of alias
		if (alias != null && !alias.isEmpty()) {
			avatar.setText(String.valueOf(alias.charAt(0)));
		} else {
			avatar.setText("?");
		}
		avatar.setBackgroundBytes(p.getId().getBytes());

		// Set contact name prominently
		name.setText(alias != null && !alias.isEmpty() ? alias : "Unknown");

		time.setText(formatDate(time.getContext(), p.getTimestamp()));
		removeButton.setOnClickListener(v -> {
			listener.onPendingContactItemRemoved(item);
			removeButton.setEnabled(false);
		});

		// Default colors
		int cyanColor = ContextCompat.getColor(status.getContext(), R.color.zerion_cyan);
		int secondaryColor = ContextCompat.getColor(status.getContext(), R.color.zerion_text_secondary);
		int errorColor = ContextCompat.getColor(status.getContext(), R.color.briar_red_500);
		int successColor = ContextCompat.getColor(status.getContext(), R.color.briar_lime_600);

		// Default state
		boolean showSpinner = false;
		int statusColor = secondaryColor;
		int indicatorColor = cyanColor;

		switch (item.getState()) {
			case WAITING_FOR_CONNECTION:
				status.setText(R.string.waiting_for_contact_to_come_online);
				statusColor = secondaryColor;
				indicatorColor = secondaryColor;
				showSpinner = false;
				break;
			case OFFLINE:
				status.setText(R.string.waiting_for_contact_to_come_online);
				statusColor = secondaryColor;
				indicatorColor = secondaryColor;
				showSpinner = false;
				break;
			case CONNECTING:
				status.setText(R.string.connecting);
				statusColor = cyanColor;
				indicatorColor = cyanColor;
				showSpinner = true;
				break;
			case ADDING_CONTACT:
				status.setText(R.string.adding_contact);
				statusColor = successColor;
				indicatorColor = successColor;
				showSpinner = true;
				break;
			case FAILED:
				status.setText(R.string.adding_contact_failed);
				statusColor = errorColor;
				indicatorColor = errorColor;
				showSpinner = false;
				break;
			default:
				throw new IllegalStateException();
		}

		status.setTextColor(statusColor);

		// Update status indicator color
		if (statusIndicator != null) {
			statusIndicator.getBackground().setTint(indicatorColor);
		}

		// Show/hide connecting spinner
		if (connectingIndicator != null) {
			connectingIndicator.setVisibility(showSpinner ? View.VISIBLE : View.GONE);
			if (showSpinner) {
				connectingIndicator.setIndicatorColor(indicatorColor);
			}
		}

		// Adjust status text margin based on spinner visibility
		android.view.ViewGroup.MarginLayoutParams params =
				(android.view.ViewGroup.MarginLayoutParams) status.getLayoutParams();
		int margin = showSpinner ? (int) (8 * status.getContext().getResources().getDisplayMetrics().density) : 0;
		params.setMarginStart(margin);
		status.setLayoutParams(params);

		removeButton.setEnabled(true);
	}

}
