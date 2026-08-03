package org.zerionproject.core.io;

import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.SettableClock;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimeoutInputStreamTest extends BrambleTestCase {

	private static final long TIMEOUT_MS = MINUTES.toMillis(1);

	private final long now = System.currentTimeMillis();

	private AtomicLong time;
	private UnresponsiveInputStream in;
	private AtomicBoolean listenerCalled;
	private TimeoutInputStream stream;
	private CountDownLatch readReturned;

	@Before
	public void setUp() {
		time = new AtomicLong(now);
		in = new UnresponsiveInputStream();
		listenerCalled = new AtomicBoolean(false);
		stream = new TimeoutInputStream(new SettableClock(time), in,
				TIMEOUT_MS, stream -> listenerCalled.set(true));
		readReturned = new CountDownLatch(1);
	}

	@Test
	public void testTimeoutIsReportedIfReadDoesNotReturn() throws Exception {
		startReading();
		try {

			assertFalse(stream.hasTimedOut());

			time.set(now + TIMEOUT_MS);

			assertFalse(stream.hasTimedOut());

			time.set(now + TIMEOUT_MS + 1);

			assertTrue(stream.hasTimedOut());

			assertFalse(listenerCalled.get());

			stream.close();

			assertTrue(listenerCalled.get());
		} finally {

			in.readFinished.countDown();
		}
	}

	@Test
	public void testTimeoutIsNotReportedIfReadReturns() throws Exception {
		startReading();
		try {

			assertFalse(stream.hasTimedOut());

			time.set(now + TIMEOUT_MS);

			assertFalse(stream.hasTimedOut());

			in.readFinished.countDown();
			readReturned.await(10, SECONDS);

			time.set(now + TIMEOUT_MS + 1);

			assertFalse(stream.hasTimedOut());

			assertFalse(listenerCalled.get());

			stream.close();

			assertTrue(listenerCalled.get());
		} finally {

			in.readFinished.countDown();
		}
	}

	private void startReading() throws Exception {

		new Thread(() -> {
			try {
				assertEquals(123, stream.read());
				readReturned.countDown();
			} catch (IOException e) {
				fail();
			}
		}).start();

		assertTrue(in.readStarted.await(10, SECONDS));
	}

	private class UnresponsiveInputStream extends InputStream {

		private final CountDownLatch readStarted = new CountDownLatch(1);
		private final CountDownLatch readFinished = new CountDownLatch(1);

		@Override
		public int read() throws IOException {
			readStarted.countDown();
			try {
				readFinished.await();
				return 123;
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
		}
	}
}
