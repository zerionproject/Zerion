package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelAuthorBindingTest {

	private static final String ED_1 =
			"ca2208a2b22967b579b72957876612bbe72a203ed530f1a6c9cd63a11b1ba7fa";
	private static final String K_1 =
			"4953c72e0ed68061d9981c08d27f84a054066e5908beef404a3733e540c0729e";
	private static final String ED_2 =
			"ea7851dc112fa8bf851d95037f03d0be25cb8f17f4ab15dfa5b3e5afa4f3089b";
	private static final String K_2 =
			"83dd7d5e191a41e298a5c37c19d726da42bcbf45aec9f7389253544c3ad8fa87";
	private static final String D_NONE =
			"4d4b12e1b8e5ab2db452a2924b4d6e94fca69644d828d7cd9ded8b8b5049e095";
	private static final String D_1 =
			"01e58b0b73e5466433d7915907197fd501174ac87c1a6df385ccf00b82de4e05";
	private static final String D_1_2 =
			"0b822b98e8bd968d0c3972b64435cd5385dfcb05b41b933aefd0c1db32bdf2ff";

	private final Random random = new Random(6151);
	private CryptoComponent crypto;
	private HybridSignaturePublicKey one;
	private HybridSignaturePublicKey two;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		byte[] channelId = run(0x20);
		one = (HybridSignaturePublicKey) ChannelMemberKeys.derive(crypto,
				run(0x00), channelId).getPublic();
		two = (HybridSignaturePublicKey) ChannelMemberKeys.derive(crypto,
				run(0x40), channelId).getPublic();
	}

	@Test
	public void theDocumentedVectorsHold() {
		assertEquals(ED_1, ChannelStore.hex(one.getEd25519PublicKey()));
		assertEquals(K_1, ChannelStore.hex(ChannelAuthorBinding.keyHash(crypto,
				one.getMlDsaPublicKey())));
		assertEquals(ED_2, ChannelStore.hex(two.getEd25519PublicKey()));
		assertEquals(K_2, ChannelStore.hex(ChannelAuthorBinding.keyHash(crypto,
				two.getMlDsaPublicKey())));
		assertEquals(D_NONE, ChannelStore.hex(ChannelAuthorBinding.digest(
				crypto, Collections.<ChannelReaction>emptyList(),
				Collections.<ChannelComment>emptyList())));
		assertEquals(D_1, ChannelStore.hex(ChannelAuthorBinding.digest(crypto,
				Collections.<ChannelReaction>emptyList(),
				Collections.singletonList(comment(one, "a")))));
		assertEquals(D_1_2, ChannelStore.hex(ChannelAuthorBinding.digest(
				crypto, Collections.singletonList(reaction(two)),
				Collections.singletonList(comment(one, "a")))));
	}

	@Test
	public void theDigestCoversDistinctBindingsInAnyOrder() {
		List<ChannelComment> many = new ArrayList<>();
		many.add(comment(two, "x"));
		many.add(comment(one, "y"));
		many.add(comment(one, "z"));
		many.add(comment(two, "x"));
		byte[] d = ChannelAuthorBinding.digest(crypto,
				Collections.<ChannelReaction>emptyList(), many);
		assertEquals(D_1_2, ChannelStore.hex(d));
		List<ChannelReaction> reactions = new ArrayList<>();
		reactions.add(reaction(one));
		reactions.add(reaction(two));
		assertEquals(D_1_2, ChannelStore.hex(ChannelAuthorBinding.digest(
				crypto, reactions, Collections.<ChannelComment>emptyList())));
	}

	@Test
	public void anyChangeOfAKeyChangesTheDigest() {
		byte[] base = ChannelAuthorBinding.digest(crypto,
				Collections.<ChannelReaction>emptyList(),
				Collections.singletonList(comment(one, "a")));
		byte[] otherMl = one.getMlDsaPublicKey().clone();
		otherMl[otherMl.length - 1] ^= 1;
		ChannelComment swapped = new ChannelComment(0L, 1L, "a", "",
				one.getEd25519PublicKey(), otherMl, 0L);
		assertFalse(Arrays.equals(base, ChannelAuthorBinding.digest(crypto,
				Collections.<ChannelReaction>emptyList(),
				Collections.singletonList(swapped))));
		byte[] otherEd = one.getEd25519PublicKey().clone();
		otherEd[0] ^= 1;
		ChannelComment moved = new ChannelComment(0L, 1L, "a", "",
				otherEd, one.getMlDsaPublicKey(), 0L);
		assertFalse(Arrays.equals(base, ChannelAuthorBinding.digest(crypto,
				Collections.<ChannelReaction>emptyList(),
				Collections.singletonList(moved))));
	}

	@Test
	public void malformedEntriesAreNotPartOfTheDigest() {
		ChannelComment shortMl = new ChannelComment(0L, 1L, "a", "",
				two.getEd25519PublicKey(), new byte[16], 0L);
		ChannelComment shortEd = new ChannelComment(0L, 2L, "a", "",
				new byte[31], two.getMlDsaPublicKey(), 0L);
		List<ChannelComment> cs = new ArrayList<>();
		cs.add(comment(one, "a"));
		cs.add(shortMl);
		cs.add(shortEd);
		assertEquals(D_1, ChannelStore.hex(ChannelAuthorBinding.digest(crypto,
				Collections.<ChannelReaction>emptyList(), cs)));
	}

	@Test
	public void sizesAreCheckedBeforeAnythingElse() {
		byte[] ed = new byte[32];
		byte[] ml = new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
		byte[] sig = new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
		assertTrue(ChannelAuthorBinding.wellFormed(ed, ml, sig));
		assertFalse(ChannelAuthorBinding.wellFormed(new byte[31], ml, sig));
		assertFalse(ChannelAuthorBinding.wellFormed(new byte[33], ml, sig));
		assertFalse(ChannelAuthorBinding.wellFormed(ed,
				Arrays.copyOf(ml, ml.length - 1), sig));
		assertFalse(ChannelAuthorBinding.wellFormed(ed,
				Arrays.copyOf(ml, ml.length + 1), sig));
		assertFalse(ChannelAuthorBinding.wellFormed(ed, ml,
				Arrays.copyOf(sig, sig.length - 1)));
		assertFalse(ChannelAuthorBinding.wellFormed(ed, ml, null));
		assertFalse(ChannelAuthorBinding.wellFormed(null, ml));
		assertFalse(ChannelAuthorBinding.wellFormed(ed, null));
	}

	@Test
	public void aSessionBindsTheChannelKeyEditorsAndFirstContacts()
			throws Exception {
		KeyPair channelKeys = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePublicKey channelKey =
				(HybridSignaturePublicKey) channelKeys.getPublic();
		HybridSignaturePublicKey editor = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		HybridSignaturePublicKey stranger = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		byte[] channelId = run(0x60);
		ChannelDelegationCert cert = new ChannelDelegationCert(channelId,
				editor.getEd25519PublicKey(), editor.getMlDsaPublicKey(), 0L,
				0L, 0L, new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES]);
		ChannelState s = state(channelId, channelKey,
				Collections.singletonList(cert));
		ChannelAuthorPins pins = new ChannelAuthorPins(
				new ChannelTestNode.MemorySettings());
		ChannelAuthorBinding.Session session =
				new ChannelAuthorBinding.Session(crypto, pins, s);

		assertTrue(session.admits(channelKey.getEd25519PublicKey(),
				channelKey.getMlDsaPublicKey()));
		assertFalse("the channel key with another ML-DSA key",
				session.admits(channelKey.getEd25519PublicKey(),
						stranger.getMlDsaPublicKey()));
		assertTrue(session.admits(editor.getEd25519PublicKey(),
				editor.getMlDsaPublicKey()));
		assertFalse("an editor with another ML-DSA key",
				session.admits(editor.getEd25519PublicKey(),
						stranger.getMlDsaPublicKey()));

		assertTrue("a first contact is admitted",
				session.admits(stranger.getEd25519PublicKey(),
						stranger.getMlDsaPublicKey()));
		session.bind(stranger.getEd25519PublicKey(),
				stranger.getMlDsaPublicKey());
		assertFalse("the same session holds the new binding",
				session.admits(stranger.getEd25519PublicKey(),
						editor.getMlDsaPublicKey()));
		assertNull("nothing is written before the commit",
				pins.get(channelId, stranger.getEd25519PublicKey()));
		session.commit(Collections::emptySet);
		assertArrayEquals(ChannelAuthorBinding.keyHash(crypto,
				stranger.getMlDsaPublicKey()),
				pins.get(channelId, stranger.getEd25519PublicKey()));

		ChannelAuthorBinding.Session later =
				new ChannelAuthorBinding.Session(crypto, pins, s);
		assertFalse("a later session refuses the other key",
				later.admits(stranger.getEd25519PublicKey(),
						editor.getMlDsaPublicKey()));
		later.bind(stranger.getEd25519PublicKey(),
				editor.getMlDsaPublicKey());
		later.commit(Collections::emptySet);
		assertArrayEquals("a bound key is never rebound",
				ChannelAuthorBinding.keyHash(crypto,
						stranger.getMlDsaPublicKey()),
				pins.get(channelId, stranger.getEd25519PublicKey()));
	}

	@Test
	public void certificatesThatDisagreeBindTheirKeyToNothing()
			throws Exception {
		HybridSignaturePublicKey channelKey = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		HybridSignaturePublicKey editor = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		HybridSignaturePublicKey other = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		byte[] channelId = run(0x61);
		byte[] sig = new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
		List<ChannelDelegationCert> certs = new ArrayList<>();
		certs.add(new ChannelDelegationCert(channelId,
				editor.getEd25519PublicKey(), editor.getMlDsaPublicKey(), 0L,
				0L, 0L, sig));
		certs.add(new ChannelDelegationCert(channelId,
				editor.getEd25519PublicKey(), other.getMlDsaPublicKey(), 0L,
				0L, 1L, sig));
		ChannelAuthorBinding.Session session =
				new ChannelAuthorBinding.Session(crypto, new ChannelAuthorPins(
						new ChannelTestNode.MemorySettings()),
						state(channelId, channelKey, certs));
		assertFalse(session.admits(editor.getEd25519PublicKey(),
				editor.getMlDsaPublicKey()));
		assertFalse(session.admits(editor.getEd25519PublicKey(),
				other.getMlDsaPublicKey()));
	}

	@Test
	public void aLargeBatchReadsThePinsOnceAndStillRefusesOtherKeys()
			throws Exception {
		HybridSignaturePublicKey channelKey = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		byte[] channelId = run(0x63);
		ChannelTestNode.MemorySettings settings =
				new ChannelTestNode.MemorySettings();
		ChannelAuthorPins pins = new ChannelAuthorPins(settings);
		List<HybridSignaturePublicKey> authors = new ArrayList<>();
		java.util.Map<String, byte[]> bound = new java.util.LinkedHashMap<>();
		for (int i = 0; i < 48; i++) {
			HybridSignaturePublicKey k = (HybridSignaturePublicKey)
					crypto.generateHybridSignatureKeyPair().getPublic();
			authors.add(k);
			bound.put(ChannelStore.hex(k.getEd25519PublicKey()),
					ChannelAuthorBinding.keyHash(crypto, k.getMlDsaPublicKey()));
		}
		pins.put(channelId, bound, Collections::emptySet);
		ChannelAuthorBinding.Session session = new ChannelAuthorBinding
				.Session(crypto, pins, state(channelId, channelKey,
				Collections.<ChannelDelegationCert>emptyList()));
		byte[] other = two.getMlDsaPublicKey();
		for (HybridSignaturePublicKey k : authors) {
			assertTrue(session.admits(k.getEd25519PublicKey(),
					k.getMlDsaPublicKey()));
		}
		for (HybridSignaturePublicKey k : authors) {
			assertFalse(session.admits(k.getEd25519PublicKey(), other));
		}
		HybridSignaturePublicKey fresh = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		assertTrue(session.admits(fresh.getEd25519PublicKey(), other));
	}

	@Test
	public void malformedKeysAreNeverAdmittedOrBound() throws Exception {
		HybridSignaturePublicKey channelKey = (HybridSignaturePublicKey)
				crypto.generateHybridSignatureKeyPair().getPublic();
		byte[] channelId = run(0x62);
		ChannelAuthorPins pins = new ChannelAuthorPins(
				new ChannelTestNode.MemorySettings());
		ChannelAuthorBinding.Session session = new ChannelAuthorBinding
				.Session(crypto, pins, state(channelId, channelKey,
				Collections.<ChannelDelegationCert>emptyList()));
		byte[] ed = new byte[32];
		random.nextBytes(ed);
		byte[] shortMl = new byte[100];
		assertFalse(session.admits(ed, shortMl));
		session.bind(ed, shortMl);
		session.commit(Collections::emptySet);
		assertNull(pins.get(channelId, ed));
	}

	private ChannelState state(byte[] channelId,
			HybridSignaturePublicKey channelKey,
			List<ChannelDelegationCert> certs) {
		return new ChannelState(channelId, new byte[16],
				channelKey.getEd25519PublicKey(), channelKey.getMlDsaPublicKey(),
				"", "", null, 0L, true, null, "", 1L, false, -1L, null, null,
				certs, Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, false,
				Collections.<ChannelDelegationCert>emptyList());
	}

	private static ChannelComment comment(HybridSignaturePublicKey k,
			String body) {
		return new ChannelComment(0L, 1L, body, "", k.getEd25519PublicKey(),
				k.getMlDsaPublicKey(), 0L);
	}

	private static ChannelReaction reaction(HybridSignaturePublicKey k) {
		return new ChannelReaction(0L, "+", k.getEd25519PublicKey(),
				k.getMlDsaPublicKey(), 0L);
	}

	private static byte[] run(int start) {
		byte[] b = new byte[ChannelConstants.CHANNEL_ID_BYTES];
		for (int i = 0; i < b.length; i++) b[i] = (byte) (start + i);
		return b;
	}
}
