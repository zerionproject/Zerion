package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@NotNullByDefault
final class VoiceCallExecutors {

	static final int MAX_THREADS = 8;
	static final int CORE_THREADS = MAX_THREADS;
	static final int QUEUE_CAPACITY = 64;
	static final long IDLE_SECONDS = 30;

	private VoiceCallExecutors() {
	}

	static ExecutorService bounded() {
		return bounded(threadFactory("VoiceCall"));
	}

	static ThreadPoolExecutor bounded(ThreadFactory factory) {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(CORE_THREADS,
				MAX_THREADS, IDLE_SECONDS, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(QUEUE_CAPACITY), factory,
				new ThreadPoolExecutor.DiscardPolicy());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	private static ThreadFactory threadFactory(String name) {
		AtomicInteger counter = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, name + "-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}
}
