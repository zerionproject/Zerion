package org.zerionproject.app.introduction;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.introduction.IntroduceeSession.Local;
import org.zerionproject.app.introduction.IntroduceeSession.Remote;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.zerionproject.core.api.identity.Author.FORMAT_VERSION;
import static org.zerionproject.app.api.introduction.IntroductionConstants.INTRODUCTION_ML_KEM_PUBLIC_KEY_BYTES;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_NONCE;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_SIGN;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_AUTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IntroductionVectorTest extends BrambleTestCase {

	private static final String ALICE_PRIVATE =
			"77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a";
	private static final String ALICE_PUBLIC =
			"8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
	private static final String BOB_PRIVATE =
			"5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb";
	private static final String BOB_PUBLIC =
			"de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";

	private static final String EARLIER_SHARED_SECRET =
			"de8d4aced1cf7ba7f500d4efd0e753faa72bc56f69c5150ebebb277995240fbc";
	private static final String EARLIER_ALICE_PRE_MASTER =
			"8e57a0544320d72dd6bb05bb9377b46e57adb884f3edea1378a006b5a914acfd";
	private static final String EARLIER_BOB_PRE_MASTER =
			"1841515c2b9c276e2195b8b6d7aab0e4d1efd74a6c2d385d10ae3df8d8f6990d";
	private static final String EARLIER_ALICE_MAC_KEY =
			"c6c01837ce0db711e0e63e2373ec43fd7cc06c6458f6f6215d682e6aa510f61f";
	private static final String EARLIER_BOB_MAC_KEY =
			"2c669ee2417089181c7071fff3f5550e0b66dc08b1be037439090254b73ce1e9";
	private static final String EARLIER_ALICE_AUTH_MAC =
			"65704b1122995a2a054ece8e18aed047a36c1f418aa4e3b9d9e41917e4e64ace";
	private static final String EARLIER_BOB_AUTH_MAC =
			"ab4b35b859989c81ae6b182b581cea3c716975d9c3e6ee561faebb30148f23a5";
	private static final String EARLIER_ALICE_NONCE =
			"a0a27c6cf94f13985b9514c5ed294c2c018bdc59c91c1aebd6d58c8662cc7ef2";
	private static final String EARLIER_BOB_NONCE =
			"0d1b07e89108a2f442540b98c7b5a49489261ee97b29083016470f2635020682";
	private static final String EARLIER_ROOT =
			"840ecbc26299aa6dba0f91abb4b9d1167144e612021c39a8cb1af48772003da4";
	private static final String EARLIER_ALICE_ACTIVATE_KEY =
			"8050118ec47eb264318f375d18a5f75acc5722490c2f979a7bf5175af4321607";
	private static final String EARLIER_BOB_ACTIVATE_KEY =
			"4b994c31d13c95e5b044ffaaed148a6312d14bbe07976c8fe5eccd3a27985f0b";
	private static final String EARLIER_ALICE_ACTIVATE_MAC =
			"d10b00c9eb3e8f902fd4804544bd29e8c6be75c4941f8f807be6d251eaefac31";
	private static final String EARLIER_BOB_ACTIVATE_MAC =
			"9599231ba48b228b116e0c23eb3a356d8295645c7a6cf9bb09799e209c2b6717";

	private final CryptoComponent cryptoComponent;
	private final IntroductionCryptoImpl crypto;

	private final AuthorId introducerId = new AuthorId(pattern(32, 3));
	private final Author aliceAuthor = author(5, "alice");
	private final Author bobAuthor = author(7, "bob");
	private final long aliceAcceptTimestamp = 1700000000000L;
	private final long bobAcceptTimestamp = 1700000000123L;
	private final byte[] aliceKemPublic =
			pattern(INTRODUCTION_ML_KEM_PUBLIC_KEY_BYTES, 11);
	private final byte[] bobKemPublic =
			pattern(INTRODUCTION_ML_KEM_PUBLIC_KEY_BYTES, 13);
	private final byte[] aliceKemSecret = pattern(32, 67);
	private final byte[] bobKemSecret = pattern(32, 71);

	public IntroductionVectorTest() throws Exception {
		IntroductionIntegrationTestComponent component =
				DaggerIntroductionIntegrationTestComponent.builder().build();
		IntroductionIntegrationTestComponent.Helper
				.injectEagerSingletons(component);
		ClientHelper clientHelper = component.getClientHelper();
		Constructor<?> cc = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl")
				.getDeclaredConstructor(Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
						Class.forName(
								"org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		cryptoComponent = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		crypto = new IntroductionCryptoImpl(cryptoComponent, clientHelper,
				null);
	}

	private static byte[] pattern(int length, int seed) {
		byte[] b = new byte[length];
		for (int i = 0; i < length; i++) b[i] = (byte) (seed + 7 * i);
		return b;
	}

	private static byte[] bytes(String hex) throws Exception {
		return StringUtils.fromHexString(hex);
	}

	private static byte[] clamped(byte[] scalar) {
		scalar[0] &= (byte) 248;
		scalar[31] &= 127;
		scalar[31] |= 64;
		return scalar;
	}

	private static String hex(byte[] b) {
		return StringUtils.toHexString(b).toLowerCase(Locale.ROOT);
	}

	private static Author author(int seed, String name) {
		return new Author(new AuthorId(pattern(32, seed)), FORMAT_VERSION,
				name, new SignaturePublicKey(pattern(32, seed + 1)));
	}

	private static Map<TransportId, TransportProperties> properties(
			String onion) {
		Map<TransportId, TransportProperties> map = new HashMap<>();
		TransportProperties tor = new TransportProperties();
		tor.put("onion", onion);
		map.put(new TransportId("org.zerionproject.vector.tor"), tor);
		TransportProperties bluetooth = new TransportProperties();
		bluetooth.put("address", onion + "-bt");
		bluetooth.put("uuid", onion + "-uuid");
		map.put(new TransportId("org.zerionproject.vector.bt"), bluetooth);
		return map;
	}

	private IntroduceeSession session(boolean alice,
			Map<TransportId, TransportProperties> aliceProperties,
			Map<TransportId, TransportProperties> bobProperties)
			throws Exception {
		PublicKey ownPublic =
				new AgreementPublicKey(bytes(alice ? ALICE_PUBLIC : BOB_PUBLIC));
		PrivateKey ownPrivate = new AgreementPrivateKey(
				clamped(bytes(alice ? ALICE_PRIVATE : BOB_PRIVATE)));
		PublicKey theirPublic =
				new AgreementPublicKey(bytes(alice ? BOB_PUBLIC : ALICE_PUBLIC));
		Local local = new Local(alice, null, -1, ownPublic, ownPrivate,
				alice ? aliceProperties : bobProperties,
				alice ? aliceAcceptTimestamp : bobAcceptTimestamp, null, null,
				alice ? aliceKemPublic : bobKemPublic, null, null);
		Remote remote = new Remote(!alice, alice ? bobAuthor : aliceAuthor,
				null, theirPublic,
				alice ? bobProperties : aliceProperties,
				alice ? bobAcceptTimestamp : aliceAcceptTimestamp, null, null,
				alice ? bobKemPublic : aliceKemPublic);
		Author introducer = new Author(introducerId, FORMAT_VERSION,
				"introducer", new SignaturePublicKey(pattern(32, 4)));
		return new IntroduceeSession(new SessionId(pattern(32, 17)),
				AWAIT_AUTH, 1L, new GroupId(pattern(32, 19)), introducer,
				local, remote, null, null);
	}

	private byte[] authMac(IntroduceeSession s, SecretKey macKey) {
		Author own = s.getLocal().alice ? aliceAuthor : bobAuthor;
		return crypto.authMac(macKey, introducerId, own.getId(), s.getLocal(),
				s.getRemote());
	}

	private void assertSignsNonce(SecretKey nonceKey, String expectedNonce)
			throws Exception {
		KeyPair identity = cryptoComponent.generateHybridSignatureKeyPair();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) identity.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) identity.getPublic();
		byte[] signature = crypto.sign(nonceKey,
				new SignaturePrivateKey(priv.getEd25519PrivateKey()),
				priv.getMlDsaPrivateKey(), pub.getMlDsaPublicKey());
		assertTrue(cryptoComponent.verifyHybridSignature(signature,
				LABEL_AUTH_SIGN, bytes(expectedNonce), pub));
		crypto.verifySignature(nonceKey,
				new SignaturePublicKey(pub.getEd25519PublicKey()), signature,
				pub.getMlDsaPublicKey());
	}

	@Test
	public void theEarlierDerivationKeepsItsBytes() throws Exception {
		IntroduceeSession alice =
				session(true, properties("alice"), properties("bob"));
		IntroduceeSession bob =
				session(false, properties("alice"), properties("bob"));

		SecretKey shared = crypto.deriveMasterKey(alice);
		assertArrayEquals(shared.getBytes(),
				crypto.deriveMasterKey(bob).getBytes());
		assertEquals(EARLIER_SHARED_SECRET, hex(shared.getBytes()));

		SecretKey alicePreMaster =
				crypto.derivePreMasterKey(alice, aliceKemSecret);
		assertArrayEquals(alicePreMaster.getBytes(),
				crypto.derivePreMasterKey(bob, aliceKemSecret).getBytes());
		assertEquals(EARLIER_ALICE_PRE_MASTER,
				hex(alicePreMaster.getBytes()));
		SecretKey bobPreMaster = crypto.derivePreMasterKey(bob, bobKemSecret);
		assertEquals(EARLIER_BOB_PRE_MASTER, hex(bobPreMaster.getBytes()));

		SecretKey aliceMacKey = crypto.deriveMacKey(alicePreMaster, true);
		SecretKey bobMacKey = crypto.deriveMacKey(bobPreMaster, false);
		assertEquals(EARLIER_ALICE_MAC_KEY, hex(aliceMacKey.getBytes()));
		assertEquals(EARLIER_BOB_MAC_KEY, hex(bobMacKey.getBytes()));

		byte[] aliceAuthMac = authMac(alice, aliceMacKey);
		byte[] bobAuthMac = authMac(bob, bobMacKey);
		assertEquals(EARLIER_ALICE_AUTH_MAC, hex(aliceAuthMac));
		assertEquals(EARLIER_BOB_AUTH_MAC, hex(bobAuthMac));
		crypto.verifyAuthMacWithKey(aliceAuthMac, bob, bobAuthor.getId(),
				aliceMacKey);
		crypto.verifyAuthMacWithKey(bobAuthMac, alice, aliceAuthor.getId(),
				bobMacKey);

		assertEquals(EARLIER_ALICE_NONCE,
				hex(cryptoComponent.mac(LABEL_AUTH_NONCE, aliceMacKey)));
		assertEquals(EARLIER_BOB_NONCE,
				hex(cryptoComponent.mac(LABEL_AUTH_NONCE, bobMacKey)));
		assertSignsNonce(aliceMacKey, EARLIER_ALICE_NONCE);
		assertSignsNonce(bobMacKey, EARLIER_BOB_NONCE);

		SecretKey root = crypto.deriveFinalMasterKey(alice, aliceKemSecret,
				bobKemSecret);
		assertArrayEquals(root.getBytes(), crypto.deriveFinalMasterKey(bob,
				aliceKemSecret, bobKemSecret).getBytes());
		assertEquals(EARLIER_ROOT, hex(root.getBytes()));

		SecretKey aliceActivateKey = crypto.deriveActivateKey(root, true);
		SecretKey bobActivateKey = crypto.deriveActivateKey(root, false);
		assertEquals(EARLIER_ALICE_ACTIVATE_KEY,
				hex(aliceActivateKey.getBytes()));
		assertEquals(EARLIER_BOB_ACTIVATE_KEY,
				hex(bobActivateKey.getBytes()));
		assertEquals(EARLIER_ALICE_ACTIVATE_MAC,
				hex(crypto.activateMac(aliceActivateKey)));
		assertEquals(EARLIER_BOB_ACTIVATE_MAC,
				hex(crypto.activateMac(bobActivateKey)));

		IntroduceeSession aliceFinal =
				IntroduceeProtocolEngine.withFinalKeys(crypto, alice, root);
		IntroduceeSession bobFinal =
				IntroduceeProtocolEngine.withFinalKeys(crypto, bob, root);
		assertEquals(EARLIER_ROOT, hex(aliceFinal.getMasterKey()));
		assertEquals(EARLIER_ALICE_ACTIVATE_MAC,
				hex(crypto.activateMac(aliceFinal)));
		assertEquals(EARLIER_BOB_ACTIVATE_MAC,
				hex(crypto.activateMac(bobFinal)));
		crypto.verifyActivateMac(crypto.activateMac(aliceFinal), bobFinal);
		crypto.verifyActivateMac(crypto.activateMac(bobFinal), aliceFinal);
	}
}
