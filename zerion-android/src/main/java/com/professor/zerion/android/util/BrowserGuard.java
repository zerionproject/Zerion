package com.professor.zerion.android.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.update.InstallSource;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

@NotNullByDefault
public final class BrowserGuard {

	static final String TOR_BROWSER = "org.torproject.torbrowser";
	static final String TOR_BROWSER_ALPHA = "org.torproject.torbrowser_alpha";
	static final String PLAY_STORE = "com.android.vending";
	static final String TOR_BROWSER_PLAY =
			"market://details?id=" + TOR_BROWSER;
	static final String TOR_BROWSER_PLAY_WEB =
			"https://play.google.com/store/apps/details?id=" + TOR_BROWSER;
	static final String GUARDIAN_REPO_FINGERPRINT =
			"B7C2EEFD8DAC7806AF67DFCD92EB18126BC08312A7F2D6F3862E46013C7A6135";
	static final String GUARDIAN_REPO =
			"fdroidrepos://guardianproject.info/fdroid/repo?fingerprint="
					+ GUARDIAN_REPO_FINGERPRINT;
	static final String GUARDIAN_PAGE = "https://guardianproject.info/fdroid/";
	static final String TOR_BROWSER_DOWNLOAD =
			"https://www.torproject.org/download/#android";
	private static final String PROBE_URL = "https://www.torproject.org/";

	private BrowserGuard() {
	}

	public static void openUrl(Context ctx, String url) {
		showDialog(ctx, url, true);
	}

	public static void openUrl(Context ctx, Uri uri) {
		openUrl(ctx, uri.toString());
	}

	public static boolean hasTorBrowser(Context ctx) {
		return torBrowserPackage(ctx) != null;
	}

	public static void offerTorBrowser(Context ctx, String message) {
		new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.browser_tor_get)
				.setMessage(message + "\n\n"
						+ ctx.getString(R.string.browser_tor_install_hint))
				.setPositiveButton(R.string.browser_tor_get,
						(d, w) -> getTorBrowser(ctx))
				.setNegativeButton(android.R.string.cancel, null)
				.setCancelable(true)
				.show();
	}

	private static void showDialog(Context ctx, String url,
			boolean offerInstall) {
		SecureAlertDialogBuilder b = new SecureAlertDialogBuilder(ctx);
		if (torBrowserPackage(ctx) != null) {
			b.setTitle(R.string.browser_tor_title)
					.setMessage(ctx.getString(R.string.browser_tor_message)
							+ "\n\n" + shown(url))
					.setPositiveButton(R.string.browser_tor_open,
							(d, w) -> launchSystemBrowser(ctx, url))
					.setNegativeButton(android.R.string.cancel, null);
		} else {
			String message = ctx.getString(R.string.browser_warning_message);
			if (offerInstall) {
				message += "\n\n"
						+ ctx.getString(R.string.browser_tor_install_hint);
			}
			b.setTitle(R.string.browser_warning_title)
					.setMessage(message + "\n\n" + shown(url))
					.setPositiveButton(R.string.browser_warning_open,
							(d, w) -> launchSystemBrowser(ctx, url));
			if (offerInstall) {
				b.setNegativeButton(R.string.browser_tor_get,
						(d, w) -> getTorBrowser(ctx));
			} else {
				b.setNegativeButton(android.R.string.cancel, null);
			}
		}
		b.setNeutralButton(R.string.browser_warning_copy,
						(d, w) -> copyToClipboard(ctx, url))
				.setCancelable(true)
				.show();
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

	@Nullable
	static String torBrowserPackage(Context ctx) {
		Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse(PROBE_URL));
		probe.addCategory(Intent.CATEGORY_BROWSABLE);
		for (String pkg : new String[] {TOR_BROWSER, TOR_BROWSER_ALPHA}) {
			if (handles(ctx, pkg, probe)) return pkg;
		}
		return null;
	}

	private static boolean handles(Context ctx, String pkg, Intent intent) {
		Intent probe = new Intent(intent);
		probe.setPackage(pkg);
		try {
			return !ctx.getPackageManager()
					.queryIntentActivities(probe, 0).isEmpty();
		} catch (RuntimeException e) {
			return false;
		}
	}

	static void getTorBrowser(Context ctx) {
		String installer = InstallSource.installerPackage(ctx);
		if (PLAY_STORE.equals(installer)) {
			Intent i = new Intent(Intent.ACTION_VIEW,
					Uri.parse(TOR_BROWSER_PLAY));
			i.setPackage(PLAY_STORE);
			if (startExplicit(ctx, i)) return;
			showDialog(ctx, TOR_BROWSER_PLAY_WEB, false);
			return;
		}
		if (InstallSource.isFdroidClient(installer)) {
			Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(GUARDIAN_REPO));
			List<String> clients = new ArrayList<>();
			clients.add(installer);
			for (String pkg : InstallSource.FDROID_CLIENTS) {
				if (!clients.contains(pkg)) clients.add(pkg);
			}
			for (String pkg : clients) {
				i.setPackage(pkg);
				if (startExplicit(ctx, i)) return;
			}
			showDialog(ctx, GUARDIAN_PAGE, false);
			return;
		}
		showDialog(ctx, TOR_BROWSER_DOWNLOAD, false);
	}

	private static boolean startExplicit(Context ctx, Intent intent) {
		String pkg = intent.getPackage();
		if (pkg == null || !handles(ctx, pkg, intent)) return false;
		try {
			Intent i = new Intent(intent);
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(i);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
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
			String tor = torBrowserPackage(ctx);
			if (tor != null) i.setPackage(tor);
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
