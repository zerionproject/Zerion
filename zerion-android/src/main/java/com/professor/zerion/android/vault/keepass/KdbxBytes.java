package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
final class KdbxBytes {

	private KdbxBytes() {
	}

	static int u16(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	static int i32(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
				| ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	static long u32(byte[] b, int off) {
		return i32(b, off) & 0xFFFFFFFFL;
	}

	static long i64(byte[] b, int off) {
		return u32(b, off) | (u32(b, off + 4) << 32);
	}

	static byte[] u64le(long v) {
		byte[] out = new byte[8];
		for (int i = 0; i < 8; i++) {
			out[i] = (byte) (v >>> (8 * i));
		}
		return out;
	}

	static byte[] i32le(int v) {
		byte[] out = new byte[4];
		for (int i = 0; i < 4; i++) {
			out[i] = (byte) (v >>> (8 * i));
		}
		return out;
	}

	static byte[] concat(byte[]... parts) {
		int total = 0;
		for (byte[] p : parts) total += p.length;
		byte[] out = new byte[total];
		int off = 0;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, out, off, p.length);
			off += p.length;
		}
		return out;
	}

	static byte[] sha256(byte[]... parts) {
		return digest("SHA-256", parts);
	}

	static byte[] sha512(byte[]... parts) {
		return digest("SHA-512", parts);
	}

	private static byte[] digest(String algorithm, byte[]... parts) {
		try {
			MessageDigest md = MessageDigest.getInstance(algorithm);
			for (byte[] p : parts) md.update(p);
			return md.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static byte[] hmacSha256(byte[] key, byte[]... parts) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			for (byte[] p : parts) mac.update(p);
			return mac.doFinal();
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a.length != b.length) return false;
		int result = 0;
		for (int i = 0; i < a.length; i++) {
			result |= a[i] ^ b[i];
		}
		return result == 0;
	}

	static boolean equalsUuid(byte[] value, String hex) {
		if (value.length != 16 || hex.length() != 32) return false;
		for (int i = 0; i < 16; i++) {
			int expected = Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
			if ((value[i] & 0xFF) != expected) return false;
		}
		return true;
	}

	static void wipe(byte[] b) {
		Arrays.fill(b, (byte) 0);
	}
}
