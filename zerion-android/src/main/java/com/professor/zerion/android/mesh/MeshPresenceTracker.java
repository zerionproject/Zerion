package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.EventBus;
import com.professor.zerion.android.mesh.event.MeshPresenceChangedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class MeshPresenceTracker {

	static final long TTL_MS = 150_000;
	private static final long SWEEP_MS = 30_000;

	private final EventBus eventBus;
	private final Map<Integer, Long> lastSeen = new ConcurrentHashMap<>();

	@Inject
	MeshPresenceTracker(EventBus eventBus) {
		this.eventBus = eventBus;
		ScheduledExecutorService sweeper =
				Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "MeshPresence");
					t.setDaemon(true);
					return t;
				});
		sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_MS, SWEEP_MS,
				TimeUnit.MILLISECONDS);
	}

	public void markPresent(ContactId contactId) {
		Long prev = lastSeen.put(contactId.getInt(), now());
		if (prev == null || now() - prev > TTL_MS) {
			eventBus.broadcast(new MeshPresenceChangedEvent(contactId, true));
		}
	}

	public boolean isPresent(ContactId contactId) {
		Long seen = lastSeen.get(contactId.getInt());
		return seen != null && now() - seen <= TTL_MS;
	}

	private void sweep() {
		long cutoff = now() - TTL_MS;
		for (Map.Entry<Integer, Long> e : lastSeen.entrySet()) {
			if (e.getValue() < cutoff
					&& lastSeen.remove(e.getKey(), e.getValue())) {
				eventBus.broadcast(new MeshPresenceChangedEvent(
						new ContactId(e.getKey()), false));
			}
		}
	}

	private static long now() {
		return System.currentTimeMillis();
	}
}
