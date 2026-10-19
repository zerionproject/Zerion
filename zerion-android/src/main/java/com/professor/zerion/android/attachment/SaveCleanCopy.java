package com.professor.zerion.android.attachment;

import android.app.Activity;
import android.content.Intent;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.util.UiUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SaveCleanCopy {

	static final String EXTRA_TOKEN = "token";
	static final long MAX_BYTES = 128L * 1024 * 1024;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<String, Pending> PENDING =
			new ConcurrentHashMap<>();

	static final class Pending {

		final String mimeType;
		final byte[] data;
		final boolean cleaned;
		final boolean owned;

		Pending(String mimeType, byte[] data, boolean cleaned,
				boolean owned) {
			this.mimeType = mimeType;
			this.data = data;
			this.cleaned = cleaned;
			this.owned = owned;
		}
	}

	private SaveCleanCopy() {
	}

	public static boolean canSave(@Nullable String mimeType) {
		return SharedMediaSanitizer.canClean(mimeType);
	}

	public static void confirm(Activity activity, String mimeType,
			Runnable onConfirmed) {
		String type = SharedMediaSanitizer.base(mimeType);
		boolean image = type.startsWith("image/");
		boolean pdf = AttachmentDocuments.PDF.equals(type);
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(image ? R.string.dialog_title_save_image
						: R.string.dialog_title_save_file)
				.setMessage(pdf ? R.string.dialog_message_save_clean_pdf
						: R.string.dialog_message_save_clean)
				.setIcon(UiUtils.getDialogIcon(activity, R.drawable.ic_security))
				.setPositiveButton(image ? R.string.save_image
						: R.string.save_file, (d, w) -> onConfirmed.run())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	public static void start(Activity activity, byte[] data,
			String mimeType) {
		launch(activity, offer(data, mimeType, false));
	}

	public static void startCopy(Activity activity, byte[] copy,
			String mimeType) {
		launch(activity, offer(copy, mimeType, true));
	}

	private static void launch(Activity activity, String token) {
		activity.startActivity(new Intent(activity,
				SaveCleanCopyActivity.class).putExtra(EXTRA_TOKEN, token));
	}

	public static byte[] readAll(InputStream in) throws IOException {
		try (InputStream source = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = source.read(buf)) != -1) {
				if (out.size() + n > MAX_BYTES) {
					throw new IOException("file too large to save");
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	static String offer(byte[] data, String mimeType) {
		return offer(data, mimeType, false);
	}

	static String offer(byte[] data, String mimeType, boolean owned) {
		String token = newToken();
		PENDING.put(token, new Pending(mimeType, data, false, owned));
		return token;
	}

	@Nullable
	static Pending get(String token) {
		return PENDING.get(token);
	}

	static boolean cleaned(String token, SharedMediaSanitizer.Cleaned clean) {
		Pending raw = PENDING.get(token);
		if (raw == null) {
			wipe(clean.getData());
			return false;
		}
		boolean fresh = clean.getData() != raw.data;
		if (!PENDING.replace(token, raw, new Pending(clean.getMimeType(),
				clean.getData(), true, raw.owned || fresh))) {
			if (fresh) wipe(clean.getData());
			return false;
		}
		if (fresh && raw.owned) wipe(raw.data);
		return true;
	}

	static void done(@Nullable Pending pending) {
		if (pending != null && pending.owned) wipe(pending.data);
	}

	private static void wipe(byte[] data) {
		java.util.Arrays.fill(data, (byte) 0);
	}

	@Nullable
	static Pending take(String token) {
		return PENDING.remove(token);
	}

	static void drop(String token) {
		done(PENDING.remove(token));
	}

	public static void clearAll() {
		for (String token : new java.util.ArrayList<>(PENDING.keySet())) {
			drop(token);
		}
	}

	private static String newToken() {
		byte[] b = new byte[16];
		RANDOM.nextBytes(b);
		StringBuilder sb = new StringBuilder(32);
		for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
		return sb.toString();
	}
}
