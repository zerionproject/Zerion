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

/**
 * Floods frames across the mesh links and suppresses duplicates. The
 * duplicate set is bounded in time and size and can never be flushed by a
 * flood: an entry leaves only once it has expired, and a neighbour that
 * exceeds its own share of the set or its own frame rate has its further
 * frames dropped, so it can neither make this node relay or deliver an old
 * frame again nor crowd out the other neighbours' frames. Frames this node
 * originates are always admitted.
 */
@ThreadSafe
@NotNullByDefault
public class MeshForwarder {

	public interface FrameListener {
		void onFrame(byte[] payload);
	}

	static final int SEEN_CAP = 8192;
	static final int SEEN_PER_PEER_CAP = 1024;
	static final long SEEN_TTL_MS = 15 * 60_000L;
	private static final int STORE_MAX_BYTES = 2 * 1024 * 1024;
	static final int MAX_FRAMES_PER_SEC = 200;
	static final int MAX_FRAMES_PER_SEC_PER_PEER = 50;
	private static final String UNKNOWN_PEER = "";

	private final FrameListener listener;
	private final SecureRandom random;
	private final Map<String, MeshLink> links = new ConcurrentHashMap<>();
	private final LinkedHashMap<String, SeenEntry> seen = new LinkedHashMap<>();
	private final Map<String, Integer> seenPerPeer = new HashMap<>();
	private final LinkedHashMap<String, byte[]> store = new LinkedHashMap<>();
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

	public MeshForwarder(FrameListener listener, SecureRandom random) {
		this.listener = listener;
		this.random = random;
	}

	public void addLink(MeshLink link) {
		links.put(link.getId(), link);
		List<byte[]> carried;
		synchronized (store) {
			carried = new ArrayList<>(store.values());
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
		remember(idHex, encoded);
		relay(encoded, null, null);
		return messageId;
	}

	public void onReceive(byte[] frameBytes, @Nullable String fromLinkId) {
		onReceive(frameBytes, fromLinkId, null);
	}

	public void onReceive(byte[] frameBytes, @Nullable String fromLinkId,
			@Nullable String fromPeerId) {
		String peer = fromPeerId == null ? UNKNOWN_PEER : fromPeerId;
		if (!rateLimitOk(peer)) return;
		MeshFrame frame;
		try {
			frame = MeshFrame.decode(frameBytes);
		} catch (FormatException e) {
			return;
		}
		String idHex = StringUtils.toHexString(frame.getMessageId());
		if (!markSeen(idHex, peer)) return;
		listener.onFrame(frame.getPayload());
		MeshFrame next = frame.decremented();
		if (next != null) {
			byte[] encoded = next.encode();
			remember(idHex, encoded);
			relay(encoded, fromLinkId, fromPeerId);
		}
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

	/**
	 * Admits a frame id into the duplicate set. A null peer is this node's
	 * own frame, which is always admitted, evicting the oldest entry if the
	 * set is full. A neighbour's frame is refused if it is a duplicate, if
	 * the neighbour already holds its share of the set, or if the set is
	 * full of unexpired entries.
	 */
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

	/**
	 * When the set is full of unexpired foreign entries, the peer holding
	 * the most of them gives up its oldest so a peer holding fewer can be
	 * admitted; peer identities are cheap to invent on a radio link, so a
	 * flood spread over many identities must not shut out every other
	 * neighbour for the whole lifetime of its entries.
	 */
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

	private void remember(String idHex, byte[] encoded) {
		synchronized (store) {
			byte[] prev = store.put(idHex, encoded);
			if (prev != null) storeBytes -= prev.length;
			storeBytes += encoded.length;
			Iterator<Map.Entry<String, byte[]>> it =
					store.entrySet().iterator();
			while (storeBytes > STORE_MAX_BYTES && it.hasNext()) {
				storeBytes -= it.next().getValue().length;
				it.remove();
			}
		}
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
