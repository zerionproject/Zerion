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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZtpTorTransportListenerBudgetTest {

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final CountDownLatch release = new CountDownLatch(1);
	private final List<Boolean> entered =
			Collections.synchronizedList(new ArrayList<>());
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
				handleIncoming(transportId, in, out, false);
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out, boolean viaAuthorizedService)
					throws IOException {
				byte[] tag = new byte[TAG_LENGTH];
				int off = 0;
				while (off < TAG_LENGTH) {
					int r = in.read(tag, off, TAG_LENGTH - off);
					if (r < 0) throw new IOException();
					off += r;
				}
				entered.add(viaAuthorizedService);
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
		transport.startAcceptingAuthorized();
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
	public void silentConnectionsToTheOpenAddressDoNotStarveTheAuthorizedOne()
			throws Exception {
		int open = transport.getLocalPort();
		for (int i = 0; i < ZtpTorTransport.MAX_PRE_TAG_CONNECTIONS; i++) {
			open(open);
		}
		assertBoundReachedEventually(open);
		Socket contact = open(transport.getAuthorizedLocalPort());
		sendTag(contact);
		assertKeptOpen(contact);
		waitForEntered(true);
	}

	@Test(timeout = 30_000)
	public void theAuthorizedListenerIsBoundedWithoutTakingTheOpenSlots()
			throws Exception {
		int authorized = transport.getAuthorizedLocalPort();
		for (int i = 0; i < ZtpTorTransport.MAX_AUTHORIZED_PRE_TAG_CONNECTIONS;
				i++) {
			open(authorized);
		}
		assertBoundReachedEventually(authorized);
		Socket viaOpen = open(transport.getLocalPort());
		sendTag(viaOpen);
		assertKeptOpen(viaOpen);
		waitForEntered(false);
	}

	private Socket open(int port) throws IOException {
		Socket s = new Socket("127.0.0.1", port);
		sockets.add(s);
		return s;
	}

	private static void sendTag(Socket s) throws IOException {
		s.getOutputStream().write(new byte[TAG_LENGTH]);
		s.getOutputStream().flush();
	}

	private void assertBoundReachedEventually(int port) throws Exception {
		long deadline = System.currentTimeMillis() + 10_000;
		while (true) {
			Socket beyond = open(port);
			beyond.setSoTimeout(500);
			boolean closed;
			try {
				closed = beyond.getInputStream().read() < 0;
			} catch (SocketTimeoutException e) {
				closed = false;
			} catch (IOException e) {
				closed = true;
			}
			if (closed) return;
			if (System.currentTimeMillis() > deadline) {
				fail("the pre-tag bound never refused a further connection");
			}
		}
	}

	private void waitForEntered(boolean viaAuthorizedService)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + 15_000;
		while (!entered.contains(viaAuthorizedService)
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertTrue("the handler never saw the connection",
				entered.contains(viaAuthorizedService));
		assertEquals(1, entered.size());
	}

	private static void assertKeptOpen(Socket s) throws IOException {
		s.setSoTimeout(1_500);
		try {
			int r = s.getInputStream().read();
			fail("the connection was closed: " + r);
		} catch (SocketTimeoutException expected) {
		} catch (IOException e) {
			fail("the connection was reset: " + e);
		}
	}
}
