package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class ZwfTagRecogniser {

	public static final long DEFAULT_EPOCH = 0;

	private final CryptoComponent crypto;
	private final int window;
	private final Object lock = new Object();
	private final Map<Integer, Map<Long, SecretKey>> tagKeys = new HashMap<>();
	private final Map<Integer, Long> highWater = new HashMap<>();
	private final Map<String, Match> tagIndex = new HashMap<>();
	private int lastSearchedContact = Integer.MIN_VALUE;
	private int lastSearchedPreferred = Integer.MIN_VALUE;
	private int searches = 0;

	static final int PREFERRED_SHARE = 4;

	public ZwfTagRecogniser(CryptoComponent crypto, int window) {
		if (window < 1) throw new IllegalArgumentException("window < 1");
		this.crypto = crypto;
		this.window = window;
	}

	public void register(int contactId, SecretKey tagKey, long highWaterMark) {
		Map<Long, SecretKey> keys = new LinkedHashMap<>();
		keys.put(DEFAULT_EPOCH, tagKey);
		register(contactId, keys, highWaterMark);
	}

	public void register(int contactId, Map<Long, SecretKey> tagKeysByEpoch,
			long highWaterMark) {
		if (tagKeysByEpoch.isEmpty()) throw new IllegalArgumentException();
		synchronized (lock) {
			Long existing = highWater.get(contactId);
			if (existing != null) removeWindow(contactId, existing);
			clearKeys(tagKeys.put(contactId,
					new LinkedHashMap<>(tagKeysByEpoch)), tagKeysByEpoch);
			highWater.put(contactId, highWaterMark);
			addWindow(contactId, highWaterMark);
		}
	}

	public void remove(int contactId) {
		synchronized (lock) {
			Long hw = highWater.remove(contactId);
			if (hw != null) removeWindow(contactId, hw);
			clearKeys(tagKeys.remove(contactId), null);
		}
	}

	private static void clearKeys(@Nullable Map<Long, SecretKey> old,
			@Nullable Map<Long, SecretKey> kept) {
		if (old == null) return;
		for (SecretKey k : old.values()) {
			if (kept != null && kept.containsValue(k)) continue;
			k.clear();
		}
	}

	public void advanceTo(int contactId, long newHighWaterMark) {
		synchronized (lock) {
			Long old = highWater.get(contactId);
			if (old == null) return;
			removeWindow(contactId, old);
			highWater.put(contactId, newHighWaterMark);
			addWindow(contactId, newHighWaterMark);
		}
	}

	public long getHighWater(int contactId) {
		synchronized (lock) {
			Long hw = highWater.get(contactId);
			return hw == null ? -1 : hw;
		}
	}

	@Nullable
	public Match recognise(byte[] tag) {
		synchronized (lock) {
			return tagIndex.get(hex(tag));
		}
	}

	@Nullable
	public Match recogniseBeyondWindow(int contactId, byte[] tag,
			long maxGap) {
		List<Map.Entry<Long, SecretKey>> keys;
		long hw;
		synchronized (lock) {
			Map<Long, SecretKey> k = tagKeys.get(contactId);
			Long h = highWater.get(contactId);
			if (k == null || h == null) return null;
			keys = new ArrayList<>(k.entrySet());
			hw = h;
		}
		long first = hw + window + 1;
		long last = hw + maxGap;
		for (long s = first; s <= last && s > 0; s++) {
			for (Map.Entry<Long, SecretKey> e : keys) {
				byte[] candidate = ZwfTag.computeTag(crypto, e.getValue(), s);
				if (MessageDigest.isEqual(candidate, tag)) {
					return new Match(contactId, s, e.getKey());
				}
			}
		}
		return null;
	}

	@Nullable
	public Match recogniseBeyondWindowNext(byte[] tag, long maxGap) {
		return recogniseBeyondWindowNext(tag, maxGap,
				java.util.Collections.emptySet());
	}

	@Nullable
	public Match recogniseBeyondWindowNext(byte[] tag, long maxGap,
			java.util.Set<Integer> preferred) {
		int contactId;
		synchronized (lock) {
			if (tagKeys.isEmpty()) return null;
			List<Integer> all = new ArrayList<>(tagKeys.keySet());
			java.util.Collections.sort(all);
			List<Integer> first = new ArrayList<>();
			List<Integer> rest = new ArrayList<>();
			for (Integer id : all) {
				if (preferred.contains(id)) first.add(id);
				else rest.add(id);
			}
			boolean takePreferred = !first.isEmpty() && (rest.isEmpty()
					|| searches % PREFERRED_SHARE != PREFERRED_SHARE - 1);
			searches++;
			if (takePreferred) {
				contactId = nextAfter(first, lastSearchedPreferred);
				lastSearchedPreferred = contactId;
			} else {
				contactId = nextAfter(rest, lastSearchedContact);
				lastSearchedContact = contactId;
			}
		}
		return recogniseBeyondWindow(contactId, tag, maxGap);
	}

	private static int nextAfter(List<Integer> sorted, int last) {
		for (Integer id : sorted) {
			if (id > last) return id;
		}
		return sorted.get(0);
	}

	private void addWindow(int contactId, long hw) {
		Map<Long, SecretKey> keys = tagKeys.get(contactId);
		if (keys == null) return;
		for (Map.Entry<Long, SecretKey> e : keys.entrySet()) {
			for (long s = Math.max(1, hw - window + 1); s <= hw + window;
					s++) {
				byte[] tag = ZwfTag.computeTag(crypto, e.getValue(), s);
				tagIndex.put(hex(tag), new Match(contactId, s, e.getKey()));
			}
		}
	}

	private void removeWindow(int contactId, long hw) {
		Map<Long, SecretKey> keys = tagKeys.get(contactId);
		if (keys == null) return;
		for (SecretKey key : keys.values()) {
			for (long s = Math.max(1, hw - window + 1); s <= hw + window;
					s++) {
				tagIndex.remove(hex(ZwfTag.computeTag(crypto, key, s)));
			}
		}
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16));
			sb.append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
	}

	public static final class Match {
		public final int contactId;
		public final long streamId;
		public final long epoch;

		Match(int contactId, long streamId, long epoch) {
			this.contactId = contactId;
			this.streamId = streamId;
			this.epoch = epoch;
		}
	}
}
