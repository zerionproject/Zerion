package com.professor.zerion.android.sticker;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@NotNullByDefault
public final class StickerStorage {

	public static final int MAX_STICKERS = 200;
	public static final int MAX_FILE_BYTES = 256 * 1024;
	private static final int GCM_NONCE_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final String DIR = "stickers";

	private final File dir;
	private final SecureRandom random = new SecureRandom();

	public StickerStorage(Context appContext) {
		this.dir = new File(appContext.getFilesDir(), DIR);
		if (!dir.exists()) {

			dir.mkdirs();
		}
	}

	public File getDir() {
		return dir;
	}

	public String save(byte[] pngBytes) throws IOException {
		if (pngBytes.length > MAX_FILE_BYTES) {
			throw new IOException("sticker too large: " + pngBytes.length);
		}
		if (count() >= MAX_STICKERS) {
			throw new IOException("sticker cap reached: " + MAX_STICKERS);
		}
		byte[] nonce = new byte[GCM_NONCE_BYTES];
		random.nextBytes(nonce);
		byte[] ciphertext;
		try {
			SecretKey key = StickerKeystore.getOrCreate();
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.ENCRYPT_MODE, key,
					new GCMParameterSpec(GCM_TAG_BITS, nonce));
			ciphertext = cipher.doFinal(pngBytes);
		} catch (Exception e) {
			throw new IOException("encrypt failed", e);
		}
		if (ciphertext.length + GCM_NONCE_BYTES > MAX_FILE_BYTES) {
			throw new IOException("sticker too large after encryption");
		}
		String id = newId();
		File f = new File(dir, id + ".bin");
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(nonce);
			out.write(ciphertext);
			out.getFD().sync();
		}
		return id;
	}

	public byte[] load(String id) throws IOException {
		File f = new File(dir, id + ".bin");
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
		byte[] nonce = Arrays.copyOfRange(all, 0, GCM_NONCE_BYTES);
		byte[] ciphertext = Arrays.copyOfRange(all, GCM_NONCE_BYTES, all.length);
		try {
			SecretKey key = StickerKeystore.getOrCreate();
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(GCM_TAG_BITS, nonce));
			return cipher.doFinal(ciphertext);
		} catch (Exception e) {
			throw new IOException("decrypt failed", e);
		} finally {
			Arrays.fill(all, (byte) 0);
		}
	}

	public List<String> listIds() {
		File[] files = dir.listFiles();
		List<String> ids = new ArrayList<>();
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
		File f = new File(dir, id + ".bin");
		if (!f.exists()) return false;
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
			long len = raf.length();
			byte[] buf = new byte[Math.min((int) len, 1 << 16)];
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
		return f.delete();
	}

	public void wipeAll() {
		for (String id : listIds()) {
			delete(id);
		}
		StickerKeystore.deleteKey();
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
