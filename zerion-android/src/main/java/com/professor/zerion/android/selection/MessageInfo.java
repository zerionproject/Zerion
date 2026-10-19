package com.professor.zerion.android.selection;

import android.content.Context;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;

@UiThread
@NotNullByDefault
public final class MessageInfo {

	private final Context ctx;
	private final StringBuilder text = new StringBuilder();

	public MessageInfo(Context ctx) {
		this.ctx = ctx;
	}

	public MessageInfo line(@StringRes int label, CharSequence value) {
		return add(ctx.getString(R.string.message_info_line,
				ctx.getString(label), value));
	}

	public MessageInfo note(@StringRes int note) {
		return add(ctx.getString(note));
	}

	private MessageInfo add(CharSequence line) {
		if (text.length() > 0) text.append('\n');
		text.append(line);
		return this;
	}

	public String text() {
		return text.toString();
	}

	public AlertDialog show() {
		AlertDialog dialog = new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.selection_info)
				.setMessage(text.toString())
				.setPositiveButton(android.R.string.ok, null)
				.show();
		View title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
		if (title != null) ViewCompat.setAccessibilityHeading(title, true);
		return dialog;
	}
}
