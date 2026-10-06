package com.professor.zerion.android.mesh;

import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class MeshLinkPolicy {

	static final int NONCE_BYTES = 8;
	static final int NONCE_PREFIX_BYTES = 2;
	static final long ADVERTISING_SET_MIN_MS = 4 * 60_000L;
	static final int ADVERTISING_SET_SPREAD_MS = 2 * 60_000;
	static final long NONCE_ANNOUNCE_INTERVAL_MS = 30_000L;
	static final long ANNOUNCED_NONCE_TTL_MS = 10 * 60_000L;
	static final int ANNOUNCED_NONCES_PER_LINK = 2;
	static final int MAX_CLIENTS = 6;
	static final int MAX_CENTRALS = 6;
	static final long NEW_LINK_GRACE_MS = 90_000L;
	static final long IDLE_EVICT_MS = 6 * 60_000L;

	static final byte LINK_CONTROL = 0x7F;
	static final byte NONCE_ANNOUNCE = 0x01;
	static final int ANNOUNCE_BYTES = 2 + NONCE_BYTES;

	private static final class Announced {
		final String nonceHex;
		final long at;

		Announced(String nonceHex, long at) {
			this.nonceHex = nonceHex;
			this.at = at;
		}
	}

	private static final class Link {
		final long since;
		long lastUseful = Long.MIN_VALUE;

		Link(long since) {
			this.since = since;
		}
	}

	private final SecureRandom random;
	private final Object lock = new Object();
	private final Map<String, Deque<Announced>> announced = new HashMap<>();
	private final Map<String, Link> links = new HashMap<>();

	MeshLinkPolicy(SecureRandom random) {
		this.random = random;
	}

	byte[] freshNonce() {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		for (int i = 0; i < NONCE_PREFIX_BYTES; i++) nonce[i] = (byte) 0xFF;
		return nonce;
	}

	long nextAdvertisingSetLifetimeMs() {
		return ADVERTISING_SET_MIN_MS
				+ random.nextInt(ADVERTISING_SET_SPREAD_MS + 1);
	}

	static byte[] encodeAnnounce(byte[] nonce) {
		byte[] out = new byte[ANNOUNCE_BYTES];
		out[0] = LINK_CONTROL;
		out[1] = NONCE_ANNOUNCE;
		System.arraycopy(nonce, 0, out, 2, NONCE_BYTES);
		return out;
	}

	static boolean isControl(byte[] frame) {
		return frame.length > 0 && frame[0] == LINK_CONTROL;
	}

	@Nullable
	static byte[] decodeAnnounce(byte[] frame) {
		if (frame.length != ANNOUNCE_BYTES || frame[0] != LINK_CONTROL
				|| frame[1] != NONCE_ANNOUNCE) {
			return null;
		}
		byte[] nonce = new byte[NONCE_BYTES];
		System.arraycopy(frame, 2, nonce, 0, NONCE_BYTES);
		return nonce;
	}

	void onAnnounce(String linkKey, byte[] nonce, long now) {
		synchronized (lock) {
			if (!links.containsKey(linkKey)) return;
			Deque<Announced> q = announced.get(linkKey);
			if (q == null) {
				q = new ArrayDeque<>();
				announced.put(linkKey, q);
			}
			String hex = StringUtils.toHexString(nonce);
			Announced last = q.peekLast();
			if (last != null && last.nonceHex.equals(hex)) q.removeLast();
			q.addLast(new Announced(hex, now));
			while (q.size() > ANNOUNCED_NONCES_PER_LINK) q.removeFirst();
		}
	}

	boolean isAnnounced(String nonceHex, long now) {
		synchronized (lock) {
			for (Deque<Announced> q : announced.values()) {
				for (Announced a : q) {
					if (a.nonceHex.equals(nonceHex)
							&& now - a.at <= ANNOUNCED_NONCE_TTL_MS) {
						return true;
					}
				}
			}
			return false;
		}
	}

	void onLinkUp(String linkKey, long now) {
		synchronized (lock) {
			links.put(linkKey, new Link(now));
		}
	}

	void onLinkDown(String linkKey) {
		synchronized (lock) {
			links.remove(linkKey);
			announced.remove(linkKey);
		}
	}

	void onUseful(String linkKey, long now) {
		synchronized (lock) {
			Link l = links.get(linkKey);
			if (l != null && now > l.lastUseful) l.lastUseful = now;
		}
	}

	@Nullable
	String idleLinkToEvict(Collection<String> keys, long now) {
		synchronized (lock) {
			String silent = null;
			long silentSince = Long.MAX_VALUE;
			String stale = null;
			long staleUseful = Long.MAX_VALUE;
			for (String key : keys) {
				Link l = links.get(key);
				if (l == null) return key;
				if (l.lastUseful == Long.MIN_VALUE) {
					if (now - l.since >= NEW_LINK_GRACE_MS
							&& l.since < silentSince) {
						silentSince = l.since;
						silent = key;
					}
				} else if (now - l.lastUseful >= IDLE_EVICT_MS
						&& l.lastUseful < staleUseful) {
					staleUseful = l.lastUseful;
					stale = key;
				}
			}
			return silent != null ? silent : stale;
		}
	}

	void clear() {
		synchronized (lock) {
			links.clear();
			announced.clear();
		}
	}

	int linkCount() {
		synchronized (lock) {
			return links.size();
		}
	}

	void expire(long now) {
		synchronized (lock) {
			for (Iterator<Deque<Announced>> it =
					announced.values().iterator(); it.hasNext(); ) {
				Deque<Announced> q = it.next();
				q.removeIf(a -> now - a.at > ANNOUNCED_NONCE_TTL_MS);
				if (q.isEmpty()) it.remove();
			}
		}
	}
}
