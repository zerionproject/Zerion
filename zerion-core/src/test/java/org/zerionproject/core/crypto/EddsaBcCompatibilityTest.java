package org.zerionproject.core.crypto;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.EdDSASecurityProvider;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.Signature;
import java.util.Random;

import static net.i2p.crypto.eddsa.EdDSAEngine.SIGNATURE_ALGORITHM;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves that migrating the app's Ed25519 from net.i2p.crypto.eddsa (which is
 * unmaintained and vulnerable to CVE-2020-36843 signature malleability) to
 * Bouncy Castle is wire-compatible: every seed derives a byte-identical public
 * key, and signatures interoperate in both directions. If this test passes,
 * existing identities (AuthorIds), voice-call onions, and stored signatures are
 * unchanged, so no re-pairing is required.
 */
public class EddsaBcCompatibilityTest {

	private static final EdDSANamedCurveSpec CURVE =
			EdDSANamedCurveTable.getByName("Ed25519");

	@Test
	public void publicKeyDerivationMatches() {
		Random r = new Random(1234);
		for (int i = 0; i < 500; i++) {
			byte[] seed = new byte[32];
			r.nextBytes(seed);

			byte[] bcPub = new Ed25519PrivateKeyParameters(seed, 0)
					.generatePublicKey().getEncoded();

			EdDSAPrivateKeySpec spec = new EdDSAPrivateKeySpec(seed, CURVE);
			// VoiceCallCryptoImpl.getLocalOnion path
			byte[] edVoicePub = spec.getA().toByteArray();
			// CryptoComponentImpl.generateSignatureKeyPair path
			byte[] edKeygenPub = new EdDSAPrivateKey(spec).getAbyte();

			assertArrayEquals(edKeygenPub, bcPub);
			assertArrayEquals(edVoicePub, bcPub);
		}
	}

	@Test
	public void signaturesInteroperate() throws Exception {
		Random r = new Random(9876);
		for (int i = 0; i < 200; i++) {
			byte[] seed = new byte[32];
			r.nextBytes(seed);
			byte[] msg = new byte[1 + r.nextInt(600)];
			r.nextBytes(msg);

			byte[] pub = new Ed25519PrivateKeyParameters(seed, 0)
					.generatePublicKey().getEncoded();

			EdDSAPrivateKey edPriv =
					new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, CURVE));
			Signature edSign = Signature.getInstance(SIGNATURE_ALGORITHM,
					new EdDSASecurityProvider());
			edSign.initSign(edPriv);
			edSign.update(msg);
			byte[] oldSig = edSign.sign();

			Ed25519Signer bcSign = new Ed25519Signer();
			bcSign.init(true, new Ed25519PrivateKeyParameters(seed, 0));
			bcSign.update(msg, 0, msg.length);
			byte[] newSig = bcSign.generateSignature();

			// Ed25519 is deterministic: identical signature bytes
			assertArrayEquals(oldSig, newSig);

			// signature from the old library verifies under Bouncy Castle
			Ed25519Signer bcVerify = new Ed25519Signer();
			bcVerify.init(false, new Ed25519PublicKeyParameters(pub, 0));
			bcVerify.update(msg, 0, msg.length);
			assertTrue(bcVerify.verifySignature(oldSig));

			// signature from Bouncy Castle verifies under the old library
			EdDSAPublicKey edPub =
					new EdDSAPublicKey(new EdDSAPublicKeySpec(pub, CURVE));
			Signature edVerify = Signature.getInstance(SIGNATURE_ALGORITHM,
					new EdDSASecurityProvider());
			edVerify.initVerify(edPub);
			edVerify.update(msg);
			assertTrue(edVerify.verify(newSig));
		}
	}

	// The Tor ED25519-V3 secret key blob (TorRendezvousCryptoImpl) is the
	// clamped SHA-512 expansion of the seed. The migrated derivation must equal
	// eddsa's getH() so the onion-service identity is unchanged.
	@Test
	public void torPrivateKeyBlobMatches() throws Exception {
		Random r = new Random(555);
		for (int i = 0; i < 300; i++) {
			byte[] seed = new byte[32];
			r.nextBytes(seed);

			byte[] edH = new EdDSAPrivateKeySpec(seed, CURVE).getH();

			byte[] h = MessageDigest.getInstance("SHA-512").digest(seed);
			h[0] &= (byte) 248;
			h[31] &= (byte) 127;
			h[31] |= (byte) 64;

			assertArrayEquals(edH, h);
		}
	}
}
