package org.briarproject.bramble.contact;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import javax.annotation.Nullable;

import static org.briarproject.bramble.api.contact.B3Constants.B3_HANDSHAKE_SESSION_LABEL;
import static org.briarproject.bramble.api.contact.B3Constants.B3_KEY_PROOF_LABEL;
import static org.briarproject.bramble.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.briarproject.bramble.api.contact.B3Constants.B3_ROLE_ALICE;
import static org.briarproject.bramble.api.contact.B3Constants.B3_ROLE_BOB;
import static org.briarproject.bramble.api.contact.B3Constants.B3_SESSION_ID_LEN;
import static org.briarproject.bramble.api.contact.B3Constants.B3_SIG_INPUT_LEN;
import static org.briarproject.bramble.api.contact.B3Constants.B3_SIG_LEN;

/**
 * B.3 — In-band hybrid-key signing.
 *
 * <p>Wire spec: {@code docs/wire/B3_B4_SPEC_v1.5.0.md} (iOS-authored)
 * and the placement decision {@code docs/wire/B3_RECORD_PLACEMENT.md}.
 * Byte-identical with iOS via the canonical vector at
 * {@code docs/wire/test_vectors/B3_v1.txt}.
 *
 * <p>Binds an ML-KEM-768 public key to an Ed25519 signing key with a
 * domain-separated, length-prefixed signature. Closes the
 * trust-on-first-use downgrade path on the post-quantum half of the
 * handshake.
 *
 * <p>Pure crypto + byte layout. Does not touch the wire record
 * ({@code CONTACT_INFO} BDF list slot[4]) — that integration lives in
 * {@code ContactExchangeManagerImpl} where the record is encoded /
 * decoded.
 */
@NotNullByDefault
public final class B3PqProof {

	private static final byte[] LABEL =
			B3_KEY_PROOF_LABEL.getBytes(StandardCharsets.UTF_8);

	private static final byte[] SESSION_KEY =
			B3_HANDSHAKE_SESSION_LABEL.getBytes(StandardCharsets.UTF_8);

	private static final int X25519_PUB_LEN = 32;

	private B3PqProof() {
	}

	/**
	 * Compute the role byte for the side whose ephemeral is
	 * {@code localEph} given the peer's ephemeral {@code remoteEph}.
	 * Both sides arrive at the same Alice/Bob assignment regardless
	 * of who saw whose pubkey first, because the comparison is on the
	 * raw 32-byte X25519 pubkey values with unsigned-byte semantics.
	 *
	 * @return {@link org.briarproject.bramble.api.contact.B3Constants#B3_ROLE_ALICE}
	 *         if {@code localEph} is lex-smaller than {@code remoteEph},
	 *         {@link org.briarproject.bramble.api.contact.B3Constants#B3_ROLE_BOB}
	 *         otherwise.
	 */
	public static byte roleFor(byte[] localEph, byte[] remoteEph) {
		requireLen(localEph, X25519_PUB_LEN, "localEph");
		requireLen(remoteEph, X25519_PUB_LEN, "remoteEph");
		return compareUnsigned(localEph, remoteEph) < 0
				? B3_ROLE_ALICE : B3_ROLE_BOB;
	}

	/**
	 * BLAKE2b-256 over the sorted concatenation of the two ephemerals,
	 * keyed with the session domain separator. The sort is what makes
	 * the sessionId symmetric — both sides produce the same 32 bytes.
	 */
	public static byte[] computeSessionId(byte[] localEph, byte[] remoteEph) {
		requireLen(localEph, X25519_PUB_LEN, "localEph");
		requireLen(remoteEph, X25519_PUB_LEN, "remoteEph");
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
				B3_SESSION_ID_LEN, null, null);
		digest.update(first, 0, first.length);
		digest.update(second, 0, second.length);
		byte[] out = new byte[B3_SESSION_ID_LEN];
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
		if (role != B3_ROLE_ALICE && role != B3_ROLE_BOB) {
			throw new IllegalArgumentException("role must be 0x01 or 0x02");
		}
		requireLen(sessionId, B3_SESSION_ID_LEN, "sessionId");
		requireLen(pqPubKey, B3_PQ_PUB_LEN, "pqPubKey");

		ByteBuffer buf = ByteBuffer.allocate(B3_SIG_INPUT_LEN)
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
		requireLen(signingPriv, 32, "signingPriv");
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
			byte[] pqPubKey, @Nullable byte[] sig) {
		requireLen(signingPub, 32, "signingPub");
		if (sig == null || sig.length != B3_SIG_LEN) return false;
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

	/**
	 * Length check for a byte array. The class is {@code @NotNullByDefault}
	 * so callers cannot legitimately pass null; this method still does an
	 * explicit non-null check via {@link Objects#requireNonNull} so the
	 * IDE's null-flow analyser narrows the type after the call returns,
	 * which would otherwise complain about the buffer arithmetic that
	 * follows.
	 */
	private static void requireLen(byte[] bytes, int expectedLen,
			String name) {
		Objects.requireNonNull(bytes, name + " must not be null");
		if (bytes.length != expectedLen) {
			throw new IllegalArgumentException(name + " must be "
					+ expectedLen + " bytes (got " + bytes.length + ")");
		}
	}
}
