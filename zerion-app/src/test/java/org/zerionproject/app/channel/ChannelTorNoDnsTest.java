package org.zerionproject.app.channel;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelTorNoDnsTest {

	private static final String ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final List<SocketAddress> endpoints = new ArrayList<>();
	private final AtomicInteger created = new AtomicInteger();

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	private TorChannelTransport transport() {
		SocketFactory recording = new SocketFactory() {
			@Override
			public Socket createSocket() {
				created.incrementAndGet();
				return new Socket() {
					@Override
					public void connect(SocketAddress endpoint, int timeout)
							throws IOException {
						endpoints.add(endpoint);
						throw new IOException("stop here");
					}
				};
			}

			@Override
			public Socket createSocket(String host, int port) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Socket createSocket(String host, int port,
					InetAddress localHost, int localPort) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Socket createSocket(InetAddress host, int port) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Socket createSocket(InetAddress address, int port,
					InetAddress localAddress, int localPort) {
				throw new UnsupportedOperationException();
			}
		};
		OnionPublisher publisher = new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort, String privateKey) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		return new TorChannelTransport(publisher, recording, exec);
	}

	@Test
	public void aChannelRequestHandsTheOnionToTorUnresolved() {
		TorChannelTransport t = transport();
		for (String onion : new String[] {ONION, ONION + ".onion",
				ONION.toUpperCase(java.util.Locale.ROOT)}) {
			try {
				t.requestFromOnion(onion, new byte[] {1});
				fail();
			} catch (IOException expected) {
				assertEquals("stop here", expected.getMessage());
			}
		}
		assertEquals(3, endpoints.size());
		for (SocketAddress a : endpoints) {
			InetSocketAddress inet = (InetSocketAddress) a;
			assertTrue("the name must reach Tor unresolved",
					inet.isUnresolved());
			assertEquals(ONION + ".onion", inet.getHostString());
		}
	}

	@Test
	public void aChannelRequestNeverAsksTheSystemResolver() {
		String probe = "zt-resolver-probe.invalid";
		try {
			InetAddress.getByName(probe);
			fail();
		} catch (UnknownHostException expected) {
		}
		assertTrue("the resolver hook must be active",
				RecordingResolverProvider.LOOKUPS.contains(probe));
		TorChannelTransport t = transport();
		for (String onion : new String[] {ONION, ONION + ".onion",
				ONION.toUpperCase(java.util.Locale.ROOT)}) {
			try {
				t.requestFromOnion(onion, new byte[] {1});
				fail();
			} catch (IOException expected) {
			}
		}
		synchronized (RecordingResolverProvider.LOOKUPS) {
			for (String name : RecordingResolverProvider.LOOKUPS) {
				assertFalse(name, name.toLowerCase(java.util.Locale.ROOT)
						.endsWith(".onion"));
			}
		}
	}

	@Test
	public void aDestinationThatIsNotAV3OnionIsRefusedBeforeAnySocket() {
		TorChannelTransport t = transport();
		for (String bad : new String[] {"example.com", "localhost",
				"127.0.0.1", ONION.substring(1), ONION + "a",
				"abcdefghijklmnop.onion", ONION + ".onion.example.com"}) {
			try {
				t.requestFromOnion(bad, new byte[] {1});
				fail(bad);
			} catch (IOException expected) {
			}
		}
		assertEquals(0, created.get());
		assertTrue(endpoints.isEmpty());
	}

	@Test
	public void anInviteLinkCarriesOnlyAV3Onion() {
		CryptoComponent crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelCodec codec = new ChannelCodec(crypto);
		byte[] channelId = new byte[32];
		byte[] ed = new byte[32];
		String link = codec.formatInviteLink(channelId, ed, null, true,
				null, ONION, false);
		assertNotNull(codec.parseInviteLink(link));
		assertEquals(ONION,
				codec.parseInviteLink(link).getOnionAddress());
		for (String bad : new String[] {"example.com",
				"attacker.example.org", "127.0.0.1", "localhost"}) {
			String swapped = link.replace("o=" + ONION, "o=" + bad);
			assertNull(bad, codec.parseInviteLink(swapped));
		}
	}
}
