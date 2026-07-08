package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public final class ChannelInviteSpanUtil {

	private static final Pattern INVITE_PATTERN = Pattern.compile(
			"zerion://channel/[A-Za-z0-9./?=&_-]+");

	private ChannelInviteSpanUtil() {
	}

	public static boolean apply(TextView textView, @Nullable String body) {
		if (body == null) return false;
		Matcher m = INVITE_PATTERN.matcher(body);
		if (!m.find()) return false;
		String link = m.group();
		Context ctx = textView.getContext();
		SpannableStringBuilder sb = new SpannableStringBuilder(body);
		int linkStart = m.start();
		int linkEnd = m.end();
		sb.setSpan(new ClickableSpan() {
			@Override
			public void onClick(View widget) {
				launchInvite(ctx, link);
			}
		}, linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

		String joinLabel = ctx.getString(
				R.string.channels_invite_inline_join);
		String copyLabel = ctx.getString(
				R.string.channels_invite_inline_copy);

		sb.append("\n\n");
		int joinStart = sb.length();
		sb.append(joinLabel);
		int joinEnd = sb.length();
		sb.setSpan(new ClickableSpan() {
			@Override
			public void onClick(View widget) {
				launchInvite(ctx, link);
			}
		}, joinStart, joinEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		sb.setSpan(new StyleSpan(Typeface.BOLD),
				joinStart, joinEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

		sb.append("     ");
		int copyStart = sb.length();
		sb.append(copyLabel);
		int copyEnd = sb.length();
		sb.setSpan(new ClickableSpan() {
			@Override
			public void onClick(View widget) {
				copyToClipboard(ctx, link);
			}
		}, copyStart, copyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		sb.setSpan(new StyleSpan(Typeface.BOLD),
				copyStart, copyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

		textView.setText(sb);
		textView.setMovementMethod(LinkMovementMethod.getInstance());
		return true;
	}

	private static void launchInvite(Context ctx, String link) {
		try {
			Intent i = new Intent(ctx, ChannelInviteHandlerActivity.class);
			i.setData(Uri.parse(link));
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(i);
		} catch (RuntimeException ignored) {
		}
	}

	private static void copyToClipboard(Context ctx, String link) {
		com.professor.zerion.android.util.SecureClipboard.copy(ctx,
				"zerion-channel-invite", link);
		Toast.makeText(ctx, R.string.channels_invite_inline_copied,
				Toast.LENGTH_SHORT).show();
	}
}
