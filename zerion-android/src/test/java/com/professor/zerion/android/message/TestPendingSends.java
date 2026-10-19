package com.professor.zerion.android.message;

import java.util.function.LongSupplier;

public final class TestPendingSends {

	private TestPendingSends() {
	}

	public static void reset() {
		PendingSends.get().reset();
	}

	public static void setClock(LongSupplier clock) {
		PendingSends.get().clock = clock;
	}
}
