package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@NotNullByDefault
public final class StrictJson {

	public static final Object NULL = new Object();

	static final int MAX_DEPTH = 32;
	static final int MAX_NUMBER_LENGTH = 64;

	private final String text;
	private int pos;

	private StrictJson(String text) {
		this.text = text;
	}

	public static Object parse(String text) throws IOException {
		StrictJson p = new StrictJson(text);
		p.skipWhitespace();
		Object value = p.value(0);
		p.skipWhitespace();
		if (p.pos != text.length()) {
			throw malformed();
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> object(Object value) throws IOException {
		if (!(value instanceof Map)) {
			throw malformed();
		}
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> array(Object value) throws IOException {
		if (!(value instanceof List)) {
			throw malformed();
		}
		return (List<Object>) value;
	}

	public static String string(Object value) throws IOException {
		if (!(value instanceof String)) {
			throw malformed();
		}
		return (String) value;
	}

	public static BigDecimal number(Object value) throws IOException {
		if (!(value instanceof BigDecimal)) {
			throw malformed();
		}
		return (BigDecimal) value;
	}

	public static long integer(Object value, long min, long max)
			throws IOException {
		BigDecimal n = number(value);
		if (n.compareTo(BigDecimal.valueOf(min)) < 0
				|| n.compareTo(BigDecimal.valueOf(max)) > 0) {
			throw malformed();
		}
		try {
			return n.longValueExact();
		} catch (ArithmeticException notAnInteger) {
			throw malformed();
		}
	}

	private static IOException malformed() {
		return new IOException("malformed JSON");
	}

	private Object value(int depth) throws IOException {
		if (pos >= text.length()) {
			throw malformed();
		}
		char c = text.charAt(pos);
		if (c == '{') {
			return object(depth + 1);
		}
		if (c == '[') {
			return array(depth + 1);
		}
		if (c == '"') {
			return string();
		}
		if (c == '-' || (c >= '0' && c <= '9')) {
			return number();
		}
		if (text.startsWith("true", pos)) {
			pos += 4;
			return Boolean.TRUE;
		}
		if (text.startsWith("false", pos)) {
			pos += 5;
			return Boolean.FALSE;
		}
		if (text.startsWith("null", pos)) {
			pos += 4;
			return NULL;
		}
		throw malformed();
	}

	private Map<String, Object> object(int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw malformed();
		}
		pos++;
		Map<String, Object> map = new LinkedHashMap<>();
		skipWhitespace();
		if (peek() == '}') {
			pos++;
			return Collections.unmodifiableMap(map);
		}
		while (true) {
			skipWhitespace();
			if (peek() != '"') {
				throw malformed();
			}
			String key = string();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			Object v = value(depth);
			if (map.put(key, v) != null) {
				throw malformed();
			}
			skipWhitespace();
			char c = next();
			if (c == '}') {
				return Collections.unmodifiableMap(map);
			}
			if (c != ',') {
				throw malformed();
			}
		}
	}

	private List<Object> array(int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw malformed();
		}
		pos++;
		List<Object> list = new ArrayList<>();
		skipWhitespace();
		if (peek() == ']') {
			pos++;
			return Collections.unmodifiableList(list);
		}
		while (true) {
			skipWhitespace();
			list.add(value(depth));
			skipWhitespace();
			char c = next();
			if (c == ']') {
				return Collections.unmodifiableList(list);
			}
			if (c != ',') {
				throw malformed();
			}
		}
	}

	private String string() throws IOException {
		expect('"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') {
				return sb.toString();
			}
			if (c < 0x20) {
				throw malformed();
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			char e = next();
			switch (e) {
				case '"':
				case '\\':
				case '/':
					sb.append(e);
					break;
				case 'b':
					sb.append('\b');
					break;
				case 'f':
					sb.append('\f');
					break;
				case 'n':
					sb.append('\n');
					break;
				case 'r':
					sb.append('\r');
					break;
				case 't':
					sb.append('\t');
					break;
				case 'u':
					sb.append((char) hex4());
					break;
				default:
					throw malformed();
			}
		}
	}

	private int hex4() throws IOException {
		int v = 0;
		for (int i = 0; i < 4; i++) {
			char c = next();
			int d = c > 0x7f ? -1 : Character.digit(c, 16);
			if (d < 0) {
				throw malformed();
			}
			v = (v << 4) | d;
		}
		return v;
	}

	private BigDecimal number() throws IOException {
		int start = pos;
		if (peek() == '-') {
			pos++;
		}
		if (peek() == '0') {
			pos++;
		} else if (isDigit(peek())) {
			digits();
		} else {
			throw malformed();
		}
		if (peek() == '.') {
			pos++;
			if (!isDigit(peek())) {
				throw malformed();
			}
			digits();
		}
		if (peek() == 'e' || peek() == 'E') {
			pos++;
			if (peek() == '+' || peek() == '-') {
				pos++;
			}
			if (!isDigit(peek())) {
				throw malformed();
			}
			digits();
		}
		if (pos - start > MAX_NUMBER_LENGTH) {
			throw malformed();
		}
		try {
			return new BigDecimal(text.substring(start, pos));
		} catch (NumberFormatException | ArithmeticException e) {
			throw malformed();
		}
	}

	private void digits() {
		while (isDigit(peek())) {
			pos++;
		}
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private char peek() {
		return pos < text.length() ? text.charAt(pos) : '\0';
	}

	private char next() throws IOException {
		if (pos >= text.length()) {
			throw malformed();
		}
		return text.charAt(pos++);
	}

	private void expect(char c) throws IOException {
		if (next() != c) {
			throw malformed();
		}
	}

	private void skipWhitespace() {
		while (pos < text.length()) {
			char c = text.charAt(pos);
			if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
				return;
			}
			pos++;
		}
	}
}
