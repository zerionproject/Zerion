package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * BIP-39 seed derivation over a mutable mnemonic. The library's derivation
 * takes the words as immutable strings; this one takes the characters the
 * caller can wipe, joins them with single spaces into a byte buffer, runs
 * PBKDF2-HMAC-SHA512 with the "mnemonic" salt and 2048 rounds, and wipes
 * every intermediate buffer. Only the English word list is supported, so
 * the mnemonic must be ASCII.
 */
@NotNullByDefault
public final class Bip39Seed {

	private static final int ROUNDS = 2048;
	private static final int SEED_BYTES = 64;
	private static final byte[] SALT =
			"mnemonic".getBytes(StandardCharsets.US_ASCII);

	private Bip39Seed() {
	}

	/**
	 * Validates a mnemonic against the English word list and its checksum
	 * without turning any of it into a string: each word is looked up by
	 * comparing characters against the list, the 11-bit indices are packed
	 * into entropy, and the checksum bits are compared with the hash.
	 *
	 * @throws IllegalArgumentException if the mnemonic is not valid.
	 */
	public static void check(char[] words) {
		byte[] joined = normalizedBytes(words);
		try {
			int count = joined.length == 0 ? 0 : 1;
			for (byte b : joined) if (b == ' ') count++;
			if (count < 12 || count > 24 || count % 3 != 0) {
				throw new IllegalArgumentException("word count");
			}
			java.util.List<String> list =
					org.bitcoinj.crypto.MnemonicCode.INSTANCE.getWordList();
			int entropyBits = count * 11 - count / 3;
			byte[] entropy = new byte[entropyBits / 8];
			int checksumBits = count / 3;
			int checksumValue = 0;
			int bit = 0;
			int start = 0;
			for (int w = 0; w < count; w++) {
				int end = start;
				while (end < joined.length && joined[end] != ' ') end++;
				int index = indexOf(list, joined, start, end);
				if (index < 0) throw new IllegalArgumentException("word");
				for (int i = 10; i >= 0; i--) {
					int v = (index >> i) & 1;
					if (bit < entropyBits) {
						if (v == 1) entropy[bit / 8] |= (byte) (0x80 >> (bit % 8));
					} else {
						checksumValue = (checksumValue << 1) | v;
					}
					bit++;
				}
				start = end + 1;
			}
			byte[] hash;
			try {
				hash = java.security.MessageDigest.getInstance("SHA-256")
						.digest(entropy);
			} catch (java.security.NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			} finally {
				Arrays.fill(entropy, (byte) 0);
			}
			int expected = (hash[0] & 0xFF) >> (8 - checksumBits);
			if (expected != checksumValue) {
				throw new IllegalArgumentException("checksum");
			}
		} finally {
			Arrays.fill(joined, (byte) 0);
		}
	}

	private static int indexOf(java.util.List<String> list, byte[] joined,
			int start, int end) {
		int lo = 0, hi = list.size() - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			int c = compare(list.get(mid), joined, start, end);
			if (c == 0) return mid;
			if (c < 0) lo = mid + 1;
			else hi = mid - 1;
		}
		return -1;
	}

	private static int compare(String word, byte[] joined, int start,
			int end) {
		int len = end - start;
		int n = Math.min(word.length(), len);
		for (int i = 0; i < n; i++) {
			int d = word.charAt(i) - (joined[start + i] & 0xFF);
			if (d != 0) return d;
		}
		return word.length() - len;
	}

	/** Derives the 64-byte seed. The caller owns and wipes {@code words}. */
	static byte[] fromMnemonic(char[] words) throws GeneralSecurityException {
		byte[] password = normalizedBytes(words);
		try {
			return pbkdf2HmacSha512(password, SALT, ROUNDS, SEED_BYTES);
		} finally {
			Arrays.fill(password, (byte) 0);
		}
	}

	/**
	 * The words as ASCII bytes separated by single spaces, with leading,
	 * trailing and repeated whitespace removed.
	 */
	static byte[] normalizedBytes(char[] words) {
		byte[] buf = new byte[words.length];
		int n = 0;
		boolean pendingSpace = false;
		for (char c : words) {
			if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
				if (n > 0) pendingSpace = true;
				continue;
			}
			if (c < 0x21 || c > 0x7e) {
				Arrays.fill(buf, (byte) 0);
				throw new IllegalArgumentException("mnemonic is not ASCII");
			}
			if (pendingSpace) {
				buf[n++] = ' ';
				pendingSpace = false;
			}
			buf[n++] = (byte) c;
		}
		byte[] out = Arrays.copyOf(buf, n);
		Arrays.fill(buf, (byte) 0);
		return out;
	}

	private static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt,
			int rounds, int length) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA512");
		mac.init(new SecretKeySpec(password, "HmacSHA512"));
		int hLen = mac.getMacLength();
		int blocks = (length + hLen - 1) / hLen;
		byte[] out = new byte[blocks * hLen];
		byte[] u = new byte[hLen];
		byte[] t = new byte[hLen];
		for (int block = 1; block <= blocks; block++) {
			mac.update(salt);
			mac.update(new byte[] {(byte) (block >>> 24), (byte) (block >>> 16),
					(byte) (block >>> 8), (byte) block});
			mac.doFinal(u, 0);
			System.arraycopy(u, 0, t, 0, hLen);
			for (int i = 1; i < rounds; i++) {
				mac.update(u);
				mac.doFinal(u, 0);
				for (int j = 0; j < hLen; j++) t[j] ^= u[j];
			}
			System.arraycopy(t, 0, out, (block - 1) * hLen, hLen);
		}
		Arrays.fill(u, (byte) 0);
		Arrays.fill(t, (byte) 0);
		byte[] seed = Arrays.copyOf(out, length);
		Arrays.fill(out, (byte) 0);
		return seed;
	}
}
