package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Recognises the tag prefixing an incoming ZWF stream and maps it to a
 * {@code (contact, streamId)}.
 *
 * <p>For each registered contact the recogniser keeps a sliding window of the
 * next {@code window} expected stream ids, indexing {@code MAC(tagKey, streamId)}
 * → {@code (contact, streamId)}. An incoming tag is a single map lookup. As
 * streams are accepted the window advances (via {@link #advanceTo}) so old tags
 * are dropped and future ones become recognisable, matching the persistent
 * receive-side stream counter.
 */
@ThreadSafe
@NotNullByDefault
public class ZwfTagRecogniser {

	private final CryptoComponent crypto;
	private final int window;
	private final Object lock = new Object();
	private final Map<Integer, SecretKey> tagKeys = new HashMap<>();
	private final Map<Integer, Long> highWater = new HashMap<>();
	private final Map<String, Match> tagIndex = new HashMap<>();

	public ZwfTagRecogniser(CryptoComponent crypto, int window) {
		if (window < 1) throw new IllegalArgumentException("window < 1");
		this.crypto = crypto;
		this.window = window;
	}

	/**
	 * Registers a contact with its tag key and the highest stream id already
	 * received from it (0 for a new contact). Tags for stream ids in
	 * {@code (highWaterMark, highWaterMark + window]} become recognisable.
	 */
	public void register(int contactId, SecretKey tagKey, long highWaterMark) {
		synchronized (lock) {
			Long existing = highWater.get(contactId);
			if (existing != null) removeWindow(contactId, existing);
			tagKeys.put(contactId, tagKey);
			highWater.put(contactId, highWaterMark);
			addWindow(contactId, highWaterMark);
		}
	}

	public void remove(int contactId) {
		synchronized (lock) {
			Long hw = highWater.remove(contactId);
			if (hw != null) removeWindow(contactId, hw);
			tagKeys.remove(contactId);
		}
	}

	/**
	 * Slides the window so ids at or below {@code newHighWaterMark} are no longer
	 * recognised and the next {@code window} ids are. Call after accepting a
	 * stream.
	 */
	public void advanceTo(int contactId, long newHighWaterMark) {
		synchronized (lock) {
			Long old = highWater.get(contactId);
			if (old == null) return;
			removeWindow(contactId, old);
			highWater.put(contactId, newHighWaterMark);
			addWindow(contactId, newHighWaterMark);
		}
	}

	@Nullable
	public Match recognise(byte[] tag) {
		synchronized (lock) {
			return tagIndex.get(hex(tag));
		}
	}

	// Must hold lock.
	private void addWindow(int contactId, long hw) {
		SecretKey key = tagKeys.get(contactId);
		if (key == null) return;
		for (long s = Math.max(1, hw - window + 1); s <= hw + window; s++) {
			byte[] tag = ZwfTag.computeTag(crypto, key, s);
			tagIndex.put(hex(tag), new Match(contactId, s));
		}
	}

	// Must hold lock.
	private void removeWindow(int contactId, long hw) {
		SecretKey key = tagKeys.get(contactId);
		if (key == null) return;
		for (long s = Math.max(1, hw - window + 1); s <= hw + window; s++) {
			tagIndex.remove(hex(ZwfTag.computeTag(crypto, key, s)));
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

		Match(int contactId, long streamId) {
			this.contactId = contactId;
			this.streamId = streamId;
		}
	}
}
