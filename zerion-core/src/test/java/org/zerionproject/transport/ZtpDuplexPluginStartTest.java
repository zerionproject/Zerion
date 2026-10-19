package org.zerionproject.transport;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.PluginException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl;

import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZtpDuplexPluginStartTest {

	private final ExecutorService exec = Executors.newCachedThreadPool();

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	@Test
	public void aFailedRepublishOfTheAnnouncedOnionDoesNotStopTheDialling()
			throws Exception {
		Mockery context = new Mockery();
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		OnionClientAuthManagerImpl auth =
				context.mock(OnionClientAuthManagerImpl.class);
		context.checking(new Expectations() {{
			ignoring(auth);
		}});
		Settings settings = new Settings();
		PluginCallback callback = (PluginCallback) Proxy.newProxyInstance(
				PluginCallback.class.getClassLoader(),
				new Class<?>[] {PluginCallback.class}, (p, m, a) ->
						m.getName().equals("getSettings") ? settings : null);
		AtomicBoolean pollerStarted = new AtomicBoolean(false);
		ZtpPoller poller = new ZtpPoller(Runnable::run, null, null, null,
				null, null) {
			@Override
			public void start() {
				pollerStarted.set(true);
			}

			@Override
			public void stop() {
			}
		};
		B4OnionRotation rotation = new B4OnionRotation(null, null, null,
				null) {
			@Override
			public void republishPendingOnion() throws DbException {
				throw new DbException();
			}

			@Override
			public void startPeriodicEvaluation() {
			}
		};
		EventBus events = new EventBus() {
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
		ZtpTorTransportTest.StubTor tor = new ZtpTorTransportTest.StubTor();
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), exec,
				null, null, () -> {
		});
		ZtpDuplexPlugin plugin = new ZtpDuplexPlugin(exec, exec, null,
				SocketFactory.getDefault(), tor, transport, poller, null,
				callback, rotation, events, null, auth);
		try {
			plugin.start();
		} catch (PluginException e) {
			fail("Tor came up but the plugin stopped before it could dial: "
					+ e);
		} finally {
			transport.stop();
		}
		assertTrue("the poller was never started", pollerStarted.get());
	}
}
