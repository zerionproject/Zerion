package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The per-contact transport session established from a completed handshake: the
 * send and receive Mode 3-Full ratchet states plus the direction-separated tag
 * and stream-header keys.
 *
 * <p>Send and receive are separated so the two directions never share a chain,
 * tag, or nonce space. The derivation is mirrored between the two endpoints:
 * one side's send-side keys equal the other side's receive-side keys, so streams
 * decrypt and tags are recognised across the link.
 */
@NotNullByDefault
public final class ZwfSession {

	private final PcsSessionState sendState;
	private final PcsSessionState recvState;
	private final SecretKey sendTagKey;
	private final SecretKey recvTagKey;
	private final SecretKey sendHeaderKey;
	private final SecretKey recvHeaderKey;
	private final boolean alice;

	ZwfSession(PcsSessionState sendState, PcsSessionState recvState,
			SecretKey sendTagKey, SecretKey recvTagKey,
			SecretKey sendHeaderKey, SecretKey recvHeaderKey, boolean alice) {
		this.sendState = sendState;
		this.recvState = recvState;
		this.sendTagKey = sendTagKey;
		this.recvTagKey = recvTagKey;
		this.sendHeaderKey = sendHeaderKey;
		this.recvHeaderKey = recvHeaderKey;
		this.alice = alice;
	}

	/** Our handshake role: the alice-role endpoint originates streamId nonces
	 * with the originator bit set (see {@link org.zerionproject.wire.ZwfNonce}). */
	public boolean isAlice() {
		return alice;
	}

	public PcsSessionState getSendState() {
		return sendState;
	}

	public PcsSessionState getRecvState() {
		return recvState;
	}

	public SecretKey getSendTagKey() {
		return sendTagKey;
	}

	public SecretKey getRecvTagKey() {
		return recvTagKey;
	}

	public SecretKey getSendHeaderKey() {
		return sendHeaderKey;
	}

	public SecretKey getRecvHeaderKey() {
		return recvHeaderKey;
	}
}
