package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZtpTorTransportInboundLimitTest {

	private static final int PRE_TAG_SLOTS = ZtpTorTransport.MAX_PRE_TAG_CONNECTIONS;
	private static final int INBOUND_SLOTS = 64;

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final CountDownLatch release = new CountDownLatch(1);
	private final AtomicInteger started = new AtomicInteger();
	private final AtomicInteger entered = new AtomicInteger();
	private final List<Socket> sockets = new ArrayList<>();
	private ZtpTorTransport transport;

	@Before
	public void setUp() throws Exception {
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) throws IOException {
				started.incrementAndGet();
				byte[] tag = new byte[TAG_LENGTH];
				int off = 0;
				while (off < TAG_LENGTH) {
					int r = in.read(tag, off, TAG_LENGTH - off);
					if (r < 0) throw new IOException();
					off += r;
				}
				entered.incrementAndGet();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			@Override
			public void handlePaired(TransportId transportId, int contactId,
					boolean incoming, InputStream in, OutputStream out) {
				throw new UnsupportedOperationException();
			}
		};
		transport = new ZtpTorTransport(new ZtpTorTransportTest.StubTor(),
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler, null, () -> {
		});
		transport.startAccepting();
	}

	@After
	public void tearDown() {
		release.countDown();
		for (Socket s : sockets) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
		exec.shutdownNow();
	}

	@Test(timeout = 30_000)
	public void aDrippingPeerLosesItsSlotAtTheDeadline() throws Exception {
		java.util.concurrent.atomic.AtomicLong now =
				new java.util.concurrent.atomic.AtomicLong(1_000_000L);
		transport.clock = now::get;
		Socket dripper = open();
		dripper.getOutputStream().write(1);
		dripper.getOutputStream().flush();
		waitForStarted(1);
		now.addAndGet(ZtpTorTransport.TAG_READ_TIMEOUT_MS + 1);
		dripper.getOutputStream().write(2);
		dripper.getOutputStream().flush();
		assertClosedByTheTransport(dripper);
		assertEquals("no tag ever reached the handler", 0, entered.get());
	}

	@Test(timeout = 60_000)
	public void preTagSlotsAreBoundedAndFreedWhenTheTagArrives()
			throws Exception {
		for (int i = 0; i < PRE_TAG_SLOTS; i++) {
			open();
			waitForStarted(i + 1);
		}
		assertClosedByTheTransport(open());
		sendTag(sockets.get(PRE_TAG_SLOTS - 1));
		waitForEntered(1);
		assertKeptOpen(open());
	}

	@Test(timeout = 60_000)
	public void liveConnectionsAreBoundedAndTheBoundIsEnforcedBeforeTheHandler()
			throws Exception {
		for (int i = 0; i < INBOUND_SLOTS; i++) sendTag(open());
		waitForEntered(INBOUND_SLOTS);
		Socket beyond = open();
		sendTag(beyond);
		assertClosedByTheTransport(beyond);
		assertEquals(INBOUND_SLOTS, entered.get());
	}

	private Socket open() throws IOException {
		Socket s = new Socket("127.0.0.1", transport.getLocalPort());
		sockets.add(s);
		return s;
	}

	private static void sendTag(Socket s) throws IOException {
		s.getOutputStream().write(new byte[TAG_LENGTH]);
		s.getOutputStream().flush();
	}

	private void waitForStarted(int n) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 15_000;
		while (started.get() < n && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(n, started.get());
	}

	private void waitForEntered(int n) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 15_000;
		while (entered.get() < n && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(n, entered.get());
	}

	private static void assertClosedByTheTransport(Socket s) throws IOException {
		s.setSoTimeout(ZtpTorTransport.TAG_READ_TIMEOUT_MS - 500);
		try {
			int r = s.getInputStream().read();
			assertEquals(-1, r);
		} catch (SocketTimeoutException e) {
			fail("the connection was kept open");
		} catch (IOException expected) {
		}
	}

	private static void assertKeptOpen(Socket s) throws IOException {
		s.setSoTimeout(1_500);
		try {
			int r = s.getInputStream().read();
			fail("the connection was closed: " + r);
		} catch (SocketTimeoutException expected) {
		}
		assertTrue(s.isConnected());
	}
}
