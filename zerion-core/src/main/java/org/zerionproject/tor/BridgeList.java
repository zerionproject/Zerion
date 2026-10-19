package org.zerionproject.tor;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Base64;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.tor.CircumventionProvider.BridgeType;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

@Immutable
@NotNullByDefault
public final class BridgeList {

	public static final String CONTEXT = "zerion-bridge-list-v1";

	public static final int MAX_FILE_BYTES = 64 * 1024;
	public static final int MAX_SIGNATURE_FILE_BYTES = 256;
	public static final int MAX_GROUPS = 16;
	public static final int MAX_GROUP_LINES = 64;
	public static final int MAX_LINES = 256;
	public static final int MAX_VALIDITY_DAYS = 366;

	public static final String OBFS4_DEFAULT = "obfs4-default";
	public static final String OBFS4_NON_DEFAULT = "obfs4-non-default";
	public static final String VANILLA = "vanilla";

	private static final Charset ASCII = Charset.forName("US-ASCII");
	private static final byte[] SIGNED_PREFIX = (CONTEXT + "\n").getBytes(ASCII);
	private static final int SIGNATURE_BYTES = 64;
	private static final Pattern SIGNATURE =
			Pattern.compile("[A-Za-z0-9+/]{85}[AQgw]==");
	private static final Pattern DATE =
			Pattern.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})");
	private static final Pattern GROUP = Pattern.compile("[a-z0-9-]{1,32}");
	private static final Set<String> FIELDS = new HashSet<>(
			asList("context", "seq", "issued", "expires", "bridges"));

	private final long seq;
	private final int issuedDay;
	private final int expiresDay;
	private final List<String> obfs4Default;
	private final List<String> obfs4NonDefault;
	private final List<String> vanilla;

	private BridgeList(long seq, int issuedDay, int expiresDay,
			List<String> obfs4Default, List<String> obfs4NonDefault,
			List<String> vanilla) {
		this.seq = seq;
		this.issuedDay = issuedDay;
		this.expiresDay = expiresDay;
		this.obfs4Default = unmodifiableList(obfs4Default);
		this.obfs4NonDefault = unmodifiableList(obfs4NonDefault);
		this.vanilla = unmodifiableList(vanilla);
	}

	public long getSeq() {
		return seq;
	}

	public int getIssuedDay() {
		return issuedDay;
	}

	public int getExpiresDay() {
		return expiresDay;
	}

	public List<String> lines(BridgeType type) {
		switch (type) {
			case DEFAULT_OBFS4:
				return obfs4Default;
			case NON_DEFAULT_OBFS4:
				return obfs4NonDefault;
			case VANILLA:
				return vanilla;
			default:
				return emptyList();
		}
	}

	public static byte[] signedMessage(byte[] file) {
		byte[] out = new byte[SIGNED_PREFIX.length + file.length];
		System.arraycopy(SIGNED_PREFIX, 0, out, 0, SIGNED_PREFIX.length);
		System.arraycopy(file, 0, out, SIGNED_PREFIX.length, file.length);
		return out;
	}

	public static boolean signatureValid(byte[] file, byte[] signatureFile,
			List<byte[]> keys) {
		return signingKey(file, signatureFile, keys) != null;
	}

	@Nullable
	public static byte[] signingKey(byte[] file, byte[] signatureFile,
			List<byte[]> keys) {
		if (file.length == 0 || file.length > MAX_FILE_BYTES) return null;
		byte[] signature = decodeSignature(signatureFile);
		if (signature == null) return null;
		for (byte[] key : keys) {
			if (key.length != Ed25519PublicKeyParameters.KEY_SIZE) continue;
			try {
				Ed25519Signer verifier = new Ed25519Signer();
				verifier.init(false, new Ed25519PublicKeyParameters(key, 0));
				verifier.update(SIGNED_PREFIX, 0, SIGNED_PREFIX.length);
				verifier.update(file, 0, file.length);
				if (verifier.verifySignature(signature)) return key.clone();
			} catch (RuntimeException ignored) {
			}
		}
		return null;
	}

	@Nullable
	static byte[] decodeSignature(byte[] signatureFile) {
		if (signatureFile.length == 0
				|| signatureFile.length > MAX_SIGNATURE_FILE_BYTES) {
			return null;
		}
		String text = new String(signatureFile, ASCII).trim();
		if (!SIGNATURE.matcher(text).matches()) return null;
		try {
			byte[] decoded = Base64.decode(text);
			return decoded.length == SIGNATURE_BYTES ? decoded : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Nullable
	public static BridgeList parse(byte[] file) {
		if (file.length == 0 || file.length > MAX_FILE_BYTES) return null;
		try {
			return fromDocument(new Parser(file).document());
		} catch (Malformed e) {
			return null;
		}
	}

	private static BridgeList fromDocument(Map<String, Object> top)
			throws Malformed {
		if (!top.keySet().equals(FIELDS)) throw new Malformed();
		if (!CONTEXT.equals(top.get("context"))) throw new Malformed();
		Object seq = top.get("seq");
		if (!(seq instanceof Long)) throw new Malformed();
		int issued = day(top.get("issued"));
		int expires = day(top.get("expires"));
		if (expires < issued || expires - issued > MAX_VALIDITY_DAYS) {
			throw new Malformed();
		}
		Object bridges = top.get("bridges");
		if (!(bridges instanceof Map)) throw new Malformed();
		Map<?, ?> groups = (Map<?, ?>) bridges;
		if (groups.size() > MAX_GROUPS) throw new Malformed();
		Set<String> seenLines = new HashSet<>();
		Set<String> seenFingerprints = new HashSet<>();
		List<String> obfs4Default = new ArrayList<>();
		List<String> obfs4NonDefault = new ArrayList<>();
		List<String> vanilla = new ArrayList<>();
		int total = 0;
		for (Map.Entry<?, ?> e : groups.entrySet()) {
			String name = (String) e.getKey();
			if (!GROUP.matcher(name).matches()) throw new Malformed();
			if (!(e.getValue() instanceof List)) throw new Malformed();
			List<?> raw = (List<?>) e.getValue();
			if (raw.size() > MAX_GROUP_LINES) throw new Malformed();
			total += raw.size();
			if (total > MAX_LINES) throw new Malformed();
			List<String> target = target(name, obfs4Default, obfs4NonDefault,
					vanilla);
			for (Object o : raw) {
				if (!(o instanceof String)) throw new Malformed();
				String line = (String) o;
				if (!seenLines.add(line)) throw new Malformed();
				if (!valid(name, line)) throw new Malformed();
				if (target == null) continue;
				if (!seenFingerprints.add(BridgeLines.fingerprint(line))) {
					throw new Malformed();
				}
				target.add(line);
			}
		}
		if (obfs4Default.isEmpty() && obfs4NonDefault.isEmpty()
				&& vanilla.isEmpty()) {
			throw new Malformed();
		}
		return new BridgeList((Long) seq, issued, expires, obfs4Default,
				obfs4NonDefault, vanilla);
	}

	@Nullable
	private static List<String> target(String group, List<String> obfs4Default,
			List<String> obfs4NonDefault, List<String> vanilla) {
		if (group.equals(OBFS4_DEFAULT)) return obfs4Default;
		if (group.equals(OBFS4_NON_DEFAULT)) return obfs4NonDefault;
		if (group.equals(VANILLA)) return vanilla;
		return null;
	}

	private static boolean valid(String group, String line) {
		if (group.equals(OBFS4_DEFAULT) || group.equals(OBFS4_NON_DEFAULT)) {
			return BridgeLines.isObfs4Line(line);
		}
		if (group.equals(VANILLA)) return BridgeLines.isVanillaLine(line);
		return BridgeLines.isWellFormed(line);
	}

	private static int day(@Nullable Object value) throws Malformed {
		if (!(value instanceof String)) throw new Malformed();
		int day = epochDay((String) value);
		if (day < 0) throw new Malformed();
		return day;
	}

	public static int epochDay(String date) {
		Matcher m = DATE.matcher(date);
		if (!m.matches()) return -1;
		int y = Integer.parseInt(m.group(1));
		int mo = Integer.parseInt(m.group(2));
		int d = Integer.parseInt(m.group(3));
		if (y < 2020 || y > 2999 || mo < 1 || mo > 12 || d < 1
				|| d > daysInMonth(y, mo)) {
			return -1;
		}
		int yy = mo <= 2 ? y - 1 : y;
		int era = yy / 400;
		int yoe = yy - era * 400;
		int doy = (153 * (mo > 2 ? mo - 3 : mo + 9) + 2) / 5 + d - 1;
		int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
		return era * 146097 + doe - 719468;
	}

	private static int daysInMonth(int year, int month) {
		if (month == 2) {
			boolean leap = (year % 4 == 0 && year % 100 != 0)
					|| year % 400 == 0;
			return leap ? 29 : 28;
		}
		if (month == 4 || month == 6 || month == 9 || month == 11) return 30;
		return 31;
	}

	private static final class Malformed extends Exception {
	}

	private static final class Parser {

		private static final int MAX_CONTAINER_DEPTH = 2;
		private static final int MAX_STRING_LENGTH = 1024;
		private static final int MAX_DIGITS = 15;

		private final byte[] in;
		private int pos = 0;

		private Parser(byte[] in) {
			this.in = in;
		}

		private Map<String, Object> document() throws Malformed {
			for (byte b : in) {
				if (b != '\n' && b != '\t' && (b < 0x20 || b > 0x7E)) {
					throw new Malformed();
				}
			}
			skipSpace();
			if (peek() != '{') throw new Malformed();
			Map<String, Object> top = object(0);
			skipSpace();
			if (pos != in.length) throw new Malformed();
			return top;
		}

		private Object value(int depth) throws Malformed {
			int c = peek();
			if (c == '{') return object(depth);
			if (c == '[') return array(depth);
			if (c == '"') return string();
			if (c >= '1' && c <= '9') return number();
			throw new Malformed();
		}

		private Map<String, Object> object(int depth) throws Malformed {
			if (depth > MAX_CONTAINER_DEPTH) throw new Malformed();
			pos++;
			Map<String, Object> out = new LinkedHashMap<>();
			skipSpace();
			if (peek() == '}') {
				pos++;
				return out;
			}
			while (true) {
				skipSpace();
				if (peek() != '"') throw new Malformed();
				String key = string();
				if (out.containsKey(key)) throw new Malformed();
				skipSpace();
				if (next() != ':') throw new Malformed();
				skipSpace();
				out.put(key, value(depth + 1));
				skipSpace();
				int c = next();
				if (c == '}') return out;
				if (c != ',') throw new Malformed();
			}
		}

		private List<Object> array(int depth) throws Malformed {
			if (depth > MAX_CONTAINER_DEPTH) throw new Malformed();
			pos++;
			List<Object> out = new ArrayList<>();
			skipSpace();
			if (peek() == ']') {
				pos++;
				return out;
			}
			while (true) {
				skipSpace();
				out.add(value(depth + 1));
				skipSpace();
				int c = next();
				if (c == ']') return out;
				if (c != ',') throw new Malformed();
			}
		}

		private String string() throws Malformed {
			pos++;
			int start = pos;
			while (true) {
				if (pos >= in.length) throw new Malformed();
				byte b = in[pos];
				if (b == '"') break;
				if (b == '\\' || b < 0x20) throw new Malformed();
				pos++;
				if (pos - start > MAX_STRING_LENGTH) throw new Malformed();
			}
			String s = new String(in, start, pos - start, ASCII);
			pos++;
			return s;
		}

		private Long number() throws Malformed {
			int start = pos;
			while (pos < in.length && in[pos] >= '0' && in[pos] <= '9') {
				pos++;
				if (pos - start > MAX_DIGITS) throw new Malformed();
			}
			return Long.parseLong(new String(in, start, pos - start, ASCII));
		}

		private int peek() {
			return pos < in.length ? in[pos] : -1;
		}

		private int next() {
			return pos < in.length ? in[pos++] : -1;
		}

		private void skipSpace() {
			while (pos < in.length
					&& (in[pos] == ' ' || in[pos] == '\t' || in[pos] == '\n')) {
				pos++;
			}
		}
	}
}
