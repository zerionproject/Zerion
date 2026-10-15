package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@NotNullByDefault
public final class RunningCopies {

	interface Abort {
		void abort();
	}

	private static final Set<Abort> RUNNING =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	private RunningCopies() {
	}

	static void start(Abort copy) {
		RUNNING.add(copy);
	}

	static void end(Abort copy) {
		RUNNING.remove(copy);
	}

	static int running() {
		return RUNNING.size();
	}

	public static void abortAll() {
		for (Abort copy : RUNNING.toArray(new Abort[0])) {
			try {
				copy.abort();
			} catch (RuntimeException ignored) {
			}
		}
	}
}
