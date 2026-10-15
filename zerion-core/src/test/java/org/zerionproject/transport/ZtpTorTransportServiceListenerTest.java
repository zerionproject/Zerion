package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZtpTorTransportServiceListenerTest {

	private final ExecutorService real = Executors.newCachedThreadPool();
	private final List<Socket> sockets =
			Collections.synchronizedList(new ArrayList<>());
	private final List<String> arrivedOn =
			Collections.synchronizedList(new ArrayList<>());
	private final CountDownLatch arrived = new CountDownLatch(1);
	private final CountDownLatch release = new CountDownLatch(1);

	private static final class RecordingTor
			extends ZtpTorTransportTest.StubTor {

		final Map<String, Integer> ports = new ConcurrentHashMap<>();

		@Override
		@Nullable
		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			HiddenServiceProperties hs = super.publishHiddenService(localPort,
					remotePort, privateKey);
			String onion = "onion-of-" + hs.privKey;
			ports.put(onion, localPort);
			try {
				java.lang.reflect.Constructor<HiddenServiceProperties> c =
						HiddenServiceProperties.class.getDeclaredConstructor(
								String.class, String.class);
				c.setAccessible(true);
				return c.newInstance(onion, hs.privKey);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}
	}

	private static final class GatedExecutor implements Executor {

		final ExecutorService real;
		final List<Runnable> held =
				Collections.synchronizedList(new ArrayList<>());
		final AtomicBoolean acceptLoopStarted = new AtomicBoolean(false);
		volatile boolean hold = false;
		volatile boolean reject = false;

		GatedExecutor(ExecutorService real) {
			this.real = real;
		}

		@Override
		public void execute(Runnable r) {
			if (acceptLoopStarted.compareAndSet(false, true)) {
				real.execute(r);
				return;
			}
			if (reject) throw new RejectedExecutionException();
			if (hold) {
				held.add(r);
				return;
			}
			real.execute(r);
		}
	}

	private ZtpConnectionHandler recordingHandler() {
		return new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) throws IOException {
				handleIncoming(transportId, in, out, false, null);
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out, boolean viaAuthorizedService,
					@Nullable String localOnion) throws IOException {
				byte[] tag = new byte[TAG_LENGTH];
				int off = 0;
				while (off < TAG_LENGTH) {
					int r = in.read(tag, off, TAG_LENGTH - off);
					if (r < 0) throw new IOException();
					off += r;
				}
				arrivedOn.add(String.valueOf(localOnion));
				arrived.countDown();
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
	}

	@After
	public void tearDown() {
		release.countDown();
		for (Socket s : new ArrayList<>(sockets)) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
		real.shutdownNow();
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

	private static boolean closedByPeer(Socket s) throws IOException {
		s.setSoTimeout(20_000);
		try {
			return s.getInputStream().read() == -1;
		} catch (java.net.SocketException e) {
			return true;
		}
	}

	@Test(timeout = 30_000)
	public void aConnectionToADedicatedServiceIsReportedWithItsOnion()
			throws Exception {
		RecordingTor tor = new RecordingTor();
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), real,
				recordingHandler(), null, () -> {
		});
		HiddenServiceProperties hs =
				transport.publishDedicatedService("nextkey");
		assertEquals("onion-of-nextkey", hs.onion);
		sendTag(open(tor.ports.get(hs.onion)));
		assertTrue(arrived.await(20, TimeUnit.SECONDS));
		assertEquals(Collections.singletonList("onion-of-nextkey"),
				arrivedOn);
	}

	@Test(timeout = 30_000)
	public void removingADedicatedServiceClosesItsListener() throws Exception {
		RecordingTor tor = new RecordingTor();
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), real,
				recordingHandler(), null, () -> {
		});
		HiddenServiceProperties hs =
				transport.publishDedicatedService("nextkey");
		int port = tor.ports.get(hs.onion);
		transport.removeHiddenService(hs.onion);
		assertFalse(transport.publishedOnions().contains(hs.onion));
		try {
			open(port);
			fail("the listener of a removed service still accepts");
		} catch (ConnectException expected) {
		}
	}

	@Test(timeout = 60_000)
	public void capacityIsReservedBeforeTheHandlerIsDispatched()
			throws Exception {
		GatedExecutor gate = new GatedExecutor(real);
		gate.hold = true;
		ZtpTorTransport transport = new ZtpTorTransport(
				new ZtpTorTransportTest.StubTor(), SocketFactory.getDefault(),
				SocketFactory.getDefault(), gate, recordingHandler(), null,
				() -> {
		});
		transport.startAccepting();
		int slots = ZtpTorTransport.MAX_PRE_TAG_CONNECTIONS;
		List<Socket> extra = new ArrayList<>();
		for (int i = 0; i < slots; i++) open(transport.getLocalPort());
		for (int i = 0; i < 4; i++) extra.add(open(transport.getLocalPort()));
		for (Socket s : extra) {
			assertTrue("a socket beyond the reserved capacity was kept",
					closedByPeer(s));
		}
		assertEquals(slots, gate.held.size());
	}

	@Test(timeout = 60_000)
	public void aRejectedDispatchFreesItsCapacity() throws Exception {
		GatedExecutor gate = new GatedExecutor(real);
		gate.reject = true;
		ZtpTorTransport transport = new ZtpTorTransport(
				new ZtpTorTransportTest.StubTor(), SocketFactory.getDefault(),
				SocketFactory.getDefault(), gate, recordingHandler(), null,
				() -> {
		});
		transport.startAccepting();
		int attempts = ZtpTorTransport.MAX_PRE_TAG_CONNECTIONS + 4;
		for (int i = 0; i < attempts; i++) {
			assertTrue("a rejected socket was left open",
					closedByPeer(open(transport.getLocalPort())));
		}
		gate.reject = false;
		sendTag(open(transport.getLocalPort()));
		assertTrue("the accept loop no longer hands sockets to the handler",
				arrived.await(20, TimeUnit.SECONDS));
	}
}
