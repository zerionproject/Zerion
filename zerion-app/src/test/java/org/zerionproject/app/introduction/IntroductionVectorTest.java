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
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.zerionproject.core.api.identity.Author.FORMAT_VERSION;
import static org.zerionproject.app.api.introduction.IntroductionConstants.INTRODUCTION_ML_KEM_PUBLIC_KEY_BYTES;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_ACTIVATE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_AUTH_MAC_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_AUTH_NONCE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_NONCE;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_SECRET_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_SIGN;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_ACTIVATE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_AUTH_MAC_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_AUTH_NONCE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_FINAL_SECRET_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ROOT_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_SHARED_SECRET_V2;
import static org.zerionproject.app.api.introduction.IntroductionManager.MAJOR_VERSION;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_AUTH;
import static org.zerionproject.app.introduction.KeySchedule.SEPARATED_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

	private static final String SEPARATED_SHARED_SECRET =
			"72e0ea5f6d0d11a576f662b9dbbf269f9629fca6c3d748cfbec6835788ef93f8";
	private static final String SEPARATED_ALICE_AUTH_SECRET =
			"5e74a6e7df3077c30612903f9d94b40082123ee68acb78d311350278c5b8adfd";
	private static final String SEPARATED_BOB_AUTH_SECRET =
			"2a433878a50d23a621f9243b91e38180b8b7c877ad8d55d115865e49952a9ad2";
	private static final String SEPARATED_ALICE_AUTH_MAC_KEY =
			"e4d982266dd56552583fdf06dc8f807114e0711456b709153dbd2ce1e97d8bf1";
	private static final String SEPARATED_ALICE_AUTH_NONCE_KEY =
			"cfe7e8f4f4cd17fccd1c20b947bac2acf0463344272fccf6e1d393ab4e02c7b1";
	private static final String SEPARATED_BOB_AUTH_MAC_KEY =
			"a5242507a721eba51df0e739d2242bc0fcfb3bd91df95c5219c16d8dac58929a";
	private static final String SEPARATED_BOB_AUTH_NONCE_KEY =
			"79f979aa556e2fbc1214f816e452aa7e5fb874b981514890b7729f7558578489";
	private static final String SEPARATED_ALICE_AUTH_MAC =
			"b67de80c597e878e7da8624a84b06e8e62f2e624ff98cec5cf092289174c1cf1";
	private static final String SEPARATED_BOB_AUTH_MAC =
			"b26fc26f461cebdf7c4a4102b083d40612f5fe26da5b6b3db6b1867449e97b7b";
	private static final String SEPARATED_ALICE_NONCE =
			"5a4d791816c65a3ef9af90c079a84e3ed996dc4ba0529237e13321e230fbf2e2";
	private static final String SEPARATED_BOB_NONCE =
			"e0d23bce724cbd316d1d46f856383c2601d32dd7dcb5423f6f66e64742c495a5";
	private static final String SEPARATED_FINAL_SECRET =
			"76d84d64c3f9a9397981cbc6fa5bfc784dc12b4cb07b693e38921294d8d2b509";
	private static final String SEPARATED_ROOT =
			"058eedff38104d21fb45d4fa0b26fba820750ada68a88eadc6c8a2c30fb75f7a";
	private static final String SEPARATED_ALICE_ACTIVATE_KEY =
			"0534fde7bb44109e731c99f97304a8f0307d87b4373a2dc37c9b98688a17f84f";
	private static final String SEPARATED_BOB_ACTIVATE_KEY =
			"bf1157fa00e6e3c460336257d4bbe0c6496118b392e3f9e62d6b78fa5b7c96fa";
	private static final String SEPARATED_ALICE_ACTIVATE_MAC =
			"925ec6dfe7fe5d5e117c2941c8cee91573d0812f6b46ec2371d7db0646ab9fd6";
	private static final String SEPARATED_BOB_ACTIVATE_MAC =
			"5428e181cc289e589d7726c328f549aa7b1daf3812c991a3ff7f03a839ee5589";

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

	private static PublicKey publicKey(boolean alice) throws Exception {
		return new AgreementPublicKey(
				bytes(alice ? ALICE_PUBLIC : BOB_PUBLIC));
	}

	private static PrivateKey privateKey(boolean alice) throws Exception {
		return new AgreementPrivateKey(
				clamped(bytes(alice ? ALICE_PRIVATE : BOB_PRIVATE)));
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

	private Map<TransportId, TransportProperties> announced(String onion,
			int version) {
		return KeySchedule.announce(properties(onion), version);
	}

	private SecretKey separatedSharedSecret(boolean alice, int aliceVersion,
			int bobVersion) throws Exception {
		return crypto.deriveSeparatedSharedSecret(publicKey(alice),
				privateKey(alice), publicKey(!alice), alice, aliceVersion,
				bobVersion);
	}

	@Test
	public void theSeparatedDerivationIsPinned() throws Exception {
		IntroduceeSession alice = session(true,
				announced("alice", SEPARATED_VERSION),
				announced("bob", SEPARATED_VERSION));
		IntroduceeSession bob = session(false,
				announced("alice", SEPARATED_VERSION),
				announced("bob", SEPARATED_VERSION));

		SecretKey shared = separatedSharedSecret(true, SEPARATED_VERSION,
				SEPARATED_VERSION);
		assertArrayEquals(shared.getBytes(), separatedSharedSecret(false,
				SEPARATED_VERSION, SEPARATED_VERSION).getBytes());
		assertEquals(SEPARATED_SHARED_SECRET, hex(shared.getBytes()));
		KeyPair aliceEphemeral =
				new KeyPair(publicKey(true), privateKey(true));
		assertEquals(SEPARATED_SHARED_SECRET, hex(
				cryptoComponent.deriveSharedSecret(LABEL_SHARED_SECRET_V2,
						publicKey(false),
						aliceEphemeral, new byte[] {MAJOR_VERSION},
						bytes(ALICE_PUBLIC), bytes(BOB_PUBLIC),
						new byte[] {SEPARATED_VERSION},
						new byte[] {SEPARATED_VERSION}).getBytes()));

		SecretKey aliceAuthSecret = cryptoComponent.deriveKey(
				LABEL_AUTH_SECRET_V2, shared, aliceKemSecret);
		SecretKey bobAuthSecret = cryptoComponent.deriveKey(
				LABEL_AUTH_SECRET_V2, shared, bobKemSecret);
		assertEquals(SEPARATED_ALICE_AUTH_SECRET,
				hex(aliceAuthSecret.getBytes()));
		assertEquals(SEPARATED_BOB_AUTH_SECRET,
				hex(bobAuthSecret.getBytes()));

		SeparatedAuthKeys aliceKeys = crypto.deriveSeparatedAuthKeys(alice,
				aliceKemSecret, true);
		SeparatedAuthKeys bobKeys = crypto.deriveSeparatedAuthKeys(bob,
				bobKemSecret, false);
		assertEquals(SEPARATED_ALICE_AUTH_MAC_KEY,
				hex(aliceKeys.getMacKey().getBytes()));
		assertEquals(SEPARATED_ALICE_AUTH_NONCE_KEY,
				hex(aliceKeys.getNonceKey().getBytes()));
		assertEquals(SEPARATED_BOB_AUTH_MAC_KEY,
				hex(bobKeys.getMacKey().getBytes()));
		assertEquals(SEPARATED_BOB_AUTH_NONCE_KEY,
				hex(bobKeys.getNonceKey().getBytes()));
		assertEquals(SEPARATED_ALICE_AUTH_MAC_KEY, hex(cryptoComponent
				.deriveKey(LABEL_ALICE_AUTH_MAC_KEY_V2, aliceAuthSecret)
				.getBytes()));
		assertEquals(SEPARATED_ALICE_AUTH_NONCE_KEY, hex(cryptoComponent
				.deriveKey(LABEL_ALICE_AUTH_NONCE_KEY_V2, aliceAuthSecret)
				.getBytes()));
		assertEquals(SEPARATED_BOB_AUTH_MAC_KEY, hex(cryptoComponent
				.deriveKey(LABEL_BOB_AUTH_MAC_KEY_V2, bobAuthSecret)
				.getBytes()));
		assertEquals(SEPARATED_BOB_AUTH_NONCE_KEY, hex(cryptoComponent
				.deriveKey(LABEL_BOB_AUTH_NONCE_KEY_V2, bobAuthSecret)
				.getBytes()));
		SeparatedAuthKeys aliceKeysAtBob = crypto.deriveSeparatedAuthKeys(bob,
				aliceKemSecret, true);
		assertArrayEquals(aliceKeys.getMacKey().getBytes(),
				aliceKeysAtBob.getMacKey().getBytes());
		assertArrayEquals(aliceKeys.getNonceKey().getBytes(),
				aliceKeysAtBob.getNonceKey().getBytes());

		byte[] aliceAuthMac = authMac(alice, aliceKeys.getMacKey());
		byte[] bobAuthMac = authMac(bob, bobKeys.getMacKey());
		assertEquals(SEPARATED_ALICE_AUTH_MAC, hex(aliceAuthMac));
		assertEquals(SEPARATED_BOB_AUTH_MAC, hex(bobAuthMac));
		crypto.verifyAuthMacWithKey(aliceAuthMac, bob, bobAuthor.getId(),
				aliceKeysAtBob.getMacKey());
		crypto.verifyAuthMacWithKey(bobAuthMac, alice, aliceAuthor.getId(),
				bobKeys.getMacKey());

		assertEquals(SEPARATED_ALICE_NONCE, hex(cryptoComponent.mac(
				LABEL_AUTH_NONCE, aliceKeys.getNonceKey())));
		assertEquals(SEPARATED_BOB_NONCE, hex(cryptoComponent.mac(
				LABEL_AUTH_NONCE, bobKeys.getNonceKey())));
		assertSignsNonce(aliceKeys.getNonceKey(), SEPARATED_ALICE_NONCE);
		assertSignsNonce(bobKeys.getNonceKey(), SEPARATED_BOB_NONCE);

		SecretKey finalSecret = cryptoComponent.deriveKey(
				LABEL_FINAL_SECRET_V2, shared, aliceKemSecret, bobKemSecret);
		assertEquals(SEPARATED_FINAL_SECRET, hex(finalSecret.getBytes()));

		SeparatedSessionKeys keys = crypto.deriveSeparatedSessionKeys(alice,
				aliceKemSecret, bobKemSecret);
		SeparatedSessionKeys keysAtBob = crypto.deriveSeparatedSessionKeys(
				bob, aliceKemSecret, bobKemSecret);
		assertArrayEquals(keys.getRootKey().getBytes(),
				keysAtBob.getRootKey().getBytes());
		assertArrayEquals(keys.getAliceActivateKey().getBytes(),
				keysAtBob.getAliceActivateKey().getBytes());
		assertArrayEquals(keys.getBobActivateKey().getBytes(),
				keysAtBob.getBobActivateKey().getBytes());
		assertEquals(SEPARATED_ROOT, hex(keys.getRootKey().getBytes()));
		assertEquals(SEPARATED_ALICE_ACTIVATE_KEY,
				hex(keys.getAliceActivateKey().getBytes()));
		assertEquals(SEPARATED_BOB_ACTIVATE_KEY,
				hex(keys.getBobActivateKey().getBytes()));
		assertEquals(SEPARATED_ROOT, hex(cryptoComponent.deriveKey(
				LABEL_ROOT_KEY_V2, finalSecret).getBytes()));
		assertEquals(SEPARATED_ALICE_ACTIVATE_KEY, hex(cryptoComponent
				.deriveKey(LABEL_ALICE_ACTIVATE_KEY_V2, finalSecret)
				.getBytes()));
		assertEquals(SEPARATED_BOB_ACTIVATE_KEY, hex(cryptoComponent
				.deriveKey(LABEL_BOB_ACTIVATE_KEY_V2, finalSecret)
				.getBytes()));
		assertEquals(SEPARATED_ALICE_ACTIVATE_MAC,
				hex(crypto.activateMac(keys.getAliceActivateKey())));
		assertEquals(SEPARATED_BOB_ACTIVATE_MAC,
				hex(crypto.activateMac(keys.getBobActivateKey())));

		IntroduceeSession aliceFinal = IntroduceeSession.withFinalKeys(alice,
				keys.getRootKey().getBytes(), keys.getAliceActivateKey(),
				keys.getBobActivateKey());
		IntroduceeSession bobFinal = IntroduceeSession.withFinalKeys(bob,
				keysAtBob.getRootKey().getBytes(),
				keysAtBob.getAliceActivateKey(),
				keysAtBob.getBobActivateKey());
		assertEquals(SEPARATED_ALICE_ACTIVATE_MAC,
				hex(crypto.activateMac(aliceFinal)));
		assertEquals(SEPARATED_BOB_ACTIVATE_MAC,
				hex(crypto.activateMac(bobFinal)));
		crypto.verifyActivateMac(crypto.activateMac(aliceFinal), bobFinal);
		crypto.verifyActivateMac(crypto.activateMac(bobFinal), aliceFinal);
	}

	@Test
	public void noSeparatedValueEqualsAnotherOrAnEarlierOne() {
		String[] values = {
				EARLIER_SHARED_SECRET, EARLIER_ALICE_PRE_MASTER,
				EARLIER_BOB_PRE_MASTER, EARLIER_ALICE_MAC_KEY,
				EARLIER_BOB_MAC_KEY, EARLIER_ALICE_AUTH_MAC,
				EARLIER_BOB_AUTH_MAC, EARLIER_ALICE_NONCE, EARLIER_BOB_NONCE,
				EARLIER_ROOT, EARLIER_ALICE_ACTIVATE_KEY,
				EARLIER_BOB_ACTIVATE_KEY, EARLIER_ALICE_ACTIVATE_MAC,
				EARLIER_BOB_ACTIVATE_MAC, SEPARATED_SHARED_SECRET,
				SEPARATED_ALICE_AUTH_SECRET, SEPARATED_BOB_AUTH_SECRET,
				SEPARATED_ALICE_AUTH_MAC_KEY, SEPARATED_ALICE_AUTH_NONCE_KEY,
				SEPARATED_BOB_AUTH_MAC_KEY, SEPARATED_BOB_AUTH_NONCE_KEY,
				SEPARATED_ALICE_AUTH_MAC, SEPARATED_BOB_AUTH_MAC,
				SEPARATED_ALICE_NONCE, SEPARATED_BOB_NONCE,
				SEPARATED_FINAL_SECRET, SEPARATED_ROOT,
				SEPARATED_ALICE_ACTIVATE_KEY, SEPARATED_BOB_ACTIVATE_KEY,
				SEPARATED_ALICE_ACTIVATE_MAC, SEPARATED_BOB_ACTIVATE_MAC
		};
		Set<String> distinct = new HashSet<>(Arrays.asList(values));
		assertEquals(values.length, distinct.size());
		for (String value : values) assertEquals(64, value.length());
	}

	@Test
	public void bothAnnouncedVersionsAreInputsOfEverySeparatedKey()
			throws Exception {
		int later = SEPARATED_VERSION + 1;
		SecretKey both = separatedSharedSecret(true, SEPARATED_VERSION,
				SEPARATED_VERSION);
		SecretKey aliceLater =
				separatedSharedSecret(true, later, SEPARATED_VERSION);
		SecretKey bobLater =
				separatedSharedSecret(true, SEPARATED_VERSION, later);
		assertFalse(Arrays.equals(both.getBytes(), aliceLater.getBytes()));
		assertFalse(Arrays.equals(both.getBytes(), bobLater.getBytes()));
		assertFalse(Arrays.equals(aliceLater.getBytes(),
				bobLater.getBytes()));
		assertArrayEquals(aliceLater.getBytes(), separatedSharedSecret(false,
				later, SEPARATED_VERSION).getBytes());

		IntroduceeSession alice = session(true,
				announced("alice", SEPARATED_VERSION),
				announced("bob", SEPARATED_VERSION));
		IntroduceeSession bobSeesAliceLater = session(false,
				announced("alice", later),
				announced("bob", SEPARATED_VERSION));
		SeparatedAuthKeys sent = crypto.deriveSeparatedAuthKeys(alice,
				aliceKemSecret, true);
		SeparatedAuthKeys expected = crypto.deriveSeparatedAuthKeys(
				bobSeesAliceLater, aliceKemSecret, true);
		assertFalse(Arrays.equals(sent.getMacKey().getBytes(),
				expected.getMacKey().getBytes()));
		assertFalse(Arrays.equals(sent.getNonceKey().getBytes(),
				expected.getNonceKey().getBytes()));
		SeparatedSessionKeys aliceKeys = crypto.deriveSeparatedSessionKeys(
				alice, aliceKemSecret, bobKemSecret);
		SeparatedSessionKeys bobKeys = crypto.deriveSeparatedSessionKeys(
				bobSeesAliceLater, aliceKemSecret, bobKemSecret);
		assertFalse(Arrays.equals(aliceKeys.getRootKey().getBytes(),
				bobKeys.getRootKey().getBytes()));
		assertFalse(Arrays.equals(aliceKeys.getAliceActivateKey().getBytes(),
				bobKeys.getAliceActivateKey().getBytes()));
		assertFalse(Arrays.equals(aliceKeys.getBobActivateKey().getBytes(),
				bobKeys.getBobActivateKey().getBytes()));
	}

	@Test
	public void theSeparatedDerivationNeedsBothAnnouncements()
			throws Exception {
		IntroduceeSession[] sessions = {
				session(true, announced("alice", SEPARATED_VERSION),
						properties("bob")),
				session(true, properties("alice"),
						announced("bob", SEPARATED_VERSION)),
				session(true, properties("alice"), properties("bob"))
		};
		for (IntroduceeSession s : sessions) {
			try {
				crypto.deriveSeparatedAuthKeys(s, aliceKemSecret, true);
				fail();
			} catch (GeneralSecurityException expected) {
			}
			try {
				crypto.deriveSeparatedSessionKeys(s, aliceKemSecret,
						bobKemSecret);
				fail();
			} catch (GeneralSecurityException expected) {
			}
		}
		try {
			separatedSharedSecret(true, SEPARATED_VERSION,
					KeySchedule.EARLIER_VERSION);
			fail();
		} catch (GeneralSecurityException expected) {
		}
	}

	@Test
	public void theEarlierAuthMacCoversTheAnnouncementAsItWasSent()
			throws Exception {
		SecretKey aliceMacKey = new SecretKey(bytes(EARLIER_ALICE_MAC_KEY));
		IntroduceeSession alice = session(true,
				announced("alice", SEPARATED_VERSION), properties("bob"));
		byte[] mac = authMac(alice, aliceMacKey);
		assertFalse(Arrays.equals(bytes(EARLIER_ALICE_AUTH_MAC), mac));

		IntroduceeSession bobSawIt = session(false,
				announced("alice", SEPARATED_VERSION), properties("bob"));
		crypto.verifyAuthMacWithKey(mac, bobSawIt, bobAuthor.getId(),
				aliceMacKey);

		IntroduceeSession bobSawItStripped =
				session(false, properties("alice"), properties("bob"));
		try {
			crypto.verifyAuthMacWithKey(mac, bobSawItStripped,
					bobAuthor.getId(), aliceMacKey);
			fail();
		} catch (GeneralSecurityException expected) {
		}

		IntroduceeSession bobAnnounced = session(false,
				announced("alice", SEPARATED_VERSION),
				announced("bob", SEPARATED_VERSION));
		try {
			crypto.verifyAuthMacWithKey(mac, bobAnnounced, bobAuthor.getId(),
					aliceMacKey);
			fail();
		} catch (GeneralSecurityException expected) {
		}
	}
}
