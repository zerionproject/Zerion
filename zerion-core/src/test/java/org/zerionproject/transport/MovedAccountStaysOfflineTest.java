package org.zerionproject.transport;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.PluginException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.plugin.tor.TorRendezvousCrypto;

import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class MovedAccountStaysOfflineTest {

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final AtomicInteger torStarts = new AtomicInteger();

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	@Test
	public void aMovedAccountDoesNotStartTor() throws Exception {
		Settings settings = new Settings();
		settings.putBoolean("accountMoved", true);
		PluginCallback callback = (PluginCallback) Proxy.newProxyInstance(
				PluginCallback.class.getClassLoader(),
				new Class<?>[] {PluginCallback.class}, (p, m, a) ->
						m.getName().equals("getSettings") ? settings : null);
		ZtpTorTransportTest.StubTor tor = new ZtpTorTransportTest.StubTor() {
			@Override
			public void start() {
				torStarts.incrementAndGet();
			}
		};
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				null, null, () -> {
		});
		TorRendezvousCrypto rendezvous = new TorRendezvousCrypto() {
			@Override
			public String getOnion(byte[] seed) {
				return "onion";
			}

			@Override
			public String getPrivateKeyBlob(byte[] seed) {
				return "key";
			}
		};
		ZtpDuplexPlugin plugin = new ZtpDuplexPlugin(exec, exec,
				SocketFactory.getDefault(), tor, transport, null, rendezvous,
				callback, null, null, null, null);
		try {
			plugin.start();
			fail("a moved account started Tor");
		} catch (PluginException expected) {
		} catch (RuntimeException e) {
			fail("a moved account went on to start: " + e);
		}
		assertEquals(0, torStarts.get());
	}
}
