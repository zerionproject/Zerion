package org.zerionproject.transport;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl;
import org.zerionproject.tor.TorWakePolicy;

import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.SocketFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZtpDuplexPluginKeepAwakeTest {

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final AtomicLong now = new AtomicLong(1_000_000);
	private final TaskScheduler noScheduler = new TaskScheduler() {
		@Override
		public Cancellable schedule(Runnable task, Executor executor,
				long delay, TimeUnit unit) {
			return () -> {
			};
		}

		@Override
		public Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval, TimeUnit unit) {
			return () -> {
			};
		}
	};

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	@Test
	public void theSettingDefaultsToOffWhenTheKeyIsAbsent() {
		assertFalse(ZtpDuplexPlugin.wantsKeepAwake(new Settings()));
		Settings on = new Settings();
		on.putBoolean(TorConstants.PREF_TOR_KEEP_AWAKE, true);
		assertTrue(ZtpDuplexPlugin.wantsKeepAwake(on));
		Settings off = new Settings();
		off.putBoolean(TorConstants.PREF_TOR_KEEP_AWAKE, false);
		assertFalse(ZtpDuplexPlugin.wantsKeepAwake(off));
	}

	@Test
	public void aTorSettingsChangeAppliesKeepAwakeWithoutARestart() {
		TorWakePolicy policy =
				new TorWakePolicy(noScheduler, Runnable::run, now::get);
		ZtpDuplexPlugin plugin = plugin(policy);
		policy.onNetworkEnabled(true);
		policy.onBootstrapProgress(100);
		now.addAndGet(10 * 60_000);
		policy.setKeepAwake(false);
		assertFalse("settled and released", policy.isHeld());

		Settings on = new Settings();
		on.putBoolean(TorConstants.PREF_TOR_KEEP_AWAKE, true);
		plugin.torSettingsListener.eventOccurred(
				new SettingsUpdatedEvent(TorConstants.ID.getString(), on));
		assertTrue("the Tor namespace switches it on", policy.isHeld());

		Settings other = new Settings();
		other.putBoolean(TorConstants.PREF_TOR_KEEP_AWAKE, false);
		plugin.torSettingsListener.eventOccurred(
				new SettingsUpdatedEvent(I2pConstants.ID.getString(), other));
		assertTrue("another namespace is ignored", policy.isHeld());

		plugin.torSettingsListener.eventOccurred(new SettingsUpdatedEvent(
				TorConstants.ID.getString(), new Settings()));
		assertFalse("an absent key means the default, off", policy.isHeld());
	}

	private ZtpDuplexPlugin plugin(TorWakePolicy policy) {
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
		ZtpPoller poller = new ZtpPoller(Runnable::run, null, null, null,
				null, null) {
			@Override
			public void start() {
			}

			@Override
			public void stop() {
			}
		};
		B4OnionRotation rotation = new B4OnionRotation(null, null, null,
				null) {
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
		return new ZtpDuplexPlugin(exec, exec, null,
				SocketFactory.getDefault(), tor, transport, poller, null,
				callback, rotation, events, null, auth, null, policy);
	}
}
