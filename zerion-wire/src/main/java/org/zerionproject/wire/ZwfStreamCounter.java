package org.zerionproject.wire;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import static org.zerionproject.wire.ZwfConstants.DIRECTION_RECV;
import static org.zerionproject.wire.ZwfConstants.DIRECTION_SEND;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

/**
 * Allocates and validates ZWF stream identifiers.
 *
 * <p><strong>Send</strong> ids are <strong>strictly monotonic and never
 * reused</strong> — across reconnects, process restarts, key rotations and
 * crashes. This is the invariant the whole wire format's security rests on.
 * <strong>Receive</strong> ids are validated against a reorder/replay window
 * (see {@link #acceptRecvStreamId}); the peer guarantees its own send
 * monotonicity, so tolerating reordering on receive does not affect our nonce
 * safety.
 *
 * <p>The AEAD nonce base is bound to {@code streamId} (see
 * {@link ZwfNonce}) and the ratchet's initial chain key is seeded from
 * {@code (rootKey, streamId)}. The contact root key is long-lived, so if a
 * stream id ever repeated under it, the derived chain keys and nonces would
 * repeat — a total break of that stream (keystream reuse + Poly1305 forgery).
 * The counter is therefore persistent and never resets.
 *
 * <p>Thread-safe. All state transitions go through {@link #store} and are
 * persisted <em>before</em> a send id is returned or a receive id is accepted.
 */
public class ZwfStreamCounter {

	private final StreamCounterStore store;
	private final Object lock = new Object();
	private final Map<Long, Long> cache = new HashMap<>();
	private final Map<Integer, NavigableSet<Long>> recvSeen = new HashMap<>();

	public ZwfStreamCounter(StreamCounterStore store) {
		if (store == null) throw new NullPointerException();
		this.store = store;
	}

	/**
	 * Allocates the next outgoing stream id for the given contact. The new
	 * high-water mark is persisted durably before this method returns, so the
	 * returned id can never be handed out again even if the process dies
	 * immediately afterwards.
	 *
	 * @return a strictly-increasing id, starting at 1.
	 * @throws IllegalStateException if the 63-bit id space is exhausted.
	 */
	public long allocateSendStreamId(int contactId) {
		synchronized (lock) {
			long key = key(contactId, DIRECTION_SEND);
			long current = current(key, contactId, DIRECTION_SEND);
			long next = current + 1;
			if (next < 0) throw new IllegalStateException("stream id overflow");
			// Persist before returning: a returned id must never be reused.
			store.storeHighWater(contactId, DIRECTION_SEND, next);
			cache.put(key, next);
			return next;
		}
	}

	/**
	 * Validates an incoming stream id from the peer, tolerating reordering.
	 * Accepts the id if it lies within the reorder window
	 * {@code (highWater - REPLAY_WINDOW_SIZE, highWater + …]} and has not already
	 * been accepted; rejects ids that are stale (older than the window) or a
	 * replay of one already seen. Advancing the high-water mark persists it
	 * durably before returning.
	 *
	 * <p>Unlike the send side (strictly monotonic), the receive side must accept
	 * streams that arrive out of order relative to a higher-numbered stream —
	 * otherwise a single early-delivered high id (natural churn, or an on-path
	 * attacker) would slide the window past every genuine lower stream and
	 * permanently break the channel.
	 *
	 * @return {@code true} if fresh and accepted; {@code false} if stale or a
	 * replay, in which case the stream must be dropped.
	 */
	public boolean acceptRecvStreamId(int contactId, long streamId) {
		if (streamId < 1) return false;
		synchronized (lock) {
			long key = key(contactId, DIRECTION_RECV);
			long hw = current(key, contactId, DIRECTION_RECV);
			if (streamId <= hw - REPLAY_WINDOW_SIZE) return false;
			NavigableSet<Long> seen = recvSeen.get(contactId);
			if (seen == null) {
				seen = new TreeSet<>();
				recvSeen.put(contactId, seen);
			}
			if (!seen.add(streamId)) return false;
			if (streamId > hw) {
				store.storeHighWater(contactId, DIRECTION_RECV, streamId);
				cache.put(key, streamId);
				seen.headSet(streamId - REPLAY_WINDOW_SIZE + 1, false).clear();
			}
			return true;
		}
	}

	/**
	 * Returns the highest receive stream id accepted so far from the contact
	 * (0 if none). Used to seed the tag recogniser's window.
	 */
	public long currentRecvHighWater(int contactId) {
		synchronized (lock) {
			return current(key(contactId, DIRECTION_RECV), contactId,
					DIRECTION_RECV);
		}
	}

	// Must be called while holding `lock`.
	private long current(long key, int contactId, int direction) {
		Long cached = cache.get(key);
		if (cached != null) return cached;
		long loaded = store.loadHighWater(contactId, direction);
		cache.put(key, loaded);
		return loaded;
	}

	private static long key(int contactId, int direction) {
		return (((long) contactId) << 1) | (direction & 1L);
	}
}
