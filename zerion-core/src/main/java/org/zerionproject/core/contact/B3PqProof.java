package org.zerionproject.core.contact;

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

import static org.zerionproject.core.api.contact.B3Constants.B3_HANDSHAKE_SESSION_LABEL;
import static org.zerionproject.core.api.contact.B3Constants.B3_KEY_PROOF_LABEL;
import static org.zerionproject.core.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_ROLE_ALICE;
import static org.zerionproject.core.api.contact.B3Constants.B3_ROLE_BOB;
import static org.zerionproject.core.api.contact.B3Constants.B3_SESSION_ID_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SIG_INPUT_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SIG_LEN;

@NotNullByDefault
public final class B3PqProof {

	private static final byte[] LABEL =
			B3_KEY_PROOF_LABEL.getBytes(StandardCharsets.UTF_8);

	private static final byte[] SESSION_KEY =
			B3_HANDSHAKE_SESSION_LABEL.getBytes(StandardCharsets.UTF_8);

	private static final int X25519_PUB_LEN = 32;

	private B3PqProof() {
	}

	public static byte roleFor(byte[] localEph, byte[] remoteEph) {
		requireLen(localEph, X25519_PUB_LEN, "localEph");
		requireLen(remoteEph, X25519_PUB_LEN, "remoteEph");
		return compareUnsigned(localEph, remoteEph) < 0
				? B3_ROLE_ALICE : B3_ROLE_BOB;
	}

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

	private static void requireLen(byte[] bytes, int expectedLen,
			String name) {
		Objects.requireNonNull(bytes, name + " must not be null");
		if (bytes.length != expectedLen) {
			throw new IllegalArgumentException(name + " must be "
					+ expectedLen + " bytes (got " + bytes.length + ")");
		}
	}
}
