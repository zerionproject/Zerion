package com.professor.zerion.android.vault.ui;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class SaveLedger<R> {

	static final int KEPT_RESULTS = 8;

	private long lastRequest = 0;
	private final Set<Long> running = new HashSet<>();
	private final Map<Long, R> finished = new LinkedHashMap<>();

	synchronized long newRequest() {
		return ++lastRequest;
	}

	synchronized void started(long request) {
		running.add(request);
	}

	synchronized void finished(long request, R result) {
		running.remove(request);
		finished.put(request, result);
		Iterator<Long> eldest = finished.keySet().iterator();
		while (finished.size() > KEPT_RESULTS) {
			eldest.next();
			eldest.remove();
		}
	}

	synchronized boolean isRunning(long request) {
		return running.contains(request);
	}

	synchronized boolean isAnyRunning() {
		return !running.isEmpty();
	}

	synchronized boolean isPending(long request) {
		return running.contains(request) || finished.containsKey(request);
	}

	@Nullable
	synchronized R take(long request) {
		return finished.remove(request);
	}
}
