package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;

/**
 * A recipient's signed prekey bundle (Phase 2 design, section 3): the identity
 * keys, a signed prekey, and a batch of one-time prekeys, each a hybrid
 * agreement key, all signed by the recipient's hybrid identity. A sender picks a
 * prekey from a verified bundle and seals to it with {@link AsyncSealedSender}.
 *
 * <p>Wire layout (big-endian):
 * <pre>
 *   version             1
 *   identitySigPub      1984
 *   identityAgreePub    1216
 *   signedPrekeyId      4
 *   signedPrekeyPub     1216
 *   signedPrekeyExpiry  8
 *   signedPrekeySig     3373   over version||signedPrekeyId||signedPrekeyPub||expiry
 *   oneTimePrekeyCount  2
 *   oneTimePrekeys[]    each { id 16, pub 1216 }
 *   bundleSig           3373   over everything above
 * </pre>
 * This class is the wire format and the signature check; generating, storing,
 * and consuming prekey private keys is a separate (infrastructure) concern.
 */
@NotNullByDefault
public class AsyncPrekeyBundle {

	public static final int VERSION = 0x01;
	public static final int ONE_TIME_PREKEY_ID_BYTES = 16;
	public static final int MAX_ONE_TIME_PREKEYS = 1000;

	private static final String LABEL_SIGNED_PREKEY =
			"org.zerionproject.async/SIGNED_PREKEY";
	private static final String LABEL_BUNDLE =
			"org.zerionproject.async/PREKEY_BUNDLE";

	private static final int SIG_PUB = HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
	private static final int AGREE_PUB = HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
	private static final int SIG = HYBRID_SIGNATURE_BYTES;
	private static final int ONE_TIME_ENTRY = ONE_TIME_PREKEY_ID_BYTES
			+ AGREE_PUB;

	public static class OneTimePrekey {
		public final byte[] id;
		public final byte[] pub;

		public OneTimePrekey(byte[] id, byte[] pub) {
			if (id.length != ONE_TIME_PREKEY_ID_BYTES
					|| pub.length != AGREE_PUB) {
				throw new IllegalArgumentException("bad prekey length");
			}
			this.id = id;
			this.pub = pub;
		}
	}

	private final byte[] identitySigPub;
	private final byte[] identityAgreePub;
	private final long signedPrekeyId;
	private final byte[] signedPrekeyPub;
	private final long signedPrekeyExpiry;
	private final byte[] signedPrekeySig;
	private final List<OneTimePrekey> oneTimePrekeys;
	private final byte[] bundleSig;

	private AsyncPrekeyBundle(byte[] identitySigPub, byte[] identityAgreePub,
			long signedPrekeyId, byte[] signedPrekeyPub,
			long signedPrekeyExpiry, byte[] signedPrekeySig,
			List<OneTimePrekey> oneTimePrekeys, byte[] bundleSig) {
		this.identitySigPub = identitySigPub;
		this.identityAgreePub = identityAgreePub;
		this.signedPrekeyId = signedPrekeyId;
		this.signedPrekeyPub = signedPrekeyPub;
		this.signedPrekeyExpiry = signedPrekeyExpiry;
		this.signedPrekeySig = signedPrekeySig;
		this.oneTimePrekeys = oneTimePrekeys;
		this.bundleSig = bundleSig;
	}

	public byte[] getIdentitySigPub() {
		return identitySigPub;
	}

	public byte[] getIdentityAgreePub() {
		return identityAgreePub;
	}

	public long getSignedPrekeyId() {
		return signedPrekeyId;
	}

	public byte[] getSignedPrekeyPub() {
		return signedPrekeyPub;
	}

	public long getSignedPrekeyExpiry() {
		return signedPrekeyExpiry;
	}

	public List<OneTimePrekey> getOneTimePrekeys() {
		return oneTimePrekeys;
	}

	/** Builds and signs a bundle with the recipient's hybrid identity. */
	public static AsyncPrekeyBundle create(CryptoComponent crypto,
			byte[] identitySigPub, PrivateKey identitySigPriv,
			byte[] identityAgreePub, long signedPrekeyId,
			byte[] signedPrekeyPub, long signedPrekeyExpiry,
			List<OneTimePrekey> oneTimePrekeys)
			throws GeneralSecurityException {
		byte[] spkSig = crypto.hybridSign(LABEL_SIGNED_PREKEY,
				signedPrekeyContent(signedPrekeyId, signedPrekeyPub,
						signedPrekeyExpiry), identitySigPriv);
		byte[] bundleContent = bundleContent(identitySigPub, identityAgreePub,
				signedPrekeyId, signedPrekeyPub, signedPrekeyExpiry, spkSig,
				oneTimePrekeys);
		byte[] bundleSig = crypto.hybridSign(LABEL_BUNDLE, bundleContent,
				identitySigPriv);
		return new AsyncPrekeyBundle(identitySigPub, identityAgreePub,
				signedPrekeyId, signedPrekeyPub, signedPrekeyExpiry, spkSig,
				new ArrayList<>(oneTimePrekeys), bundleSig);
	}

	/** Verifies both signatures against the bundle's own identity key. A caller
	 * must still check that identity key is a known, trusted contact. */
	public boolean verify(CryptoComponent crypto) {
		try {
			PublicKey id = crypto.getHybridSignatureKeyParser()
					.parsePublicKey(identitySigPub);
			if (!crypto.verifyHybridSignature(signedPrekeySig,
					LABEL_SIGNED_PREKEY,
					signedPrekeyContent(signedPrekeyId, signedPrekeyPub,
							signedPrekeyExpiry), id)) {
				return false;
			}
			byte[] bundleContent = bundleContent(identitySigPub,
					identityAgreePub, signedPrekeyId, signedPrekeyPub,
					signedPrekeyExpiry, signedPrekeySig, oneTimePrekeys);
			return crypto.verifyHybridSignature(bundleSig, LABEL_BUNDLE,
					bundleContent, id);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	private static byte[] signedPrekeyContent(long id, byte[] pub,
			long expiry) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(VERSION);
		writeUint32(out, id);
		out.write(pub, 0, pub.length);
		writeUint64(out, expiry);
		return out.toByteArray();
	}

	private static byte[] bundleContent(byte[] identitySigPub,
			byte[] identityAgreePub, long signedPrekeyId,
			byte[] signedPrekeyPub, long signedPrekeyExpiry,
			byte[] signedPrekeySig, List<OneTimePrekey> oneTimePrekeys) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(VERSION);
		out.write(identitySigPub, 0, identitySigPub.length);
		out.write(identityAgreePub, 0, identityAgreePub.length);
		writeUint32(out, signedPrekeyId);
		out.write(signedPrekeyPub, 0, signedPrekeyPub.length);
		writeUint64(out, signedPrekeyExpiry);
		out.write(signedPrekeySig, 0, signedPrekeySig.length);
		writeUint16(out, oneTimePrekeys.size());
		for (OneTimePrekey p : oneTimePrekeys) {
			out.write(p.id, 0, p.id.length);
			out.write(p.pub, 0, p.pub.length);
		}
		return out.toByteArray();
	}

	public byte[] encode() {
		byte[] content = bundleContent(identitySigPub, identityAgreePub,
				signedPrekeyId, signedPrekeyPub, signedPrekeyExpiry,
				signedPrekeySig, oneTimePrekeys);
		byte[] out = new byte[content.length + SIG];
		System.arraycopy(content, 0, out, 0, content.length);
		System.arraycopy(bundleSig, 0, out, content.length, SIG);
		return out;
	}

	public static AsyncPrekeyBundle decode(byte[] in) throws FormatException {
		int fixed = 1 + SIG_PUB + AGREE_PUB + ByteUtils.INT_32_BYTES
				+ AGREE_PUB + ByteUtils.INT_64_BYTES + SIG
				+ ByteUtils.INT_16_BYTES;
		if (in.length < fixed + SIG) throw new FormatException();
		if ((in[0] & 0xFF) != VERSION) throw new FormatException();
		int off = 1;
		byte[] identitySigPub = slice(in, off, SIG_PUB);
		off += SIG_PUB;
		byte[] identityAgreePub = slice(in, off, AGREE_PUB);
		off += AGREE_PUB;
		long signedPrekeyId = ByteUtils.readUint32(in, off);
		off += ByteUtils.INT_32_BYTES;
		byte[] signedPrekeyPub = slice(in, off, AGREE_PUB);
		off += AGREE_PUB;
		long signedPrekeyExpiry = ByteUtils.readUint64(in, off);
		off += ByteUtils.INT_64_BYTES;
		byte[] signedPrekeySig = slice(in, off, SIG);
		off += SIG;
		int count = ByteUtils.readUint16(in, off);
		off += ByteUtils.INT_16_BYTES;
		if (count < 0 || count > MAX_ONE_TIME_PREKEYS) {
			throw new FormatException();
		}
		if (in.length != fixed + (long) count * ONE_TIME_ENTRY + SIG) {
			throw new FormatException();
		}
		List<OneTimePrekey> oneTime = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			byte[] id = slice(in, off, ONE_TIME_PREKEY_ID_BYTES);
			off += ONE_TIME_PREKEY_ID_BYTES;
			byte[] pub = slice(in, off, AGREE_PUB);
			off += AGREE_PUB;
			oneTime.add(new OneTimePrekey(id, pub));
		}
		byte[] bundleSig = slice(in, off, SIG);
		return new AsyncPrekeyBundle(identitySigPub, identityAgreePub,
				signedPrekeyId, signedPrekeyPub, signedPrekeyExpiry,
				signedPrekeySig, oneTime, bundleSig);
	}

	private static byte[] slice(byte[] in, int off, int len) {
		return Arrays.copyOfRange(in, off, off + len);
	}

	private static void writeUint16(ByteArrayOutputStream out, int v) {
		byte[] b = new byte[ByteUtils.INT_16_BYTES];
		ByteUtils.writeUint16(v, b, 0);
		out.write(b, 0, b.length);
	}

	private static void writeUint32(ByteArrayOutputStream out, long v) {
		byte[] b = new byte[ByteUtils.INT_32_BYTES];
		ByteUtils.writeUint32(v, b, 0);
		out.write(b, 0, b.length);
	}

	private static void writeUint64(ByteArrayOutputStream out, long v) {
		byte[] b = new byte[ByteUtils.INT_64_BYTES];
		ByteUtils.writeUint64(v, b, 0);
		out.write(b, 0, b.length);
	}
}
