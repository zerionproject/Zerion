package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

/**
 * The opaque sealed-sender envelope carried by a relay, as specified in
 * docs/protocol/ASYNC-SEALED-SENDER.md. This class is only the wire format:
 * it holds and
 * (de)serialises the fields. Deriving the message key, sealing, and opening are
 * done elsewhere and are gated behind an external cryptographer review before
 * any use.
 *
 * <p>Wire layout (all integers big-endian):
 * <pre>
 *   0     version           1
 *   1     prekeyKind        1     0x01 one-time, 0x00 signed-prekey
 *   2     prekeyId          16
 *   18    signedPrekeyId    4     uint32
 *   22    senderEphemeralPub 1216 hybrid agreement public key
 *   1238  kemCiphertext     1088  ML-KEM-768 ciphertext
 *   2326  ttl               4     uint32 seconds (relay-visible, advisory)
 *   2330  dedupId           16    relay-visible
 *   2346  ciphertextLen     4     uint32 length of the AEAD blob
 *   2350  aeadBlob          var   Poly1305 tag(16) || XSalsa20 ciphertext
 * </pre>
 */
@NotNullByDefault
public class AsyncEnvelope {

	public static final int VERSION = 0x01;
	public static final int PREKEY_KIND_ONE_TIME = 0x01;
	public static final int PREKEY_KIND_SIGNED = 0x00;

	public static final int PREKEY_ID_BYTES = 16;
	public static final int DEDUP_ID_BYTES = 16;
	static final int EPHEMERAL_PUB_BYTES =
			PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
	static final int KEM_CIPHERTEXT_BYTES =
			PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;

	static final int OFF_VERSION = 0;
	static final int OFF_PREKEY_KIND = 1;
	static final int OFF_PREKEY_ID = 2;
	static final int OFF_SIGNED_PREKEY_ID = OFF_PREKEY_ID + PREKEY_ID_BYTES;
	public static final int OFF_EPHEMERAL_PUB =
			OFF_SIGNED_PREKEY_ID + ByteUtils.INT_32_BYTES;
	public static final int OFF_KEM_CIPHERTEXT =
			OFF_EPHEMERAL_PUB + EPHEMERAL_PUB_BYTES;
	public static final int OFF_TTL =
			OFF_KEM_CIPHERTEXT + KEM_CIPHERTEXT_BYTES;
	static final int OFF_DEDUP_ID = OFF_TTL + ByteUtils.INT_32_BYTES;
	static final int OFF_CIPHERTEXT_LEN = OFF_DEDUP_ID + DEDUP_ID_BYTES;
	public static final int HEADER_BYTES =
			OFF_CIPHERTEXT_LEN + ByteUtils.INT_32_BYTES;

	/** Bounds the AEAD blob so a hostile envelope cannot force a huge
	 * allocation. Fixed inner overhead is ~5.4 KB, so this allows a payload of
	 * roughly 1 MB. */
	public static final int MAX_AEAD_BLOB_BYTES = 6 * 1024 * 1024;

	private final int prekeyKind;
	private final byte[] prekeyId;
	private final long signedPrekeyId;
	private final byte[] senderEphemeralPub;
	private final byte[] kemCiphertext;
	private final long ttl;
	private final byte[] dedupId;
	private final byte[] aeadBlob;

	public AsyncEnvelope(int prekeyKind, byte[] prekeyId, long signedPrekeyId,
			byte[] senderEphemeralPub, byte[] kemCiphertext, long ttl,
			byte[] dedupId, byte[] aeadBlob) {
		if (prekeyId.length != PREKEY_ID_BYTES
				|| senderEphemeralPub.length != EPHEMERAL_PUB_BYTES
				|| kemCiphertext.length != KEM_CIPHERTEXT_BYTES
				|| dedupId.length != DEDUP_ID_BYTES) {
			throw new IllegalArgumentException("bad field length");
		}
		this.prekeyKind = prekeyKind;
		this.prekeyId = prekeyId;
		this.signedPrekeyId = signedPrekeyId;
		this.senderEphemeralPub = senderEphemeralPub;
		this.kemCiphertext = kemCiphertext;
		this.ttl = ttl;
		this.dedupId = dedupId;
		this.aeadBlob = aeadBlob;
	}

	public int getPrekeyKind() {
		return prekeyKind;
	}

	public byte[] getPrekeyId() {
		return prekeyId;
	}

	public long getSignedPrekeyId() {
		return signedPrekeyId;
	}

	public byte[] getSenderEphemeralPub() {
		return senderEphemeralPub;
	}

	public byte[] getKemCiphertext() {
		return kemCiphertext;
	}

	public long getTtl() {
		return ttl;
	}

	public byte[] getDedupId() {
		return dedupId;
	}

	public byte[] getAeadBlob() {
		return aeadBlob;
	}

	public byte[] encode() {
		byte[] out = new byte[HEADER_BYTES + aeadBlob.length];
		out[OFF_VERSION] = (byte) VERSION;
		out[OFF_PREKEY_KIND] = (byte) prekeyKind;
		System.arraycopy(prekeyId, 0, out, OFF_PREKEY_ID, PREKEY_ID_BYTES);
		ByteUtils.writeUint32(signedPrekeyId, out, OFF_SIGNED_PREKEY_ID);
		System.arraycopy(senderEphemeralPub, 0, out, OFF_EPHEMERAL_PUB,
				EPHEMERAL_PUB_BYTES);
		System.arraycopy(kemCiphertext, 0, out, OFF_KEM_CIPHERTEXT,
				KEM_CIPHERTEXT_BYTES);
		ByteUtils.writeUint32(ttl, out, OFF_TTL);
		System.arraycopy(dedupId, 0, out, OFF_DEDUP_ID, DEDUP_ID_BYTES);
		ByteUtils.writeUint32(aeadBlob.length, out, OFF_CIPHERTEXT_LEN);
		System.arraycopy(aeadBlob, 0, out, HEADER_BYTES, aeadBlob.length);
		return out;
	}

	public static AsyncEnvelope decode(byte[] in) throws FormatException {
		if (in.length < HEADER_BYTES) throw new FormatException();
		if ((in[OFF_VERSION] & 0xFF) != VERSION) throw new FormatException();
		int kind = in[OFF_PREKEY_KIND] & 0xFF;
		if (kind != PREKEY_KIND_ONE_TIME && kind != PREKEY_KIND_SIGNED) {
			throw new FormatException();
		}
		long cipherLen = ByteUtils.readUint32(in, OFF_CIPHERTEXT_LEN);
		if (cipherLen < 0 || cipherLen > MAX_AEAD_BLOB_BYTES
				|| in.length != HEADER_BYTES + cipherLen) {
			throw new FormatException();
		}
		byte[] prekeyId = Arrays.copyOfRange(in, OFF_PREKEY_ID,
				OFF_PREKEY_ID + PREKEY_ID_BYTES);
		long signedPrekeyId = ByteUtils.readUint32(in, OFF_SIGNED_PREKEY_ID);
		byte[] ephemeralPub = Arrays.copyOfRange(in, OFF_EPHEMERAL_PUB,
				OFF_EPHEMERAL_PUB + EPHEMERAL_PUB_BYTES);
		byte[] kemCiphertext = Arrays.copyOfRange(in, OFF_KEM_CIPHERTEXT,
				OFF_KEM_CIPHERTEXT + KEM_CIPHERTEXT_BYTES);
		long ttl = ByteUtils.readUint32(in, OFF_TTL);
		byte[] dedupId = Arrays.copyOfRange(in, OFF_DEDUP_ID,
				OFF_DEDUP_ID + DEDUP_ID_BYTES);
		byte[] aeadBlob = Arrays.copyOfRange(in, HEADER_BYTES, in.length);
		return new AsyncEnvelope(kind, prekeyId, signedPrekeyId, ephemeralPub,
				kemCiphertext, ttl, dedupId, aeadBlob);
	}
}
