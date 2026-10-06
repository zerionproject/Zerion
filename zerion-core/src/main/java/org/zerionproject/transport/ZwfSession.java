package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public final class ZwfSession {

	public static final class RecvKeys {
		private final long epoch;
		private final PcsSessionState state;
		private final SecretKey tagKey;
		private final SecretKey headerKey;

		RecvKeys(long epoch, PcsSessionState state, SecretKey tagKey,
				SecretKey headerKey) {
			this.epoch = epoch;
			this.state = state;
			this.tagKey = tagKey;
			this.headerKey = headerKey;
		}

		public long getEpoch() {
			return epoch;
		}

		public PcsSessionState getState() {
			return state;
		}

		public SecretKey getTagKey() {
			return tagKey;
		}

		public SecretKey getHeaderKey() {
			return headerKey;
		}

		void clear() {
			tagKey.clear();
			headerKey.clear();
			SecretKey root = state.getRootKey();
			if (root != null) root.clear();
		}
	}

	private final long sendEpoch;
	private final PcsSessionState sendState;
	private final SecretKey sendTagKey;
	private final SecretKey sendHeaderKey;
	private final List<RecvKeys> recv;
	private final boolean alice;

	ZwfSession(PcsSessionState sendState, PcsSessionState recvState,
			SecretKey sendTagKey, SecretKey recvTagKey,
			SecretKey sendHeaderKey, SecretKey recvHeaderKey, boolean alice) {
		this(0, sendState, sendTagKey, sendHeaderKey,
				Collections.singletonList(new RecvKeys(0, recvState,
						recvTagKey, recvHeaderKey)), alice);
	}

	ZwfSession(long sendEpoch, PcsSessionState sendState,
			SecretKey sendTagKey, SecretKey sendHeaderKey, List<RecvKeys> recv,
			boolean alice) {
		if (recv.isEmpty()) throw new IllegalArgumentException();
		this.sendEpoch = sendEpoch;
		this.sendState = sendState;
		this.sendTagKey = sendTagKey;
		this.sendHeaderKey = sendHeaderKey;
		this.recv = Collections.unmodifiableList(new ArrayList<>(recv));
		this.alice = alice;
	}

	public boolean isAlice() {
		return alice;
	}

	public long getSendEpoch() {
		return sendEpoch;
	}

	public PcsSessionState getSendState() {
		return sendState;
	}

	public PcsSessionState getRecvState() {
		return recv.get(0).getState();
	}

	public SecretKey getSendTagKey() {
		return sendTagKey;
	}

	public SecretKey getRecvTagKey() {
		return recv.get(0).getTagKey();
	}

	public SecretKey getSendHeaderKey() {
		return sendHeaderKey;
	}

	public SecretKey getRecvHeaderKey() {
		return recv.get(0).getHeaderKey();
	}

	public List<RecvKeys> getRecvKeys() {
		return recv;
	}

	@Nullable
	public RecvKeys getRecvKeys(long epoch) {
		for (RecvKeys r : recv) if (r.getEpoch() == epoch) return r;
		return null;
	}

	public void clear() {
		sendTagKey.clear();
		sendHeaderKey.clear();
		SecretKey sendRoot = sendState.getRootKey();
		if (sendRoot != null) sendRoot.clear();
		for (RecvKeys r : recv) r.clear();
	}
}
