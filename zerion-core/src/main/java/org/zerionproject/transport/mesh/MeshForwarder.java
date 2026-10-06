package org.zerionproject.transport.mesh;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class MeshForwarder {

	public interface FrameListener {
		void onFrame(byte[] payload);

		default void onFrame(byte[] payload, String fromPeer) {
			onFrame(payload);
		}
	}

	static final int SEEN_CAP = 8192;
	static final int SEEN_PER_PEER_CAP = 1024;
	static final long SEEN_TTL_MS = 15 * 60_000L;

	public static final int MAX_PAYLOAD_BYTES = 32 * 1024;
	public static final int MAX_FRAME_BYTES =
			MeshFrame.HEADER_BYTES + MAX_PAYLOAD_BYTES;
	static final int STORE_MAX_BYTES = 2 * 1024 * 1024;
	static final int STORE_MAX_FRAMES = 1024;
	static final int STORE_PER_PEER_MAX_BYTES = 1024 * 1024;
	static final int STORE_PER_PEER_MAX_FRAMES = 256;
	static final int MAX_FRAMES_PER_SEC = 200;
	static final int MAX_FRAMES_PER_SEC_PER_PEER = 50;
	private static final String UNKNOWN_PEER = "";

	private final FrameListener listener;
	private final SecureRandom random;
	private final Map<String, MeshLink> links = new ConcurrentHashMap<>();
	private final LinkedHashMap<String, SeenEntry> seen = new LinkedHashMap<>();
	private final Map<String, Integer> seenPerPeer = new HashMap<>();
	private final LinkedHashMap<String, Carried> store = new LinkedHashMap<>();
	private final Map<String, Share> storeShares = new HashMap<>();
	private final Map<String, RateWindow> peerRates = new HashMap<>();
	private final RateWindow globalRate = new RateWindow();
	private long storeBytes = 0;
	volatile LongSupplier clock = System::currentTimeMillis;

	private static final class SeenEntry {
		final long at;
		final String peer;

		SeenEntry(long at, String peer) {
			this.at = at;
			this.peer = peer;
		}
	}

	private static final class RateWindow {
		long start = 0;
		int count = 0;
	}

	private static final class Carried {
		final byte[] frame;
		@Nullable
		final String peer;

		Carried(byte[] frame, @Nullable String peer) {
			this.frame = frame;
			this.peer = peer;
		}
	}

	private static final class Share {
		long bytes = 0;
		int frames = 0;
	}

	public MeshForwarder(FrameListener listener, SecureRandom random) {
		this.listener = listener;
		this.random = random;
	}

	public void addLink(MeshLink link) {
		links.put(link.getId(), link);
		List<byte[]> carried = new ArrayList<>();
		synchronized (store) {
			for (Carried c : store.values()) carried.add(c.frame);
		}
		for (byte[] frame : carried) link.broadcast(frame);
	}

	public void removeLink(String linkId) {
		links.remove(linkId);
	}

	public byte[] originate(byte[] payload) {
		byte[] messageId = new byte[MeshFrame.MESSAGE_ID_BYTES];
		random.nextBytes(messageId);
		int hops = MeshFrame.MAX_HOPS - random.nextInt(3);
		MeshFrame frame = new MeshFrame(hops, messageId, payload);
		String idHex = StringUtils.toHexString(messageId);
		markSeen(idHex, null);
		byte[] encoded = frame.encode();
		remember(idHex, encoded, null);
		relay(encoded, null, null);
		return messageId;
	}

	public boolean onReceive(byte[] frameBytes, @Nullable String fromLinkId) {
		return onReceive(frameBytes, fromLinkId, null);
	}

	public boolean onReceive(byte[] frameBytes, @Nullable String fromLinkId,
			@Nullable String fromPeerId) {
		if (frameBytes.length > MAX_FRAME_BYTES) return false;
		String peer = fromPeerId == null ? UNKNOWN_PEER : fromPeerId;
		if (!rateLimitOk(peer)) return false;
		MeshFrame frame;
		try {
			frame = MeshFrame.decode(frameBytes);
		} catch (FormatException e) {
			return false;
		}
		String idHex = StringUtils.toHexString(frame.getMessageId());
		if (!markSeen(idHex, peer)) return false;
		listener.onFrame(frame.getPayload(), peer);
		MeshFrame next = frame.decremented();
		if (next != null) {
			byte[] encoded = next.encode();
			remember(idHex, encoded, peer);
			relay(encoded, fromLinkId, fromPeerId);
		}
		return true;
	}

	private void relay(byte[] encoded, @Nullable String fromLinkId,
			@Nullable String fromPeerId) {
		for (MeshLink link : links.values()) {
			if (fromLinkId != null && link.getId().equals(fromLinkId)) {
				link.broadcast(encoded, fromPeerId);
			} else {
				link.broadcast(encoded);
			}
		}
	}

	private boolean markSeen(String idHex, @Nullable String peer) {
		synchronized (seen) {
			long now = clock.getAsLong();
			expire(now);
			if (seen.containsKey(idHex)) return false;
			if (peer == null) {
				while (seen.size() >= SEEN_CAP) evictOldest();
				seen.put(idHex, new SeenEntry(now, UNKNOWN_PEER));
				return true;
			}
			int held = seenPerPeer.getOrDefault(peer, 0);
			if (held >= SEEN_PER_PEER_CAP) return false;
			if (seen.size() >= SEEN_CAP && !evictFromHeaviestPeer(held)) {
				return false;
			}
			seenPerPeer.put(peer, held + 1);
			seen.put(idHex, new SeenEntry(now, peer));
			return true;
		}
	}

	private boolean evictFromHeaviestPeer(int newcomerHeld) {
		String heaviest = null;
		int most = newcomerHeld;
		for (Map.Entry<String, Integer> e : seenPerPeer.entrySet()) {
			if (e.getValue() > most) {
				most = e.getValue();
				heaviest = e.getKey();
			}
		}
		if (heaviest == null) return false;
		Iterator<Map.Entry<String, SeenEntry>> it = seen.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, SeenEntry> e = it.next();
			if (e.getValue().peer.equals(heaviest)) {
				release(e.getValue());
				it.remove();
				return true;
			}
		}
		return false;
	}

	private void expire(long now) {
		Iterator<Map.Entry<String, SeenEntry>> it =
				seen.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, SeenEntry> e = it.next();
			if (now - e.getValue().at < SEEN_TTL_MS) return;
			release(e.getValue());
			it.remove();
		}
	}

	private void evictOldest() {
		Iterator<Map.Entry<String, SeenEntry>> it =
				seen.entrySet().iterator();
		if (!it.hasNext()) return;
		release(it.next().getValue());
		it.remove();
	}

	private void release(SeenEntry e) {
		if (e.peer.equals(UNKNOWN_PEER)) return;
		Integer held = seenPerPeer.get(e.peer);
		if (held == null) return;
		if (held <= 1) seenPerPeer.remove(e.peer);
		else seenPerPeer.put(e.peer, held - 1);
	}

	private void remember(String idHex, byte[] encoded, @Nullable String peer) {
		synchronized (store) {
			Carried prev = store.remove(idHex);
			if (prev != null) forget(prev);
			if (peer != null) {
				Share held = storeShares.get(peer);
				while (held != null && (held.bytes + encoded.length
						> STORE_PER_PEER_MAX_BYTES
						|| held.frames >= STORE_PER_PEER_MAX_FRAMES)) {
					if (!evictOldestOf(peer)) break;
					held = storeShares.get(peer);
				}
			}
			store.put(idHex, new Carried(encoded, peer));
			Share share = storeShares.get(peer);
			if (share == null) {
				share = new Share();
				storeShares.put(peer, share);
			}
			share.bytes += encoded.length;
			share.frames++;
			storeBytes += encoded.length;
			while (storeBytes > STORE_MAX_BYTES
					|| store.size() > STORE_MAX_FRAMES) {
				if (!evictOldestOf(heaviestHolder())) break;
			}
		}
	}

	@Nullable
	private String heaviestHolder() {
		String heaviest = null;
		long most = -1;
		for (Map.Entry<String, Share> e : storeShares.entrySet()) {
			if (e.getValue().bytes > most) {
				most = e.getValue().bytes;
				heaviest = e.getKey();
			}
		}
		return heaviest;
	}

	private boolean evictOldestOf(@Nullable String holder) {
		Iterator<Map.Entry<String, Carried>> it = store.entrySet().iterator();
		while (it.hasNext()) {
			Carried c = it.next().getValue();
			if (holder == null ? c.peer == null : holder.equals(c.peer)) {
				it.remove();
				forget(c);
				return true;
			}
		}
		return false;
	}

	private void forget(Carried c) {
		storeBytes -= c.frame.length;
		Share share = storeShares.get(c.peer);
		if (share == null) return;
		share.bytes -= c.frame.length;
		if (--share.frames <= 0) storeShares.remove(c.peer);
	}

	private boolean rateLimitOk(String peer) {
		long now = clock.getAsLong();
		synchronized (peerRates) {
			RateWindow w = peerRates.get(peer);
			if (w == null) {
				if (peerRates.size() >= SEEN_CAP) peerRates.clear();
				w = new RateWindow();
				peerRates.put(peer, w);
			}
			if (!admit(w, now, MAX_FRAMES_PER_SEC_PER_PEER)) return false;
			return admit(globalRate, now, MAX_FRAMES_PER_SEC);
		}
	}

	private static boolean admit(RateWindow w, long now, int max) {
		if (now - w.start > 1000) {
			w.start = now;
			w.count = 0;
		}
		return ++w.count <= max;
	}
}
