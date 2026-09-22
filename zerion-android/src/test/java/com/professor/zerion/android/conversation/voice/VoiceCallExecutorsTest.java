package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The call executor never creates more than its maximum number of threads
 * however much work is submitted, queues a bounded amount beyond that and
 * drops the rest, and every thread it creates is a daemon so a leaked call
 * cannot keep the process alive.
 */
public class VoiceCallExecutorsTest {

	@Test
	public void threadsAreBoundedAndOverflowIsDroppedNotRunInline()
			throws Exception {
		AtomicInteger created = new AtomicInteger();
		ThreadPoolExecutor pool = VoiceCallExecutors.bounded(r -> {
			created.incrementAndGet();
			Thread t = new Thread(r, "test-call");
			t.setDaemon(true);
			return t;
		});
		try {
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger started = new AtomicInteger();
			AtomicInteger ran = new AtomicInteger();
			int submitted = VoiceCallExecutors.MAX_THREADS
					+ VoiceCallExecutors.QUEUE_CAPACITY + 50;
			Thread caller = Thread.currentThread();
			AtomicInteger inline = new AtomicInteger();
			for (int i = 0; i < submitted; i++) {
				pool.execute(() -> {
					if (Thread.currentThread() == caller) inline.incrementAndGet();
					started.incrementAndGet();
					try {
						release.await(30, TimeUnit.SECONDS);
					} catch (InterruptedException ignored) {
					}
					ran.incrementAndGet();
				});
			}
			long deadline = System.currentTimeMillis() + 10_000;
			while (started.get() < VoiceCallExecutors.MAX_THREADS
					&& System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}
			assertEquals(VoiceCallExecutors.MAX_THREADS, created.get());
			assertEquals(VoiceCallExecutors.MAX_THREADS,
					pool.getPoolSize());
			assertEquals(VoiceCallExecutors.QUEUE_CAPACITY,
					pool.getQueue().size());
			assertEquals(0, inline.get());
			release.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
			assertEquals(VoiceCallExecutors.MAX_THREADS
					+ VoiceCallExecutors.QUEUE_CAPACITY, ran.get());
			assertEquals(VoiceCallExecutors.MAX_THREADS, created.get());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void theDefaultPoolUsesDaemonThreads() throws Exception {
		ThreadPoolExecutor pool =
				(ThreadPoolExecutor) VoiceCallExecutors.bounded();
		try {
			boolean[] daemon = new boolean[1];
			CountDownLatch done = new CountDownLatch(1);
			pool.execute(() -> {
				daemon[0] = Thread.currentThread().isDaemon();
				done.countDown();
			});
			assertTrue(done.await(10, TimeUnit.SECONDS));
			assertTrue(daemon[0]);
			assertEquals(VoiceCallExecutors.MAX_THREADS,
					pool.getMaximumPoolSize());
		} finally {
			pool.shutdownNow();
		}
	}
}
