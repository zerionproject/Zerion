package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The contact exchange proves possession of the whole hybrid identity: the
 * signature over the exchange nonce must verify under both the Ed25519 and
 * the ML-DSA-65 half, so a peer that can forge one half alone cannot pass.
 */
public class ContactExchangeHybridSignatureTest {

	private CryptoComponent crypto;
	private ContactExchangeCryptoImpl exchangeCrypto;
	private PublicKey ed25519Pub;
	private PrivateKey ed25519Priv;
	private byte[] mlDsaPub, mlDsaPriv;
	private SecretKey masterKey;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImpl = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImpl.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		exchangeCrypto = new ContactExchangeCryptoImpl(crypto);
		KeyPair identity = crypto.generateHybridSignatureKeyPair();
		byte[] pub = identity.getPublic().getEncoded();
		byte[] priv = identity.getPrivate().getEncoded();
		ed25519Pub = new SignaturePublicKey(Arrays.copyOfRange(pub, 0, 32));
		ed25519Priv = new SignaturePrivateKey(Arrays.copyOfRange(priv, 0, 32));
		mlDsaPub = Arrays.copyOfRange(pub, 32, pub.length);
		mlDsaPriv = Arrays.copyOfRange(priv, 32, priv.length);
		byte[] k = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(k);
		masterKey = new SecretKey(k);
	}

	@Test
	public void testHybridSignatureVerifiesForTheSigningRole() {
		byte[] sig = exchangeCrypto.hybridSign(ed25519Priv, mlDsaPriv,
				masterKey, true);
		assertEquals(HYBRID_SIGNATURE_BYTES, sig.length);
		assertTrue(exchangeCrypto.verifyHybrid(ed25519Pub, mlDsaPub,
				masterKey, true, sig));
		assertFalse("the other role derives another nonce",
				exchangeCrypto.verifyHybrid(ed25519Pub, mlDsaPub, masterKey,
						false, sig));
	}

	@Test
	public void testEitherWrongIdentityHalfFails() {
		byte[] sig = exchangeCrypto.hybridSign(ed25519Priv, mlDsaPriv,
				masterKey, false);
		KeyPair other = crypto.generateHybridSignatureKeyPair();
		byte[] otherPub = other.getPublic().getEncoded();
		PublicKey otherEd = new SignaturePublicKey(
				Arrays.copyOfRange(otherPub, 0, 32));
		byte[] otherMl = Arrays.copyOfRange(otherPub, 32, otherPub.length);
		assertFalse(exchangeCrypto.verifyHybrid(otherEd, mlDsaPub, masterKey,
				false, sig));
		assertFalse(exchangeCrypto.verifyHybrid(ed25519Pub, otherMl,
				masterKey, false, sig));
		byte[] otherKey = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(otherKey);
		assertFalse(exchangeCrypto.verifyHybrid(ed25519Pub, mlDsaPub,
				new SecretKey(otherKey), false, sig));
	}

	@Test
	public void testClassicalOnlySignatureIsNotAccepted() {
		byte[] ed25519Only = exchangeCrypto.sign(ed25519Priv, masterKey, true);
		assertTrue(exchangeCrypto.verify(ed25519Pub, masterKey, true,
				ed25519Only));
		assertFalse(exchangeCrypto.verifyHybrid(ed25519Pub, mlDsaPub,
				masterKey, true, ed25519Only));
		byte[] sig = exchangeCrypto.hybridSign(ed25519Priv, mlDsaPriv,
				masterKey, true);
		byte[] tamperedMlDsa = sig.clone();
		tamperedMlDsa[sig.length - 1] ^= 1;
		assertFalse(exchangeCrypto.verifyHybrid(ed25519Pub, mlDsaPub,
				masterKey, true, tamperedMlDsa));
		byte[] tamperedEd = sig.clone();
		tamperedEd[3] ^= 1;
		assertFalse(exchangeCrypto.verifyHybrid(ed25519Pub, mlDsaPub,
				masterKey, true, tamperedEd));
		assertFalse(exchangeCrypto.verifyHybrid(ed25519Pub, new byte[7],
				masterKey, true, sig));
	}
}
