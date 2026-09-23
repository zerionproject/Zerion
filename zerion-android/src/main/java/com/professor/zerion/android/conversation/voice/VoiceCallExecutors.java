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
 * (capture, playback, the network reader, the keepalive, video) that block
 * at the same time, and a few short tasks (signalling, teardown) that must
 * run while they block, so every task gets a thread up to the maximum and
 * only work beyond that is queued; a pool that queued behind a couple of
 * core threads left the loops waiting on each other, which silenced one
 * direction and held back the hang-up. Idle threads still time out. A peer
 * or a fault that floods the service with work cannot make it create
 * threads without limit, and work that would exceed the queue is dropped
 * rather than run on the caller.
 */
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
