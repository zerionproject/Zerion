package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executor behind a call. A call needs a handful of long-lived loops
 * (capture, playback, the network reader, the keepalive) and a few short
 * tasks (signalling, teardown), so the pool is bounded well above that and
 * queues anything beyond it instead of creating a thread per task; a peer
 * or a fault that floods the service with work cannot make it create threads
 * without limit. Work that would exceed the queue is dropped rather than run
 * on the caller.
 */
@NotNullByDefault
final class VoiceCallExecutors {

	static final int CORE_THREADS = 2;
	static final int MAX_THREADS = 8;
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
