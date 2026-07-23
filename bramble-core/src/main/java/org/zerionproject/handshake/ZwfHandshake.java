package org.zerionproject.handshake;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.contact.HandshakeCrypto;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.zerionproject.core.api.crypto.CryptoConstants.MAC_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_BYTES;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.handshake.ZwfHandshakeIo.TYPE_EPHEMERAL_KEY;
import static org.zerionproject.handshake.ZwfHandshakeIo.TYPE_KEM_CIPHERTEXT;
import static org.zerionproject.handshake.ZwfHandshakeIo.TYPE_MINOR_VERSION;
import static org.zerionproject.handshake.ZwfHandshakeIo.TYPE_MODE3_CAPABILITY;
import static org.zerionproject.handshake.ZwfHandshakeIo.TYPE_PROOF;
import static org.zerionproject.handshake.ZwfHandshakeIo.TYPE_STATIC_KEY;

/**
 * The native Zerion contact-exchange handshake. It runs a hybrid post-quantum
 * key agreement — hybrid X25519 + ML-KEM static and ephemeral keys,
 * forward-secret KEM, hybrid identity proof — over {@link ZwfHandshakeIo}
 * framing. It refuses any non-post-quantum path; there is no classical
 * handshake.
 *
 * <p>On success it yields the shared contact {@code rootKey} and the role
 * tiebreaker, from which the transport tag/header keys and the Mode 3-Full
 * ratchet state are derived.
 */
@NotNullByDefault
public class ZwfHandshake {

	private static final byte PROTOCOL_MINOR_VERSION = 2;
	private static final byte FS_MINOR_VERSION = 2;
	private static final int PROOF_BYTES = MAC_BYTES;
	private static final String MODE3_CAP_MAC_LABEL =
			"org.zerionproject.handshake/MODE3_CAPABILITY_MAC";

	private final CryptoComponent crypto;
	private final HandshakeCrypto handshakeCrypto;

	public ZwfHandshake(CryptoComponent crypto, HandshakeCrypto handshakeCrypto) {
		this.crypto = crypto;
		this.handshakeCrypto = handshakeCrypto;
	}

	/**
	 * Runs the handshake to completion.
	 *
	 * @param ourStaticKeyPair our hybrid handshake identity key pair.
	 * @param theirCommitment the peer's key commitment from the pairing link
	 * (the 32-byte hash of their static hybrid key).
	 */
	public ZwfHandshakeResult run(KeyPair ourStaticKeyPair,
			byte[] theirCommitment, InputStream in, OutputStream out)
			throws IOException {
		if (theirCommitment.length < HYBRID_COMMITMENT_BYTES)
			throw new FormatException();
		byte[] commitment = theirCommitment.length == HYBRID_COMMITMENT_BYTES
				? theirCommitment
				: Arrays.copyOf(theirCommitment, HYBRID_COMMITMENT_BYTES);
		ZwfHandshakeIo io = new ZwfHandshakeIo(in, out);
		byte[] ourStaticPub = ourStaticKeyPair.getPublic().getEncoded();
		byte[] ourCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL, ourStaticPub);
		boolean alice = Bytes.compare(ourCommitment, commitment) < 0;

		KeyPair ourEph = null;
		byte[] kemSecret = null;
		SecretKey rootKey = null;
		boolean success = false;
		try {
			PublicKey theirStatic;
			if (alice) {
				io.write(TYPE_STATIC_KEY, ourStaticPub);
				theirStatic = receiveAgreementKey(io, TYPE_STATIC_KEY);
			} else {
				theirStatic = receiveAgreementKey(io, TYPE_STATIC_KEY);
				io.write(TYPE_STATIC_KEY, ourStaticPub);
			}
			byte[] expected = crypto.hash(HYBRID_COMMITMENT_LABEL,
					theirStatic.getEncoded());
			if (!constantTimeEquals(expected, commitment)) {
				throw new FormatException();
			}

			ourEph = handshakeCrypto.generateHybridEphemeralKeyPair();
			byte[] ourEphPub = ourEph.getPublic().getEncoded();
			PublicKey theirEph;
			int theirMinor;
			if (alice) {
				io.write(TYPE_MINOR_VERSION,
						new byte[] {PROTOCOL_MINOR_VERSION});
				io.write(TYPE_EPHEMERAL_KEY, ourEphPub);
				theirMinor = receiveMinorVersion(io);
				theirEph = receiveAgreementKey(io, TYPE_EPHEMERAL_KEY);
			} else {
				theirMinor = receiveMinorVersion(io);
				theirEph = receiveAgreementKey(io, TYPE_EPHEMERAL_KEY);
				io.write(TYPE_MINOR_VERSION,
						new byte[] {PROTOCOL_MINOR_VERSION});
				io.write(TYPE_EPHEMERAL_KEY, ourEphPub);
			}
			if (theirMinor < FS_MINOR_VERSION) throw new FormatException();

			byte[] kemCiphertext;
			try {
				if (alice) {
					HybridEncapsulationResult enc =
							handshakeCrypto.hybridEncapsulate(theirEph);
					kemCiphertext = enc.getCiphertext();
					kemSecret = enc.getSharedSecret();
					io.write(TYPE_KEM_CIPHERTEXT, kemCiphertext);
				} else {
					kemCiphertext = io.read(TYPE_KEM_CIPHERTEXT);
					checkLength(kemCiphertext, ML_KEM_768_CIPHERTEXT_BYTES);
					kemSecret = new byte[0];
				}
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			}

			try {
				rootKey = handshakeCrypto.deriveHybridMasterKeyFs(theirStatic,
						theirEph, ourStaticKeyPair, ourEph, kemCiphertext,
						kemSecret, alice, PROTOCOL_MINOR_VERSION,
						(byte) theirMinor);
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			}

			byte[] ourProof = handshakeCrypto.proveOwnership(rootKey, alice);
			byte[] theirProof;
			if (alice) {
				io.write(TYPE_PROOF, ourProof);
				theirProof = io.read(TYPE_PROOF);
			} else {
				theirProof = io.read(TYPE_PROOF);
				io.write(TYPE_PROOF, ourProof);
			}
			checkLength(theirProof, PROOF_BYTES);

			io.write(TYPE_MODE3_CAPABILITY, macCapability(rootKey));
			boolean mode3Capable = receiveMode3Capability(io, rootKey);

			if (!handshakeCrypto.verifyOwnership(rootKey, !alice, theirProof)) {
				throw new FormatException();
			}

			byte[] theirStaticPub = theirStatic.getEncoded();
			byte[] ourEphX25519 = Arrays.copyOfRange(ourEphPub, 0, 32);
			byte[] theirEphX25519 =
					Arrays.copyOfRange(theirEph.getEncoded(), 0, 32);
			ZwfHandshakeResult result = new ZwfHandshakeResult(rootKey, alice,
					mode3Capable, ourStaticPub, theirStaticPub, ourEphX25519,
					theirEphX25519);
			success = true;
			return result;
		} finally {
			if (kemSecret != null) Arrays.fill(kemSecret, (byte) 0);
			if (ourEph != null) {
				PrivateKey ephPriv = ourEph.getPrivate();
				if (ephPriv instanceof HybridAgreementPrivateKey) {
					((HybridAgreementPrivateKey) ephPriv).clear();
				}
			}
			if (!success) {
				if (rootKey != null) rootKey.clear();
			}
		}
	}

	private static PublicKey receiveAgreementKey(ZwfHandshakeIo io, byte type)
			throws IOException {
		byte[] key = io.read(type);
		checkLength(key, HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
		return new HybridAgreementPublicKey(key);
	}

	private static int receiveMinorVersion(ZwfHandshakeIo io)
			throws IOException {
		byte[] mv = io.read(TYPE_MINOR_VERSION);
		if (mv.length != 1) throw new FormatException();
		return mv[0] & 0xFF;
	}

	private byte[] macCapability(SecretKey rootKey) {
		byte[] cap = new byte[] {0x01};
		byte[] mac = crypto.mac(MODE3_CAP_MAC_LABEL, rootKey, cap);
		byte[] payload = new byte[cap.length + mac.length];
		System.arraycopy(cap, 0, payload, 0, cap.length);
		System.arraycopy(mac, 0, payload, cap.length, mac.length);
		return payload;
	}

	private boolean receiveMode3Capability(ZwfHandshakeIo io, SecretKey rootKey)
			throws IOException {
		byte[] payload = io.read(TYPE_MODE3_CAPABILITY);
		if (payload.length < 1) throw new FormatException();
		byte[] cap = new byte[] {payload[0]};
		byte[] mac = Arrays.copyOfRange(payload, 1, payload.length);
		if (!crypto.verifyMac(mac, MODE3_CAP_MAC_LABEL, rootKey, cap)) {
			throw new FormatException();
		}
		return payload[0] == 0x01;
	}

	private static void checkLength(byte[] b, int expected)
			throws FormatException {
		if (b.length != expected) throw new FormatException();
	}

	private static boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a.length < HYBRID_COMMITMENT_BYTES
				|| b.length < HYBRID_COMMITMENT_BYTES) {
			return false;
		}
		int result = 0;
		for (int i = 0; i < HYBRID_COMMITMENT_BYTES; i++) {
			result |= a[i] ^ b[i];
		}
		return result == 0;
	}
}
