package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.core.util.StringUtils;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.contact.HandshakeConstants.ALICE_PROOF_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.BOB_PROOF_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.KCI_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeConstants.PROTOCOL_MINOR_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class HandshakeVectorTest {

	private static final String LEGACY_K =
			"c71e44b660b5a7895d405605f54c74f7f2ea2823a927d8def6aa325f9478c55d";
	private static final String LEGACY_ALICE_PROOF =
			"f326e813a9136d6357570f3cfc7a1f3f167c6ad238f581bb1ce3e480f6376a7d";
	private static final String LEGACY_BOB_PROOF =
			"cc8ebf4ac973f4c11c9d2c1efda817d60d4ffe1f2a4efedf213b7c8f085f94b0";
	private static final String LEGACY_MODE3_MAC =
			"f525909cd44c6e391c4af078d2a4619b0bfd91002ead1e6359d1175a34993fdf";
	private static final String SEPARATED_K =
			"2ad8eef89d68fa615a3b7bce1c24d5a0652cd85256bb97cb574b6b9f4d8fbfda";
	private static final String SEPARATED_CONFIRMATION =
			"117d16ecbf655b2c30d684ee651a5577bfb899cccd486f00b6e1f7d2fccc6839";
	private static final String SEPARATED_EXCHANGE =
			"821e0b3e331813c5214eaa6130326d8befb094b2dca8effd70a0a82522b5abee";
	private static final String SEPARATED_ROOT =
			"c0fa59e60f63c79b360a33a54ea1fefb9cd93e9a29197a163ee4e4b3b226f7dc";
	private static final String SEPARATED_ALICE_PROOF =
			"97df5c02ea0b0769449b7dfa347db87b3c56df28f98b25a692906cde8ef168e9";

	private CryptoComponent crypto;
	private HandshakeCryptoImpl handshakeCrypto;

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
		handshakeCrypto = new HandshakeCryptoImpl(crypto);
	}

	private static byte[] pattern(int length, int seed) {
		byte[] b = new byte[length];
		for (int i = 0; i < length; i++) b[i] = (byte) (seed + 7 * i);
		return b;
	}

	private static KeyPair keyPair(int seed) {
		byte[] priv = pattern(32, seed);
		priv[0] &= (byte) 248;
		priv[31] &= 127;
		priv[31] |= 64;
		byte[] pub = new byte[32];
		X25519.scalarMultBase(priv, 0, pub, 0);
		return new KeyPair(new HybridAgreementPublicKey(pub,
				pattern(ML_KEM_768_PUBLIC_KEY_BYTES, seed + 1)),
				new HybridAgreementPrivateKey(priv,
						pattern(ML_KEM_768_PRIVATE_KEY_BYTES, seed + 2)));
	}

	private SecretKey derive(boolean alice, byte aliceMinor, byte bobMinor)
			throws Exception {
		KeyPair aliceStatic = keyPair(11);
		KeyPair aliceEphemeral = keyPair(23);
		KeyPair bobStatic = keyPair(37);
		KeyPair bobEphemeral = keyPair(41);
		byte[] ephemeralCt = pattern(ML_KEM_768_CIPHERTEXT_BYTES, 53);
		byte[] toAliceCt = pattern(ML_KEM_768_CIPHERTEXT_BYTES, 59);
		byte[] toBobCt = pattern(ML_KEM_768_CIPHERTEXT_BYTES, 61);
		byte[] ephemeralSecret = pattern(32, 67);
		byte[] toAliceSecret = pattern(32, 71);
		byte[] toBobSecret = pattern(32, 73);
		if (alice) {
			return handshakeCrypto.deriveHybridMasterKeyPqAuth(
					bobStatic.getPublic(), bobEphemeral.getPublic(),
					aliceStatic, aliceEphemeral, ephemeralCt, ephemeralSecret,
					toAliceCt, toAliceSecret, toBobCt, toBobSecret, true,
					aliceMinor, bobMinor);
		}
		return handshakeCrypto.deriveHybridMasterKeyPqAuth(
				aliceStatic.getPublic(), aliceEphemeral.getPublic(),
				bobStatic, bobEphemeral, ephemeralCt, ephemeralSecret,
				toAliceCt, toAliceSecret, toBobCt, toBobSecret, false,
				bobMinor, aliceMinor);
	}

	private static String hex(byte[] b) {
		return StringUtils.toHexString(b).toLowerCase(java.util.Locale.ROOT);
	}

	@Test
	public void theEarlierDerivationKeepsItsBytes() throws Exception {
		SecretKey k = derive(true, KCI_MINOR_VERSION, KCI_MINOR_VERSION);
		assertArrayEquals(k.getBytes(),
				derive(false, KCI_MINOR_VERSION, KCI_MINOR_VERSION).getBytes());
		assertEquals(LEGACY_K, hex(k.getBytes()));
		HandshakeKeys keys = handshakeCrypto.deriveSessionKeys(k, false);
		assertEquals(LEGACY_ALICE_PROOF, hex(handshakeCrypto.proveOwnership(
				keys.getConfirmationKey(), true)));
		assertEquals(LEGACY_BOB_PROOF, hex(handshakeCrypto.proveOwnership(
				keys.getConfirmationKey(), false)));
		assertEquals(LEGACY_ALICE_PROOF, hex(crypto.mac(ALICE_PROOF_LABEL,
				k)));
		assertEquals(LEGACY_BOB_PROOF, hex(crypto.mac(BOB_PROOF_LABEL, k)));
		assertEquals(LEGACY_MODE3_MAC, hex(crypto.mac(
				"org.zerionproject/MODE3_CAPABILITY_MAC",
				keys.getConfirmationKey(), new byte[] {1})));
	}

	@Test
	public void theSeparatedDerivationIsPinned() throws Exception {
		SecretKey k = derive(true, PROTOCOL_MINOR_VERSION,
				PROTOCOL_MINOR_VERSION);
		assertEquals(SEPARATED_K, hex(k.getBytes()));
		HandshakeKeys keys = handshakeCrypto.deriveSessionKeys(k, true);
		assertEquals(SEPARATED_CONFIRMATION,
				hex(keys.getConfirmationKey().getBytes()));
		assertEquals(SEPARATED_EXCHANGE, hex(keys.getExchangeKey().getBytes()));
		assertEquals(SEPARATED_ROOT, hex(keys.getRootKey().getBytes()));
		assertEquals(SEPARATED_ALICE_PROOF, hex(handshakeCrypto.proveOwnership(
				keys.getConfirmationKey(), true)));
	}
}
