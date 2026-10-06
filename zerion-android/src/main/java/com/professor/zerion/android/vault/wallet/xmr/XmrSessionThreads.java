package com.professor.zerion.android.vault.wallet.xmr;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
