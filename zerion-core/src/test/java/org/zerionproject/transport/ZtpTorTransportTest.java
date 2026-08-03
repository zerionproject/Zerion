package org.zerionproject.transport;

import org.briarproject.onionwrapper.TorWrapper;
import org.zerionproject.core.api.plugin.TransportId;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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

		public void enableConnectionPadding(boolean enable) {
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
}
