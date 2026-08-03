package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.transport.TransportConstants.MAC_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_NONCE_LENGTH;

/**
 * The asynchronous sealed-sender crypto core (Phase 2 design, sections 4 and 5),
 * built entirely by composing the existing audited primitives: hybrid
 * encapsulation + one-pass agreement for the message key, keyed BLAKE2b for the
 * AEAD key and nonce, an inner hybrid signature for sender authentication, and
 * XSalsa20-Poly1305 for the record. No new cryptographic primitive is
 * introduced.
 *
 * <p>NOT WIRED INTO ANY TRANSPORT and gated behind review. It carries no live
 * traffic; it exists to be reviewed and tested.
 *
 * <p>Deviation from the draft design, section 4.4: the draft folded
 * {@code sendTimestamp} into the key-deriving transcript, but that field is not
 * on the outer wire, so a recipient could not rebuild the transcript to derive
 * the key. Here the key-deriving transcript uses only fields the recipient can
 * reconstruct from the wire and its own identity; {@code sendTimestamp} lives in
 * the signed inner record instead, so it is authenticated without being needed
 * to derive the key.
 */
@NotNullByDefault
public class AsyncSealedSender {

	private static final String LABEL_AGREE = "ASYNC_SEALED_SENDER_V1";
	private static final String LABEL_ENVELOPE_KEY =
			"org.zerionproject.async/ENVELOPE_KEY";
	private static final String LABEL_ENVELOPE_NONCE =
			"org.zerionproject.async/ENVELOPE_NONCE";
	private static final String LABEL_SENDER_AUTH =
			"org.zerionproject.async/SENDER_AUTH";

	private static final int SIG_PUB = HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
	private static final int AGREE_PUB = HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
	private static final int SIG = HYBRID_SIGNATURE_BYTES;
	private static final int TTL = ByteUtils.INT_32_BYTES;
	private static final int TS = ByteUtils.INT_64_BYTES;
	private static final int DEDUP = AsyncEnvelope.DEDUP_ID_BYTES;

	private final CryptoComponent crypto;

	public AsyncSealedSender(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	public byte[] seal(SealRequest r) throws GeneralSecurityException {
		KeyPair ephemeral = crypto.generateHybridAgreementKeyPair();
		SecretKey mk = null;
		try {
			byte[] ephemeralPub = ephemeral.getPublic().getEncoded();
			HybridEncapsulationResult enc =
					crypto.hybridEncapsulate(r.recipientAgreementPub);
			byte[] kemCt = enc.getCiphertext();
			byte[] transcript = transcript(r.recipientIdentitySigPub,
					r.recipientIdentityAgreePub, r.prekeyKind, r.prekeyId,
					r.signedPrekeyId, ephemeralPub, kemCt, r.ttl, r.dedupId);
			mk = crypto.deriveHybridSharedSecretAsResponder(LABEL_AGREE,
					r.recipientAgreementPub, ephemeral, enc.getSharedSecret(),
					transcript);
			enc.clearSecret();
			byte[] innerPrefix = concat(r.senderIdentitySigPub,
					new byte[] {(byte) r.messageType}, r.payload,
					uint32(r.ttl), r.dedupId, uint64(r.sendTimestamp));
			byte[] signedContent = concat(transcript, innerPrefix);
			byte[] sig = crypto.hybridSign(LABEL_SENDER_AUTH, signedContent,
					r.senderIdentitySigPrivateKey);
			byte[] innerRecord = concat(innerPrefix, sig);
			byte[] aeadBlob = aead(true, mk, transcript, innerRecord);
			AsyncEnvelope env = new AsyncEnvelope(r.prekeyKind, r.prekeyId,
					r.signedPrekeyId, ephemeralPub, kemCt, r.ttl, r.dedupId,
					aeadBlob);
			return env.encode();
		} finally {
			clearAgreementPrivateKey(ephemeral);
			if (mk != null) mk.clear();
		}
	}

	/**
	 * Opens an envelope, returning the payload and the authenticated sender
	 * identity, or throwing if any check fails (fail-closed). This is the
	 * crypto core only. The caller MUST additionally, and these are
	 * load-bearing for the forward-secrecy and replay guarantees:
	 * <ul>
	 * <li>check the returned {@code senderIdentitySigPub} is a known, accepted
	 * contact (open accepts a valid signature by <em>any</em> identity, since
	 * the identity is carried inside the sealed record);
	 * <li>consume the one-time prekey used by this envelope and delete its
	 * private key, so it cannot open a replayed envelope;
	 * <li>record the envelope's dedup id in a durable seen-set and reject
	 * repeats, which is the replay defence on the reused signed-prekey path.
	 * </ul>
	 */
	public OpenedMessage open(byte[] envelopeBytes, OpenRequest r)
			throws GeneralSecurityException, FormatException {
		AsyncEnvelope env = AsyncEnvelope.decode(envelopeBytes);
		PublicKey ephemeralPub = crypto.getHybridAgreementKeyParser()
				.parsePublicKey(env.getSenderEphemeralPub());
		byte[] transcript = transcript(r.recipientIdentitySigPub,
				r.recipientIdentityAgreePub, env.getPrekeyKind(),
				env.getPrekeyId(), env.getSignedPrekeyId(),
				env.getSenderEphemeralPub(), env.getKemCiphertext(),
				env.getTtl(), env.getDedupId());
		SecretKey mk = crypto.deriveHybridSharedSecret(LABEL_AGREE,
				ephemeralPub, r.recipientAgreementKeyPair,
				env.getKemCiphertext(), transcript);
		byte[] innerRecord;
		try {
			innerRecord = aead(false, mk, transcript, env.getAeadBlob());
		} finally {
			mk.clear();
		}

		int trailer = TTL + DEDUP + TS + SIG;
		int front = SIG_PUB + 1;
		if (innerRecord.length < front + trailer) throw new FormatException();
		int payloadLen = innerRecord.length - front - trailer;

		byte[] senderSigPub = Arrays.copyOfRange(innerRecord, 0, SIG_PUB);
		int messageType = innerRecord[SIG_PUB] & 0xFF;
		byte[] payload = Arrays.copyOfRange(innerRecord, front,
				front + payloadLen);
		int off = front + payloadLen;
		long ttlInner = ByteUtils.readUint32(innerRecord, off);
		off += TTL;
		byte[] dedupInner = Arrays.copyOfRange(innerRecord, off, off + DEDUP);
		off += DEDUP;
		long sendTimestamp = ByteUtils.readUint64(innerRecord, off);
		off += TS;
		byte[] sig = Arrays.copyOfRange(innerRecord, off, off + SIG);

		if (ttlInner != env.getTtl()
				|| !constantTimeEquals(dedupInner, env.getDedupId())) {
			throw new FormatException();
		}
		byte[] innerPrefix = Arrays.copyOfRange(innerRecord, 0, off);
		byte[] signedContent = concat(transcript, innerPrefix);
		PublicKey senderSig = crypto.getHybridSignatureKeyParser()
				.parsePublicKey(senderSigPub);
		if (!crypto.verifyHybridSignature(sig, LABEL_SENDER_AUTH,
				signedContent, senderSig)) {
			throw new FormatException();
		}
		return new OpenedMessage(senderSigPub, messageType, payload,
				sendTimestamp);
	}

	private byte[] transcript(byte[] recipientIdentitySigPub,
			byte[] recipientIdentityAgreePub, int prekeyKind, byte[] prekeyId,
			long signedPrekeyId, byte[] ephemeralPub, byte[] kemCt, long ttl,
			byte[] dedupId) {
		// Every transcript field must be fixed-size for the raw concatenation
		// to be unambiguous. The wire-derived fields are already length-checked
		// by AsyncEnvelope; the caller-supplied identity fields are checked
		// here so a future caller cannot introduce a canonicalization ambiguity.
		if (recipientIdentitySigPub.length != SIG_PUB
				|| recipientIdentityAgreePub.length != AGREE_PUB) {
			throw new IllegalArgumentException("bad identity key length");
		}
		return concat(new byte[] {(byte) AsyncEnvelope.VERSION},
				recipientIdentitySigPub, recipientIdentityAgreePub,
				new byte[] {(byte) prekeyKind}, prekeyId,
				uint32(signedPrekeyId), ephemeralPub, kemCt, uint32(ttl),
				dedupId);
	}

	private byte[] aead(boolean encrypt, SecretKey mk, byte[] transcript,
			byte[] input) throws GeneralSecurityException {
		SecretKey envKey = crypto.deriveKey(LABEL_ENVELOPE_KEY, mk);
		try {
			byte[] nonce = Arrays.copyOf(
					crypto.mac(LABEL_ENVELOPE_NONCE, mk, transcript),
					STREAM_HEADER_NONCE_LENGTH);
			XSalsa20Poly1305AuthenticatedCipher cipher =
					new XSalsa20Poly1305AuthenticatedCipher();
			cipher.init(encrypt, envKey, nonce);
			int outLen = encrypt ? input.length + MAC_LENGTH
					: input.length - MAC_LENGTH;
			if (outLen < 0) {
				throw new GeneralSecurityException("short input");
			}
			byte[] out = new byte[outLen];
			cipher.process(input, 0, input.length, out, 0);
			return out;
		} finally {
			envKey.clear();
		}
	}

	private static void clearAgreementPrivateKey(KeyPair keyPair) {
		PrivateKey priv = keyPair.getPrivate();
		if (priv instanceof HybridAgreementPrivateKey) {
			((HybridAgreementPrivateKey) priv).clear();
		}
	}

	private static byte[] uint32(long v) {
		byte[] b = new byte[ByteUtils.INT_32_BYTES];
		ByteUtils.writeUint32(v, b, 0);
		return b;
	}

	private static byte[] uint64(long v) {
		byte[] b = new byte[ByteUtils.INT_64_BYTES];
		ByteUtils.writeUint64(v, b, 0);
		return b;
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a.length != b.length) return false;
		int r = 0;
		for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
		return r == 0;
	}

	public static class SealRequest {
		public PublicKey recipientAgreementPub;
		public int prekeyKind;
		public byte[] prekeyId;
		public long signedPrekeyId;
		public byte[] recipientIdentitySigPub;
		public byte[] recipientIdentityAgreePub;
		public byte[] senderIdentitySigPub;
		public PrivateKey senderIdentitySigPrivateKey;
		public int messageType;
		public byte[] payload;
		public long ttl;
		public byte[] dedupId;
		public long sendTimestamp;
	}

	public static class OpenRequest {
		public KeyPair recipientAgreementKeyPair;
		public byte[] recipientIdentitySigPub;
		public byte[] recipientIdentityAgreePub;
	}

	public static class OpenedMessage {
		private final byte[] senderIdentitySigPub;
		private final int messageType;
		private final byte[] payload;
		private final long sendTimestamp;

		OpenedMessage(byte[] senderIdentitySigPub, int messageType,
				byte[] payload, long sendTimestamp) {
			this.senderIdentitySigPub = senderIdentitySigPub;
			this.messageType = messageType;
			this.payload = payload;
			this.sendTimestamp = sendTimestamp;
		}

		public byte[] getSenderIdentitySigPub() {
			return senderIdentitySigPub;
		}

		public int getMessageType() {
			return messageType;
		}

		public byte[] getPayload() {
			return payload;
		}

		public long getSendTimestamp() {
			return sendTimestamp;
		}
	}
}
