package com.professor.zerion.android.contact.identity;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * B.3 — In-band hybrid-key signing.
 *
 * Wire spec: docs/wire/B3_B4_SPEC_v1.5.0.md (iOS-authored, byte-identical
 * with this Android implementation).
 *
 * Binds an ML-KEM-768 public key to an Ed25519 signing key with a
 * domain-separated, length-prefixed signature. Closes the
 * trust-on-first-use downgrade path on the post-quantum half of the
 * handshake.
 *
 * This class is pure crypto + byte layout. It does not touch the wire
 * record (CONTACT_INFO BDF list slot[4]) — that integration lives in the
 * messaging client where the record is encoded / decoded.
 */
public final class B3PqProof {

	/** Domain separator for the signature input. UTF-8, 22 bytes, no NUL. */
	private static final byte[] LABEL =
			"ZERION_PQ_KEY_PROOF_v1".getBytes(StandardCharsets.UTF_8);

	/** Domain separator used as the BLAKE2b-256 key for sessionId.
	 * UTF-8, 27 bytes, no NUL. */
	private static final byte[] SESSION_KEY =
			"ZERION_HANDSHAKE_SESSION_v1".getBytes(StandardCharsets.UTF_8);

	/** Role byte: lower-pubkey side (Alice / initiator). */
	public static final byte ROLE_ALICE = 0x01;

	/** Role byte: higher-pubkey side (Bob / responder). */
	public static final byte ROLE_BOB = 0x02;

	private static final int X25519_PUB_LEN = 32;
	private static final int SESSION_ID_LEN = 32;
	private static final int MLKEM_768_PUB_LEN = 1184;
	private static final int SIG_LEN = 64;

	/** Total signature input length: 4+22 + 1 + 4+32 + 4+1184 = 1251. */
	static final int SIG_INPUT_LEN = 1251;

	private B3PqProof() {
	}

	/**
	 * Compute the role byte for the side whose ephemeral is {@code localEph}
	 * given the peer's ephemeral {@code remoteEph}. Both sides arrive at the
	 * same Alice/Bob assignment regardless of who saw whose pubkey first
	 * because the comparison is on the raw 32-byte X25519 pubkey values
	 * with unsigned-byte semantics.
	 *
	 * @return {@link #ROLE_ALICE} if {@code localEph} is lex-smaller than
	 *         {@code remoteEph}, {@link #ROLE_BOB} otherwise.
	 */
	public static byte roleFor(byte[] localEph, byte[] remoteEph) {
		require(localEph != null && localEph.length == X25519_PUB_LEN,
				"localEph must be 32 bytes");
		require(remoteEph != null && remoteEph.length == X25519_PUB_LEN,
				"remoteEph must be 32 bytes");
		return compareUnsigned(localEph, remoteEph) < 0 ? ROLE_ALICE : ROLE_BOB;
	}

	/**
	 * BLAKE2b-256 over the sorted concatenation of the two ephemerals,
	 * keyed with the session domain separator. The sort is what makes the
	 * sessionId symmetric — both sides produce the same 32 bytes.
	 */
	public static byte[] computeSessionId(byte[] localEph, byte[] remoteEph) {
		require(localEph != null && localEph.length == X25519_PUB_LEN,
				"localEph must be 32 bytes");
		require(remoteEph != null && remoteEph.length == X25519_PUB_LEN,
				"remoteEph must be 32 bytes");
		byte[] first;
		byte[] second;
		if (compareUnsigned(localEph, remoteEph) < 0) {
			first = localEph;
			second = remoteEph;
		} else {
			first = remoteEph;
			second = localEph;
		}
		Blake2bDigest digest = new Blake2bDigest(SESSION_KEY,
				SESSION_ID_LEN, null, null);
		digest.update(first, 0, first.length);
		digest.update(second, 0, second.length);
		byte[] out = new byte[SESSION_ID_LEN];
		digest.doFinal(out, 0);
		return out;
	}

	/**
	 * Build the exact 1251-byte signature input per spec section 1.
	 *
	 * <pre>
	 * uint32_BE(len(label))     || label                      // 4 + 22  =   26
	 * uint8(role)                                              // 1       =    1
	 * uint32_BE(len(sessionId)) || sessionId                   // 4 + 32  =   36
	 * uint32_BE(len(pqPubKey))  || pqPubKey                    // 4 + 1184 = 1188
	 *                                                          // total   = 1251
	 * </pre>
	 */
	public static byte[] computeSigInput(byte role, byte[] sessionId,
			byte[] pqPubKey) {
		require(role == ROLE_ALICE || role == ROLE_BOB,
				"role must be 0x01 or 0x02");
		require(sessionId != null && sessionId.length == SESSION_ID_LEN,
				"sessionId must be 32 bytes");
		require(pqPubKey != null && pqPubKey.length == MLKEM_768_PUB_LEN,
				"pqPubKey must be 1184 bytes (ML-KEM-768)");

		ByteBuffer buf = ByteBuffer.allocate(SIG_INPUT_LEN)
				.order(ByteOrder.BIG_ENDIAN);
		buf.putInt(LABEL.length);
		buf.put(LABEL);
		buf.put(role);
		buf.putInt(sessionId.length);
		buf.put(sessionId);
		buf.putInt(pqPubKey.length);
		buf.put(pqPubKey);
		return buf.array();
	}

	/**
	 * Sign the binding from the publisher's side.
	 *
	 * @param signingPriv  raw 32-byte Ed25519 private seed
	 * @param localEph     the publisher's X25519 ephemeral pubkey
	 * @param remoteEph    the peer's X25519 ephemeral pubkey
	 * @param pqPubKey     the publisher's ML-KEM-768 pubkey
	 * @return the 64-byte detached Ed25519 signature
	 */
	public static byte[] sign(byte[] signingPriv,
			byte[] localEph, byte[] remoteEph, byte[] pqPubKey) {
		require(signingPriv != null && signingPriv.length == 32,
				"signingPriv must be 32 bytes (Ed25519 seed)");
		byte role = roleFor(localEph, remoteEph);
		byte[] sessionId = computeSessionId(localEph, remoteEph);
		byte[] input = computeSigInput(role, sessionId, pqPubKey);
		Ed25519PrivateKeyParameters sk =
				new Ed25519PrivateKeyParameters(signingPriv, 0);
		Ed25519Signer signer = new Ed25519Signer();
		signer.init(true, sk);
		signer.update(input, 0, input.length);
		return signer.generateSignature();
	}

	/**
	 * Verify a binding from the receiver's side.
	 *
	 * @param signingPub   raw 32-byte Ed25519 public key (the signer's)
	 * @param signerEph    the signer's X25519 ephemeral pubkey
	 * @param verifierEph  this side's X25519 ephemeral pubkey
	 * @param pqPubKey     the signer's ML-KEM-768 pubkey
	 * @param sig          the 64-byte signature from CONTACT_INFO slot[4]
	 * @return {@code true} iff the signature is valid for these inputs
	 */
	public static boolean verify(byte[] signingPub,
			byte[] signerEph, byte[] verifierEph,
			byte[] pqPubKey, byte[] sig) {
		require(signingPub != null && signingPub.length == 32,
				"signingPub must be 32 bytes");
		if (sig == null || sig.length != SIG_LEN) return false;
		byte signerRole = roleFor(signerEph, verifierEph);
		byte[] sessionId = computeSessionId(signerEph, verifierEph);
		byte[] input = computeSigInput(signerRole, sessionId, pqPubKey);
		Ed25519PublicKeyParameters pk =
				new Ed25519PublicKeyParameters(signingPub, 0);
		Ed25519Signer verifier = new Ed25519Signer();
		verifier.init(false, pk);
		verifier.update(input, 0, input.length);
		return verifier.verifySignature(sig);
	}

	/** Unsigned byte-by-byte lex compare. Java's signed-byte default
	 * would invert ordering above 0x7F — never use it for wire-format
	 * sorting. Matches {@code org.briarproject.bramble.api.Bytes.compare}. */
	static int compareUnsigned(byte[] a, byte[] b) {
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			int au = a[i] & 0xFF;
			int bu = b[i] & 0xFF;
			if (au < bu) return -1;
			if (au > bu) return 1;
		}
		return a.length - b.length;
	}

	private static void require(boolean condition, String msg) {
		if (!condition) throw new IllegalArgumentException(msg);
	}
}
