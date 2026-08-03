package org.zerionproject.core.connection;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.ConnectionClosedEvent;
import org.zerionproject.core.api.plugin.event.ConnectionOpenedEvent;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.sync.event.CloseSyncConnectionsEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionClosedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionOpenedEvent;
import org.zerionproject.core.api.sync.Priority;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.Collections.emptyList;

@ThreadSafe
@NotNullByDefault
class ConnectionRegistryImpl implements ConnectionRegistry, EventListener {

	private final EventBus eventBus;
	private final Map<TransportId, List<TransportId>> transportPrefs;

	private final Object lock = new Object();
	@GuardedBy("lock")
	private final Map<ContactId, List<ConnectionRecord>> contactConnections;
	@GuardedBy("lock")
	private final Set<PendingContactId> connectedPendingContacts;

	@Inject
	ConnectionRegistryImpl(EventBus eventBus, PluginConfig pluginConfig) {
		this.eventBus = eventBus;
		transportPrefs = pluginConfig.getTransportPreferences();
		contactConnections = new HashMap<>();
		connectedPendingContacts = new HashSet<>();
		eventBus.addListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof TransportInactiveEvent) {
			closeConnections(((TransportInactiveEvent) e).getTransportId());
		} else if (e instanceof CloseSyncConnectionsEvent) {
			closeConnections(((CloseSyncConnectionsEvent) e).getTransportId());
		}
	}

	private void closeConnections(TransportId t) {
		List<InterruptibleConnection> toClose = new ArrayList<>();
		synchronized (lock) {
			for (List<ConnectionRecord> recs : contactConnections.values()) {
				for (ConnectionRecord rec : recs) {
					if (rec.transportId.equals(t)) toClose.add(rec.conn);
				}
			}
		}
		for (InterruptibleConnection conn : toClose) conn.forceClose();
	}

	@Override
	public void registerIncomingConnection(ContactId c, TransportId t,
			InterruptibleConnection conn) {
		registerConnection(c, t, conn, true);
	}

	@Override
	public void registerOutgoingConnection(ContactId c, TransportId t,
			InterruptibleConnection conn, Priority priority) {
		registerConnection(c, t, conn, false);
		setPriority(c, t, conn, priority);
	}

	private void registerConnection(ContactId c, TransportId t,
			InterruptibleConnection conn, boolean incoming) {
		boolean firstConnection;
		synchronized (lock) {
			List<ConnectionRecord> recs = contactConnections.get(c);
			if (recs == null) {
				recs = new ArrayList<>();
				contactConnections.put(c, recs);
			}
			firstConnection = recs.isEmpty();
			recs.add(new ConnectionRecord(t, conn));
		}
		eventBus.broadcast(new ConnectionOpenedEvent(c, t, incoming));
		if (firstConnection) {
			eventBus.broadcast(new ContactConnectedEvent(c));
		}
	}

	@Override
	public void setPriority(ContactId c, TransportId t,
			InterruptibleConnection conn, Priority priority) {
		List<InterruptibleConnection> toInterrupt;
		boolean interruptNewConnection = false;
		synchronized (lock) {
			List<ConnectionRecord> recs = contactConnections.get(c);
			if (recs == null) throw new IllegalArgumentException();
			toInterrupt = new ArrayList<>(recs.size());
			for (ConnectionRecord rec : recs) {
				if (rec.conn == conn) {
					rec.priority = priority;
				} else if (rec.priority != null) {
					int compare = compareConnections(t, priority,
							rec.transportId, rec.priority);
					if (compare == -1) {
						interruptNewConnection = true;
					} else if (compare == 1 && !rec.interrupted) {
						toInterrupt.add(rec.conn);
						rec.interrupted = true;
					}
				}
			}
		}
		if (interruptNewConnection) {
			conn.interruptOutgoingSession();
		}
		for (InterruptibleConnection old : toInterrupt) {
			old.interruptOutgoingSession();
		}
	}

	private int compareConnections(TransportId tA, Priority pA, TransportId tB,
			Priority pB) {
		if (getBetterTransports(tA).contains(tB)) return -1;
		if (getBetterTransports(tB).contains(tA)) return 1;
		return tA.equals(tB) ? Bytes.compare(pA.getNonce(), pB.getNonce()) : 0;
	}

	private List<TransportId> getBetterTransports(TransportId t) {
		List<TransportId> better = transportPrefs.get(t);
		return better == null ? emptyList() : better;
	}

	@Override
	public void unregisterConnection(ContactId c, TransportId t,
			InterruptibleConnection conn, boolean incoming, boolean exception) {
		boolean lastConnection;
		synchronized (lock) {
			List<ConnectionRecord> recs = contactConnections.get(c);
			if (recs == null || !recs.remove(new ConnectionRecord(t, conn)))
				throw new IllegalArgumentException();
			lastConnection = recs.isEmpty();
		}
		eventBus.broadcast(
				new ConnectionClosedEvent(c, t, incoming, exception));
		if (lastConnection) {
			eventBus.broadcast(new ContactDisconnectedEvent(c));
		}
	}

	@Override
	public Collection<ContactId> getConnectedContacts(TransportId t) {
		synchronized (lock) {
			List<ContactId> contactIds = new ArrayList<>();
			for (Entry<ContactId, List<ConnectionRecord>> e :
					contactConnections.entrySet()) {
				for (ConnectionRecord rec : e.getValue()) {
					if (rec.transportId.equals(t)) {
						contactIds.add(e.getKey());
						break;
					}
				}
			}
			return contactIds;
		}
	}

	@Override
	public Collection<ContactId> getConnectedOrBetterContacts(TransportId t) {
		synchronized (lock) {
			List<TransportId> better = getBetterTransports(t);
			List<ContactId> contactIds = new ArrayList<>();
			for (Entry<ContactId, List<ConnectionRecord>> e :
					contactConnections.entrySet()) {
				for (ConnectionRecord rec : e.getValue()) {
					if (rec.transportId.equals(t) ||
							better.contains(rec.transportId)) {
						contactIds.add(e.getKey());
						break;
					}
				}
			}
			return contactIds;
		}
	}

	@Override
	public boolean isConnected(ContactId c, TransportId t) {
		synchronized (lock) {
			List<ConnectionRecord> recs = contactConnections.get(c);
			if (recs == null) return false;
			for (ConnectionRecord rec : recs) {
				if (rec.transportId.equals(t)) return true;
			}
			return false;
		}
	}

	@Override
	public boolean isConnected(ContactId c) {
		synchronized (lock) {
			List<ConnectionRecord> recs = contactConnections.get(c);
			return recs != null && !recs.isEmpty();
		}
	}

	@Override
	public boolean registerConnection(PendingContactId p) {
		boolean added;
		synchronized (lock) {
			added = connectedPendingContacts.add(p);
		}
		if (added) eventBus.broadcast(new RendezvousConnectionOpenedEvent(p));
		return added;
	}

	@Override
	public void unregisterConnection(PendingContactId p, boolean success) {
		synchronized (lock) {
			if (!connectedPendingContacts.remove(p))
				throw new IllegalArgumentException();
		}
		eventBus.broadcast(new RendezvousConnectionClosedEvent(p, success));
	}

	private static class ConnectionRecord {

		private final TransportId transportId;
		private final InterruptibleConnection conn;
		@GuardedBy("lock")
		@Nullable
		private Priority priority = null;
		@GuardedBy("lock")
		private boolean interrupted = false;

		private ConnectionRecord(TransportId transportId,
				InterruptibleConnection conn) {
			this.transportId = transportId;
			this.conn = conn;
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof ConnectionRecord) {
				return conn == ((ConnectionRecord) o).conn;
			} else {
				return false;
			}
		}

		@Override
		public int hashCode() {
			return conn.hashCode();
		}
	}
}
