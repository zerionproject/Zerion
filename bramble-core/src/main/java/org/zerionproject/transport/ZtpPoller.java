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

	private final Executor ioExecutor;
	private final TaskScheduler taskScheduler;
	private final ContactManager contactManager;
	private final TransportPropertyManager transportPropertyManager;
	private final EventBus eventBus;
	private final OverlayTransport transport;

	private final Set<Integer> connecting = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Long> nextDialAt = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> failStreak = new ConcurrentHashMap<>();
	private final AtomicLong backoffEpoch = new AtomicLong();
	private final Random backoffJitter = new Random();
	private volatile boolean running = false;
	@Nullable
	private volatile String ourAddress;
	@Nullable
	private volatile Cancellable repollTask;

	public ZtpPoller(Executor ioExecutor,
			TaskScheduler taskScheduler, ContactManager contactManager,
			TransportPropertyManager transportPropertyManager, EventBus eventBus,
			OverlayTransport transport) {
		this.ioExecutor = ioExecutor;
		this.taskScheduler = taskScheduler;
		this.contactManager = contactManager;
		this.transportPropertyManager = transportPropertyManager;
		this.eventBus = eventBus;
		this.transport = transport;
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

	public void pollNow() {
		if (!running) return;
		ioExecutor.execute(() -> {
			if (!running) return;
			clearAllBackoff();
			refreshOurAddress();
			try {
				for (Contact c : contactManager.getContacts()) {
					connect(c.getId().getInt(), false);
				}
			} catch (DbException e) {
				// retried next sweep
			}
		});
	}

	private void pollAll() {
		if (!running) return;
		refreshOurAddress();
		try {
			for (Contact c : contactManager.getContacts()) {
				connect(c.getId().getInt(), false);
			}
		} catch (DbException e) {
			// retried next sweep
		}
		scheduleRepoll();
	}

	private void clearAllBackoff() {
		backoffEpoch.incrementAndGet();
		nextDialAt.clear();
		failStreak.clear();
	}

	private void refreshOurAddress() {
		try {
			String a = transportPropertyManager
					.getLocalProperties(transport.getTransportId())
					.get(transport.getAddressPropertyKey());
			if (a != null) ourAddress = a;
		} catch (DbException e) {
			// keep previous value
		}
	}

	private void connect(int contactId, boolean urgent) {
		if (!running) return;
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
			try {
				String address = getPeerAddress(contactId);
				if (address == null) return;
				if (!isDesignatedDialer(address)) return;
				dialed = true;
				boolean fast = failStreak.getOrDefault(contactId, 0)
						< FAST_DIAL_BURST;
				sessionMs = transport.dial(contactId, address, fast);
			} catch (Exception e) {
			} finally {
				connecting.remove(contactId);
				if (dialed) recordDialOutcome(contactId, sessionMs, epoch);
			}
		});
	}

	private void recordDialOutcome(int contactId, long sessionMs, long epoch) {
		boolean connected = sessionMs >= MIN_CONNECTED_MS;
		if (connected) {
			failStreak.remove(contactId);
			nextDialAt.remove(contactId);
		} else if (backoffEpoch.get() == epoch) {
			int streak = failStreak.merge(contactId, 1, Integer::sum);
			long shift = Math.min(streak - 1, 6);
			long backoff = Math.min(MIN_BACKOFF_MS << shift, MAX_BACKOFF_MS);
			long jitter = (long) (backoff * 0.2 * backoffJitter.nextDouble());
			nextDialAt.put(contactId,
					System.currentTimeMillis() + backoff + jitter);
		}
	}

	private boolean isDesignatedDialer(String peerAddress) {
		String mine = ourAddress;
		return mine == null || mine.compareTo(peerAddress) < 0;
	}

	@Nullable
	private String getPeerAddress(int contactId) {
		try {
			TransportProperties props =
					transportPropertyManager.getRemoteProperties(
							new ContactId(contactId),
							transport.getTransportId());
			return props.get(transport.getAddressPropertyKey());
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
			if (connected) clearAllBackoff();
			ioExecutor.execute(() -> transport.setNetworkEnabled(connected));
		}
	}
}
