package org.zerionproject.transport.mesh;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class MeshForwarder {

	public interface FrameListener {
		void onFrame(byte[] payload);
	}

	private static final int SEEN_CAP = 8192;
	private static final int STORE_MAX_BYTES = 2 * 1024 * 1024;
	private static final int MAX_FRAMES_PER_SEC = 200;

	private final FrameListener listener;
	private final SecureRandom random;
	private final Map<String, MeshLink> links = new ConcurrentHashMap<>();
	private final LinkedHashSet<String> seen = new LinkedHashSet<>();
	private final LinkedHashMap<String, byte[]> store = new LinkedHashMap<>();
	private long storeBytes = 0;
	private long rateWindowStart = 0;
	private int rateWindowCount = 0;

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
		markSeen(idHex);
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
		if (!rateLimitOk()) return;
		MeshFrame frame;
		try {
			frame = MeshFrame.decode(frameBytes);
		} catch (FormatException e) {
			return;
		}
		String idHex = StringUtils.toHexString(frame.getMessageId());
		if (!markSeen(idHex)) return;
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

	private boolean markSeen(String idHex) {
		synchronized (seen) {
			if (!seen.add(idHex)) return false;
			while (seen.size() > SEEN_CAP) {
				seen.remove(seen.iterator().next());
			}
			return true;
		}
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

	private synchronized boolean rateLimitOk() {
		long now = System.currentTimeMillis();
		if (now - rateWindowStart > 1000) {
			rateWindowStart = now;
			rateWindowCount = 0;
		}
		return ++rateWindowCount <= MAX_FRAMES_PER_SEC;
	}
}
