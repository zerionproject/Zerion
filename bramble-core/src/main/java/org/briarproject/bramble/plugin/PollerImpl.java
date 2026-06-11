package org.briarproject.bramble.plugin;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.connection.ConnectionManager;
import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.event.ContactAddedEvent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.plugin.ConnectionHandler;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.plugin.event.ConnectionClosedEvent;
import org.briarproject.bramble.api.plugin.event.ConnectionOpenedEvent;
import org.briarproject.bramble.api.plugin.event.TransportActiveEvent;
import org.briarproject.bramble.api.plugin.event.TransportInactiveEvent;
import org.briarproject.bramble.api.plugin.simplex.SimplexPlugin;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.sync.event.MessageSharedEvent;
import org.briarproject.bramble.api.sync.event.MessageToAckEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.bramble.api.system.Wakeful;
import org.briarproject.bramble.api.system.WakefulIoExecutor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
@ThreadSafe
@NotNullByDefault
class PollerImpl implements Poller, EventListener {

	private static final long DATA_CONNECT_DEBOUNCE_MS = 3000;

	private final Executor ioExecutor, wakefulIoExecutor;
	private final TaskScheduler scheduler;
	private final ConnectionManager connectionManager;
	private final ConnectionRegistry connectionRegistry;
	private final PluginManager pluginManager;
	private final TransportPropertyManager transportPropertyManager;
	private final SecureRandom random;
	private final Clock clock;
	private final Lock lock;
	@GuardedBy("lock")
	private final Map<TransportId, ScheduledPollTask> tasks;
	@GuardedBy("lock")
	private final Map<ContactId, Long> lastDataConnect = new HashMap<>();

	@Inject
	PollerImpl(@IoExecutor Executor ioExecutor,
			@WakefulIoExecutor Executor wakefulIoExecutor,
			TaskScheduler scheduler,
			ConnectionManager connectionManager,
			ConnectionRegistry connectionRegistry,
			PluginManager pluginManager,
			TransportPropertyManager transportPropertyManager,
			SecureRandom random,
			Clock clock) {
		this.ioExecutor = ioExecutor;
		this.wakefulIoExecutor = wakefulIoExecutor;
		this.scheduler = scheduler;
		this.connectionManager = connectionManager;
		this.connectionRegistry = connectionRegistry;
		this.pluginManager = pluginManager;
		this.transportPropertyManager = transportPropertyManager;
		this.random = random;
		this.clock = clock;
		lock = new ReentrantLock();
		tasks = new HashMap<>();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactAddedEvent) {
			ContactAddedEvent c = (ContactAddedEvent) e;
			connectToContact(c.getContactId());
		} else if (e instanceof ConnectionClosedEvent) {
			ConnectionClosedEvent c = (ConnectionClosedEvent) e;
			reschedule(c.getTransportId());
			if (!c.isIncoming() && c.isException()) {
				connectToContact(c.getContactId(), c.getTransportId());
			}
		} else if (e instanceof ConnectionOpenedEvent) {
			ConnectionOpenedEvent c = (ConnectionOpenedEvent) e;
			if (c.isIncoming()) {
				pollNow(c.getTransportId());
			} else {
				reschedule(c.getTransportId());
			}
		} else if (e instanceof TransportActiveEvent) {
			TransportActiveEvent t = (TransportActiveEvent) e;
			pollNow(t.getTransportId());
		} else if (e instanceof TransportInactiveEvent) {
			TransportInactiveEvent t = (TransportInactiveEvent) e;
			cancel(t.getTransportId());
		} else if (e instanceof MessageSharedEvent) {
			MessageSharedEvent m = (MessageSharedEvent) e;
			for (Entry<ContactId, Boolean> v :
					m.getGroupVisibility().entrySet()) {
				if (Boolean.TRUE.equals(v.getValue()))
					connectToContactOnData(v.getKey());
			}
		} else if (e instanceof MessageToAckEvent) {
			connectToContactOnData(((MessageToAckEvent) e).getContactId());
		}
	}

	private void connectToContactOnData(ContactId c) {
		long now = clock.currentTimeMillis();
		lock.lock();
		try {
			Long last = lastDataConnect.get(c);
			if (last != null && now - last < DATA_CONNECT_DEBOUNCE_MS) return;
			lastDataConnect.put(c, now);
		} finally {
			lock.unlock();
		}
		connectToContact(c);
	}

	private void connectToContact(ContactId c) {
		for (SimplexPlugin s : pluginManager.getSimplexPlugins())
			if (s.shouldPoll()) connectToContact(c, s);
		for (DuplexPlugin d : pluginManager.getDuplexPlugins())
			if (d.shouldPoll()) connectToContact(c, d);
	}

	private void connectToContact(ContactId c, TransportId t) {
		Plugin p = pluginManager.getPlugin(t);
		if (p instanceof SimplexPlugin && p.shouldPoll())
			connectToContact(c, (SimplexPlugin) p);
		else if (p instanceof DuplexPlugin && p.shouldPoll())
			connectToContact(c, (DuplexPlugin) p);
	}

	private void connectToContact(ContactId c, SimplexPlugin p) {
		wakefulIoExecutor.execute(() -> {
			TransportId t = p.getId();
			if (connectionRegistry.isConnected(c, t)) return;
			try {
				TransportProperties props =
						transportPropertyManager.getRemoteProperties(c, t);
				TransportConnectionWriter w = p.createWriter(props);
				if (w != null)
					connectionManager.manageOutgoingConnection(c, t, w);
			} catch (DbException e) {
			}
		});
	}

	private void connectToContact(ContactId c, DuplexPlugin p) {
		wakefulIoExecutor.execute(() -> {
			TransportId t = p.getId();
			if (connectionRegistry.isConnected(c, t)) return;
			try {
				TransportProperties props =
						transportPropertyManager.getRemoteProperties(c, t);
				DuplexTransportConnection d = p.createConnection(props);
				if (d != null)
					connectionManager.manageOutgoingConnection(c, t, d);
			} catch (DbException e) {
			}
		});
	}

	private void reschedule(TransportId t) {
		Plugin p = pluginManager.getPlugin(t);
		if (p != null && p.shouldPoll())
			schedule(p, jitter(p.getPollingInterval()));
	}

	private int jitter(int base) {
		if (base <= 0) return 0;
		double u = random.nextDouble();
		if (u <= 0d) u = 1e-12;
		long draw = Math.round(-base * Math.log(1d - u));
		long cap = (long) base * 3L;
		long bounded = Math.min(cap, Math.max(0L, draw));
		return (int) bounded;
	}

	private void pollNow(TransportId t) {
		Plugin p = pluginManager.getPlugin(t);
		if (p != null && p.shouldPoll()) schedule(p, 0);
	}

	private void schedule(Plugin p, int delay) {
		long due = clock.currentTimeMillis() + delay;
		TransportId t = p.getId();
		lock.lock();
		try {
			ScheduledPollTask scheduled = tasks.get(t);
			if (scheduled == null || due < scheduled.task.due) {
				if (scheduled != null) scheduled.cancellable.cancel();
				PollTask task = new PollTask(p, due);
				Cancellable cancellable = scheduler.schedule(task, ioExecutor,
						delay, MILLISECONDS);
				tasks.put(t, new ScheduledPollTask(task, cancellable));
			}
		} finally {
			lock.unlock();
		}
	}

	private void cancel(TransportId t) {
		lock.lock();
		try {
			ScheduledPollTask scheduled = tasks.remove(t);
			if (scheduled != null) scheduled.cancellable.cancel();
		} finally {
			lock.unlock();
		}
	}

	@IoExecutor
	private void poll(Plugin p) {
		TransportId t = p.getId();
		try {
			Map<ContactId, TransportProperties> remote =
					transportPropertyManager.getRemoteProperties(t);
			Collection<ContactId> connected =
					connectionRegistry.getConnectedOrBetterContacts(t);
			Collection<Pair<TransportProperties, ConnectionHandler>>
					properties = new ArrayList<>();
			for (Entry<ContactId, TransportProperties> e : remote.entrySet()) {
				ContactId c = e.getKey();
				if (!connected.contains(c))
					properties.add(new Pair<>(e.getValue(), new Handler(c, t)));
			}
			if (!properties.isEmpty()) p.poll(properties);
		} catch (DbException e) {
		}
	}

	private class ScheduledPollTask {

		private final PollTask task;
		private final Cancellable cancellable;

		private ScheduledPollTask(PollTask task, Cancellable cancellable) {
			this.task = task;
			this.cancellable = cancellable;
		}
	}

	private class PollTask implements Runnable {

		private final Plugin plugin;
		private final long due;

		private PollTask(Plugin plugin, long due) {
			this.plugin = plugin;
			this.due = due;
		}

		@Override
		@IoExecutor
		@Wakeful
		public void run() {
			lock.lock();
			try {
				TransportId t = plugin.getId();
				ScheduledPollTask scheduled = tasks.get(t);
				if (scheduled != null && scheduled.task != this)
					return;
				tasks.remove(t);
			} finally {
				lock.unlock();
			}
			int delay = jitter(plugin.getPollingInterval());
			schedule(plugin, delay);
			poll(plugin);
		}
	}

	private class Handler implements ConnectionHandler {

		private final ContactId contactId;
		private final TransportId transportId;

		private Handler(ContactId contactId, TransportId transportId) {
			this.contactId = contactId;
			this.transportId = transportId;
		}

		@Override
		public void handleConnection(DuplexTransportConnection c) {
			connectionManager.manageOutgoingConnection(contactId,
					transportId, c);
		}

		@Override
		public void handleReader(TransportConnectionReader r) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void handleWriter(TransportConnectionWriter w) {
			connectionManager.manageOutgoingConnection(contactId,
					transportId, w);
		}
	}
}
