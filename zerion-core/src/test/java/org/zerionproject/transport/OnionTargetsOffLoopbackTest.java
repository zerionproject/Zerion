package org.zerionproject.transport;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.OnionTargets;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.zerionproject.core.plugin.tor.ChannelOnionAdapter;
import org.zerionproject.core.plugin.tor.TorRendezvousCrypto;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;

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

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;

public class OnionTargetsOffLoopbackTest {

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final List<String> publishedTargets =
			Collections.synchronizedList(new ArrayList<>());
	private final List<Boolean> seen =
			Collections.synchronizedList(new ArrayList<>());
	private final CountDownLatch twoIncoming = new CountDownLatch(2);
	private UnixDomainTargets targets;

	@After
	public void tearDown() {
		exec.shutdownNow();
		if (targets != null) targets.deleteAll();
	}

	private class TargetRecordingTor extends ZtpTorTransportTest.StubTor {

		@Override
		public HiddenServiceProperties publishHiddenService(String target,
				int remotePort, @Nullable String privateKey) {
			publishedTargets.add(target);
			return new HiddenServiceProperties(
					"onion" + publishedTargets.size(),
					privateKey == null ? "generated" : privateKey);
		}

		@Override
		@Nullable
		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			throw new AssertionError("a loopback target was published");
		}
	}

	private final ZtpConnectionHandler handler = new ZtpConnectionHandler() {
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
			twoIncoming.countDown();
		}

		@Override
		public void handlePaired(TransportId transportId, int contactId,
				boolean incoming, InputStream in, OutputStream out) {
			throw new UnsupportedOperationException();
		}
	};

	private ZtpTorTransport transport() throws IOException {
		targets = new UnixDomainTargets();
		return new ZtpTorTransport(new TargetRecordingTor(),
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				handler, null, () -> {
		}, new TorProcessWatch(), targets);
	}

	@Test(timeout = 30_000)
	public void theContactAddressesForwardToUnixSocketsOnly()
			throws Exception {
		ZtpTorTransport t = transport();
		t.start(null);
		assertEquals(1, publishedTargets.size());
		assertTrue(OnionTargets.isUnix(publishedTargets.get(0)));
		assertEquals(t.getOpenTarget(), publishedTargets.get(0));
		assertTrue(OnionTargets.isUnix(t.getAuthorizedTarget()));
		assertEquals("the open listener has a port", -1, t.getLocalPort());
		assertEquals("the authorized listener has a port", -1,
				t.getAuthorizedLocalPort());
		try (Socket open = targets.connect(1);
				Socket authorized = targets.connect(2)) {
			open.getOutputStream().write(new byte[TAG_LENGTH]);
			open.getOutputStream().flush();
			authorized.getOutputStream().write(new byte[TAG_LENGTH]);
			authorized.getOutputStream().flush();
			assertTrue(twoIncoming.await(20, TimeUnit.SECONDS));
		}
		assertTrue(seen.contains(true));
		assertTrue(seen.contains(false));
		t.stop();
		assertTrue(targets.opened.get(0).isClosed());
		assertTrue(targets.opened.get(1).isClosed());
	}

	@Test(timeout = 30_000)
	public void pairingAndCallEndpointsForwardToUnixSockets()
			throws Exception {
		ZtpTorTransport t = transport();
		t.start(null);
		ZtpDuplexPlugin plugin = plugin(t);
		CountDownLatch connected = new CountDownLatch(1);
		RendezvousEndpoint endpoint = plugin.createRendezvousEndpoint(
				n -> new byte[n], false, new ConnectionHandler() {
					@Override
					public void handleConnection(DuplexTransportConnection c) {
						connected.countDown();
					}

					@Override
					public void handleReader(TransportConnectionReader r) {
					}

					@Override
					public void handleWriter(TransportConnectionWriter w) {
					}
				});
		assertNotNull(endpoint);
		assertEquals(2, publishedTargets.size());
		assertTrue(OnionTargets.isUnix(publishedTargets.get(1)));
		UnixDomainTargets.Listener listener = targets.opened.get(2);
		assertEquals(listener.getTorTarget(), publishedTargets.get(1));
		try (Socket s = targets.connect(3)) {
			assertTrue(connected.await(20, TimeUnit.SECONDS));
		}
		endpoint.close();
		assertTrue("the endpoint's listener stays open",
				listener.isClosed());
		t.stop();
	}

	@Test(timeout = 30_000)
	public void aChannelServerForwardsToAUnixSocket() throws Exception {
		ZtpTorTransport t = transport();
		t.start(null);
		ChannelOnionAdapter adapter = plugin(t);
		String target = adapter.openOnionTarget().getTorTarget();
		assertTrue(OnionTargets.isUnix(target));
		ChannelOnionAdapter.ChannelOnionHandle h =
				adapter.publishChannelOnion(target, null);
		assertEquals(target, publishedTargets.get(1));
		assertEquals("generated", h.getPrivateKey());
		t.stop();
	}

	private ZtpDuplexPlugin plugin(ZtpTorTransport t) {
		TorRendezvousCrypto rendezvous = new TorRendezvousCrypto() {
			@Override
			public String getOnion(byte[] seed) {
				return "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";
			}

			@Override
			public String getPrivateKeyBlob(byte[] seed) {
				return "ED25519-V3:key";
			}
		};
		return new ZtpDuplexPlugin(exec, exec, SocketFactory.getDefault(),
				new ZtpTorTransportTest.StubTor(), t, null, rendezvous, null,
				null, null, null, null);
	}

	@Test
	public void aLoopbackOnlyWrapperRefusesAUnixTarget() {
		try {
			new ZtpTorTransportTest.StubTor().publishHiddenService(
					"unix:/data/zo/x", 80, null);
			throw new AssertionError("a Unix target was published elsewhere");
		} catch (IOException expected) {
		}
		assertFalse(OnionTargets.isUnix("127.0.0.1:80"));
	}
}
