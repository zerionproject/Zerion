package com.professor.zerion.android.vault.share;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.MimeTypeMap;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class VaultShareRegistry {

	public static final long MAX_LIFETIME_MS = 10L * 60L * 1000L;

	static final String PATH = "item";

	private static final Object LOCK = new Object();
	@GuardedBy("LOCK")
	private static final Map<String, Entry> ENTRIES = new HashMap<>();
	private static final SecureRandom RANDOM = new SecureRandom();

	static final class Entry {
		final byte[] data;
		final String name;
		final String mimeType;
		final long expiresAt;
		@GuardedBy("LOCK")
		boolean released;

		Entry(byte[] data, String name, String mimeType, long expiresAt) {
			this.data = data;
			this.name = name;
			this.mimeType = mimeType;
			this.expiresAt = expiresAt;
		}
	}

	private VaultShareRegistry() {
	}

	static String authority(Context ctx) {
		return ctx.getPackageName() + ".vaultshare";
	}

	public static Uri register(Context ctx, byte[] data, String name) {
		byte[] tokenBytes = new byte[16];
		RANDOM.nextBytes(tokenBytes);
		StringBuilder token = new StringBuilder();
		for (byte b : tokenBytes) {
			token.append(String.format(Locale.ROOT, "%02x", b & 0xff));
		}
		String key = token.toString();
		Entry e = new Entry(data, name, mimeTypeOf(name),
				SystemClock.elapsedRealtime() + MAX_LIFETIME_MS);
		synchronized (LOCK) {
			releaseExpiredLocked();
			ENTRIES.put(key, e);
		}
		new Handler(Looper.getMainLooper()).postDelayed(
				VaultShareRegistry::releaseExpired, MAX_LIFETIME_MS + 1000L);
		return new Uri.Builder().scheme("content")
				.authority(authority(ctx)).appendPath(PATH).appendPath(key)
				.appendPath(name).build();
	}

	public static boolean isVaultShare(Context ctx, Uri uri) {
		return authority(ctx).equals(uri.getAuthority());
	}

	@Nullable
	static Entry get(Uri uri) {
		String key = keyOf(uri);
		if (key == null) return null;
		synchronized (LOCK) {
			releaseExpiredLocked();
			return ENTRIES.get(key);
		}
	}

	static int read(Entry e, int offset, byte[] buf) {
		synchronized (LOCK) {
			if (e.released) return -1;
			int n = Math.min(buf.length, e.data.length - offset);
			if (n <= 0) return 0;
			System.arraycopy(e.data, offset, buf, 0, n);
			return n;
		}
	}

	public static void release(Uri uri) {
		String key = keyOf(uri);
		if (key == null) return;
		synchronized (LOCK) {
			Entry e = ENTRIES.remove(key);
			if (e != null) wipeLocked(e);
		}
	}

	public static void releaseAll() {
		synchronized (LOCK) {
			for (Entry e : ENTRIES.values()) wipeLocked(e);
			ENTRIES.clear();
		}
	}

	static int size() {
		synchronized (LOCK) {
			return ENTRIES.size();
		}
	}

	private static void releaseExpired() {
		synchronized (LOCK) {
			releaseExpiredLocked();
		}
	}

	@GuardedBy("LOCK")
	private static void releaseExpiredLocked() {
		long now = SystemClock.elapsedRealtime();
		List<String> expired = new ArrayList<>();
		for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
			if (now >= e.getValue().expiresAt) expired.add(e.getKey());
		}
		for (String k : expired) wipeLocked(ENTRIES.remove(k));
	}

	@GuardedBy("LOCK")
	private static void wipeLocked(Entry e) {
		e.released = true;
		Arrays.fill(e.data, (byte) 0);
	}

	@Nullable
	private static String keyOf(Uri uri) {
		List<String> segments = uri.getPathSegments();
		if (segments.size() < 2 || !PATH.equals(segments.get(0))) return null;
		return segments.get(1);
	}

	private static String mimeTypeOf(String name) {
		int dot = name.lastIndexOf('.');
		if (dot >= 0 && dot < name.length() - 1) {
			String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
			if (ext.equals("md") || ext.equals("markdown")) {
				return "text/markdown";
			}
			String mime = MimeTypeMap.getSingleton()
					.getMimeTypeFromExtension(ext);
			if (mime != null) return mime;
		}
		return "application/octet-stream";
	}
}
