package com.professor.zerion.android.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.net.IDN;
import java.util.Locale;

import javax.annotation.Nullable;

@NotNullByDefault
public final class BrowserGuard {

	private BrowserGuard() {
	}

	public static void openUrl(Context ctx, String url) {
		new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.browser_warning_title)
				.setMessage(ctx.getString(R.string.browser_warning_message)
						+ "\n\n" + shown(url))
				.setPositiveButton(R.string.browser_warning_open,
						(d, w) -> launchSystemBrowser(ctx, url))
				.setNeutralButton(R.string.browser_warning_copy,
						(d, w) -> copyToClipboard(ctx, url))
				.setNegativeButton(android.R.string.cancel, null)
				.setCancelable(true)
				.show();
	}

	public static void openUrl(Context ctx, Uri uri) {
		openUrl(ctx, uri.toString());
	}

	public static String shown(String url) {
		String clean = DisplayNames.clean(url);
		String host = webHost(url);
		int start = url.indexOf("//");
		if (host == null || host.startsWith("[") || start < 0) return clean;
		start += 2;
		int end = start;
		while (end < url.length() && "/\\?#".indexOf(url.charAt(end)) < 0) {
			end++;
		}
		int user = url.lastIndexOf('@', end - 1) + 1;
		if (user < start) user = start;
		int port = url.lastIndexOf(':', end - 1);
		if (port < user) port = end;
		if (url.substring(user, port).equalsIgnoreCase(host)) return clean;
		return DisplayNames.clean(url.substring(0, user) + host
				+ url.substring(port));
	}

	@Nullable
	public static String webHost(String url) {
		Uri uri;
		try {
			uri = Uri.parse(url);
		} catch (RuntimeException e) {
			return null;
		}
		String scheme = uri.getScheme();
		if (scheme == null) return null;
		scheme = scheme.toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) return null;
		String authority = uri.getEncodedAuthority();
		if (authority == null) return null;
		String host = authority.substring(authority.lastIndexOf('@') + 1);
		if (host.startsWith("[")) {
			int end = host.indexOf(']');
			if (end < 2) return null;
			String literal = host.substring(1, end);
			if (!literal.matches("[0-9A-Fa-f:.]+")) return null;
			return "[" + literal.toLowerCase(Locale.ROOT) + "]";
		}
		int port = host.lastIndexOf(':');
		if (port >= 0) host = host.substring(0, port);
		if (host.isEmpty() || host.indexOf('%') >= 0) return null;
		String ascii;
		try {
			ascii = IDN.toASCII(host);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (!ascii.matches("[A-Za-z0-9.-]+")) return null;
		return ascii.toLowerCase(Locale.ROOT);
	}

	private static void launchSystemBrowser(Context ctx, String url) {
		if (webHost(url) == null) {
			Toast.makeText(ctx, R.string.link_unsupported,
					Toast.LENGTH_SHORT).show();
			return;
		}
		try {
			Intent i = new Intent(Intent.ACTION_VIEW,
					Uri.parse(url).normalizeScheme());
			i.addCategory(Intent.CATEGORY_BROWSABLE);
			i.setSelector(null);
			i.setComponent(null);
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(i);
		} catch (RuntimeException e) {
			Toast.makeText(ctx, R.string.updates_no_browser,
					Toast.LENGTH_SHORT).show();
		}
	}

	private static void copyToClipboard(Context ctx, String url) {
		SecureClipboard.copy(ctx, "link", url);
		Toast.makeText(ctx, R.string.browser_warning_link_copied,
				Toast.LENGTH_SHORT).show();
	}
}
