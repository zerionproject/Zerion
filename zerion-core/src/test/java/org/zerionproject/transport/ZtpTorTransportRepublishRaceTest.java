package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ZtpTorTransportRepublishRaceTest {

	private final ExecutorService exec = Executors.newCachedThreadPool();

	private static final class GatedTor extends ZtpTorTransportTest.StubTor {

		final Set<String> live = ConcurrentHashMap.newKeySet();
		final CountDownLatch inRepublish = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		volatile boolean gate = false;

		@Override
		@Nullable
		public HiddenServiceProperties publishHiddenService(int localPort,
				int remotePort, @Nullable String privateKey) {
			String key = privateKey == null ? "startup" : privateKey;
			String onion = "onion-of-" + key;
			live.add(onion);
			try {
				java.lang.reflect.Constructor<HiddenServiceProperties> c =
						HiddenServiceProperties.class.getDeclaredConstructor(
								String.class, String.class);
				c.setAccessible(true);
				return c.newInstance(onion, key);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}

		@Override
		public void removeHiddenService(String onion) {
			if (gate && onion.equals("onion-of-first")) {
				gate = false;
				inRepublish.countDown();
				try {
					resume.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			live.remove(onion);
		}
	}

	private static ZtpConnectionHandler noHandler() {
		return new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}

			@Override
			public void handlePaired(TransportId transportId, int contactId,
					boolean incoming, InputStream in, OutputStream out) {
			}
		};
	}

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	@Test(timeout = 60_000)
	public void anOnionRemovedWhileServicesAreRepublishedStaysRemoved()
			throws Exception {
		GatedTor tor = new GatedTor();
		ZtpTorTransport t = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				noHandler(), null, () -> {
		});
		t.start(null);
		t.publishDedicatedService("first");
		t.publishDedicatedService("second");
		assertTrue(tor.live.contains("onion-of-second"));

		tor.gate = true;
		Thread republish = new Thread(t::republishHiddenServices);
		republish.start();
		assertTrue(tor.inRepublish.await(20, TimeUnit.SECONDS));

		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread remove = new Thread(() -> {
			try {
				t.removeHiddenService("onion-of-second");
			} catch (IOException | RuntimeException e) {
				failure.set(e);
			}
		});
		remove.start();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (remove.getState() != Thread.State.BLOCKED
				&& remove.getState() != Thread.State.TERMINATED
				&& System.nanoTime() < deadline) {
			Thread.yield();
		}
		tor.resume.countDown();
		republish.join(20_000);
		remove.join(20_000);

		assertNull(failure.get());
		assertFalse("a removed onion was published again",
				tor.live.contains("onion-of-second"));
		assertFalse(t.publishedOnions().contains("onion-of-second"));
		assertTrue(tor.live.contains("onion-of-first"));
		t.stop();
	}
}
