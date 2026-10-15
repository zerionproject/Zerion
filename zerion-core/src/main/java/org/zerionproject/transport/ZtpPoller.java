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
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
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
	static final long MIN_CONNECTED_MS = 10_000L;
	private static final int FAST_DIAL_BURST = 3;
	private static final long MIN_RESTART_BACKOFF_MS = 60_000L;
	private static final long MAX_RESTART_BACKOFF_MS = 10 * 60_000L;
	static final int REFRESH_AFTER_FAILURES = 2;
	static final long RECENT_CONNECTION_MS = 30 * 60_000L;
	static final long MIN_REFRESH_INTERVAL_MS = 2 * 60_000L;
	static final long REACH_OUT_DELAY_MS = 15_000L;
	static final long REACH_OUT_WINDOW_MS = 10 * 60_000L;
	static final long SLOW_REACH_OUT_MS = 5 * 60_000L;
	static final long REACH_OUT_JITTER_MS = 5_000L;
	static final long SLOW_REACH_OUT_JITTER_MS = 60_000L;

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
	private final Map<Integer, ReachOut> reachOuts = new ConcurrentHashMap<>();
	private final Map<Integer, Long> slowReachOutAt = new ConcurrentHashMap<>();
	private final Map<Integer, Long> connectedSince = new ConcurrentHashMap<>();
	private volatile long arrivedUntil = 0;
	private volatile boolean arrivedRefreshed = false;
	private volatile java.util.function.IntPredicate connected = c -> false;
	private volatile long lastRefreshAt = 0;
	volatile java.util.function.LongSupplier clock =
			() -> System.nanoTime() / 1_000_000L;
	volatile java.util.function.LongSupplier wallClock =
			System::currentTimeMillis;
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

	public void setConnectedCheck(java.util.function.IntPredicate connected) {
		this.connected = connected;
	}

	public void dialNow(int contactId) {
		if (!running) return;
		Contact c;
		try {
			c = contactManager.getContact(new ContactId(contactId));
		} catch (DbException | RuntimeException e) {
			return;
		}
		connect(c, true, false, false, true);
	}

	public void dialFromEitherSide(int contactId) {
		connect(contactId, true, true);
	}

	public void dialOpenFromEitherSide(int contactId) {
		connect(contactId, true, true, true);
	}

	public void pollNow() {
		if (!running) return;
		ioExecutor.execute(() -> {
			if (!running) return;
			clearAllBackoff();
			nextRestartAt = 0;
			restartBackoffMs = MIN_RESTART_BACKOFF_MS;
			long now = clock.getAsLong();
			arrivedUntil = now + REACH_OUT_WINDOW_MS;
			arrivedRefreshed = false;
			try {
				for (Contact c : contactManager.getContacts()) {
					if (!isDesignatedDialer(c)) {
						reachOut(c.getId().getInt(), now);
					}
					connect(c, false);
				}
			} catch (DbException e) {
			}
		});
	}

	public ZtpDialDiagnostics diagnostics(int contactId) {
		Long next = nextDialAt.get(contactId);
		return new ZtpDialDiagnostics(connecting.contains(contactId),
				next == null ? 0L
						: wallClock.getAsLong() + next - clock.getAsLong());
	}

	private void pollAll() {
		if (!running) return;
		if (transport.isNetworkDegraded()) {
			long now = clock.getAsLong();
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
				reachOutIfDue(c);
			}
		} catch (DbException e) {
		}
		scheduleRepoll();
	}

	private static final class ReachOut {
		private final long from;
		private final long until;

		private ReachOut(long from, long until) {
			this.from = from;
			this.until = until;
		}
	}

	private void reachOut(int contactId, long now) {
		slowReachOutAt.remove(contactId);
		long jitter = (long) (REACH_OUT_JITTER_MS * backoffJitter.nextDouble());
		reachOuts.put(contactId, new ReachOut(now + REACH_OUT_DELAY_MS + jitter,
				now + REACH_OUT_WINDOW_MS));
	}

	private long nextSlowReachOut(long now) {
		return now + SLOW_REACH_OUT_MS
				+ (long) (SLOW_REACH_OUT_JITTER_MS * backoffJitter.nextDouble());
	}

	private void endReachOut(int contactId) {
		reachOuts.remove(contactId);
		slowReachOutAt.remove(contactId);
	}

	private void reachOutIfDue(Contact c) {
		if (isDesignatedDialer(c)) return;
		int contactId = c.getId().getInt();
		ReachOut r = reachOuts.get(contactId);
		if (connected.test(contactId)) {
			if (r != null) reachOuts.remove(contactId, r);
			slowReachOutAt.remove(contactId);
			return;
		}
		long now = clock.getAsLong();
		if (r != null && now < r.until) {
			if (now >= r.from) connect(c, false, true, false);
			return;
		}
		if (r != null) reachOuts.remove(contactId, r);
		Long next = slowReachOutAt.putIfAbsent(contactId, nextSlowReachOut(now));
		if (next == null || now < next) return;
		slowReachOutAt.put(contactId, nextSlowReachOut(now));
		connect(c, true, true, false);
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
		connect(contactId, urgent, false);
	}

	private void connect(int contactId, boolean urgent, boolean eitherSide) {
		connect(contactId, urgent, eitherSide, false);
	}

	private void connect(int contactId, boolean urgent, boolean eitherSide,
			boolean open) {
		if (!running) return;
		Contact c;
		try {
			c = contactManager.getContact(new ContactId(contactId));
		} catch (DbException | RuntimeException e) {
			return;
		}
		connect(c, urgent, eitherSide, open);
	}

	private void connect(Contact contact, boolean urgent) {
		connect(contact, urgent, false, false);
	}

	private void connect(Contact contact, boolean urgent, boolean eitherSide,
			boolean open) {
		connect(contact, urgent, eitherSide, open, false);
	}

	private void connect(Contact contact, boolean urgent, boolean eitherSide,
			boolean open, boolean whileConnected) {
		if (!running) return;
		int contactId = contact.getId().getInt();
		if (!eitherSide && !isDesignatedDialer(contact)) return;
		if (!eitherSide && !whileConnected && connected.test(contactId)) {
			return;
		}
		if (!urgent) {
			Long next = nextDialAt.get(contactId);
			if (next != null && clock.getAsLong() < next) {
				return;
			}
		}
		if (connecting.contains(contactId)) return;
		long epoch = backoffEpoch.get();
		ioExecutor.execute(() -> {
			if (!running) return;
			if (!connecting.add(contactId)) return;
			boolean dialed = false;
			OverlayTransport.DialResult result =
					OverlayTransport.DialResult.NOT_CONNECTED;
			String address = null;
			try {
				address = getPeerAddress(contactId, open);
				if (address == null) return;
				dialed = true;
				boolean fast = failStreak.getOrDefault(contactId, 0)
						< FAST_DIAL_BURST;
				result = transport.dial(contactId, address, fast, open);
			} catch (Exception e) {
			} finally {
				connecting.remove(contactId);
				if (dialed) {
					recordDialOutcome(contactId, result.getSessionMs(), epoch);
					reportPendingOnionOutcome(contact.getId(), address,
							result.isAuthenticated());
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
			if (pending == null) return;
			if (pending.equals(address)) {
				if (connected) b4.onSuccessfulConnect(cid, address);
				else b4.onPendingDialFailed(cid);
			} else if (!connected) {
				b4.onFallbackDialFailed(cid);
			}
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
		boolean recent = last != null && now - last <= RECENT_CONNECTION_MS;
		boolean arrived = last == null && now < arrivedUntil
				&& !arrivedRefreshed;
		if (!recent && !arrived) return false;
		synchronized (this) {
			if (now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) return false;
			lastRefreshAt = now;
			if (!recent) arrivedRefreshed = true;
		}
		transport.refreshPeerDescriptors();
		return true;
	}

	private void contactDisconnected(int contactId) {
		if (!running) return;
		Long since = connectedSince.remove(contactId);
		if (since != null && clock.getAsLong() - since < MIN_CONNECTED_MS) {
			return;
		}
		Contact c;
		try {
			c = contactManager.getContact(new ContactId(contactId));
		} catch (DbException | RuntimeException e) {
			return;
		}
		failStreak.remove(contactId);
		nextDialAt.remove(contactId);
		if (isDesignatedDialer(c)) connect(c, true, false, false);
		else reachOut(contactId, clock.getAsLong());
	}

	public static boolean isDesignatedDialer(Contact contact) {
		byte[] ours = contact.getLocalAuthorId().getBytes();
		byte[] theirs = contact.getAuthor().getId().getBytes();
		return org.zerionproject.core.api.Bytes.compare(ours, theirs) < 0;
	}

	@Nullable
	private String getPeerAddress(int contactId, boolean open) {
		try {
			ContactId c = new ContactId(contactId);
			TransportProperties props = open
					? transportPropertyManager.getOpenRemoteProperties(c,
							transport.getTransportId())
					: transportPropertyManager.getRemoteProperties(c,
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
		} else if (e instanceof ContactConnectedEvent) {
			int id = ((ContactConnectedEvent) e).getContactId().getInt();
			long now = clock.getAsLong();
			connectedSince.put(id, now);
			lastConnectedAt.put(id, now);
			endReachOut(id);
		} else if (e instanceof ContactDisconnectedEvent) {
			contactDisconnected(
					((ContactDisconnectedEvent) e).getContactId().getInt());
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
