package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
class Sam3Reply {

	private final String type;
	private final Map<String, String> params;

	private Sam3Reply(String type, Map<String, String> params) {
		this.type = type;
		this.params = params;
	}

	String getType() {
		return type;
	}

	@Nullable
	String get(String key) {
		return params.get(key);
	}

	String getResult() {
		String r = params.get("RESULT");
		return r == null ? "I2P_ERROR" : r;
	}

	boolean isOk() {
		return "OK".equals(params.get("RESULT"));
	}

	static Sam3Reply parse(String line) {
		java.util.List<String> tokens = tokenize(line);
		StringBuilder type = new StringBuilder();
		Map<String, String> params = new LinkedHashMap<>();
		for (String token : tokens) {
			int eq = token.indexOf('=');
			if (eq > 0 && type.length() > 0) {
				String key = token.substring(0, eq);
				String value = unquote(token.substring(eq + 1));
				params.put(key, value);
			} else {
				if (type.length() > 0) type.append(' ');
				type.append(token);
			}
		}
		return new Sam3Reply(type.toString(), params);
	}

	private static java.util.List<String> tokenize(String line) {
		java.util.List<String> out = new java.util.ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
				cur.append(c);
			} else if (c == ' ' && !inQuotes) {
				if (cur.length() > 0) {
					out.add(cur.toString());
					cur.setLength(0);
				}
			} else {
				cur.append(c);
			}
		}
		if (cur.length() > 0) out.add(cur.toString());
		return out;
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && value.charAt(0) == '"'
				&& value.charAt(value.length() - 1) == '"') {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}
}
