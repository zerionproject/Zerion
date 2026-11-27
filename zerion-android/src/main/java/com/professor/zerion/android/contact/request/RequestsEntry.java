package com.professor.zerion.android.contact.request;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.db.DbException;

import java.util.concurrent.Executor;

import androidx.annotation.UiThread;

@NotNullByDefault
public class RequestsEntry {

	private final Activity activity;
	private final View entry;
	private final TextView badge;
	private final ContactRequestManager requests;
	private final Executor db;

	public RequestsEntry(Activity activity, View entry,
			ContactRequestManager requests, Executor db) {
		this.activity = activity;
		this.entry = entry;
		this.requests = requests;
		this.db = db;
		badge = entry.findViewById(R.id.contactRequestsBadge);
		entry.setOnClickListener(v -> activity.startActivity(
				RequestsActivity.intent(activity,
						RequestsActivity.TAB_RECEIVED)));
		show(0);
	}

	public void refresh() {
		db.execute(() -> {
			int count;
			try {
				count = requests.getUnopenedCount();
			} catch (DbException e) {
				return;
			}
			activity.runOnUiThread(() -> show(count));
		});
	}

	@UiThread
	private void show(int count) {
		String title = activity.getString(R.string.requests_title);
		if (count <= 0) {
			badge.setVisibility(View.GONE);
			entry.setContentDescription(title);
			return;
		}
		badge.setText(String.valueOf(count));
		badge.setVisibility(View.VISIBLE);
		entry.setContentDescription(title + ", " + activity.getResources()
				.getQuantityString(R.plurals.requests_unopened, count, count));
	}
}
