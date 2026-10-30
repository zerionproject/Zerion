package com.professor.zerion.android.sticker;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public final class StickerStorage {

	public static final int MAX_STICKERS = 200;
	public static final int MAX_FILE_BYTES = 256 * 1024;
	static final String PREF_KEY = "sticker_key_v2";
	static final String PENDING_SUFFIX = ".new";
	static final String TMP_SUFFIX = ".tmp";
	private static final Object KEY_CREATION_LOCK = new Object();
	private static final int KEY_BYTES = 32;
	private static final int GCM_NONCE_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final byte[] AAD =
			"zerion-sticker-v2".getBytes(StandardCharsets.UTF_8);

	public interface LegacyKey {

		@Nullable
		SecretKey get();
	}

	private final File dir;
	private final SharedPreferences profilePrefs;
	private final LegacyKey legacy;
	private final SecureRandom random = new SecureRandom();
	private final Object keyLock = new Object();

	@Nullable
	private volatile SecretKey profileKey;

	public StickerStorage(File dir, SharedPreferences profilePrefs) {
		this(dir, profilePrefs, StickerKeystore::getIfExists);
	}

	StickerStorage(File dir, SharedPreferences profilePrefs,
			LegacyKey legacy) {
		this.dir = dir;
		this.profilePrefs = profilePrefs;
		this.legacy = legacy;
		if (!dir.exists()) dir.mkdirs();
	}

	public File getDir() {
		return dir;
	}

	private SecretKey profileKey() throws IOException {
		SecretKey k = profileKey;
		if (k != null) return k;
		synchronized (keyLock) {
			k = profileKey;
			if (k != null) return k;
			byte[] raw = storedKey();
			if (raw == null) {
				synchronized (KEY_CREATION_LOCK) {
					raw = storedKey();
					if (raw == null) raw = createKey();
				}
			}
			k = new SecretKeySpec(raw, "AES");
			Arrays.fill(raw, (byte) 0);
			profileKey = k;
			return k;
		}
	}

	@Nullable
	private byte[] storedKey() throws IOException {
		String stored = profilePrefs.getString(PREF_KEY, null);
		if (stored == null) return null;
		byte[] raw = decodeKey(stored);
		if (raw == null) throw new IOException("sticker key damaged");
		return raw;
	}

	@Nullable
	private static byte[] decodeKey(String encoded) {
		try {
			byte[] raw = Base64.getDecoder().decode(encoded);
			if (raw.length == KEY_BYTES) return raw;
			Arrays.fill(raw, (byte) 0);
		} catch (IllegalArgumentException ignored) {
		}
		return null;
	}

	private byte[] createKey() throws IOException {
		byte[] fresh = new byte[KEY_BYTES];
		random.nextBytes(fresh);
		String encoded = Base64.getEncoder().encodeToString(fresh);
		boolean saved = profilePrefs.edit().putString(PREF_KEY, encoded)
				.commit();
		byte[] stored = null;
		if (saved) {
			stored = storedKey();
		} else {
			String now = profilePrefs.getString(PREF_KEY, null);
			if (now != null) stored = decodeKey(now);
		}
		Arrays.fill(fresh, (byte) 0);
		if (stored == null) throw new IOException("sticker key not stored");
		return stored;
	}

	@Nullable
	private SecretKey profileKeyIfStored() {
		if (profileKey != null) return profileKey;
		if (!profilePrefs.contains(PREF_KEY)) return null;
		try {
			return profileKey();
		} catch (IOException e) {
			return null;
		}
	}

	public String save(byte[] pngBytes) throws IOException {
		if (pngBytes.length > MAX_FILE_BYTES) {
			throw new IOException("sticker too large: " + pngBytes.length);
		}
		if (count() >= MAX_STICKERS) {
			throw new IOException("sticker cap reached: " + MAX_STICKERS);
		}
		byte[] sealed = seal(profileKey(), pngBytes);
		if (sealed.length > MAX_FILE_BYTES) {
			throw new IOException("sticker too large after encryption");
		}
		String id = newId();
		writeDurably(new File(dir, id + ".bin"), sealed);
		return id;
	}

	private static byte[] seal(SecretKey key, byte[] plaintext)
			throws IOException {
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.ENCRYPT_MODE, key);
			cipher.updateAAD(AAD);
			byte[] nonce = cipher.getIV();
			byte[] ciphertext = cipher.doFinal(plaintext);
			byte[] out = new byte[nonce.length + ciphertext.length];
			System.arraycopy(nonce, 0, out, 0, nonce.length);
			System.arraycopy(ciphertext, 0, out, nonce.length,
					ciphertext.length);
			return out;
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new IOException("encrypt failed", e);
		}
	}

	private static void writeDurably(File f, byte[] data) throws IOException {
		File tmp = new File(f.getParentFile(), f.getName() + TMP_SUFFIX);
		try (FileOutputStream out = new FileOutputStream(tmp)) {
			out.write(data);
			out.getFD().sync();
		}
		try {
			Files.move(tmp.toPath(), f.toPath(),
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			if (f.exists() && !f.delete()) {
				tmp.delete();
				throw new IOException("cannot replace sticker file", e);
			}
			if (!tmp.renameTo(f)) {
				tmp.delete();
				throw new IOException("cannot rename sticker file", e);
			}
		}
	}

	public byte[] load(String id) throws IOException {
		File f = new File(dir, id + ".bin");
		finishPending(f);
		byte[] all = readSealed(f);
		try {
			byte[] png = open(profileKeyIfStored(), all, true);
			if (png != null) return png;
			png = open(legacy.get(), all, false);
			if (png == null) throw new IOException("decrypt failed");
			migrate(f, png);
			return png;
		} finally {
			Arrays.fill(all, (byte) 0);
		}
	}

	private static byte[] readSealed(File f) throws IOException {
		long len = f.length();
		if (len <= GCM_NONCE_BYTES || len > MAX_FILE_BYTES) {
			throw new IOException("bad sticker file");
		}
		byte[] all = new byte[(int) len];
		try (FileInputStream in = new FileInputStream(f)) {
			int total = 0;
			while (total < all.length) {
				int n = in.read(all, total, all.length - total);
				if (n < 0) break;
				total += n;
			}
			if (total != all.length) {
				throw new IOException("short read on sticker file");
			}
		}
		return all;
	}

	@Nullable
	private static byte[] open(@Nullable SecretKey key, byte[] all,
			boolean withAad) {
		if (key == null) return null;
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(GCM_TAG_BITS, all, 0,
							GCM_NONCE_BYTES));
			if (withAad) cipher.updateAAD(AAD);
			return cipher.doFinal(all, GCM_NONCE_BYTES,
					all.length - GCM_NONCE_BYTES);
		} catch (GeneralSecurityException | RuntimeException e) {
			return null;
		}
	}

	private void migrate(File f, byte[] png) {
		SecretKey key;
		try {
			key = profileKey();
		} catch (IOException e) {
			return;
		}
		File pending = new File(dir, f.getName() + PENDING_SUFFIX);
		try {
			writeDurably(pending, seal(key, png));
			overwrite(f);
			finishPending(f);
		} catch (IOException ignored) {
			pending.delete();
		}
	}

	private void finishPending(File target) {
		File pending = new File(dir, target.getName() + PENDING_SUFFIX);
		if (!pending.isFile()) return;
		if (!decryptsUnderProfileKey(pending)) {
			overwrite(pending);
			pending.delete();
			return;
		}
		try {
			Files.move(pending.toPath(), target.toPath(),
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			if (target.delete() || !target.exists()) {
				pending.renameTo(target);
			}
		}
	}

	private boolean decryptsUnderProfileKey(File pending) {
		byte[] all;
		try {
			all = readSealed(pending);
		} catch (IOException e) {
			return false;
		}
		try {
			byte[] png = open(profileKeyIfStored(), all, true);
			if (png == null) return false;
			Arrays.fill(png, (byte) 0);
			return true;
		} finally {
			Arrays.fill(all, (byte) 0);
		}
	}

	public List<String> listIds() {
		File[] files = dir.listFiles();
		List<String> ids = new ArrayList<>();
		if (files == null) return ids;
		for (File f : files) {
			String name = f.getName();
			if (name.endsWith(TMP_SUFFIX)) {
				overwrite(f);
				f.delete();
			} else if (name.endsWith(".bin" + PENDING_SUFFIX)) {
				finishPending(new File(dir, name.substring(0,
						name.length() - PENDING_SUFFIX.length())));
			}
		}
		files = dir.listFiles();
		if (files == null) return ids;
		Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
		for (File f : files) {
			String name = f.getName();
			if (name.endsWith(".bin")) {
				ids.add(name.substring(0, name.length() - 4));
			}
		}
		return ids;
	}

	public int count() {
		return listIds().size();
	}

	public boolean delete(String id) {
		for (String suffix : new String[] {PENDING_SUFFIX, TMP_SUFFIX,
				PENDING_SUFFIX + TMP_SUFFIX}) {
			File leftover = new File(dir, id + ".bin" + suffix);
			if (leftover.exists()) {
				overwrite(leftover);
				leftover.delete();
			}
		}
		File f = new File(dir, id + ".bin");
		if (!f.exists()) return false;
		overwrite(f);
		return f.delete();
	}

	private void overwrite(File f) {
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
			long len = raf.length();
			byte[] buf = new byte[(int) Math.min(len, 1 << 16)];
			random.nextBytes(buf);
			raf.seek(0);
			long remaining = len;
			while (remaining > 0) {
				int n = (int) Math.min(remaining, buf.length);
				raf.write(buf, 0, n);
				remaining -= n;
			}
			raf.getFD().sync();
		} catch (IOException ignored) {
		}
	}

	public void wipeAll() {
		for (String id : listIds()) {
			delete(id);
		}
	}

	private String newId() {
		byte[] b = new byte[16];
		random.nextBytes(b);
		StringBuilder sb = new StringBuilder(32);
		for (byte v : b) sb.append(String.format("%02x", v));
		return sb.toString();
	}

	public static byte[] readFully(FileInputStream in, int max) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int total = 0;
		int n;
		while ((n = in.read(buf)) > 0) {
			total += n;
			if (total > max) {
				throw new IOException("payload exceeds " + max + " bytes");
			}
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}
}
