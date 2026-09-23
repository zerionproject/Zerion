package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Derives a {@link ZwfSession} from a completed handshake's root key.
 *
 * <p>Everything is derived deterministically from the shared root key and the
 * {@code alice} role tiebreaker, using per-direction labels so that one
 * endpoint's send-side material equals the other endpoint's receive-side
 * material:
 * <pre>
 *   my send key   = deriveKey(alice ? A : B, rootKey)
 *   my recv key   = deriveKey(alice ? B : A, rootKey)
 * </pre>
 * Since the peer computes with the opposite {@code alice}, my send key equals
 * the peer's recv key and vice versa. The two directions therefore never share a
 * chain, tag, or nonce space.
 *
 * <p>For a first session (fresh pairing) the Mode 3-Full ratchet starts from a
 * fresh initial state on each side; the peers' ML-KEM public keys are advertised
 * in-band in the first frames (the first message per direction is the
 * zero-ciphertext sentinel, after which the per-message post-quantum path
 * engages). Every later connection starts the same way: no Mode 3-Full state
 * is carried across connections, so the peer's key is re-learned in-band and
 * nothing of a finished connection's key material survives it.
 */
@NotNullByDefault
public class ZwfSessionFactory {

	private static final String A_ROOT = "org.zerionproject.transport/DIR_A_ROOT";
	private static final String B_ROOT = "org.zerionproject.transport/DIR_B_ROOT";
	private static final String A_TAG = "org.zerionproject.transport/DIR_A_TAG";
	private static final String B_TAG = "org.zerionproject.transport/DIR_B_TAG";
	private static final String A_HDR = "org.zerionproject.transport/DIR_A_HEADER";
	private static final String B_HDR = "org.zerionproject.transport/DIR_B_HEADER";

	private final CryptoComponent crypto;
	private final Mode3FullRatchet mode3FullRatchet;

	public ZwfSessionFactory(CryptoComponent crypto,
			Mode3FullRatchet mode3FullRatchet) {
		this.crypto = crypto;
		this.mode3FullRatchet = mode3FullRatchet;
	}

	/**
	 * Derives a first session for a freshly paired contact, starting the Mode
	 * 3-Full ratchet from a fresh initial state.
	 */
	public ZwfSession deriveSession(SecretKey rootKey, boolean alice) {
		return deriveSession(rootKey, alice, mode3FullRatchet.createInitialState());
	}

	/**
	 * The receive-side tag key for recognising a contact's incoming streams,
	 * derived directly from the root key and role without building a full
	 * session (used to seed the transport's tag recogniser for every contact).
	 */
	public SecretKey deriveRecvTagKey(SecretKey rootKey, boolean alice) {
		return crypto.deriveKey(alice ? B_TAG : A_TAG, rootKey);
	}

	private ZwfSession deriveSession(SecretKey rootKey, boolean alice,
			Mode3FullState sharedM3f) {
		SecretKey sendRootKey = crypto.deriveKey(alice ? A_ROOT : B_ROOT, rootKey);
		SecretKey recvRootKey = crypto.deriveKey(alice ? B_ROOT : A_ROOT, rootKey);
		SecretKey sendTagKey = crypto.deriveKey(alice ? A_TAG : B_TAG, rootKey);
		SecretKey recvTagKey = crypto.deriveKey(alice ? B_TAG : A_TAG, rootKey);
		SecretKey sendHeaderKey = crypto.deriveKey(alice ? A_HDR : B_HDR, rootKey);
		SecretKey recvHeaderKey = crypto.deriveKey(alice ? B_HDR : A_HDR, rootKey);

		KeyPair sendDhKp = crypto.generateAgreementKeyPair();
		KeyPair recvDhKp = crypto.generateAgreementKeyPair();
		PcsSessionState sendState = PcsSessionState.createInitialMode3Full(
				sendRootKey, sendRootKey, new DhRatchetState(sendDhKp, null),
				sharedM3f);
		PcsSessionState recvState = PcsSessionState.createInitialMode3Full(
				recvRootKey, recvRootKey, new DhRatchetState(recvDhKp, null),
				sharedM3f);

		return new ZwfSession(sendState, recvState, sendTagKey, recvTagKey,
				sendHeaderKey, recvHeaderKey, alice);
	}
}
