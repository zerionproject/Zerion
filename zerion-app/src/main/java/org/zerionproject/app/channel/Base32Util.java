package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
final class Base32Util {

	private static final char[] ALPHABET =
			"abcdefghijklmnopqrstuvwxyz234567".toCharArray();
	private static final int[] DECODE = new int[128];

	static {
		for (int i = 0; i < DECODE.length; i++) DECODE[i] = -1;
		for (int i = 0; i < ALPHABET.length; i++) {
			DECODE[ALPHABET[i]] = i;
			char upper = (char) (ALPHABET[i] >= 'a' && ALPHABET[i] <= 'z'
					? ALPHABET[i] - 32 : ALPHABET[i]);
			DECODE[upper] = i;
		}
	}

	private Base32Util() {
	}

	static String encode(byte[] data) {
		if (data.length == 0) return "";
		StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
		int buffer = 0;
		int bitsLeft = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xFF);
			bitsLeft += 8;
			while (bitsLeft >= 5) {
				int index = (buffer >> (bitsLeft - 5)) & 0x1F;
				sb.append(ALPHABET[index]);
				bitsLeft -= 5;
			}
		}
		if (bitsLeft > 0) {
			int index = (buffer << (5 - bitsLeft)) & 0x1F;
			sb.append(ALPHABET[index]);
		}
		return sb.toString();
	}

	static byte[] decode(String input) {
		if (input.isEmpty()) return new byte[0];
		int bits = input.length() * 5;
		byte[] out = new byte[bits / 8];
		int buffer = 0;
		int bitsLeft = 0;
		int outIdx = 0;
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c >= DECODE.length || DECODE[c] < 0) {
				throw new IllegalArgumentException(
						"Invalid base32 character");
			}
			buffer = (buffer << 5) | DECODE[c];
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				out[outIdx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
				bitsLeft -= 8;
			}
		}
		return out;
	}
}
