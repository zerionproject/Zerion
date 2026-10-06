package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PeerMlDsaKeyExchangeTest {

	private static final int ALICE_SEES_BOB = 2;
	private static final int BOB_SEES_ALICE = 1;

	private RootEvolutionTestBed bed;
	private SecretKey pairingRoot;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		pairingRoot = bed.randomKey();
	}

	static final class Identity implements RootEvolutionManager.PeerIdentity {
		final byte[] ownKey;
		final byte[] ownSignature;
		final Map<Integer, byte[]> known = new ConcurrentHashMap<>();
		final Map<Integer, byte[]> offered = new ConcurrentHashMap<>();

		Identity(byte[] ownKey, byte[] ownSignature) {
			this.ownKey = ownKey;
			this.ownSignature = ownSignature;
		}

		@Override
		public boolean knowsPeerKey(ContactId c) {
			return known.containsKey(c.getInt());
		}

		@Override
		@Nullable
		public byte[][] ownKeyAndSignature() {
			return new byte[][] {ownKey, ownSignature};
		}

		@Override
		public void learnPeerKey(ContactId c, byte[] mlDsaKey,
				byte[] signature) {
			offered.put(c.getInt(), mlDsaKey);
			if (Arrays.equals(signature, expectedSignature(mlDsaKey))) {
				known.putIfAbsent(c.getInt(), mlDsaKey);
			}
		}
	}

	static byte[] expectedSignature(byte[] key) {
		byte[] s = new byte[RootEvolutionRecord.ED25519_SIGNATURE_LENGTH];
		for (int i = 0; i < s.length; i++) s[i] = (byte) (key[i] ^ 0x5a);
		return s;
	}

	private byte[] randomKey() {
		byte[] k = new byte[RootEvolutionRecord.ML_DSA_PUBLIC_KEY_LENGTH];
		bed.crypto.getSecureRandom().nextBytes(k);
		return k;
	}

	@Test(timeout = 120_000)
	public void aPeerWhoseKeyIsUnknownSendsItOnTheNextConnection()
			throws Exception {
		byte[] aliceKey = randomKey();
		byte[] bobKey = randomKey();
		Identity aliceIdentity = new Identity(aliceKey,
				expectedSignature(aliceKey));
		Identity bobIdentity = new Identity(bobKey, expectedSignature(bobKey));
		aliceIdentity.known.put(ALICE_SEES_BOB, bobKey);
		Device alice = bed.new Device("alice", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true, null,
				aliceIdentity);
		Device bob = bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), true, null,
				bobIdentity);
		int a = alice.received.size();
		int b = bob.received.size();
		bed.connect(alice, bob, () -> alice.received.size() > a
				&& bob.received.size() > b
				&& bobIdentity.known.containsKey(BOB_SEES_ALICE));
		assertArrayEquals("bob learned alice's key", aliceKey,
				bobIdentity.known.get(BOB_SEES_ALICE));
		assertNull("alice already knew bob's key and was not offered it",
				aliceIdentity.offered.get(ALICE_SEES_BOB));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob)
				|| alice.keys().getEpoch() >= 0);
	}

	@Test(timeout = 120_000)
	public void aKeyWithAWrongSignatureIsOfferedButNotLearned()
			throws Exception {
		byte[] aliceKey = randomKey();
		byte[] bobKey = randomKey();
		Identity aliceIdentity = new Identity(aliceKey,
				expectedSignature(bobKey));
		Identity bobIdentity = new Identity(bobKey, expectedSignature(bobKey));
		aliceIdentity.known.put(ALICE_SEES_BOB, bobKey);
		Device alice = bed.new Device("alice", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true, null,
				aliceIdentity);
		Device bob = bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), true, null,
				bobIdentity);
		int a = alice.received.size();
		int b = bob.received.size();
		bed.connect(alice, bob, () -> alice.received.size() > a
				&& bob.received.size() > b
				&& bobIdentity.offered.containsKey(BOB_SEES_ALICE));
		assertArrayEquals(aliceKey, bobIdentity.offered.get(BOB_SEES_ALICE));
		assertNull(bobIdentity.known.get(BOB_SEES_ALICE));
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
