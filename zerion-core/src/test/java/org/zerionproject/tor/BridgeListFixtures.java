package org.zerionproject.tor;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

public final class BridgeListFixtures {

	public static final long DAY_MS = 24L * 60 * 60 * 1000;
	public static final long HOUR_MS = 60L * 60 * 1000;

	private static final SecureRandom RANDOM = new SecureRandom();

	private BridgeListFixtures() {
	}

	public static final class Signer {
		private final Ed25519PrivateKeyParameters key =
				new Ed25519PrivateKeyParameters(RANDOM);

		public byte[] publicKey() {
			return key.generatePublicKey().getEncoded();
		}

		public byte[] signRaw(byte[] message) {
			Ed25519Signer s = new Ed25519Signer();
			s.init(true, key);
			s.update(message, 0, message.length);
			return s.generateSignature();
		}

		public byte[] sign(byte[] file) {
			return encode(signRaw(BridgeList.signedMessage(file)));
		}

		public static byte[] encode(byte[] signature) {
			return (Base64.getEncoder().encodeToString(signature) + "\n")
					.getBytes(StandardCharsets.US_ASCII);
		}
	}

	public static List<String> bundled(String letter) {
		InputStream in = BridgeListFixtures.class.getClassLoader()
				.getResourceAsStream("bridges-" + letter + "-zz");
		List<String> lines = new ArrayList<>();
		try (Scanner s = new Scanner(in, "UTF-8")) {
			while (s.hasNextLine()) {
				String line = s.nextLine().trim();
				if (!line.isEmpty()) lines.add(line);
			}
		}
		return lines;
	}

	public static String date(long epochDay) {
		return LocalDate.ofEpochDay(epochDay).toString();
	}

	public static long day(long ms) {
		return Math.floorDiv(ms, DAY_MS);
	}

	public static String fingerprint(int n) {
		return String.format(Locale.US, "%040X", 0xABC000L + n);
	}

	public static String obfs4(int n) {
		char[] cert = new char[70];
		for (int i = 0; i < cert.length; i++) {
			cert[i] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
					.charAt((n * 7 + i) % 64);
		}
		return "obfs4 45.33." + (n / 200) + "." + (1 + n % 200) + ":443 "
				+ fingerprint(n) + " cert=" + new String(cert) + " iat-mode=0";
	}

	public static String vanilla(int n) {
		return "45.79." + (n / 200) + "." + (1 + n % 200) + ":9001 "
				+ fingerprint(5000 + n);
	}

	public static Map<String, List<String>> groups(Object... nameAndLines) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (int i = 0; i < nameAndLines.length; i += 2) {
			@SuppressWarnings("unchecked")
			List<String> lines = (List<String>) nameAndLines[i + 1];
			out.put((String) nameAndLines[i], lines);
		}
		return out;
	}

	public static String json(long seq, String issued, String expires,
			Map<String, List<String>> groups) {
		return json(BridgeList.CONTEXT, String.valueOf(seq), issued, expires,
				groups, "");
	}

	public static String json(String context, String seq, String issued,
			String expires, Map<String, List<String>> groups, String extra) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\n");
		sb.append("  \"context\": \"").append(context).append("\",\n");
		sb.append("  \"seq\": ").append(seq).append(",\n");
		sb.append("  \"issued\": \"").append(issued).append("\",\n");
		sb.append("  \"expires\": \"").append(expires).append("\",\n");
		sb.append(extra);
		sb.append("  \"bridges\": {");
		boolean firstGroup = true;
		for (Map.Entry<String, List<String>> e : groups.entrySet()) {
			sb.append(firstGroup ? "\n" : ",\n");
			firstGroup = false;
			sb.append("    \"").append(e.getKey()).append("\": [");
			boolean first = true;
			for (String line : e.getValue()) {
				sb.append(first ? "\n" : ",\n");
				first = false;
				sb.append("      \"").append(line).append('"');
			}
			sb.append(first ? "]" : "\n    ]");
		}
		sb.append(firstGroup ? "}\n" : "\n  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	public static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}
}
