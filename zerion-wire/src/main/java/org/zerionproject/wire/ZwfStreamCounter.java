package org.zerionproject.wire;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.zerionproject.wire.ZwfConstants.DIRECTION_RECV;
import static org.zerionproject.wire.ZwfConstants.DIRECTION_SEND;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

public class ZwfStreamCounter {

	private final StreamCounterStore store;
	private final Object lock = new Object();
	private final Map<Long, Long> cache = new HashMap<>();
	private final Map<Integer, NavigableSet<Long>> recvSeen = new HashMap<>();
	private final Map<Integer, Long> startupRecvFloor = new HashMap<>();
	private final Map<Integer, Long> stateGeneration = new HashMap<>();
	private final ConcurrentMap<Integer, AtomicLong> generations =
			new ConcurrentHashMap<>();

	public ZwfStreamCounter(StreamCounterStore store) {
		if (store == null) throw new NullPointerException();
		this.store = store;
	}

	public long generation(int contactId) {
		AtomicLong g = generations.get(contactId);
		return g == null ? 0 : g.get();
	}

	public void retireContact(int contactId) {
		AtomicLong g = generations.get(contactId);
		if (g == null) {
			AtomicLong created = new AtomicLong();
			g = generations.putIfAbsent(contactId, created);
			if (g == null) g = created;
		}
		g.incrementAndGet();
	}

	public long allocateSendStreamId(int contactId) {
		return allocateSendStreamId(contactId, generation(contactId));
	}

	public long allocateSendStreamId(int contactId, long generation) {
		synchronized (lock) {
			if (!enterGeneration(contactId, generation)) {
				throw new IllegalStateException("contact id retired");
			}
			long key = key(contactId, DIRECTION_SEND);
			long current = current(key, contactId, DIRECTION_SEND);
			long next = current + 1;
			if (next < 0) throw new IllegalStateException("stream id overflow");
			if (!store.storeHighWaterIf(contactId, DIRECTION_SEND, next,
					() -> generation(contactId) == generation)) {
				throw new IllegalStateException("contact id retired");
			}
			cache.put(key, next);
			return next;
		}
	}

	public boolean acceptRecvStreamId(int contactId, long streamId) {
		return acceptRecvStreamId(contactId, streamId, generation(contactId));
	}

	public boolean acceptRecvStreamId(int contactId, long streamId,
			long generation) {
		if (streamId < 1) return false;
		synchronized (lock) {
			if (!enterGeneration(contactId, generation)) return false;
			long key = key(contactId, DIRECTION_RECV);
			long hw = current(key, contactId, DIRECTION_RECV);
			if (streamId <= hw - REPLAY_WINDOW_SIZE) return false;
			Long floor = startupRecvFloor.get(contactId);
			if (floor != null && streamId <= floor) return false;
			NavigableSet<Long> seen = recvSeen.get(contactId);
			if (seen == null) {
				seen = new TreeSet<>();
				recvSeen.put(contactId, seen);
			}
			if (!seen.add(streamId)) return false;
			if (streamId > hw) {
				if (!store.storeHighWaterIf(contactId, DIRECTION_RECV,
						streamId,
						() -> generation(contactId) == generation)) {
					seen.remove(streamId);
					return false;
				}
				cache.put(key, streamId);
				seen.headSet(streamId - REPLAY_WINDOW_SIZE + 1, false).clear();
			}
			return true;
		}
	}

	public long currentRecvHighWater(int contactId) {
		synchronized (lock) {
			enterGeneration(contactId, generation(contactId));
			return current(key(contactId, DIRECTION_RECV), contactId,
					DIRECTION_RECV);
		}
	}

	private boolean enterGeneration(int contactId, long generation) {
		long currentGeneration = generation(contactId);
		if (generation != currentGeneration) return false;
		Long loaded = stateGeneration.get(contactId);
		if (loaded == null || loaded != currentGeneration) {
			cache.remove(key(contactId, DIRECTION_SEND));
			cache.remove(key(contactId, DIRECTION_RECV));
			recvSeen.remove(contactId);
			startupRecvFloor.remove(contactId);
			stateGeneration.put(contactId, currentGeneration);
		}
		return true;
	}

	private long current(long key, int contactId, int direction) {
		Long cached = cache.get(key);
		if (cached != null) return cached;
		long loaded = store.loadHighWater(contactId, direction);
		cache.put(key, loaded);
		if (direction == DIRECTION_RECV && loaded > 0) {
			startupRecvFloor.put(contactId, loaded);
		}
		return loaded;
	}

	private static long key(int contactId, int direction) {
		return (((long) contactId) << 1) | (direction & 1L);
	}
}
