package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZtpTorTransportInboundStressTest {

	private static final int INBOUND = 64;
	private static final byte VALID = 1;

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final List<Socket> sockets =
			Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger inHandler = new AtomicInteger();
	private final AtomicInteger maxInHandler = new AtomicInteger();
	private final AtomicInteger validHandled = new AtomicInteger();
	private final AtomicInteger rejected = new AtomicInteger();
	private volatile CountDownLatch release = new CountDownLatch(1);

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

	private ZtpConnectionHandler handler() {
		return new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) throws IOException {
				int now = inHandler.incrementAndGet();
				maxInHandler.accumulateAndGet(now, Math::max);
				try {
					byte[] tag = new byte[TAG_LENGTH];
					int off = 0;
					while (off < TAG_LENGTH) {
						int r = in.read(tag, off, TAG_LENGTH - off);
						if (r < 0) throw new IOException();
						off += r;
					}
					if (tag[0] != VALID) {
						rejected.incrementAndGet();
						throw new IOException();
					}
					validHandled.incrementAndGet();
					try {
						release.await(30, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				} finally {
					inHandler.decrementAndGet();
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
		exec.shutdownNow();
	}

	private ZtpTorTransport started(RecordingTor tor) throws IOException {
		ZtpTorTransport t = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler(), null, () -> {
		});
		t.startAccepting();
		return t;
	}

	private Socket open(int port) throws IOException {
		Socket s = new Socket("127.0.0.1", port);
		sockets.add(s);
		return s;
	}

	private static void send(Socket s, byte first) throws IOException {
		byte[] tag = new byte[TAG_LENGTH];
		tag[0] = first;
		s.getOutputStream().write(tag);
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

	private static int closedAmong(List<Socket> all, int expected)
			throws IOException {
		java.util.Set<Socket> open = new java.util.HashSet<>(all);
		int closed = 0;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (closed < expected && System.nanoTime() < deadline) {
			java.util.Iterator<Socket> it = open.iterator();
			while (it.hasNext()) {
				Socket s = it.next();
				s.setSoTimeout(50);
				try {
					if (s.getInputStream().read() == -1) {
						closed++;
						it.remove();
					}
				} catch (java.net.SocketTimeoutException e) {
				} catch (java.net.SocketException e) {
					closed++;
					it.remove();
				}
			}
		}
		return closed;
	}

	private void admit(int port) throws Exception {
		for (int attempt = 0; attempt < 50; attempt++) {
			int before = validHandled.get();
			Socket s = open(port);
			send(s, VALID);
			s.setSoTimeout(20);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline) {
				if (validHandled.get() > before) return;
				try {
					if (s.getInputStream().read() == -1) break;
				} catch (java.net.SocketTimeoutException e) {
				} catch (java.net.SocketException e) {
					break;
				}
			}
		}
		throw new AssertionError("a valid peer was never admitted");
	}

	private void awaitValid(int n) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (validHandled.get() < n && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(n, validHandled.get());
	}

	@Test(timeout = 120_000)
	public void aBurstBeyondCapacityNeverRunsMoreHandlersThanTheLimit()
			throws Exception {
		ZtpTorTransport t = started(new RecordingTor());
		int port = t.getLocalPort();
		List<Socket> burst = new ArrayList<>();
		for (int i = 0; i < 3 * INBOUND; i++) {
			Socket s = open(port);
			send(s, VALID);
			burst.add(s);
		}
		awaitValid(INBOUND);
		assertEquals(2 * INBOUND, closedAmong(burst, 2 * INBOUND));
		assertEquals(INBOUND, validHandled.get());
		assertTrue(maxInHandler.get() <= INBOUND);
		release.countDown();
		release = new CountDownLatch(1);
		for (Socket s : burst) s.close();
		validHandled.set(0);
		for (int i = 0; i < INBOUND; i++) admit(port);
		awaitValid(INBOUND);
		assertTrue(maxInHandler.get() <= INBOUND);
	}

	@Test(timeout = 120_000)
	public void aReconnectStormLeavesNoCapacityBehind() throws Exception {
		ZtpTorTransport t = started(new RecordingTor());
		int port = t.getLocalPort();
		for (int i = 0; i < 400; i++) {
			Socket s = new Socket("127.0.0.1", port);
			if (i % 2 == 0) send(s, (byte) 0);
			s.close();
		}
		for (int i = 0; i < INBOUND; i++) admit(port);
		awaitValid(INBOUND);
	}

	@Test(timeout = 120_000)
	public void validPeersGetThroughAFloodOfMalformedTags() throws Exception {
		ZtpTorTransport t = started(new RecordingTor());
		int port = t.getLocalPort();
		Random random = new Random(7);
		List<Thread> flooders = new ArrayList<>();
		for (int f = 0; f < 4; f++) {
			Thread th = new Thread(() -> {
				for (int i = 0; i < 150; i++) {
					try (Socket s = new Socket("127.0.0.1", port)) {
						byte[] junk = new byte[TAG_LENGTH];
						random.nextBytes(junk);
						junk[0] = 0;
						s.getOutputStream().write(junk);
						s.getOutputStream().flush();
						s.setSoTimeout(5_000);
						while (s.getInputStream().read() >= 0) {
						}
					} catch (IOException ignored) {
					}
				}
			});
			th.start();
			flooders.add(th);
		}
		for (int i = 0; i < 10; i++) admit(port);
		awaitValid(10);
		for (Thread th : flooders) th.join(60_000);
		assertTrue(rejected.get() > 0);
		assertTrue(maxInHandler.get() <= INBOUND);
	}

	@Test(timeout = 120_000)
	public void aRotationListenerSharesTheOpenCapacity() throws Exception {
		RecordingTor tor = new RecordingTor();
		ZtpTorTransport t = started(tor);
		HiddenServiceProperties next = t.publishDedicatedService("nextkey");
		int main = t.getLocalPort();
		int rotation = tor.ports.get(next.onion);
		List<Socket> extra = new ArrayList<>();
		for (int i = 0; i < INBOUND; i++) admit(i % 2 == 0 ? main : rotation);
		awaitValid(INBOUND);
		for (int i = 0; i < 8; i++) {
			Socket s = open(i % 2 == 0 ? main : rotation);
			send(s, VALID);
			extra.add(s);
		}
		for (Socket s : extra) assertTrue(closedByPeer(s));
		assertEquals(INBOUND, validHandled.get());
	}

	@Test(timeout = 120_000)
	public void capacityHeldAcrossAListenerRestartIsReturned()
			throws Exception {
		RecordingTor tor = new RecordingTor();
		ZtpTorTransport t = started(tor);
		int port = t.getLocalPort();
		for (int i = 0; i < INBOUND; i++) admit(port);
		awaitValid(INBOUND);
		t.stop();
		release.countDown();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (inHandler.get() > 0 && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(0, inHandler.get());
		release = new CountDownLatch(1);
		validHandled.set(0);
		t.startAccepting();
		int again = t.getLocalPort();
		for (int i = 0; i < INBOUND; i++) admit(again);
		awaitValid(INBOUND);
	}
}
