package org.zerionproject.transport;

import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.LocationUtils;
import org.briarproject.onionwrapper.TorWrapper;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.SettingsManager;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises the testable part of the Tor transport - the accept loop and dial
 * dispatch - with real loopback sockets and no Tor daemon. The Tor process and
 * onion publish/dial are validated on-device.
 */
public class ZtpTorTransportTest {

	/**
	 * No-op Tor: these tests drive accept/dial directly, never start Tor.
	 * It reports itself connected, since the transport dials only then.
	 */
	private static class StubTor implements TorWrapper {
		public void start() {
		}

		public void stop() {
		}

		public void setObserver(@Nullable Observer observer) {
		}

		public TorState getTorState() {
			return TorState.CONNECTED;
		}

		public boolean isTorRunning() {
			return false;
		}

		@Nullable
		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			try {
				java.lang.reflect.Constructor<HiddenServiceProperties> c =
						HiddenServiceProperties.class.getDeclaredConstructor(
								String.class, String.class);
				c.setAccessible(true);
				return c.newInstance("onion",
						privateKey == null ? "generated" : privateKey);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}

		public void removeHiddenService(String onion) {
		}

		public void enableNetwork(boolean enable) {
		}

		public void enableBridges(List<String> bridges) {
		}

		public void disableBridges() {
		}

		public void enableConnectionPadding(boolean enable)
				throws IOException {
		}

		public void enableIpv6(boolean ipv6Only) {
		}

		public File getLyrebirdExecutableFile() {
			return new File(".");
		}
	}

	@Test(timeout = 15_000)
	public void acceptedConnectionsReachTheHandler() throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		CountDownLatch incoming = new CountDownLatch(1);
		AtomicInteger firstByte = new AtomicInteger(-1);
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) throws IOException {
				firstByte.set(in.read());
				incoming.countDown();
			}
		};
		ZtpTorTransport t = new ZtpTorTransport(new StubTor(),
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler, null, () -> {
		});
		t.startAccepting(0);

		Socket client = new Socket("127.0.0.1", t.getLocalPort());
		client.getOutputStream().write(0x42);
		client.getOutputStream().flush();

		assertTrue(incoming.await(10, TimeUnit.SECONDS));
		assertEquals(0x42, firstByte.get());
		client.close();
		exec.shutdownNow();
	}

	@Test(timeout = 15_000)
	public void dialledConnectionsReachTheHandler() throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		// A stand-in "peer" the fake socket factory connects to.
		ServerSocket peer = new ServerSocket(0, 1,
				InetAddress.getByName("127.0.0.1"));
		SocketFactory fakeFactory = new SocketFactory() {
			@Override
			public Socket createSocket(String host, int port)
					throws IOException {
				// ignore the .onion host; connect to the local peer
				return new Socket("127.0.0.1", peer.getLocalPort());
			}

			@Override
			public Socket createSocket(String h, int p, InetAddress a, int lp) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Socket createSocket(InetAddress a, int p) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Socket createSocket(InetAddress a, int p, InetAddress la,
					int lp) {
				throw new UnsupportedOperationException();
			}
		};
		CountDownLatch outgoing = new CountDownLatch(1);
		AtomicInteger gotContact = new AtomicInteger(-1);
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
				gotContact.set(contactId);
				outgoing.countDown();
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}
		};
		ZtpTorTransport t = new ZtpTorTransport(new StubTor(), fakeFactory,
				fakeFactory, exec, handler, null, () -> {
		});
		long sessionMs = t.dial(7, "somefakeonionaddress", false);

		assertTrue(outgoing.await(10, TimeUnit.SECONDS));
		assertEquals(7, gotContact.get());
		assertTrue(sessionMs >= 0);
		peer.close();
		exec.shutdownNow();
	}

	@Test(timeout = 15_000)
	public void failedConnectReportsNotConnected() throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		SocketFactory failingFactory = new SocketFactory() {
			@Override
			public Socket createSocket(String host, int port)
					throws IOException {
				throw new IOException("peer unreachable");
			}

			@Override
			public Socket createSocket(String h, int p, InetAddress a, int lp) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Socket createSocket(InetAddress a, int p) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Socket createSocket(InetAddress a, int p, InetAddress la,
					int lp) {
				throw new UnsupportedOperationException();
			}
		};
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
				throw new AssertionError("handler must not run");
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}
		};
		ZtpTorTransport t = new ZtpTorTransport(new StubTor(), failingFactory,
				failingFactory, exec, handler, null, () -> {
		});
		assertEquals(ZtpTorTransport.DIAL_NOT_CONNECTED,
				t.dial(7, "somefakeonionaddress", true));
		exec.shutdownNow();
	}

	/** Records the wrapper calls in order so the start sequence can be checked. */
	private static class RecordingTor extends StubTor {
		final List<String> calls = new ArrayList<>();
		boolean failPadding = false;

		@Override
		public void start() {
			calls.add("start");
		}

		@Override
		public void enableConnectionPadding(boolean enable)
				throws IOException {
			calls.add("padding:" + enable);
			if (failPadding) throw new IOException("control connection lost");
		}

		@Override
		public void enableNetwork(boolean enable) {
			calls.add("network:" + enable);
		}

		@Override
		public void stop() {
			calls.add("stop");
		}
	}

	private TorBridgeConfigurator acceptingBridgeConfigurator(TorWrapper tor) {
		Mockery context = new Mockery();
		SettingsManager settingsManager = context.mock(SettingsManager.class);
		CircumventionProvider circumvention =
				context.mock(CircumventionProvider.class);
		LocationUtils locationUtils = context.mock(LocationUtils.class);
		EventBus eventBus = context.mock(EventBus.class);
		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
		}});
		return new TorBridgeConfigurator(settingsManager, circumvention,
				locationUtils, tor, eventBus, Runnable::run) {
			@Override
			public boolean apply() {
				return true;
			}
		};
	}

	@Test(timeout = 15_000)
	public void startEnablesTorConnectionPaddingBeforeTheNetwork()
			throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		RecordingTor tor = new RecordingTor();
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}
		};
		ZtpTorTransport t = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler, acceptingBridgeConfigurator(tor), () -> {
		});
		t.start(null);
		assertEquals("start", tor.calls.get(0));
		assertEquals("padding:true", tor.calls.get(1));
		assertEquals("network:true", tor.calls.get(2));
		t.stop();
		exec.shutdownNow();
	}

	@Test(timeout = 15_000)
	public void startFailsClosedWhenTorDoesNotConfirmIsolation()
			throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		RecordingTor tor = new RecordingTor();
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}
		};
		List<String> verifications = new ArrayList<>();
		TorPrivacyConfigurator refusing = () -> {
			verifications.add("verify");
			throw new IOException("Tor SOCKS isolation is not active");
		};
		ZtpTorTransport t = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler, acceptingBridgeConfigurator(tor), refusing);
		try {
			t.start(null);
			fail("start must not succeed without verified isolation");
		} catch (IOException expected) {
		}
		assertEquals(1, verifications.size());
		assertTrue(tor.calls.contains("padding:true"));
		assertTrue("Tor must be stopped again", tor.calls.contains("stop"));
		assertTrue("the network must stay disabled",
				!tor.calls.contains("network:true"));
		exec.shutdownNow();
	}

	@Test(timeout = 15_000)
	public void startFailsClosedWhenPaddingCannotBeEnabled()
			throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		RecordingTor tor = new RecordingTor();
		tor.failPadding = true;
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}
		};
		ZtpTorTransport t = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler, acceptingBridgeConfigurator(tor), () -> {
		});
		try {
			t.start(null);
			fail("start must not succeed without padding");
		} catch (IOException expected) {
		}
		assertTrue(tor.calls.contains("padding:true"));
		assertTrue("the network must not be enabled without padding",
				!tor.calls.contains("network:true"));
		exec.shutdownNow();
	}

	private static ZtpConnectionHandler tagReadingHandler(
			CountDownLatch tagsRead) {
		return new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) throws IOException {
				byte[] tag = new byte[org.zerionproject.wire.ZwfConstants
						.TAG_LENGTH];
				int off = 0;
				while (off < tag.length) {
					int r = in.read(tag, off, tag.length - off);
					if (r < 0) throw new IOException("eof");
					off += r;
				}
				tagsRead.countDown();
				while (in.read() >= 0) {
				}
			}
		};
	}

	@Test(timeout = 30_000)
	public void silentInboundConnectionIsClosedAtTheTagDeadline()
			throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		ZtpTorTransport t = new ZtpTorTransport(new StubTor(),
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				tagReadingHandler(new CountDownLatch(1)), null, () -> {
		});
		t.startAccepting(0);
		Socket silent = new Socket("127.0.0.1", t.getLocalPort());
		silent.setSoTimeout(ZtpTorTransport.TAG_READ_TIMEOUT_MS + 10_000);
		long start = System.currentTimeMillis();
		assertEquals("the server must close a silent connection", -1,
				silent.getInputStream().read());
		long held = System.currentTimeMillis() - start;
		assertTrue("closed after the deadline, held " + held + " ms",
				held >= ZtpTorTransport.TAG_READ_TIMEOUT_MS - 500);
		assertTrue("closed near the deadline, held " + held + " ms",
				held < ZtpTorTransport.TAG_READ_TIMEOUT_MS + 5_000);
		silent.close();
		exec.shutdownNow();
	}

	@Test(timeout = 30_000)
	public void silentConnectionsAreCappedBelowTheSessionSlots()
			throws Exception {
		ExecutorService exec = Executors.newCachedThreadPool();
		CountDownLatch tagsRead = new CountDownLatch(1);
		ZtpTorTransport t = new ZtpTorTransport(new StubTor(),
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				tagReadingHandler(tagsRead), null, () -> {
		});
		t.startAccepting(0);
		List<Socket> silent = new ArrayList<>();
		for (int i = 0; i < ZtpTorTransport.MAX_PRE_TAG_CONNECTIONS; i++) {
			silent.add(new Socket("127.0.0.1", t.getLocalPort()));
		}
		Thread.sleep(500);
		Socket oneTooMany = new Socket("127.0.0.1", t.getLocalPort());
		oneTooMany.setSoTimeout(3_000);
		assertEquals("a silent connection beyond the pre-tag budget is "
				+ "refused at once", -1, oneTooMany.getInputStream().read());
		oneTooMany.close();
		for (Socket s : silent) {
			s.setSoTimeout(ZtpTorTransport.TAG_READ_TIMEOUT_MS + 10_000);
			assertEquals("silent connections are closed at the deadline",
					-1, s.getInputStream().read());
			s.close();
		}
		Socket talking = new Socket("127.0.0.1", t.getLocalPort());
		talking.getOutputStream().write(
				new byte[org.zerionproject.wire.ZwfConstants.TAG_LENGTH]);
		talking.getOutputStream().flush();
		assertTrue("a connection that delivers its tag once the budget is "
				+ "free again is served", tagsRead.await(10_000,
				TimeUnit.MILLISECONDS));
		talking.close();
		exec.shutdownNow();
	}

	@Test
	public void preambleStreamSignalsOnceWhenTheTagIsComplete()
			throws Exception {
		AtomicInteger signals = new AtomicInteger();
		byte[] data = new byte[40];
		ZtpTorTransport.PreambleDeadlineInputStream in =
				new ZtpTorTransport.PreambleDeadlineInputStream(
						new java.io.ByteArrayInputStream(data), 16,
						signals::incrementAndGet);
		byte[] buf = new byte[10];
		assertEquals(10, in.read(buf, 0, 10));
		assertEquals(0, signals.get());
		assertEquals(5, in.read(buf, 0, 5));
		assertEquals(0, signals.get());
		assertTrue(in.read() >= 0);
		assertEquals(1, signals.get());
		while (in.read() >= 0) {
		}
		assertEquals(1, signals.get());
	}
}
