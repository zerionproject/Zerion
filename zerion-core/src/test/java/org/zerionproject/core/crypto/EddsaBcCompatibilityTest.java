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
			byte[] edVoicePub = spec.getA().toByteArray();
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

			assertArrayEquals(oldSig, newSig);

			Ed25519Signer bcVerify = new Ed25519Signer();
			bcVerify.init(false, new Ed25519PublicKeyParameters(pub, 0));
			bcVerify.update(msg, 0, msg.length);
			assertTrue(bcVerify.verifySignature(oldSig));

			EdDSAPublicKey edPub =
					new EdDSAPublicKey(new EdDSAPublicKeySpec(pub, CURVE));
			Signature edVerify = Signature.getInstance(SIGNATURE_ALGORITHM,
					new EdDSASecurityProvider());
			edVerify.initVerify(edPub);
			edVerify.update(msg);
			assertTrue(edVerify.verify(newSig));
		}
	}

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
