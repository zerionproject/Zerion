package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.system.TaskScheduler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZtpDuplexPluginInboxPowTest {

	private static final String ONION =
			"i66iurfyjjh5tqpqhq7luu6defbb5rs7mxzvqgryaabqkzkgmjhql7qd";
	private static final String TARGET = "unix:/data/x/inbox";
	private static final String KEY_BLOB = "ED25519-V3:AAAA";

	private final ArrayDeque<Boolean> pow = new ArrayDeque<>();
	private final List<String> removed = new ArrayList<>();

	private ZtpDuplexPlugin plugin() {
		ZtpTorTransportTest.StubTor tor = new ZtpTorTransportTest.StubTor();
		Executor direct = Runnable::run;
		TaskScheduler scheduler = new TaskScheduler() {
			@Override
			public Cancellable schedule(Runnable task, Executor executor,
					long delay, TimeUnit unit) {
				return () -> {
				};
			}

			@Override
			public Cancellable scheduleWithFixedDelay(Runnable task,
					Executor executor, long delay, long interval,
					TimeUnit unit) {
				return () -> {
				};
			}
		};
		EventBus bus = new EventBus() {
			@Override
			public void addListener(EventListener l) {
			}

			@Override
			public void removeListener(EventListener l) {
			}

			@Override
			public void broadcast(Event e) {
			}
		};
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), direct,
				null, null, () -> {
		});
		TorOnionServiceControl control = new TorOnionServiceControl(
				new File("."), () -> {
			throw new IOException("no Tor in this test");
		}) {
			@Override
			public InboxPublished publishInbox(String privateKey,
					String target, int remotePort, byte[] clientPublicKey) {
				return new InboxPublished(ONION, privateKey, pow.removeFirst());
			}

			@Override
			public void remove(String onion) {
				removed.add(onion);
			}
		};
		return new ZtpDuplexPlugin(direct, direct, scheduler,
				SocketFactory.getDefault(), tor, transport, null, null, null,
				null, bus, control, null);
	}

	@Test
	public void theInboxReportsTheModeOfItsLastPublication() throws Exception {
		ZtpDuplexPlugin plugin = plugin();
		pow.add(true);
		pow.add(false);
		pow.add(true);
		assertEquals(ONION, plugin.publishInboxOnion(TARGET, KEY_BLOB,
				new byte[32]));
		assertTrue(plugin.isInboxPowProtected(ONION));
		plugin.publishInboxOnion(TARGET, KEY_BLOB, new byte[32]);
		assertFalse("a republish without proof of work clears it",
				plugin.isInboxPowProtected(ONION));
		plugin.publishInboxOnion(TARGET, KEY_BLOB, new byte[32]);
		assertTrue(plugin.isInboxPowProtected(ONION));
		plugin.removeInboxOnion(ONION);
		assertFalse("a removed inbox no longer counts",
				plugin.isInboxPowProtected(ONION));
		assertEquals(singletonList(ONION), removed);
	}
}
