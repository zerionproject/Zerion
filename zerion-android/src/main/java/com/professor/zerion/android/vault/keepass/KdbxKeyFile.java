package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayDeque;
import java.util.Deque;

import javax.annotation.Nullable;

@NotNullByDefault
final class KdbxKeyFile {

	static final int MAX_XML_KEY_FILE = 1024 * 1024;
	private static final int MAX_TEXT = 4096;

	private KdbxKeyFile() {
	}

	static byte[] keyFromFile(byte[] file) throws KdbxException {
		if (file.length == 0) throw KdbxException.wrongKey("empty key file");
		byte[] xmlKey = looksLikeXml(file) ? parseXmlKeyFile(file) : null;
		if (xmlKey != null) return xmlKey;
		if (file.length == 32) return file.clone();
		if (file.length == 64) {
			byte[] hex = decodeHex(new String(file, java.nio.charset.StandardCharsets.US_ASCII));
			if (hex != null) return hex;
		}
		return KdbxBytes.sha256(file);
	}

	private static boolean looksLikeXml(byte[] file) {
		if (file.length > MAX_XML_KEY_FILE) return false;
		int i = 0;
		if (file.length >= 3 && (file[0] & 0xFF) == 0xEF
				&& (file[1] & 0xFF) == 0xBB && (file[2] & 0xFF) == 0xBF) {
			i = 3;
		}
		while (i < file.length && isSpace(file[i])) i++;
		return i < file.length && file[i] == '<';
	}

	private static boolean isSpace(byte b) {
		return b == ' ' || b == '\t' || b == '\r' || b == '\n';
	}

	@Nullable
	private static byte[] parseXmlKeyFile(byte[] file) throws KdbxException {
		if (KdbxXml.containsDeclaration(file)) {
			throw KdbxException.corrupted("key file declaration");
		}
		Handler handler = new Handler();
		try {
			KdbxXml.parse(file, handler);
		} catch (KdbxException e) {
			if (e.reason == KdbxException.Reason.CORRUPTED && !handler.sawKeyFile) {
				return null;
			}
			throw e;
		}
		if (!handler.sawKeyFile) return null;
		String data = handler.data.toString();
		StringBuilder compact = new StringBuilder(data.length());
		for (int i = 0; i < data.length(); i++) {
			char c = data.charAt(i);
			if (!Character.isWhitespace(c)) compact.append(c);
		}
		String text = compact.toString();
		if (text.isEmpty()) throw KdbxException.corrupted("key file data");
		String version = handler.version.toString().trim();
		byte[] key;
		if (version.startsWith("2")) {
			key = decodeHex(text);
			if (key == null) throw KdbxException.corrupted("key file hex");
			String hashHex = handler.hash;
			if (hashHex != null) {
				byte[] expected = decodeHex(hashHex.trim());
				if (expected == null || expected.length < 1 || expected.length > 32) {
					throw KdbxException.corrupted("key file hash");
				}
				byte[] actual = KdbxBytes.sha256(key);
				byte[] prefix = new byte[expected.length];
				System.arraycopy(actual, 0, prefix, 0, expected.length);
				if (!KdbxBytes.constantTimeEquals(prefix, expected)) {
					throw KdbxException.corrupted("key file hash mismatch");
				}
			}
		} else {
			try {
				key = java.util.Base64.getDecoder().decode(text);
			} catch (IllegalArgumentException e) {
				throw KdbxException.corrupted("key file base64");
			}
		}
		if (key.length == 0 || key.length > 64) {
			throw KdbxException.corrupted("key file length");
		}
		return key;
	}

	@Nullable
	static byte[] decodeHex(String text) {
		int n = text.length();
		if (n == 0 || (n & 1) != 0) return null;
		byte[] out = new byte[n / 2];
		for (int i = 0; i < n; i += 2) {
			int hi = Character.digit(text.charAt(i), 16);
			int lo = Character.digit(text.charAt(i + 1), 16);
			if (hi < 0 || lo < 0) return null;
			out[i / 2] = (byte) ((hi << 4) | lo);
		}
		return out;
	}

	private static final class Handler extends DefaultHandler {

		final Deque<String> path = new ArrayDeque<>();
		final StringBuilder version = new StringBuilder();
		final StringBuilder data = new StringBuilder();
		boolean sawKeyFile = false;
		@Nullable
		String hash = null;

		@Override
		public void startElement(String uri, String localName, String qName,
				Attributes attributes) throws SAXException {
			if (path.isEmpty()) {
				if (!"KeyFile".equals(qName)) {
					throw new SAXException("not a key file");
				}
				sawKeyFile = true;
			}
			if (path.size() > 8) throw new SAXException("key file depth");
			path.push(qName);
			if (isData()) {
				String h = attributes.getValue("Hash");
				if (h != null) hash = h;
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) {
			path.pop();
		}

		@Override
		public void characters(char[] ch, int start, int length)
				throws SAXException {
			StringBuilder target;
			if (isData()) target = data;
			else if (isVersion()) target = version;
			else return;
			if (target.length() + length > MAX_TEXT) {
				throw new SAXException("key file text");
			}
			target.append(ch, start, length);
		}

		private boolean isData() {
			return path.size() == 3 && "Data".equals(path.peek())
					&& pathAt(1).equals("Key");
		}

		private boolean isVersion() {
			return path.size() == 3 && "Version".equals(path.peek())
					&& pathAt(1).equals("Meta");
		}

		private String pathAt(int depthFromTop) {
			int i = 0;
			for (String s : path) {
				if (i++ == depthFromTop) return s;
			}
			return "";
		}
	}
}
