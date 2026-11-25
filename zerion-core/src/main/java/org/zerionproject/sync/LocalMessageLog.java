package org.zerionproject.sync;

import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.UserMessageMarker;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

@ThreadSafe
@Singleton
@NotNullByDefault
public class LocalMessageLog implements UserMessageMarker {

	public static final long FRESH_MESSAGE_MS = 24 * 60 * 60_000L;
	private static final int MAX_ENTRIES = 1024;
	static final int MAX_TRAFFIC_ENTRIES = 16_384;

	private final LongSupplier clock;
	private final Map<MessageId, Long> added = new LinkedHashMap<>();
	private final Set<MessageId> unannounced = new HashSet<>();
	private final Map<MessageId, Long> traffic = new LinkedHashMap<>();

	@Inject
	public LocalMessageLog() {
		this(System::currentTimeMillis);
	}

	public LocalMessageLog(LongSupplier clock) {
		this.clock = clock;
	}

	@Override
	public synchronized void noteUserMessage(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		added.remove(id);
		added.put(id, now);
		unannounced.add(id);
		if (added.size() > MAX_ENTRIES) {
			Iterator<MessageId> it = added.keySet().iterator();
			unannounced.remove(it.next());
			it.remove();
		}
		noteTraffic(id, now);
	}

	@Override
	public synchronized void noteUserPart(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		noteTraffic(id, now);
	}

	private void noteTraffic(MessageId id, long now) {
		traffic.remove(id);
		traffic.put(id, now);
		if (traffic.size() > MAX_TRAFFIC_ENTRIES) {
			Iterator<MessageId> it = traffic.keySet().iterator();
			it.next();
			it.remove();
		}
	}

	public synchronized boolean isUserTraffic(MessageId id) {
		expire(clock.getAsLong());
		return traffic.containsKey(id);
	}

	public synchronized void noteSentInFull(MessageId id) {
		traffic.remove(id);
	}

	public synchronized void noteAcked(Collection<MessageId> acked) {
		for (MessageId id : acked) traffic.remove(id);
	}

	public synchronized boolean isUnannounced(MessageId id) {
		expire(clock.getAsLong());
		return unannounced.contains(id);
	}

	@Override
	public synchronized void noteLaterPart(MessageId id) {
		unannounced.remove(id);
	}

	public synchronized boolean isRecentLocal(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		return added.containsKey(id);
	}

	public synchronized boolean takeFirstOffer(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		return unannounced.remove(id);
	}

	public synchronized void restoreOffers(Collection<MessageId> ids) {
		expire(clock.getAsLong());
		for (MessageId id : ids) {
			if (added.containsKey(id)) unannounced.add(id);
		}
	}

	public synchronized boolean takeFirstSend(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		unannounced.remove(id);
		return added.remove(id) != null;
	}

	private void expire(long now) {
		Iterator<Map.Entry<MessageId, Long>> it = added.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<MessageId, Long> e = it.next();
			if (now - e.getValue() <= FRESH_MESSAGE_MS) break;
			unannounced.remove(e.getKey());
			it.remove();
		}
		Iterator<Long> parts = traffic.values().iterator();
		while (parts.hasNext()) {
			if (now - parts.next() <= FRESH_MESSAGE_MS) break;
			parts.remove();
		}
	}
}
