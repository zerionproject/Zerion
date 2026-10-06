package org.zerionproject.app.conversation.voice;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class VoiceCallEndpointLifetimeTest {

	private final AtomicInteger published = new AtomicInteger();
	private final AtomicInteger closed = new AtomicInteger();
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private final VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(
			DaggerVoiceCryptoTestComponent.create().getCryptoComponent());
	private final VoiceCallConnectionManagerImpl manager =
			new VoiceCallConnectionManagerImpl(pluginManager(), crypto, 500,
					new long[] {0}, now::get);

	@After
	public void tearDown() {
		manager.shutdown();
	}

	private PluginManager pluginManager() {
		RendezvousEndpoint endpoint = new RendezvousEndpoint() {
			@Override
			public TransportProperties getRemoteTransportProperties() {
				return new TransportProperties();
			}

			@Override
			public void close() {
				closed.incrementAndGet();
			}
		};
		DuplexPlugin plugin = (DuplexPlugin) Proxy.newProxyInstance(
				DuplexPlugin.class.getClassLoader(),
				new Class<?>[] {DuplexPlugin.class}, (p, m, a) -> {
					switch (m.getName()) {
						case "supportsRendezvous":
							return true;
						case "createRendezvousEndpoint":
							published.incrementAndGet();
							return endpoint;
						default:
							return null;
					}
				});
		return (PluginManager) Proxy.newProxyInstance(
				PluginManager.class.getClassLoader(),
				new Class<?>[] {PluginManager.class}, (p, m, a) ->
						m.getName().equals("getPlugin") ? plugin : null);
	}

	private static SecretKey key() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x21);
		return new SecretKey(raw);
	}

	@Test
	public void aReconnectKeepsTheEndpointThatIsStillPublished()
			throws Exception {
		VoiceCallConnectionManager.EndpointInfo first =
				manager.createIncomingEndpoint("call-1", key(), false,
						c -> {
						});
		VoiceCallConnectionManager.EndpointInfo again =
				manager.createIncomingEndpoint("call-1", key(), false,
						c -> {
						});
		assertEquals("the same address was published twice", 1,
				published.get());
		assertEquals(first.onionAddress, again.onionAddress);
		assertEquals("the live endpoint was closed", 0, closed.get());
		manager.closeEndpoint("call-1");
		assertEquals(1, closed.get());
		manager.createIncomingEndpoint("call-1", key(), false, c -> {
		});
		assertEquals("a closed endpoint is published again", 2,
				published.get());
	}

	@Test
	public void theCallerExpectsTheAddressTheCalleePublishes()
			throws Exception {
		VoiceCallConnectionManager.EndpointInfo callee =
				manager.createIncomingEndpoint("call-1", key(), false,
						c -> {
						});
		assertEquals(callee.onionAddress,
				manager.expectedPeerOnion("call-1", key(), true));
	}

	@Test
	public void aLongCallKeepsItsEndpoint() throws Exception {
		manager.createIncomingEndpoint("call-1", key(), false, c -> {
		});
		for (int minute = 0; minute < 90; minute++) {
			now.addAndGet(60_000L);
			manager.keepEndpoint("call-1");
			manager.cleanupExpiredEndpoints();
		}
		assertEquals("the endpoint of a running call was swept", 0,
				closed.get());
		now.addAndGet(VoiceCallConnectionManagerImpl.ENDPOINT_TIMEOUT_MS
				+ 1);
		manager.cleanupExpiredEndpoints();
		assertEquals("an endpoint nobody marks is swept", 1, closed.get());
	}
}
