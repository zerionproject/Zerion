package org.zerionproject.core.contact.request;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.core.util.StringUtils;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.AEAD_TAG_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HYBRID_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KDF_LABEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KEM_CIPHERTEXT_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_ACK;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_EPHEMERAL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_HELLO;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_INBOX_CLIENT_AUTH;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_ONE_TIME_KEY_HASH;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_POP;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_POP_DH;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_PUZZLE;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_PUZZLE_EPOCH;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_REQUEST_ID_HASH;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_SEAL_KEY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_SEAL_NONCE;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LABEL_SENDER_TAG;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.NONCE_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PLAINTEXT_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SEALED_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CALIBRATION_BLOCK_HASHES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CALIBRATION_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CALIBRATION_WARMUP_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SENDER_DIFFICULTY_CAP;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SENDER_SOLVE_BUDGET_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SOLVE_SAFETY_FACTOR;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SOLUTION_X_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.X25519_BYTES;

@ThreadSafe
@NotNullByDefault
public class ContactRequestCrypto {

	static final long SOLVE_ITERATION_LIMIT = 1L << (SENDER_DIFFICULTY_CAP + 6);
	static final int DEADLINE_CHECK_MASK = 0x3FF;

	private final CryptoComponent crypto;
	private final LongSupplier millis;
	private final Object calibrationLock = new Object();
	private volatile double hashesPerSecond = 0;
	private int calibrations = 0;

	@Inject
	public ContactRequestCrypto(CryptoComponent crypto) {
		this(crypto, () -> System.nanoTime() / 1_000_000L);
	}

	ContactRequestCrypto(CryptoComponent crypto, LongSupplier millis) {
		this.crypto = crypto;
		this.millis = millis;
	}

	public CryptoComponent getCrypto() {
		return crypto;
	}

	public byte[] puzzleNonce(byte[] inboxSecret, long epoch, int level) {
		SecretKey k = new SecretKey(inboxSecret.clone());
		try {
			return crypto.mac(LABEL_PUZZLE_EPOCH, k, uint64(epoch),
					new byte[] {(byte) level});
		} finally {
			k.clear();
		}
	}

	public byte[] puzzleHash(byte[] nonce, byte[] linkId, byte[] x) {
		return crypto.hash(LABEL_PUZZLE, nonce, linkId, x);
	}

	public boolean isSolution(byte[] nonce, byte[] linkId, byte[] x,
			int difficulty) {
		if (x.length != SOLUTION_X_BYTES) return false;
		return leadingZeroBits(puzzleHash(nonce, linkId, x)) >= difficulty;
	}

	@Nullable
	public byte[] solve(byte[] nonce, byte[] linkId, int difficulty,
			Random random, BooleanSupplier cancelled) {
		return solve(nonce, linkId, difficulty, random, cancelled,
				SENDER_SOLVE_BUDGET_MS);
	}

	public double hashesPerSecond() {
		double rate = hashesPerSecond;
		if (rate > 0) return rate;
		synchronized (calibrationLock) {
			if (hashesPerSecond > 0) return hashesPerSecond;
			calibrations++;
			byte[] nonce = new byte[32];
			byte[] linkId = new byte[32];
			byte[] x = new byte[SOLUTION_X_BYTES];
			Blake2bDigest prefix = puzzlePrefix(nonce, linkId, x.length);
			byte[] out = new byte[32];
			long counter = 0;
			long warm = millis.getAsLong() + CALIBRATION_WARMUP_MS;
			while (millis.getAsLong() < warm) {
				counter = hashBlock(prefix, x, out, counter);
			}
			long hashes = 0;
			long start = millis.getAsLong();
			long end;
			do {
				counter = hashBlock(prefix, x, out, counter);
				hashes += CALIBRATION_BLOCK_HASHES;
				end = millis.getAsLong();
			} while (end - start < CALIBRATION_MS);
			long took = Math.max(1, end - start);
			hashesPerSecond = hashes * 1000.0 / took;
			return hashesPerSecond;
		}
	}

	private static long hashBlock(Blake2bDigest prefix, byte[] x,
			byte[] out, long counter) {
		for (int i = 0; i < CALIBRATION_BLOCK_HASHES; i++) {
			putLong(x, counter++);
			Blake2bDigest d = new Blake2bDigest(prefix);
			d.update(x, 0, x.length);
			d.doFinal(out, 0);
		}
		return counter;
	}

	int calibrationCount() {
		synchronized (calibrationLock) {
			return calibrations;
		}
	}

	public boolean affordable(int difficulty) {
		if (difficulty < 0 || difficulty > SENDER_DIFFICULTY_CAP) return false;
		if (difficulty == 0) return true;
		double expectedMs = Math.pow(2, difficulty) * 1000.0
				/ hashesPerSecond();
		return expectedMs * SOLVE_SAFETY_FACTOR <= SENDER_SOLVE_BUDGET_MS;
	}

	@Nullable
	public byte[] solve(byte[] nonce, byte[] linkId, int difficulty,
			Random random, BooleanSupplier cancelled, long budgetMs) {
		if (difficulty < 0 || difficulty > SENDER_DIFFICULTY_CAP) return null;
		byte[] x = new byte[SOLUTION_X_BYTES];
		random.nextBytes(x);
		if (difficulty == 0) return x;
		Blake2bDigest prefix = puzzlePrefix(nonce, linkId, x.length);
		byte[] out = new byte[32];
		long counter = ByteUtils.readUint64(x, 0);
		long start = millis.getAsLong();
		for (long i = 0; i < SOLVE_ITERATION_LIMIT; i++) {
			if ((i & DEADLINE_CHECK_MASK) == 0) {
				if (cancelled.getAsBoolean()) return null;
				if (i > 0 && millis.getAsLong() - start >= budgetMs) return null;
			}
			putLong(x, counter + i);
			Blake2bDigest d = new Blake2bDigest(prefix);
			d.update(x, 0, x.length);
			d.doFinal(out, 0);
			if (leadingZeroBits(out) >= difficulty) return x;
		}
		return null;
	}

	private static Blake2bDigest puzzlePrefix(byte[] nonce, byte[] linkId,
			int xLength) {
		Blake2bDigest prefix = new Blake2bDigest(256);
		update(prefix, StringUtils.toUtf8(LABEL_PUZZLE));
		update(prefix, nonce);
		update(prefix, linkId);
		byte[] length = new byte[4];
		ByteUtils.writeUint32(xLength, length, 0);
		prefix.update(length, 0, length.length);
		return prefix;
	}

	private static void putLong(byte[] b, long v) {
		for (int j = 7; j >= 0; j--) {
			b[j] = (byte) v;
			v >>>= 8;
		}
	}

	private static void update(Blake2bDigest d, byte[] input) {
		byte[] length = new byte[4];
		ByteUtils.writeUint32(input.length, length, 0);
		d.update(length, 0, length.length);
		d.update(input, 0, input.length);
	}

	static int leadingZeroBits(byte[] h) {
		int bits = 0;
		for (byte b : h) {
			int v = b & 0xFF;
			if (v == 0) {
				bits += 8;
				continue;
			}
			return bits + Integer.numberOfLeadingZeros(v) - 24;
		}
		return bits;
	}

	public byte[] helloHash(long epoch, int level, byte[] nonce,
			byte[] keyPayload) {
		return crypto.hash(LABEL_HELLO, uint64(epoch),
				new byte[] {(byte) level}, nonce, keyPayload);
	}

	public byte[] clientAuthPrivateKey(byte[] body, byte[] onionPublicKey) {
		byte[] seed = crypto.hash(LABEL_INBOX_CLIENT_AUTH, body,
				onionPublicKey);
		seed[0] &= (byte) 248;
		seed[31] &= (byte) 127;
		seed[31] |= (byte) 64;
		return seed;
	}

	public byte[] clientAuthPublicKey(byte[] body, byte[] onionPublicKey) {
		byte[] priv = clientAuthPrivateKey(body, onionPublicKey);
		try {
			return new X25519PrivateKeyParameters(priv, 0).generatePublicKey()
					.getEncoded();
		} finally {
			Arrays.fill(priv, (byte) 0);
		}
	}

	public byte[] senderTag(byte[] accountTagSecret, byte[] linkId) {
		SecretKey k = new SecretKey(accountTagSecret.clone());
		try {
			return crypto.mac(LABEL_SENDER_TAG, k, linkId);
		} finally {
			k.clear();
		}
	}

	public byte[] requestIdHash(byte[] requestId) {
		return crypto.hash(LABEL_REQUEST_ID_HASH, requestId);
	}

	public byte[] oneTimeKeyHash(byte[] oneTimeLink) {
		return crypto.hash(LABEL_ONE_TIME_KEY_HASH, oneTimeLink);
	}

	public byte[] proofOfPossession(byte[] linkX25519, KeyPair oneTimeKeyPair,
			byte[] helloNonce, byte[] requestId)
			throws GeneralSecurityException {
		KeyPair ours = x25519Half(oneTimeKeyPair);
		SecretKey dh = crypto.deriveSharedSecret(LABEL_POP_DH,
				new AgreementPublicKey(linkX25519), ours);
		try {
			return crypto.mac(LABEL_POP, dh, helloNonce, requestId);
		} finally {
			dh.clear();
			clear(ours.getPrivate());
		}
	}

	public boolean verifyProofOfPossession(byte[] pop, byte[] oneTimeX25519,
			KeyPair linkKeyPair, byte[] helloNonce, byte[] requestId) {
		KeyPair ours = x25519Half(linkKeyPair);
		SecretKey dh = null;
		try {
			dh = crypto.deriveSharedSecret(LABEL_POP_DH,
					new AgreementPublicKey(oneTimeX25519), ours);
			byte[] expected = crypto.mac(LABEL_POP, dh, helloNonce,
					requestId);
			return MessageDigest.isEqual(expected, pop);
		} catch (GeneralSecurityException e) {
			return false;
		} finally {
			if (dh != null) dh.clear();
			clear(ours.getPrivate());
		}
	}

	public byte[] ack(SecretKey mk, byte[] requestId, int kind) {
		return crypto.mac(LABEL_ACK, mk, requestId, new byte[] {(byte) kind});
	}

	public static final class Sealed {

		public final byte[] payload;
		public final SecretKey mk;

		Sealed(byte[] payload, SecretKey mk) {
			this.payload = payload;
			this.mk = mk;
		}
	}

	public static final class Opened {

		public final byte[] plaintext;
		public final SecretKey mk;

		Opened(byte[] plaintext, SecretKey mk) {
			this.plaintext = plaintext;
			this.mk = mk;
		}
	}

	public Sealed seal(PublicKey linkKey, byte[] inboxEphemeral,
			byte[] helloHash, byte[] onionPublicKey, byte[] linkId,
			byte[] plaintext) throws GeneralSecurityException {
		if (plaintext.length != PLAINTEXT_BYTES) {
			throw new IllegalArgumentException();
		}
		HybridEncapsulationResult enc = crypto.hybridEncapsulate(linkKey);
		byte[] ct = enc.getCiphertext();
		byte[] ss = enc.getSharedSecret();
		KeyPair e = crypto.generateHybridAgreementKeyPair();
		KeyPair eX = x25519Half(e);
		SecretKey eph = null;
		SecretKey mk = null;
		try {
			eph = crypto.deriveSharedSecret(LABEL_EPHEMERAL,
					new AgreementPublicKey(inboxEphemeral), eX);
			mk = crypto.deriveHybridSharedSecretAsResponder(KDF_LABEL,
					linkKey, e, ss, helloHash, onionPublicKey, linkId, ct,
					eph.getBytes());
			byte[] blob = aead(true, mk, plaintext);
			byte[] payload = new byte[SEALED_BYTES];
			byte[] ePub = e.getPublic().getEncoded();
			System.arraycopy(ePub, 0, payload, 0, HYBRID_PUBLIC_KEY_BYTES);
			System.arraycopy(ct, 0, payload, HYBRID_PUBLIC_KEY_BYTES,
					KEM_CIPHERTEXT_BYTES);
			System.arraycopy(blob, 0, payload,
					HYBRID_PUBLIC_KEY_BYTES + KEM_CIPHERTEXT_BYTES,
					blob.length);
			Sealed sealed = new Sealed(payload, mk);
			mk = null;
			return sealed;
		} finally {
			Arrays.fill(ss, (byte) 0);
			enc.clearSecret();
			if (eph != null) eph.clear();
			if (mk != null) mk.clear();
			clear(e.getPrivate());
			clear(eX.getPrivate());
		}
	}

	public Opened open(KeyPair linkKeyPair, KeyPair inboxEphemeral,
			byte[] helloHash, byte[] onionPublicKey, byte[] linkId,
			byte[] payload) throws GeneralSecurityException {
		if (payload.length != SEALED_BYTES) {
			throw new GeneralSecurityException();
		}
		byte[] ePubBytes = Arrays.copyOfRange(payload, 0,
				HYBRID_PUBLIC_KEY_BYTES);
		byte[] ct = Arrays.copyOfRange(payload, HYBRID_PUBLIC_KEY_BYTES,
				HYBRID_PUBLIC_KEY_BYTES + KEM_CIPHERTEXT_BYTES);
		byte[] blob = Arrays.copyOfRange(payload,
				HYBRID_PUBLIC_KEY_BYTES + KEM_CIPHERTEXT_BYTES,
				payload.length);
		HybridAgreementPublicKey ePub = new HybridAgreementPublicKey(
				ePubBytes);
		SecretKey eph = crypto.deriveSharedSecret(LABEL_EPHEMERAL,
				new AgreementPublicKey(ePub.getX25519PublicKey()),
				inboxEphemeral);
		SecretKey mk = null;
		try {
			mk = crypto.deriveHybridSharedSecret(KDF_LABEL, ePub,
					linkKeyPair, ct, helloHash, onionPublicKey, linkId, ct,
					eph.getBytes());
			byte[] plaintext = aead(false, mk, blob);
			Opened opened = new Opened(plaintext, mk);
			mk = null;
			return opened;
		} finally {
			eph.clear();
			if (mk != null) mk.clear();
		}
	}

	private byte[] aead(boolean encrypt, SecretKey mk, byte[] input)
			throws GeneralSecurityException {
		SecretKey key = crypto.deriveKey(LABEL_SEAL_KEY, mk);
		try {
			byte[] nonce = Arrays.copyOf(crypto.mac(LABEL_SEAL_NONCE, mk),
					NONCE_BYTES);
			XSalsa20Poly1305AuthenticatedCipher cipher =
					new XSalsa20Poly1305AuthenticatedCipher();
			cipher.init(encrypt, key, nonce);
			int outLen = encrypt ? input.length + AEAD_TAG_BYTES
					: input.length - AEAD_TAG_BYTES;
			if (outLen < 0) throw new GeneralSecurityException();
			byte[] out = new byte[outLen];
			cipher.process(input, 0, input.length, out, 0);
			return out;
		} finally {
			key.clear();
		}
	}

	static KeyPair x25519Half(KeyPair hybrid) {
		HybridAgreementPublicKey pub =
				(HybridAgreementPublicKey) hybrid.getPublic();
		HybridAgreementPrivateKey priv =
				(HybridAgreementPrivateKey) hybrid.getPrivate();
		return new KeyPair(pub.getX25519Component(),
				priv.getX25519Component());
	}

	public static KeyPair agreementKeyPair(byte[] pub, byte[] priv) {
		if (pub.length != X25519_BYTES || priv.length != X25519_BYTES) {
			throw new IllegalArgumentException();
		}
		return new KeyPair(new AgreementPublicKey(pub),
				new AgreementPrivateKey(priv));
	}

	private static void clear(PrivateKey k) {
		if (k instanceof HybridAgreementPrivateKey) {
			((HybridAgreementPrivateKey) k).clear();
		} else if (k instanceof AgreementPrivateKey) {
			Arrays.fill(((AgreementPrivateKey) k).getBytes(), (byte) 0);
		}
	}

	static byte[] uint64(long v) {
		byte[] b = new byte[8];
		ByteUtils.writeUint64(v, b, 0);
		return b;
	}
}
