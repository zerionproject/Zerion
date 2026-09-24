package com.professor.zerion.android.vault.wallet.xmr;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Threads for the wallet's session and crypto executors. The native wallet
 * runs its refresh, transaction construction and key derivation on the
 * calling thread, and those paths use far more stack than a default thread
 * carries, so every executor thread is created with a generous stack instead
 * of the platform default. The size is a request the runtime honours on the
 * platforms the app runs on.
 */
final class XmrSessionThreads {

	static final long STACK_BYTES = 8L << 20;

	private XmrSessionThreads() {
	}

	static ThreadFactory factory(String name) {
		AtomicInteger counter = new AtomicInteger();
		return r -> new Thread(null, r, name + "-" + counter.incrementAndGet(),
				STACK_BYTES);
	}

	static ExecutorService singleThread(String name) {
		return Executors.newSingleThreadExecutor(factory(name));
	}
}
