package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The authorized onion service forwards to its own local listener, and the
 * handler is told which listener a connection came through, so the inbound
 * policy can refuse a committed contact over the open service.
 */
public class ZtpTorTransportAuthorizedListenerTest {

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final List<Boolean> seen =
			Collections.synchronizedList(new ArrayList<>());
	private final CountDownLatch two = new CountDownLatch(2);
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
				seen.add(viaAuthorizedService);
				two.countDown();
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
		transport.startAccepting(0);
		transport.startAcceptingAuthorized();
	}

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	@Test(timeout = 30_000)
	public void testConnectionsAreTaggedByTheListenerTheyArrivedOn()
			throws Exception {
		assertNotEquals(transport.getLocalPort(),
				transport.getAuthorizedLocalPort());
		try (Socket open = new Socket("127.0.0.1", transport.getLocalPort());
				Socket authorized = new Socket("127.0.0.1",
						transport.getAuthorizedLocalPort())) {
			open.getOutputStream().write(new byte[TAG_LENGTH]);
			open.getOutputStream().flush();
			authorized.getOutputStream().write(new byte[TAG_LENGTH]);
			authorized.getOutputStream().flush();
			assertTrue(two.await(20, TimeUnit.SECONDS));
		}
		assertEquals(2, seen.size());
		assertTrue(seen.contains(true));
		assertTrue(seen.contains(false));
	}
}
