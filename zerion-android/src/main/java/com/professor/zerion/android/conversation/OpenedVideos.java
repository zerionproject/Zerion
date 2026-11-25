package com.professor.zerion.android.conversation;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class OpenedVideos {

	static final int MAX_REMEMBERED = 512;

	private static final Set<String> OPENED = new LinkedHashSet<>();

	private OpenedVideos() {
	}

	public static synchronized boolean add(String key) {
		if (!OPENED.add(key)) return false;
		Iterator<String> oldest = OPENED.iterator();
		while (OPENED.size() > MAX_REMEMBERED && oldest.hasNext()) {
			oldest.next();
			oldest.remove();
		}
		return true;
	}

	public static synchronized boolean contains(String key) {
		return OPENED.contains(key);
	}

	public static synchronized void remove(String key) {
		OPENED.remove(key);
	}

	public static synchronized void removeIf(Predicate<String> gone) {
		OPENED.removeIf(gone);
	}

	public static synchronized void clear() {
		OPENED.clear();
	}

	static synchronized int size() {
		return OPENED.size();
	}
}
