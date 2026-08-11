package com.professor.zerion.android.conversation.voice;

class CallHex {

	private CallHex() {
	}

	static String bytesToHex(byte[] bytes) {
		StringBuilder hex = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	static byte[] hexToBytes(String hex) {
		int len = hex.length();
		if (len % 2 != 0) {
			throw new IllegalArgumentException("Invalid hex string length");
		}
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			int hi = Character.digit(hex.charAt(i), 16);
			int lo = Character.digit(hex.charAt(i + 1), 16);
			if (hi < 0 || lo < 0) {
				throw new IllegalArgumentException("Invalid hex character");
			}
			data[i / 2] = (byte) ((hi << 4) + lo);
		}
		return data;
	}
}
