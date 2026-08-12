package org.zerionproject.core.plugin;

import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.BluetoothConstants;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.LanTcpConstants;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
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
class PluginManagerImpl implements PluginManager, Service, EventListener {
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
	private final Object restartLock = new Object();
	private volatile Boolean offlineModeCache;

	private static final String OFFLINE_NS = "org.zerionproject.mode";
	private static final String OFFLINE_KEY = "offline";

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
		eventBus.addListener(this);
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
		boolean offline = isOfflineMode();
		for (DuplexPluginFactory f : pluginConfig.getDuplexFactories()) {
			TransportId t = f.getId();
			if (offline && !t.equals(LanTcpConstants.ID) &&
					!t.equals(BluetoothConstants.ID)) continue;
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
		eventBus.removeListener(this);
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

	@Override
	public boolean isOfflineMode() {
		Boolean cached = offlineModeCache;
		if (cached != null) return cached;
		try {
			boolean loaded = settingsManager.getSettings(OFFLINE_NS)
					.getBoolean(OFFLINE_KEY, false);
			offlineModeCache = loaded;
			return loaded;
		} catch (DbException e) {
			return false;
		}
	}

	@Override
	public void setOfflineMode(boolean offline) {
		offlineModeCache = offline;
		ioExecutor.execute(() -> {
			Settings s = new Settings();
			s.putBoolean(OFFLINE_KEY, offline);
			mergeSettings(s, OFFLINE_NS);
			if (offline) stopAllDuplex();
			else startAllDuplex();
		});
	}

	private void stopAllDuplex() {
		synchronized (restartLock) {
			for (DuplexPluginFactory f : pluginConfig.getDuplexFactories()) {
				if (f.getId().equals(LanTcpConstants.ID)) continue;
				if (f.getId().equals(BluetoothConstants.ID)) continue;
				Plugin old = plugins.remove(f.getId());
				if (old instanceof DuplexPlugin) {
					duplexPlugins.remove(old);
				}
				if (old != null) {
					try {
						old.stop();
					} catch (PluginException ex) {
					}
				}
			}
		}
	}

	private void startAllDuplex() {
		for (DuplexPluginFactory f : pluginConfig.getDuplexFactories()) {
			// LAN + Bluetooth are kept running in offline mode (see
			// stopAllDuplex), so don't tear them down and rebuild them when
			// coming back online — that briefly kills the BLE pairing/mesh
			// transport for no reason.
			if (f.getId().equals(LanTcpConstants.ID)) continue;
			if (f.getId().equals(BluetoothConstants.ID)) continue;
			restartPlugin(f.getId());
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (I2pConstants.ID.getString().equals(s.getNamespace())) {
				restartPlugin(I2pConstants.ID);
			}
		}
	}

	private void restartPlugin(TransportId t) {
		if (isOfflineMode()) return;
		DuplexPluginFactory factory = null;
		for (DuplexPluginFactory f : pluginConfig.getDuplexFactories()) {
			if (f.getId().equals(t)) {
				factory = f;
				break;
			}
		}
		if (factory == null) return;
		DuplexPluginFactory f = factory;
		wakefulIoExecutor.execute(() -> {
			synchronized (restartLock) {
				Plugin old = plugins.remove(t);
				if (old instanceof DuplexPlugin) {
					duplexPlugins.remove(old);
				}
				if (old != null) {
					try {
						old.stop();
					} catch (PluginException ex) {
					}
				}
				if (isOfflineMode()) return;
				DuplexPlugin fresh = f.createPlugin(new Callback(t));
				if (fresh == null) return;
				plugins.put(t, fresh);
				duplexPlugins.add(fresh);
				CountDownLatch latch = new CountDownLatch(1);
				startLatches.put(t, latch);
				try {
					fresh.start();
				} catch (PluginException ex) {
				} finally {
					latch.countDown();
				}
			}
		});
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
