package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.UserMessageMarker;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.HashMap;
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
	static final int MAX_TRAFFIC_SENDS = 3;

	private final LongSupplier clock;
	private final Map<MessageId, Long> added = new LinkedHashMap<>();
	private final Set<MessageId> unannounced = new HashSet<>();
	private final Map<MessageId, long[]> traffic = new LinkedHashMap<>();
	private final Map<ContactId, Set<MessageId>> wanted = new HashMap<>();

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
		traffic.put(id, new long[] {now, 0});
		if (traffic.size() > MAX_TRAFFIC_ENTRIES) {
			Iterator<MessageId> it = traffic.keySet().iterator();
			MessageId oldest = it.next();
			it.remove();
			dropWanted(oldest);
		}
	}

	private void dropWanted(MessageId id) {
		Iterator<Set<MessageId>> it = wanted.values().iterator();
		while (it.hasNext()) {
			Set<MessageId> ids = it.next();
			if (ids.remove(id) && ids.isEmpty()) it.remove();
		}
	}

	public synchronized boolean isUserTraffic(MessageId id) {
		expire(clock.getAsLong());
		return traffic.containsKey(id);
	}

	public synchronized boolean takeUserTraffic(ContactId c, MessageId id) {
		expire(clock.getAsLong());
		forgetWanted(c, id);
		long[] noted = traffic.get(id);
		if (noted == null) return false;
		if (++noted[1] >= MAX_TRAFFIC_SENDS) {
			traffic.remove(id);
			dropWanted(id);
		}
		return true;
	}

	public synchronized void noteRequested(ContactId c,
			Collection<MessageId> requested) {
		expire(clock.getAsLong());
		for (MessageId id : requested) {
			if (!traffic.containsKey(id)) continue;
			Set<MessageId> ids = wanted.get(c);
			if (ids == null) {
				ids = new HashSet<>();
				wanted.put(c, ids);
			}
			ids.add(id);
		}
	}

	public synchronized void noteAcked(Collection<MessageId> acked) {
		for (MessageId id : acked) {
			if (traffic.remove(id) != null) dropWanted(id);
		}
	}

	public synchronized boolean hasWanted(ContactId c) {
		expire(clock.getAsLong());
		return wanted.containsKey(c);
	}

	public synchronized void forgetWanted(ContactId c) {
		wanted.remove(c);
	}

	public synchronized void forgetWanted(ContactId c, MessageId id) {
		Set<MessageId> ids = wanted.get(c);
		if (ids != null && ids.remove(id) && ids.isEmpty()) wanted.remove(c);
	}

	public synchronized boolean hasUnannounced() {
		expire(clock.getAsLong());
		return !unannounced.isEmpty();
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
		Iterator<Map.Entry<MessageId, long[]>> parts =
				traffic.entrySet().iterator();
		while (parts.hasNext()) {
			Map.Entry<MessageId, long[]> e = parts.next();
			if (now - e.getValue()[0] <= FRESH_MESSAGE_MS) break;
			parts.remove();
			dropWanted(e.getKey());
		}
	}
}
