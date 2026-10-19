package com.professor.zerion.android.update;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BridgeListSourceTest {

	private static final java.util.function.LongSupplier MONOTONIC =
			() -> System.nanoTime() / 1_000_000L;

	private static class RecordingTorSockets extends SocketFactory {
		final List<SocketAddress> connects = new ArrayList<>();
		int created = 0;

		final List<Integer> timeouts = new ArrayList<>();
		long[] clock = {0};
		long perConnect = 0;

		@Override
		public Socket createSocket() {
			created++;
			return new Socket() {
				@Override
				public void connect(SocketAddress endpoint, int timeout)
						throws IOException {
					connects.add(endpoint);
					timeouts.add(timeout);
					clock[0] += perConnect;
					throw new IOException("no Tor in this test");
				}
			};
		}

		@Override
		public Socket createSocket(String host, int port) {
			throw new AssertionError("only unconnected sockets");
		}

		@Override
		public Socket createSocket(String host, int port,
				InetAddress localHost, int localPort) {
			throw new AssertionError("only unconnected sockets");
		}

		@Override
		public Socket createSocket(InetAddress host, int port) {
			throw new AssertionError("only unconnected sockets");
		}

		@Override
		public Socket createSocket(InetAddress address, int port,
				InetAddress localAddress, int localPort) {
			throw new AssertionError("only unconnected sockets");
		}
	}

	@Test
	public void theListIsFetchedOnlyThroughTheTorSocketsWithoutALocalLookup() {
		RecordingTorSockets tor = new RecordingTorSockets();
		try {
			BridgeListSource.fetch(tor, BridgeListSource.URLS, MONOTONIC);
			fail("fetched without Tor");
		} catch (IOException expected) {
		}
		assertEquals(1, tor.created);
		assertEquals(1, tor.connects.size());
		InetSocketAddress target = (InetSocketAddress) tor.connects.get(0);
		assertTrue("the name is resolved by Tor, not on the phone",
				target.isUnresolved());
		assertEquals("raw.githubusercontent.com", target.getHostString());
		assertEquals(443, target.getPort());
	}

	@Test
	public void bothRequestsAndEverySourceShareOneDeadline() {
		RecordingTorSockets tor = new RecordingTorSockets();
		tor.perConnect = 100_000;
		List<String> three = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			three.add("https://raw.githubusercontent.com/x/" + i + ".json");
		}
		try {
			BridgeListSource.fetch(tor, three, () -> tor.clock[0]);
			fail();
		} catch (IOException expected) {
		}
		assertEquals(120_000, BridgeListSource.FETCH_DEADLINE_MS);
		assertEquals("the first wait is the usual one, the second what is"
				+ " left of two minutes, and then nothing more is tried",
				Arrays.asList(TorHttpsGet.CONNECT_TIMEOUT_MS, 20_000),
				tor.timeouts);
		assertEquals(2, tor.created);
	}

	@Test
	public void withoutTorThereIsNoFetchAtAll() {
		SocketFactory none = new RecordingTorSockets() {
			@Override
			public Socket createSocket() {
				throw new IllegalStateException("Tor is not running");
			}
		};
		try {
			BridgeListSource.fetch(none, BridgeListSource.URLS, MONOTONIC);
			fail();
		} catch (IllegalStateException | IOException expected) {
		}
	}

	@Test
	public void theSourcesAreHttpsOnTheProjectRepository() {
		assertEquals(Collections.singletonList("https://raw.githubusercontent"
				+ ".com/zerionproject/Zerion/master/docs/bridges/bridges.json"),
				BridgeListSource.URLS);
		RecordingTorSockets tor = new RecordingTorSockets();
		try {
			BridgeListSource.fetch(tor, Collections.singletonList(
					"http://raw.githubusercontent.com/x/bridges.json"),
					MONOTONIC);
			fail();
		} catch (IOException expected) {
		}
		assertEquals("plain http is refused before any socket", 0,
				tor.created);
	}
}
