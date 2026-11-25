package com.professor.zerion.android.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.update.InstallSource;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
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
		String tor = torBrowserPackage(ctx);
		if (tor != null) {
			b.setTitle(R.string.browser_tor_title)
					.setMessage(ctx.getString(R.string.browser_tor_message)
							+ "\n\n" + shownWithNote(ctx, url))
					.setPositiveButton(R.string.browser_tor_open,
							(d, w) -> launchSystemBrowser(ctx, url, tor))
					.setNegativeButton(android.R.string.cancel, null);
		} else {
			String message = ctx.getString(R.string.browser_warning_message);
			if (offerInstall) {
				message += "\n\n"
						+ ctx.getString(R.string.browser_tor_install_hint);
			}
			b.setTitle(R.string.browser_warning_title)
					.setMessage(message + "\n\n" + shownWithNote(ctx, url))
					.setPositiveButton(R.string.browser_warning_open,
							(d, w) -> launchSystemBrowser(ctx, url, null));
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

	public static String shownWithNote(Context ctx, String url) {
		String text = shown(url);
		if (!hostNeedsAlphabetNote(url)) return text;
		return text + "\n" + ctx.getString(R.string.link_host_other_alphabet);
	}

	public static boolean hostNeedsAlphabetNote(String url) {
		WebLink link = WebLink.parse(url);
		if (link == null) return false;
		if (hasOtherAlphabet(link.host)) return true;
		for (int i = 0; i < link.writtenHost.length(); i++) {
			if (link.writtenHost.charAt(i) > 0x7E) return true;
		}
		return !link.writtenHost.toLowerCase(Locale.ROOT)
				.equals(link.spelledHost);
	}

	public static boolean hasOtherAlphabet(@Nullable String host) {
		if (host == null) return false;
		for (String label : host.split("\\.")) {
			if (label.startsWith("xn--")) return true;
		}
		return false;
	}

	public static String shown(String url) {
		String opened = opened(url);
		return opened == null ? DisplayNames.clean(url) : opened;
	}

	@Nullable
	public static String opened(String url) {
		WebLink link = WebLink.parse(url);
		return link == null ? null : link.address;
	}

	@Nullable
	public static String webHost(String url) {
		WebLink link = WebLink.parse(url);
		return link == null ? null : link.host;
	}

	private static final class WebLink {

		private static final String AUTHORITY_END = "/\\?#";
		private static final int HIGHEST_PORT = 65_535;
		private static final String HEX = "0123456789ABCDEF";

		private static final long NUMBER_TOO_LARGE = 1L << 40;

		private final String writtenHost;
		private final String spelledHost;
		private final String host;
		private final String address;

		private WebLink(String writtenHost, String spelledHost, String host,
				String address) {
			this.writtenHost = writtenHost;
			this.spelledHost = spelledHost;
			this.host = host;
			this.address = address;
		}

		@Nullable
		static WebLink parse(String url) {
			int colon = url.indexOf(':');
			if (colon < 0) return null;
			String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) return null;
			if (!url.startsWith("//", colon + 1)) return null;
			int start = colon + 3;
			int end = start;
			while (end < url.length()
					&& AUTHORITY_END.indexOf(url.charAt(end)) < 0) {
				end++;
			}
			String authority = url.substring(start, end);
			String hostAndPort =
					authority.substring(authority.lastIndexOf('@') + 1);
			String writtenHost;
			String spelledHost;
			String host;
			String port;
			if (hostAndPort.startsWith("[")) {
				int close = hostAndPort.indexOf(']');
				if (close < 2) return null;
				String literal = hostAndPort.substring(1, close);
				if (!literal.matches("[0-9A-Fa-f:.]+")) return null;
				writtenHost = hostAndPort.substring(0, close + 1);
				host = "[" + literal.toLowerCase(Locale.ROOT) + "]";
				spelledHost = host;
				port = hostAndPort.substring(close + 1);
			} else {
				int portStart = hostAndPort.lastIndexOf(':');
				writtenHost = portStart < 0 ? hostAndPort
						: hostAndPort.substring(0, portStart);
				port = portStart < 0 ? "" : hostAndPort.substring(portStart);
				if (writtenHost.isEmpty() || writtenHost.indexOf('%') >= 0) {
					return null;
				}
				String ascii;
				try {
					ascii = IDN.toASCII(writtenHost);
				} catch (IllegalArgumentException e) {
					return null;
				}
				if (!ascii.matches("[A-Za-z0-9.-]+")) return null;
				spelledHost = ascii.toLowerCase(Locale.ROOT);
				if (endsInANumber(spelledHost)) {
					host = dottedQuad(spelledHost);
					if (host == null) return null;
				} else {
					host = spelledHost;
				}
			}
			if (!isPort(port)) return null;
			return new WebLink(writtenHost, spelledHost, host,
					scheme + "://" + host + port
							+ pathWithHiddenLettersEncoded(url.substring(end)));
		}

		private static boolean endsInANumber(String host) {
			String[] parts = host.split("\\.", -1);
			int last = parts.length - 1;
			if (parts[last].isEmpty()) {
				if (last == 0) return false;
				last--;
			}
			String part = parts[last];
			if (part.isEmpty()) return false;
			boolean digitsOnly = true;
			for (int i = 0; i < part.length() && digitsOnly; i++) {
				char c = part.charAt(i);
				digitsOnly = c >= '0' && c <= '9';
			}
			return digitsOnly || numberValue(part) >= 0;
		}

		@Nullable
		private static String dottedQuad(String host) {
			String[] parts = host.split("\\.", -1);
			int count = parts.length;
			if (parts[count - 1].isEmpty()) count--;
			if (count < 1 || count > 4) return null;
			long[] numbers = new long[count];
			for (int i = 0; i < count; i++) {
				numbers[i] = numberValue(parts[i]);
				if (numbers[i] < 0) return null;
				if (i < count - 1 && numbers[i] > 255) return null;
			}
			if (numbers[count - 1] >= 1L << (8 * (5 - count))) return null;
			long address = numbers[count - 1];
			for (int i = 0; i < count - 1; i++) {
				address += numbers[i] << (8 * (3 - i));
			}
			return (address >>> 24) + "." + ((address >>> 16) & 0xFF) + "."
					+ ((address >>> 8) & 0xFF) + "." + (address & 0xFF);
		}

		private static long numberValue(String part) {
			if (part.isEmpty()) return -1;
			int radix = 10;
			String digits = part;
			if (part.length() >= 2 && (part.startsWith("0x")
					|| part.startsWith("0X"))) {
				radix = 16;
				digits = part.substring(2);
			} else if (part.length() >= 2 && part.charAt(0) == '0') {
				radix = 8;
				digits = part.substring(1);
			}
			long value = 0;
			for (int i = 0; i < digits.length(); i++) {
				int digit = HEX.indexOf(Character.toUpperCase(digits.charAt(i)));
				if (digit < 0 || digit >= radix) return -1;
				value = Math.min(value * radix + digit, NUMBER_TOO_LARGE);
			}
			return value;
		}

		private static String pathWithHiddenLettersEncoded(String rest) {
			StringBuilder out = new StringBuilder(rest.length());
			int i = 0;
			if (rest.startsWith("\\")) {
				out.append('/');
				i = 1;
			}
			while (i < rest.length()) {
				int cp = rest.codePointAt(i);
				i += Character.charCount(cp);
				if (!DisplayNames.hidden(cp)) {
					out.appendCodePoint(cp);
					continue;
				}
				String letter = Character.getType(cp) == Character.SURROGATE
						? "\uFFFD" : new String(Character.toChars(cp));
				for (byte b : letter.getBytes(StandardCharsets.UTF_8)) {
					out.append('%').append(HEX.charAt((b >> 4) & 0xF))
							.append(HEX.charAt(b & 0xF));
				}
			}
			return out.toString();
		}

		private static boolean isPort(String port) {
			if (port.isEmpty()) return true;
			if (!port.matches(":[0-9]{0,5}")) return false;
			return port.length() == 1
					|| Integer.parseInt(port.substring(1)) <= HIGHEST_PORT;
		}
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

	@Nullable
	static String defaultBrowserPackage(Context ctx) {
		ResolveInfo chosen;
		try {
			chosen = ctx.getPackageManager().resolveActivity(
					anyWebLink("https"), PackageManager.MATCH_DEFAULT_ONLY);
		} catch (RuntimeException e) {
			return null;
		}
		if (chosen == null || chosen.activityInfo == null) return null;
		String pkg = chosen.activityInfo.packageName;
		return pkg != null && opensAnyWebLink(ctx, pkg) ? pkg : null;
	}

	private static Intent anyWebLink(String scheme) {
		Intent any = new Intent(Intent.ACTION_VIEW,
				Uri.parse(scheme + "://"));
		any.addCategory(Intent.CATEGORY_BROWSABLE);
		return any;
	}

	private static boolean opensAnyWebLink(Context ctx, String pkg) {
		return handles(ctx, pkg, anyWebLink("http"))
				&& handles(ctx, pkg, anyWebLink("https"));
	}

	private static boolean opensOnlyInBrowsers(Context ctx, Intent link) {
		List<ResolveInfo> handlers;
		try {
			handlers = ctx.getPackageManager().queryIntentActivities(link,
					PackageManager.MATCH_DEFAULT_ONLY);
		} catch (RuntimeException e) {
			return false;
		}
		if (handlers.isEmpty()) return false;
		for (ResolveInfo handler : handlers) {
			if (handler.activityInfo == null
					|| handler.activityInfo.packageName == null
					|| !opensAnyWebLink(ctx,
					handler.activityInfo.packageName)) {
				return false;
			}
		}
		return true;
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

	private static void launchSystemBrowser(Context ctx, String url,
			@Nullable String promised) {
		String address = opened(url);
		if (address == null) {
			Toast.makeText(ctx, R.string.link_unsupported,
					Toast.LENGTH_SHORT).show();
			return;
		}
		Intent i;
		try {
			i = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
		} catch (RuntimeException e) {
			Toast.makeText(ctx, R.string.link_unsupported,
					Toast.LENGTH_SHORT).show();
			return;
		}
		i.addCategory(Intent.CATEGORY_BROWSABLE);
		i.setSelector(null);
		i.setComponent(null);
		if (promised != null && !handles(ctx, promised, i)) {
			showDialog(ctx, url, true);
			return;
		}
		String browser = promised != null ? promised : torBrowserPackage(ctx);
		if (browser == null) browser = defaultBrowserPackage(ctx);
		if (browser != null) {
			i.setPackage(browser);
		} else if (!opensOnlyInBrowsers(ctx, i)) {
			Toast.makeText(ctx, R.string.updates_no_browser,
					Toast.LENGTH_SHORT).show();
			return;
		}
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			ctx.startActivity(i);
		} catch (RuntimeException e) {
			Toast.makeText(ctx, R.string.updates_no_browser,
					Toast.LENGTH_SHORT).show();
		}
	}

	private static void copyToClipboard(Context ctx, String url) {
		String address = opened(url);
		SecureClipboard.copy(ctx, "link", address == null ? url : address);
		Toast.makeText(ctx, R.string.browser_warning_link_copied,
				Toast.LENGTH_SHORT).show();
	}
}
