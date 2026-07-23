package org.zerionproject.core.plugin;

import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.lifecycle.ServiceException;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.Plugin.State;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.PluginException;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.plugin.simplex.SimplexPlugin;
import org.zerionproject.core.api.plugin.simplex.SimplexPluginFactory;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.WakefulIoExecutor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.Collections.emptyList;
import static org.zerionproject.core.api.plugin.Plugin.PREF_PLUGIN_ENABLE;
import static org.zerionproject.core.api.plugin.Plugin.State.ACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.DISABLED;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;

@ThreadSafe
@NotNullByDefault
class PluginManagerImpl implements PluginManager, Service {
	private final Executor ioExecutor, wakefulIoExecutor;
	private final EventBus eventBus;
	private final PluginConfig pluginConfig;
	private final ConnectionManager connectionManager;
	private final SettingsManager settingsManager;
	private final TransportPropertyManager transportPropertyManager;
	private final Map<TransportId, Plugin> plugins;
	private final List<SimplexPlugin> simplexPlugins;
	private final List<DuplexPlugin> duplexPlugins;
	private final Map<TransportId, CountDownLatch> startLatches;
	private final AtomicBoolean used = new AtomicBoolean(false);

	@Inject
	PluginManagerImpl(@IoExecutor Executor ioExecutor,
			@WakefulIoExecutor Executor wakefulIoExecutor,
			EventBus eventBus,
			PluginConfig pluginConfig,
			ConnectionManager connectionManager,
			SettingsManager settingsManager,
			TransportPropertyManager transportPropertyManager) {
		this.ioExecutor = ioExecutor;
		this.wakefulIoExecutor = wakefulIoExecutor;
		this.eventBus = eventBus;
		this.pluginConfig = pluginConfig;
		this.connectionManager = connectionManager;
		this.settingsManager = settingsManager;
		this.transportPropertyManager = transportPropertyManager;
		plugins = new ConcurrentHashMap<>();
		simplexPlugins = new CopyOnWriteArrayList<>();
		duplexPlugins = new CopyOnWriteArrayList<>();
		startLatches = new ConcurrentHashMap<>();
	}

	@Override
	public void startService() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		for (SimplexPluginFactory f : pluginConfig.getSimplexFactories()) {
			TransportId t = f.getId();
			SimplexPlugin s = f.createPlugin(new Callback(t));
			if (s == null) {
			} else {
				plugins.put(t, s);
				simplexPlugins.add(s);
				CountDownLatch startLatch = new CountDownLatch(1);
				startLatches.put(t, startLatch);
				wakefulIoExecutor.execute(new PluginStarter(s, startLatch));
			}
		}
		for (DuplexPluginFactory f : pluginConfig.getDuplexFactories()) {
			TransportId t = f.getId();
			DuplexPlugin d = f.createPlugin(new Callback(t));
			if (d == null) {
			} else {
				plugins.put(t, d);
				duplexPlugins.add(d);
				CountDownLatch startLatch = new CountDownLatch(1);
				startLatches.put(t, startLatch);
				wakefulIoExecutor.execute(new PluginStarter(d, startLatch));
			}
		}
	}

	@Override
	public void stopService() throws ServiceException {
		CountDownLatch stopLatch = new CountDownLatch(plugins.size());
		for (SimplexPlugin s : simplexPlugins) {
			CountDownLatch startLatch = startLatches.get(s.getId());
			ioExecutor.execute(new PluginStopper(s, startLatch, stopLatch));
		}
		for (DuplexPlugin d : duplexPlugins) {
			CountDownLatch startLatch = startLatches.get(d.getId());
			ioExecutor.execute(new PluginStopper(d, startLatch, stopLatch));
		}
		try {
			stopLatch.await();
		} catch (InterruptedException e) {
			throw new ServiceException(e);
		}
	}

	@Override
	public Plugin getPlugin(TransportId t) {
		return plugins.get(t);
	}

	@Override
	public Collection<SimplexPlugin> getSimplexPlugins() {
		return new ArrayList<>(simplexPlugins);
	}

	@Override
	public Collection<DuplexPlugin> getDuplexPlugins() {
		return new ArrayList<>(duplexPlugins);
	}

	@Override
	public Collection<DuplexPlugin> getKeyAgreementPlugins() {
		List<DuplexPlugin> supported = new ArrayList<>();
		for (DuplexPlugin d : duplexPlugins)
			if (d.supportsKeyAgreement()) supported.add(d);
		return supported;
	}

	@Override
	public Collection<DuplexPlugin> getRendezvousPlugins() {
		List<DuplexPlugin> supported = new ArrayList<>();
		for (DuplexPlugin d : duplexPlugins)
			if (d.supportsRendezvous()) supported.add(d);
		return supported;
	}

	@Override
	public void setPluginEnabled(TransportId t, boolean enabled) {
		Plugin plugin = plugins.get(t);
		if (plugin == null) return;

		Settings s = new Settings();
		s.putBoolean(PREF_PLUGIN_ENABLE, enabled);
		ioExecutor.execute(() -> mergeSettings(s, t.getString()));
	}

	private void mergeSettings(Settings s, String namespace) {
		try {
			settingsManager.mergeSettings(s, namespace);
		} catch (DbException e) {
		}
	}

	private static class PluginStarter implements Runnable {

		private final Plugin plugin;
		private final CountDownLatch startLatch;

		private PluginStarter(Plugin plugin, CountDownLatch startLatch) {
			this.plugin = plugin;
			this.startLatch = startLatch;
		}

		@Override
		public void run() {
			try {
				plugin.start();
			} catch (PluginException e) {
			} finally {
				startLatch.countDown();
			}
		}
	}

	private static class PluginStopper implements Runnable {

		private final Plugin plugin;
		private final CountDownLatch startLatch, stopLatch;

		private PluginStopper(Plugin plugin, CountDownLatch startLatch,
				CountDownLatch stopLatch) {
			this.plugin = plugin;
			this.startLatch = startLatch;
			this.stopLatch = stopLatch;
		}

		@Override
		public void run() {
			try {
				startLatch.await();
				plugin.stop();
			} catch (InterruptedException e) {
			} catch (PluginException e) {
			} finally {
				stopLatch.countDown();
			}
		}
	}

	private class Callback implements PluginCallback {

		private final TransportId id;
		private final Object stateLock = new Object();

		@GuardedBy("lock")
		private State state = STARTING_STOPPING;

		private Callback(TransportId id) {
			this.id = id;
		}

		@Override
		public Settings getSettings() {
			try {
				return settingsManager.getSettings(id.getString());
			} catch (DbException e) {
				return new Settings();
			}
		}

		@Override
		public TransportProperties getLocalProperties() {
			try {
				return transportPropertyManager.getLocalProperties(id);
			} catch (DbException e) {
				return new TransportProperties();
			}
		}

		@Override
		public Collection<TransportProperties> getRemoteProperties() {
			try {
				Map<ContactId, TransportProperties> remote =
						transportPropertyManager.getRemoteProperties(id);
				return remote.values();
			} catch (DbException e) {
				return emptyList();
			}
		}

		@Override
		public void mergeSettings(Settings s) {
			PluginManagerImpl.this.mergeSettings(s, id.getString());
		}

		@Override
		public void mergeLocalProperties(TransportProperties p) {
			try {
				transportPropertyManager.mergeLocalProperties(id, p);
			} catch (DbException e) {
			}
		}

		@Override
		public void pluginStateChanged(State newState) {
			synchronized (stateLock) {
				if (newState != state) {
					State oldState = state;
					state = newState;
					eventBus.broadcast(new TransportStateEvent(id, newState));
					if (newState == ACTIVE) {
						eventBus.broadcast(new TransportActiveEvent(id));
					} else if (oldState == ACTIVE) {
						eventBus.broadcast(new TransportInactiveEvent(id));
					}
				} else if (newState == DISABLED) {
					eventBus.broadcast(new TransportStateEvent(id, newState));
				}
			}
		}

		@Override
		public void handleConnection(DuplexTransportConnection d) {
			connectionManager.manageIncomingConnection(id, d);
		}

		@Override
		public void handleReader(TransportConnectionReader r) {
			connectionManager.manageIncomingConnection(id, r);
		}

		@Override
		public void handleWriter(TransportConnectionWriter w) {
			throw new UnsupportedOperationException();
		}
	}
}
