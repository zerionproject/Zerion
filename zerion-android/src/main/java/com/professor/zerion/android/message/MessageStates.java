package com.professor.zerion.android.message;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.DrawableRes;
import androidx.annotation.UiThread;
import androidx.appcompat.content.res.AppCompatResources;

@UiThread
@NotNullByDefault
public final class MessageStates {

	private static final int SUBJECT_CHARS = 40;

	private MessageStates() {
	}

	public static CharSequence meta(Context ctx, CharSequence time,
			@Nullable MessageState state) {
		if (state == null) return time;
		return ctx.getString(R.string.message_state_meta, time,
				ctx.getString(state.getLabel()));
	}

	@Nullable
	public static String description(Context ctx, CharSequence time,
			@Nullable MessageState state) {
		if (state == null) return null;
		return ctx.getString(R.string.message_state_description, time,
				ctx.getString(state.getLabel()));
	}

	public static void bind(TextView meta, CharSequence time,
			@Nullable MessageState state) {
		Context ctx = meta.getContext();
		meta.setText(meta(ctx, time, state));
		meta.setContentDescription(description(ctx, time, state));
		Drawable icon = null;
		if (state != null) {
			Drawable d = AppCompatResources.getDrawable(ctx, state.getIcon());
			if (d != null) {
				icon = d.mutate();
				icon.setTintList(meta.getTextColors());
			}
		}
		meta.setCompoundDrawablePadding(Math.round(4
				* ctx.getResources().getDisplayMetrics().density));
		meta.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon,
				null);
	}

	public static void bind(TextView meta, ImageView icon, CharSequence time,
			MessageState state) {
		Context ctx = meta.getContext();
		meta.setText(meta(ctx, time, state));
		meta.setContentDescription(description(ctx, time, state));
		icon.setImageResource(state.getIcon());
		icon.setContentDescription(null);
		icon.setImportantForAccessibility(
				View.IMPORTANT_FOR_ACCESSIBILITY_NO);
	}

	public static void outline(View bubble, boolean notSent,
			@DrawableRes int outline) {
		bubble.setForeground(notSent
				? AppCompatResources.getDrawable(bubble.getContext(), outline)
				: null);
	}

	public static void actions(@Nullable View actions, CharSequence subject,
			@Nullable Runnable edit, @Nullable Runnable retry,
			@Nullable Runnable delete) {
		actions(actions, subject, edit, false, retry, delete);
	}

	public static void actions(@Nullable View actions, CharSequence subject,
			@Nullable Runnable edit, boolean editCopies,
			@Nullable Runnable retry, @Nullable Runnable delete) {
		if (actions == null) return;
		boolean shown = edit != null || retry != null || delete != null;
		actions.setVisibility(shown ? View.VISIBLE : View.GONE);
		String about = subject(subject);
		View editButton = actions.findViewById(R.id.messageEditButton);
		if (editButton instanceof TextView) {
			((TextView) editButton).setText(editCopies
					? R.string.copy_text : R.string.action_edit_message);
		}
		bindAction(editButton, edit, about);
		bindAction(actions.findViewById(R.id.messageRetryButton), retry,
				about);
		bindAction(actions.findViewById(R.id.messageDeleteButton), delete,
				about);
	}

	public static void hideActions(@Nullable View actions) {
		actions(actions, "", null, null, null);
	}

	public static String subject(CharSequence subject) {
		String s = subject.toString().trim().replace('\n', ' ');
		if (s.length() <= SUBJECT_CHARS) return s;
		int end = Character.isHighSurrogate(s.charAt(SUBJECT_CHARS - 1))
				? SUBJECT_CHARS - 1 : SUBJECT_CHARS;
		return s.substring(0, end) + "…";
	}

	private static void bindAction(@Nullable View button,
			@Nullable Runnable action, String about) {
		if (button == null) return;
		button.setVisibility(action == null ? View.GONE : View.VISIBLE);
		button.setEnabled(action != null);
		if (action == null) {
			button.setOnClickListener(null);
			button.setContentDescription(null);
			return;
		}
		CharSequence label = button instanceof TextView
				? ((TextView) button).getText() : "";
		button.setContentDescription(about.isEmpty() ? label
				: button.getContext().getString(
						R.string.message_state_action_description, label,
						about));
		button.setOnClickListener(v -> {
			v.setEnabled(false);
			action.run();
		});
	}
}
