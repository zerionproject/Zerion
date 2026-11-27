package com.professor.zerion.android.contact.request;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.db.DbException;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

@NotNullByDefault
public final class RestorePausePrompt {

	private static final AtomicBoolean shown = new AtomicBoolean(false);

	private RestorePausePrompt() {
	}

	static void resetForTests() {
		shown.set(false);
	}

	public static void showOnce(Activity activity,
			ContactRequestManager requests, Executor db) {
		whenPaused(requests, db, () -> activity.runOnUiThread(() -> {
			if (activity.isFinishing() || shown.getAndSet(true)) return;
			show(activity, requests, db, null);
		}));
	}

	@UiThread
	public static void bindBanner(Activity activity, TextView banner,
			ContactRequestManager requests, Executor db) {
		banner.setText(R.string.invites_paused_banner);
		banner.setOnClickListener(v -> show(activity, requests, db,
				() -> banner.setVisibility(View.GONE)));
		db.execute(() -> {
			boolean paused;
			try {
				paused = requests.isPausedAfterRestore();
			} catch (DbException e) {
				return;
			}
			activity.runOnUiThread(() -> banner.setVisibility(
					paused ? View.VISIBLE : View.GONE));
		});
	}

	private static void whenPaused(ContactRequestManager requests,
			Executor db, Runnable then) {
		db.execute(() -> {
			try {
				if (requests.isPausedAfterRestore()) then.run();
			} catch (DbException e) {
			}
		});
	}

	@UiThread
	static void show(Activity activity, ContactRequestManager requests,
			Executor db, @Nullable Runnable confirmed) {
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(R.string.restore_pause_title)
				.setMessage(R.string.restore_pause_message)
				.setPositiveButton(R.string.restore_pause_yes, (d, w) ->
						db.execute(() -> {
							try {
								requests.confirmOnlyActiveDevice();
							} catch (DbException e) {
								return;
							}
							if (confirmed != null) {
								activity.runOnUiThread(confirmed);
							}
						}))
				.setNegativeButton(R.string.restore_pause_not_now, null)
				.setCancelable(false)
				.show();
	}
}
