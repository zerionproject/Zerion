package org.zerionproject.transport;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.network.event.NetworkStatusEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.system.TaskScheduler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static java.lang.Boolean.TRUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@ThreadSafe
@NotNullByDefault
public class ZtpPoller implements EventListener {

	private static final long REPOLL_INTERVAL_MS = 5_000L;
	private static final long MIN_BACKOFF_MS = 5_000L;
	private static final long MAX_BACKOFF_MS = 60_000L;
	private static final long MIN_CONNECTED_MS = 10_000L;
	private static final int FAST_DIAL_BURST = 3;
	private static final long MIN_RESTART_BACKOFF_MS = 60_000L;
	private static final long MAX_RESTART_BACKOFF_MS = 10 * 60_000L;
	static final int REFRESH_AFTER_FAILURES = 2;
	static final long RECENT_CONNECTION_MS = 30 * 60_000L;
	static final long MIN_REFRESH_INTERVAL_MS = 2 * 60_000L;

	private final Executor ioExecutor;
	private final TaskScheduler taskScheduler;
	private final ContactManager contactManager;
	private final TransportPropertyManager transportPropertyManager;
	private final EventBus eventBus;
	private final OverlayTransport transport;

	private final Set<Integer> connecting = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Long> nextDialAt = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> failStreak = new ConcurrentHashMap<>();
	private final Map<Integer, Long> lastConnectedAt = new ConcurrentHashMap<>();
	private volatile long lastRefreshAt = 0;
	volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
	private final AtomicLong backoffEpoch = new AtomicLong();
	private volatile long nextRestartAt = 0;
	private volatile long restartBackoffMs = MIN_RESTART_BACKOFF_MS;
	@Nullable
	private volatile Boolean lastReportedConnected = null;
	private final Random backoffJitter = new Random();
	private volatile boolean running = false;
	@Nullable
	private volatile Cancellable repollTask;
	@Nullable
	private final javax.inject.Provider<org.zerionproject.core.plugin.tor
			.B4OnionRotation> rotation;

	public ZtpPoller(Executor ioExecutor,
			TaskScheduler taskScheduler, ContactManager contactManager,
			TransportPropertyManager transportPropertyManager, EventBus eventBus,
			OverlayTransport transport) {
		this(ioExecutor, taskScheduler, contactManager,
				transportPropertyManager, eventBus, transport, null);
	}

	public ZtpPoller(Executor ioExecutor,
			TaskScheduler taskScheduler, ContactManager contactManager,
			TransportPropertyManager transportPropertyManager, EventBus eventBus,
			OverlayTransport transport,
			@Nullable javax.inject.Provider<org.zerionproject.core.plugin.tor
					.B4OnionRotation> rotation) {
		this.ioExecutor = ioExecutor;
		this.taskScheduler = taskScheduler;
		this.contactManager = contactManager;
		this.transportPropertyManager = transportPropertyManager;
		this.eventBus = eventBus;
		this.transport = transport;
		this.rotation = rotation;
	}

	public void start() {
		running = true;
		eventBus.addListener(this);
		scheduleRepoll();
	}

	public void stop() {
		running = false;
		eventBus.removeListener(this);
		Cancellable c = repollTask;
		if (c != null) c.cancel();
	}

	private void scheduleRepoll() {
		if (!running) return;
		repollTask = taskScheduler.schedule(this::pollAll, ioExecutor,
				REPOLL_INTERVAL_MS, MILLISECONDS);
	}

	public void dialNow(int contactId) {
		connect(contactId, true);
	}

	public void pollNow() {
		if (!running) return;
		ioExecutor.execute(() -> {
			if (!running) return;
			clearAllBackoff();
			nextRestartAt = 0;
			restartBackoffMs = MIN_RESTART_BACKOFF_MS;
			try {
				for (Contact c : contactManager.getContacts()) {
					connect(c, false);
				}
			} catch (DbException e) {
			}
		});
	}

	private void pollAll() {
		if (!running) return;
		if (transport.isNetworkDegraded()) {
			long now = System.currentTimeMillis();
			if (now >= nextRestartAt) {
				nextRestartAt = now + restartBackoffMs;
				restartBackoffMs =
						Math.min(restartBackoffMs * 2, MAX_RESTART_BACKOFF_MS);
				transport.restartNetwork();
			}
		}
		try {
			for (Contact c : contactManager.getContacts()) {
				connect(c, false);
			}
		} catch (DbException e) {
		}
		scheduleRepoll();
	}

	private boolean recordConnectivity(boolean connected) {
		Boolean previous = lastReportedConnected;
		lastReportedConnected = connected;
		return previous == null || previous != connected;
	}

	private void clearAllBackoff() {
		backoffEpoch.incrementAndGet();
		nextDialAt.clear();
		failStreak.clear();
	}

	private void connect(int contactId, boolean urgent) {
		if (!running) return;
		Contact c;
		try {
			c = contactManager.getContact(new ContactId(contactId));
		} catch (DbException | RuntimeException e) {
			return;
		}
		connect(c, urgent);
	}

	private void connect(Contact contact, boolean urgent) {
		if (!running) return;
		int contactId = contact.getId().getInt();
		if (!isDesignatedDialer(contact)) return;
		if (!urgent) {
			Long next = nextDialAt.get(contactId);
			if (next != null && System.currentTimeMillis() < next) {
				return;
			}
		}
		if (connecting.contains(contactId)) return;
		long epoch = backoffEpoch.get();
		ioExecutor.execute(() -> {
			if (!running) return;
			if (!connecting.add(contactId)) return;
			boolean dialed = false;
			long sessionMs = OverlayTransport.DIAL_NOT_CONNECTED;
			String address = null;
			try {
				address = getPeerAddress(contactId);
				if (address == null) return;
				dialed = true;
				boolean fast = failStreak.getOrDefault(contactId, 0)
						< FAST_DIAL_BURST;
				sessionMs = transport.dial(contactId, address, fast);
			} catch (Exception e) {
			} finally {
				connecting.remove(contactId);
				if (dialed) {
					recordDialOutcome(contactId, sessionMs, epoch);
					reportPendingOnionOutcome(contact.getId(), address,
							sessionMs >= MIN_CONNECTED_MS);
				}
			}
		});
	}

	private void reportPendingOnionOutcome(ContactId cid,
			@Nullable String address, boolean connected) {
		javax.inject.Provider<org.zerionproject.core.plugin.tor
				.B4OnionRotation> r = rotation;
		if (r == null || address == null) return;
		try {
			org.zerionproject.core.plugin.tor.B4OnionRotation b4 = r.get();
			String pending = b4.getPendingOnionForContact(cid);
			if (pending == null || !pending.equals(address)) return;
			if (connected) b4.onSuccessfulConnect(cid, address);
			else b4.onPendingDialFailed(cid);
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void recordDialOutcome(int contactId, long sessionMs, long epoch) {
		boolean connected = sessionMs >= MIN_CONNECTED_MS;
		long now = clock.getAsLong();
		if (connected) {
			failStreak.remove(contactId);
			nextDialAt.remove(contactId);
			lastConnectedAt.put(contactId, now);
		} else if (backoffEpoch.get() == epoch) {
			int streak = failStreak.merge(contactId, 1, Integer::sum);
			if (streak >= REFRESH_AFTER_FAILURES
					&& refreshPeerDescriptors(contactId, now)) {
				nextDialAt.remove(contactId);
				return;
			}
			long shift = Math.min(streak - 1, 6);
			long backoff = Math.min(MIN_BACKOFF_MS << shift, MAX_BACKOFF_MS);
			long jitter = (long) (backoff * 0.2 * backoffJitter.nextDouble());
			nextDialAt.put(contactId, now + backoff + jitter);
		}
	}

	private boolean refreshPeerDescriptors(int contactId, long now) {
		Long last = lastConnectedAt.get(contactId);
		if (last == null || now - last > RECENT_CONNECTION_MS) return false;
		synchronized (this) {
			if (now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) return false;
			lastRefreshAt = now;
		}
		transport.refreshPeerDescriptors();
		return true;
	}

	static boolean isDesignatedDialer(Contact contact) {
		byte[] ours = contact.getLocalAuthorId().getBytes();
		byte[] theirs = contact.getAuthor().getId().getBytes();
		return org.zerionproject.core.api.Bytes.compare(ours, theirs) < 0;
	}

	@Nullable
	private String getPeerAddress(int contactId) {
		try {
			TransportProperties props =
					transportPropertyManager.getRemoteProperties(
							new ContactId(contactId),
							transport.getTransportId());
			String address = props.get(transport.getAddressPropertyKey());
			if (address == null || !transport.isValidAddress(address)) {
				return null;
			}
			return address;
		} catch (DbException e) {
			return null;
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (!running) return;
		if (e instanceof ContactAddedEvent) {
			connect(((ContactAddedEvent) e).getContactId().getInt(), true);
		} else if (e instanceof MessageToAckEvent) {
			connect(((MessageToAckEvent) e).getContactId().getInt(), true);
		} else if (e instanceof MessageSharedEvent) {
			Map<ContactId, Boolean> visibility =
					((MessageSharedEvent) e).getGroupVisibility();
			for (Map.Entry<ContactId, Boolean> entry : visibility.entrySet()) {
				if (entry.getValue() == TRUE) {
					connect(entry.getKey().getInt(), true);
				}
			}
		} else if (e instanceof NetworkStatusEvent) {
			boolean connected =
					((NetworkStatusEvent) e).getStatus().isConnected();
			if (connected && recordConnectivity(true)) clearAllBackoff();
			else if (!connected) recordConnectivity(false);
			ioExecutor.execute(() -> transport.setNetworkEnabled(connected));
		}
	}
}
