package com.professor.zerion.android.conversation.linkpreview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.briarproject.briar.api.messaging.LinkPreview;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public class LinkPreviewFetcher {

	private static final int MAX_HTML_SIZE = 256 * 1024;
	private static final int MAX_IMAGE_SIZE = 50 * 1024;
	private static final int CONNECT_TIMEOUT_MS = 30000;
	private static final int READ_TIMEOUT_MS = 30000;
	private static final Pattern URL_PATTERN = Pattern.compile(
			"https?://[\\w\\-]+(\\.[\\w\\-]+)+[^\\s]*",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern PRIVATE_IP_PATTERN = Pattern.compile(
			"^https?://(10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|192\\.168\\." +
			"|127\\.|0\\.|169\\.254\\." +
			"|100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\." +
			"|localhost|\\[::1\\]|\\[::ffff:|\\[fc|\\[fd)");

	private final int torSocksPort;

	public LinkPreviewFetcher(int torSocksPort) {
		this.torSocksPort = torSocksPort;
	}

	@Nullable
	public static String extractUrl(String text) {
		Matcher m = URL_PATTERN.matcher(text);
		if (m.find()) {
			String url = m.group();
			if (PRIVATE_IP_PATTERN.matcher(url).find()) return null;
			return url;
		}
		return null;
	}

	@Nullable
	public LinkPreview fetch(String url) {
		if (PRIVATE_IP_PATTERN.matcher(url).find()) return null;
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			return null;
		}

		HttpURLConnection conn = null;
		try {
			Proxy proxy = new Proxy(Proxy.Type.SOCKS,
					new InetSocketAddress("127.0.0.1", torSocksPort));
			conn = (HttpURLConnection)
					new URL(url).openConnection(proxy);
			conn.setRequestProperty("User-Agent", "");
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			// Disable auto-redirects to check redirect targets for SSRF
			conn.setInstanceFollowRedirects(false);

			int responseCode = conn.getResponseCode();
			// Handle redirects manually with private IP check
			if (responseCode >= 300 && responseCode < 400) {
				String location = conn.getHeaderField("Location");
				conn.disconnect();
				if (location == null ||
						PRIVATE_IP_PATTERN.matcher(location).find()) {
					return null;
				}
				// Follow one redirect only
				conn = (HttpURLConnection)
						new URL(location).openConnection(proxy);
				conn.setRequestProperty("User-Agent", "");
				conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
				conn.setReadTimeout(READ_TIMEOUT_MS);
				conn.setInstanceFollowRedirects(false);
				responseCode = conn.getResponseCode();
			}
			if (responseCode != 200) return null;

			String contentType = conn.getContentType();
			if (contentType != null &&
					!contentType.toLowerCase(java.util.Locale.ROOT)
							.contains("text/html")) {
				return null;
			}

			String html = readLimited(conn.getInputStream(), MAX_HTML_SIZE);
			if (html == null) return null;

			OpenGraphParser parser = new OpenGraphParser();
			parser.parse(html);

			String title = parser.getTitle();
			if (title == null || title.isEmpty()) return null;
			if (title.length() > 200) title = title.substring(0, 200);

			String description = parser.getDescription();
			if (description != null && description.length() > 500) {
				description = description.substring(0, 500);
			}

			byte[] imageData = null;
			String imageUrl = parser.getImageUrl();
			if (imageUrl != null && !imageUrl.isEmpty()) {
				if (imageUrl.startsWith("//")) {
					imageUrl = "https:" + imageUrl;
				} else if (imageUrl.startsWith("/")) {
					URL base = new URL(url);
					imageUrl = base.getProtocol() + "://" +
							base.getHost() + imageUrl;
				}
				if (!PRIVATE_IP_PATTERN.matcher(imageUrl).find()) {
					imageData = fetchImage(imageUrl, proxy);
				}
			}

			return new LinkPreview(url, title, description, imageData);
		} catch (Exception e) {
			return null;
		} finally {
			if (conn != null) conn.disconnect();
		}
	}

	@Nullable
	private byte[] fetchImage(String imageUrl, Proxy proxy) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection)
					new URL(imageUrl).openConnection(proxy);
			conn.setRequestProperty("User-Agent", "");
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setInstanceFollowRedirects(false);

			int responseCode = conn.getResponseCode();
			if (responseCode >= 300 && responseCode < 400) {
				String location = conn.getHeaderField("Location");
				conn.disconnect();
				if (location == null ||
						PRIVATE_IP_PATTERN.matcher(location).find()) {
					return null;
				}
				conn = (HttpURLConnection)
						new URL(location).openConnection(proxy);
				conn.setRequestProperty("User-Agent", "");
				conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
				conn.setReadTimeout(READ_TIMEOUT_MS);
				conn.setInstanceFollowRedirects(false);
				responseCode = conn.getResponseCode();
			}
			if (responseCode != 200) return null;

			String contentType = conn.getContentType();
			if (contentType == null ||
					!contentType.toLowerCase(java.util.Locale.ROOT)
							.startsWith("image/")) {
				return null;
			}

			byte[] rawData = readBytesLimited(conn.getInputStream(),
					MAX_IMAGE_SIZE);
			if (rawData == null) return null;

			// Re-encode image to strip EXIF/metadata
			Bitmap bmp = BitmapFactory.decodeByteArray(
					rawData, 0, rawData.length);
			if (bmp == null) return null;
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				bmp.compress(Bitmap.CompressFormat.JPEG, 80, out);
				byte[] clean = out.toByteArray();
				return clean.length <= MAX_IMAGE_SIZE ? clean : null;
			} finally {
				bmp.recycle();
			}
		} catch (Exception e) {
			return null;
		} finally {
			if (conn != null) conn.disconnect();
		}
	}

	@Nullable
	private static String readLimited(InputStream in, int maxSize) {
		try {
			byte[] data = readBytesLimited(in, maxSize);
			if (data == null) return null;
			return new String(data, "UTF-8");
		} catch (Exception e) {
			return null;
		}
	}

	@Nullable
	private static byte[] readBytesLimited(InputStream in, int maxSize) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int total = 0;
			int n;
			while ((n = in.read(buf)) != -1) {
				total += n;
				if (total > maxSize) return null;
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} catch (Exception e) {
			return null;
		} finally {
			try { in.close(); } catch (Exception ignored) {}
		}
	}
}
