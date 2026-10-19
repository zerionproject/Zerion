package com.professor.zerion.android.util;

import android.view.View;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
public final class Feedback {

	private static final int MAX_LINES = 4;

	private Feedback() {
	}

	public static Snackbar show(View composer, CharSequence text) {
		return show(composer, text, 0, null);
	}

	public static Snackbar show(View composer, CharSequence text,
			@StringRes int action, @Nullable Runnable onAction) {
		ZerionSnackbarBuilder builder = new ZerionSnackbarBuilder();
		if (action != 0 && onAction != null) {
			builder.setAction(action, v -> onAction.run());
		}
		if (composer.isShown()) builder.setAnchorView(composer);
		Snackbar s = builder.make(composer, text, Snackbar.LENGTH_LONG);
		TextView label = s.getView().findViewById(
				com.google.android.material.R.id.snackbar_text);
		if (label != null) label.setMaxLines(MAX_LINES);
		s.show();
		return s;
	}

	public static Snackbar couldNotSend(View composer, int count,
			@Nullable Runnable retry) {
		CharSequence text = composer.getResources().getQuantityString(
				R.plurals.message_state_could_not_send, count, count);
		return show(composer, text, R.string.message_state_retry, retry);
	}
}
