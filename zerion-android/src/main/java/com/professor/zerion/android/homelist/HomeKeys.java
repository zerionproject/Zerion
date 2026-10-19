package com.professor.zerion.android.homelist;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
@NotNullByDefault
public final class HomeKeys {

	private final Map<String, Integer> seen = new HashMap<>();

	public String next(String prefix, String value) {
		String base = prefix + ":" + value;
		Integer count = seen.get(base);
		int n = count == null ? 1 : count + 1;
		seen.put(base, n);
		return prefix + ":" + n + ":" + value;
	}
}
