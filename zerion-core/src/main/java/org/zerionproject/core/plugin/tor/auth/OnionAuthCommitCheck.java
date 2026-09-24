package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.annotation.Nullable;

/**
 * Whether a received AUTH_COMMIT may move a pair to AUTH_REQUIRED. A commit
 * names the receiver's authorized service and the fingerprint of the
 * sender's client key as the sender knows them, and carries the sender's
 * service generation. Every condition is bound to the persisted state: a
 * commit that is stale, from the future, for another service, for another
 * key, before both directions were proven, or for a revoked contact
 * changes nothing. The channel guarantees the record came from the contact
 * whose group it arrived in.
 */
@NotNullByDefault
public final class OnionAuthCommitCheck {

	public enum Verdict {
		ACCEPT,
		UNSUPPORTED_VERSION,
		STALE_GENERATION,
		FUTURE_GENERATION,
		ONION_MISMATCH,
		KEY_MISMATCH,
		LOCAL_KEY_MISSING,
		NOT_READY,
		REVOKED
	}

	private OnionAuthCommitCheck() {
	}

	/**
	 * @param r the persisted record for the contact the commit came from
	 * @param commit the received record
	 * @param localGen this device's current authorized service generation
	 * @param localOnion this device's current authorized service address
	 */
	public static Verdict check(OnionAuthRecord r,
			OnionAuthRecords.Record commit, long localGen,
			@Nullable String localOnion) {
		if (r.state == State.REVOKED) return Verdict.REVOKED;
		if (commit.version != OnionAuthRecords.PROTOCOL_VERSION) {
			return Verdict.UNSUPPORTED_VERSION;
		}
		if (commit.keyVersion < r.peerGen) return Verdict.STALE_GENERATION;
		if (commit.keyVersion > r.peerGen) return Verdict.FUTURE_GENERATION;
		if (r.dialPrivateKey == null || r.dialPublicKey == null
				|| r.peerPublicKey == null) {
			return Verdict.LOCAL_KEY_MISSING;
		}
		if (r.localGen != localGen) return Verdict.STALE_GENERATION;
		if (localOnion == null || commit.onion == null
				|| !commit.onion.equals(localOnion)) {
			return Verdict.ONION_MISMATCH;
		}
		if (commit.fingerprint == null || !Arrays.equals(commit.fingerprint,
				fingerprint(r.peerPublicKey))) {
			return Verdict.KEY_MISMATCH;
		}
		if (!r.readyReceived || !r.probeSucceeded
				|| !r.peerProbeSucceeded) {
			return Verdict.NOT_READY;
		}
		return Verdict.ACCEPT;
	}

	/** SHA-256 of a client public key, as carried in a commit. */
	public static byte[] fingerprint(byte[] publicKey) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(publicKey);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
