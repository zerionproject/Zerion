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

	/** No-op Tor: these tests drive accept/dial directly, never start Tor. */
	private static class StubTor implements TorWrapper {
		public void start() {
		}

		public void stop() {
		}

		public void setObserver(@Nullable Observer observer) {
		}

		public TorState getTorState() {
			return TorState.STOPPED;
		}

		public boolean isTorRunning() {
			return false;
		}

		@Nullable
		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			return null;
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
				handler, null);
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
				fakeFactory, exec, handler, null);
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
				failingFactory, exec, handler, null);
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
				handler, acceptingBridgeConfigurator(tor));
		t.start(null);
		assertEquals("start", tor.calls.get(0));
		assertEquals("padding:true", tor.calls.get(1));
		assertEquals("network:true", tor.calls.get(2));
		t.stop();
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
				handler, acceptingBridgeConfigurator(tor));
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
}
