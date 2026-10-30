package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.contact.request.ContactRequestRecords.Hello;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;
import org.zerionproject.core.util.StringUtils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIFFICULTY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.EPOCH_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_MAX_LEVEL_EPOCHS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_ZERO_WORK_PER_10_MIN;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HELLO_PROTOCOL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LEVEL_THRESHOLDS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_LEVEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REPLAY_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ZERO_WORK_LIMIT_PER_MINUTE;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ZERO_WORK_WINDOW_MS;

@ThreadSafe
@NotNullByDefault
final class InboxPuzzle {

	interface FloodListener {

		void flooded();
	}

	private final ContactRequestCrypto crypto;
	private final byte[] inboxSecret;
	private final byte[] linkId;
	private final FloodListener floodListener;

	private final ArrayDeque<Long> events = new ArrayDeque<>();
	private final ArrayDeque<Long> zeroWork = new ArrayDeque<>();
	private final ArrayDeque<Long> zeroWorkFlood = new ArrayDeque<>();
	private final Map<String, Long> replay = new HashMap<>();
	private final AtomicBoolean floodReported = new AtomicBoolean(false);
	private long epoch = -1, previousEpoch = -1;
	private int level = 0, previousLevel = 0;
	private int maxLevelEpochs = 0;
	private boolean flooded = false;

	InboxPuzzle(ContactRequestCrypto crypto, byte[] inboxSecret,
			byte[] linkId) {
		this(crypto, inboxSecret, linkId, () -> {
		});
	}

	InboxPuzzle(ContactRequestCrypto crypto, byte[] inboxSecret,
			byte[] linkId, FloodListener floodListener) {
		this.crypto = crypto;
		this.inboxSecret = inboxSecret.clone();
		this.linkId = linkId.clone();
		this.floodListener = floodListener;
	}

	static int levelFor(int events) {
		for (int k = 0; k < LEVEL_THRESHOLDS.length; k++) {
			if (events < LEVEL_THRESHOLDS[k]) return k;
		}
		return MAX_LEVEL;
	}

	byte[] nonce(long e, int k) {
		return crypto.puzzleNonce(inboxSecret, e, k);
	}

	Hello hello(long now) {
		Hello hello;
		synchronized (this) {
			roll(now);
			hello = new Hello(HELLO_PROTOCOL, level, DIFFICULTY[level], epoch,
					nonce(epoch, level));
		}
		report();
		return hello;
	}

	int getLevel(long now) {
		int k;
		synchronized (this) {
			roll(now);
			k = level;
		}
		report();
		return k;
	}

	synchronized boolean isFlooded() {
		return flooded;
	}

	synchronized int getMaxLevelEpochs() {
		return maxLevelEpochs;
	}

	boolean isValid(Solution s, @Nullable Hello announced, long now) {
		boolean valid;
		synchronized (this) {
			roll(now);
			valid = checkValid(s, announced, now);
		}
		report();
		return valid;
	}

	private boolean checkValid(Solution s, @Nullable Hello announced,
			long now) {
		if (s.level > MAX_LEVEL) return false;
		boolean window;
		if (s.epoch == epoch) {
			window = s.level == level || s.level == level - 1;
		} else if (s.epoch == epoch - 1 && previousEpoch == epoch - 1) {
			window = s.level == previousLevel
					|| s.level == previousLevel - 1;
		} else {
			window = false;
		}
		if (!window) return false;
		if (s.level == 0 && (announced == null
				|| announced.epoch != s.epoch || announced.level != 0)) {
			return false;
		}
		purgeReplay(now);
		if (replay.containsKey(replayKey(s))) return false;
		return crypto.isSolution(nonce(s.epoch, s.level), linkId, s.x,
				DIFFICULTY[s.level]);
	}

	boolean admit(Solution s, long now) {
		boolean admitted;
		synchronized (this) {
			roll(now);
			purgeReplay(now);
			String key = replayKey(s);
			if (replay.containsKey(key)) {
				admitted = false;
			} else {
				replay.put(key, now + REPLAY_WINDOW_MS);
				events.addLast(now);
				long epochStart = epoch * EPOCH_MS;
				int inEpoch = 0;
				for (long t : events) if (t >= epochStart) inEpoch++;
				setLevel(Math.max(level, levelFor(inEpoch)));
				admitted = true;
			}
		}
		report();
		return admitted;
	}

	void zeroWork(long now) {
		synchronized (this) {
			roll(now);
			zeroWork.addLast(now);
			zeroWorkFlood.addLast(now);
			applyZeroWorkFloor(now);
			purgeZeroWorkFlood(now);
			if (zeroWorkFlood.size() > FLOOD_ZERO_WORK_PER_10_MIN) {
				flooded = true;
			}
		}
		report();
	}

	private void report() {
		boolean fire;
		synchronized (this) {
			fire = flooded;
		}
		if (fire && floodReported.compareAndSet(false, true)) {
			floodListener.flooded();
		}
	}

	private void setLevel(int k) {
		if (k == level) return;
		level = k;
		if (level == MAX_LEVEL) {
			maxLevelEpochs = 1;
		} else {
			maxLevelEpochs = 0;
		}
	}

	private void purgeZeroWorkFlood(long now) {
		while (!zeroWorkFlood.isEmpty()
				&& zeroWorkFlood.peekFirst() <= now - FLOOD_WINDOW_MS) {
			zeroWorkFlood.removeFirst();
		}
	}

	private void applyZeroWorkFloor(long now) {
		while (!zeroWork.isEmpty()
				&& zeroWork.peekFirst() <= now - ZERO_WORK_WINDOW_MS) {
			zeroWork.removeFirst();
		}
		if (zeroWork.size() > ZERO_WORK_LIMIT_PER_MINUTE) {
			setLevel(Math.max(level, 1));
		}
	}

	private void roll(long now) {
		long e = now / EPOCH_MS;
		if (e == epoch) return;
		boolean consecutive = e == epoch + 1 && level == MAX_LEVEL;
		previousEpoch = epoch;
		previousLevel = level;
		epoch = e;
		long epochStart = e * EPOCH_MS;
		while (!events.isEmpty()
				&& events.peekFirst() < epochStart - 2 * EPOCH_MS) {
			events.removeFirst();
		}
		int recent = 0;
		for (long t : events) {
			if (t >= epochStart - EPOCH_MS && t < epochStart) recent++;
		}
		int next = levelFor(recent);
		if (next == MAX_LEVEL && consecutive) {
			level = MAX_LEVEL;
			maxLevelEpochs++;
			if (maxLevelEpochs >= FLOOD_MAX_LEVEL_EPOCHS) flooded = true;
		} else {
			level = -1;
			setLevel(next);
		}
		applyZeroWorkFloor(now);
		purgeZeroWorkFlood(now);
	}

	private void purgeReplay(long now) {
		Iterator<Long> it = replay.values().iterator();
		while (it.hasNext()) if (it.next() <= now) it.remove();
	}

	private static String replayKey(Solution s) {
		return s.epoch + ":" + s.level + ":" + StringUtils.toHexString(s.x);
	}
}
