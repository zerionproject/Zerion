package com.professor.zerion.android.vault.wallet.xmr;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Executor threads for the native wallet carry the requested stack: a
 * recursion runs several times deeper on a factory thread than on a thread
 * created with a small stack, and the threads carry the executor's name.
 */
public class XmrSessionThreadsTest {

	@Test
	public void factoryThreadsCarryTheRequestedStackAndName() throws Exception {
		Thread named = XmrSessionThreads.factory("XmrSession").newThread(() -> {
		});
		assertEquals("XmrSession-1", named.getName());

		int small = depthOn(new Thread(null, null, "small", 256L << 10));
		int large = depthOn(XmrSessionThreads.factory("XmrCrypto"));
		assertTrue("small " + small + " large " + large, large >= 4 * small);
	}

	private static int depthOn(java.util.concurrent.ThreadFactory factory)
			throws InterruptedException {
		AtomicInteger depth = new AtomicInteger();
		Thread t = factory.newThread(() -> depth.set(measure()));
		t.start();
		t.join(60_000);
		return depth.get();
	}

	private static int depthOn(Thread template) throws InterruptedException {
		AtomicInteger depth = new AtomicInteger();
		Thread t = new Thread(null, () -> depth.set(measure()),
				template.getName(), 256L << 10);
		t.start();
		t.join(60_000);
		return depth.get();
	}

	private static int measure() {
		try {
			return recurse(0);
		} catch (StackOverflowError e) {
			return Integer.MAX_VALUE;
		}
	}

	private static int recurse(int depth) {
		long[] frame = new long[16];
		frame[depth & 15] = depth;
		try {
			return recurse(depth + 1) + (int) (frame[0] & 0);
		} catch (StackOverflowError e) {
			return depth;
		}
	}
}
