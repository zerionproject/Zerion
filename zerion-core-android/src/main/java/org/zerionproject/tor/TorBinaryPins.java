package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

@NotNullByDefault
public final class TorBinaryPins implements TorBinaryVerifier {

	static final String RESOURCE = "/org/zerionproject/tor/binaries.sha256";

	private final Map<String, Set<String>> pinsByLibrary;

	private TorBinaryPins(Map<String, Set<String>> pinsByLibrary) {
		this.pinsByLibrary = pinsByLibrary;
	}

	public static TorBinaryPins shipped() throws IOException {
		InputStream in = TorBinaryPins.class.getResourceAsStream(RESOURCE);
		if (in == null) throw new IOException("Tor binary pins missing");
		try {
			return parse(in);
		} finally {
			in.close();
		}
	}

	static TorBinaryPins parse(InputStream in) throws IOException {
		Map<String, Set<String>> pins = new HashMap<>();
		BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF_8));
		String line;
		while ((line = r.readLine()) != null) {
			String s = line.trim();
			if (s.isEmpty() || s.startsWith("#")) continue;
			String[] parts = s.split("\\s+");
			if (parts.length != 2) throw new IOException("Tor binary pin line");
			int slash = parts[0].lastIndexOf('/');
			if (slash <= 0 || slash == parts[0].length() - 1) {
				throw new IOException("Tor binary pin name");
			}
			String library = parts[0].substring(slash + 1);
			String hash = parts[1].toLowerCase(Locale.US);
			if (!isSha256Hex(hash)) throw new IOException("Tor binary pin hash");
			Set<String> set = pins.get(library);
			if (set == null) {
				set = new HashSet<>();
				pins.put(library, set);
			}
			set.add(hash);
		}
		if (pins.isEmpty()) throw new IOException("Tor binary pins empty");
		return new TorBinaryPins(pins);
	}

	private static boolean isSha256Hex(String s) {
		if (s.length() != 64) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!hex) return false;
		}
		return true;
	}

	Set<String> libraries() {
		return Collections.unmodifiableSet(pinsByLibrary.keySet());
	}

	Set<String> pinsFor(String library) {
		Set<String> s = pinsByLibrary.get(library);
		return s == null ? Collections.<String>emptySet()
				: Collections.unmodifiableSet(s);
	}

	@Override
	public void verify(File tor, File lyrebird) throws IOException {
		verify(tor, AndroidTorWrapper.TOR_LIB_NAME);
		verify(lyrebird, AndroidTorWrapper.LYREBIRD_LIB_NAME);
	}

	void verify(File file, String library) throws IOException {
		Set<String> accepted = pinsByLibrary.get(library);
		if (accepted == null || accepted.isEmpty()) {
			throw new IOException("No pin for " + library);
		}
		if (!file.isFile()) throw new IOException("Missing " + library);
		String got = sha256Hex(file);
		if (!accepted.contains(got)) {
			throw new IOException(library + " does not match its pin");
		}
	}

	static String sha256Hex(File f) throws IOException {
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		try (InputStream in = new FileInputStream(f)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
		}
		byte[] digest = md.digest();
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for (byte b : digest) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
