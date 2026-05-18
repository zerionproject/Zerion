package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.MlKemKeyPair;
import org.briarproject.bramble.api.crypto.pcs.MlKemProvider;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet.PqRecvResult;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet.PqSendResult;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullState;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_RECV_SK_LRU_SIZE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Mode3FullRatchetImplTest {

	private CryptoComponent crypto;
	private MlKemProvider mlKemProvider;
	private Mode3FullRatchetImpl ratchet;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.briarproject.bramble.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.briarproject.bramble.api.system.SecureRandomProvider"),
				Class.forName(
						"org.briarproject.bramble.crypto.PasswordBasedKdf"));
		constructor.setAccessible(true);
		crypto = (CryptoComponent) constructor.newInstance(
				new TestSecureRandomProvider(), null);

		mlKemProvider = new MlKemProviderImpl(crypto.getSecureRandom());
		ratchet = new Mode3FullRatchetImpl(crypto, mlKemProvider);
	}

	private SecretKey randomKey() {
		byte[] keyBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(keyBytes);
		return new SecretKey(keyBytes);
	}

	@Test
	public void testInitialStateBothSidesAgreeOnCkPq() {
		// Same root key on both sides → both should derive the same
		// initial CK_pq (deterministic split).
		SecretKey root = randomKey();

		Mode3FullState a = ratchet.createInitialState(root);
		Mode3FullState b = ratchet.createInitialState(root);

		// Different ephemeral keypairs (random) but matching CK_pq.
		assertArrayEquals(a.getCkPq().getBytes(), b.getCkPq().getBytes());
		assertNull(a.getTheirActivePqPk());
		assertNotNull(a.getOurActiveKeyPair());
	}

	@Test
	public void testFirstSendUsesZeroSentinel() {
		// theirActivePqPk == null → sender emits all-zero CT.
		SecretKey root = randomKey();
		Mode3FullState state = ratchet.createInitialState(root);

		PqSendResult result = ratchet.pqEncapsulateSend(state);

		assertEquals(MLKEM_CIPHERTEXT_SIZE, result.getCiphertext().length);
		for (byte b : result.getCiphertext()) {
			assertEquals(0, b);
		}
		assertEquals(MLKEM_ENCAPSULATION_KEY_SIZE,
				result.getPkAdvertise().length);
	}

	@Test
	public void testReceiverHandlesZeroSentinelWithoutAbsorb() throws Exception {
		// Receiver gets all-zero CT (first frame from peer) → CK_pq
		// advances via symmetric label only, no decap attempt.
		SecretKey root = randomKey();
		Mode3FullState rState = ratchet.createInitialState(root);

		byte[] zeroCt = new byte[MLKEM_CIPHERTEXT_SIZE];
		byte[] peerPk = new byte[MLKEM_ENCAPSULATION_KEY_SIZE];
		// Real ephemeral pubkey for the slot — receiver stores it for
		// next outbound, doesn't decap it.
		MlKemKeyPair tmp = mlKemProvider.generateKeyPair();
		System.arraycopy(tmp.getEncapsulationKey(), 0, peerPk, 0,
				MLKEM_ENCAPSULATION_KEY_SIZE);

		PqRecvResult result = ratchet.pqDecapsulateRecv(rState, null,
				zeroCt, peerPk);

		// CK_pq advanced; peer's PK stored for next encap from us.
		assertFalse(Arrays.equals(rState.getCkPq().getBytes(),
				result.getNewCkPq().getBytes()));
		assertArrayEquals(peerPk,
				result.getNewState().getTheirActivePqPk());
	}

	@Test
	public void testEncapDecapRoundTripSymmetric() throws Exception {
		// Alice encapsulates to Bob's pubkey → CT + ss_alice_view.
		// Bob decapsulates CT with his privkey → ss_bob_view.
		// Both views must match.
		MlKemKeyPair bobKp = mlKemProvider.generateKeyPair();

		// Simulate Alice's state with Bob's pubkey installed.
		SecretKey root = randomKey();
		Mode3FullState aliceState = ratchet.createInitialState(root);
		aliceState = aliceState.withRecvAdvance(aliceState.getCkPq(),
				bobKp.getEncapsulationKey());

		PqSendResult aliceSend = ratchet.pqEncapsulateSend(aliceState);

		// Bob's state has bobKp as his active.
		Mode3FullState bobState = new Mode3FullState(aliceState.getCkPq(),
				null, bobKp, new LinkedHashMap<>(), 0);

		PqRecvResult bobRecv = ratchet.pqDecapsulateRecv(bobState,
				aliceSend.getKpIdUsed(),
				aliceSend.getCiphertext(), aliceSend.getPkAdvertise());

		// After absorbing the same KEM secret into the same CK_pq, both
		// sides' new CK_pq must match.
		assertArrayEquals(aliceSend.getNewCkPq().getBytes(),
				bobRecv.getNewCkPq().getBytes());
	}

	@Test
	public void testFullRoundTripBothDirections() throws Exception {
		// Full session: Alice and Bob each derive initial state, swap
		// PKs via withRecvAdvance, then send a chain of messages and
		// verify hybrid MK derivation stays in sync.
		SecretKey root = randomKey();
		MlKemKeyPair aliceKp = mlKemProvider.generateKeyPair();
		MlKemKeyPair bobKp = mlKemProvider.generateKeyPair();

		Mode3FullState aliceState = new Mode3FullState(
				ratchet.createInitialState(root).getCkPq(),
				bobKp.getEncapsulationKey(), aliceKp,
				new LinkedHashMap<>(), 0);
		Mode3FullState bobState = new Mode3FullState(
				ratchet.createInitialState(root).getCkPq(),
				aliceKp.getEncapsulationKey(), bobKp,
				new LinkedHashMap<>(), 0);

		SecretKey classicalMk = randomKey();

		// Alice sends a message.
		PqSendResult aliceSend = ratchet.pqEncapsulateSend(aliceState);
		SecretKey aliceMk = ratchet.deriveHybridMessageKey(classicalMk,
				aliceState.getCkPq()); // OLD ckPq for hybrid MK (Fix B)

		// Bob receives Alice's message.
		PqRecvResult bobRecv = ratchet.pqDecapsulateRecv(bobState,
				aliceSend.getKpIdUsed(),
				aliceSend.getCiphertext(), aliceSend.getPkAdvertise());
		SecretKey bobMk = ratchet.deriveHybridMessageKey(classicalMk,
				bobState.getCkPq()); // OLD ckPq

		assertArrayEquals(aliceMk.getBytes(), bobMk.getBytes());
	}

	@Test
	public void testHybridMessageKeyDependsOnClassicalAndPq() {
		SecretKey classical1 = randomKey();
		SecretKey classical2 = randomKey();
		SecretKey pq1 = randomKey();
		SecretKey pq2 = randomKey();

		SecretKey mk11 = ratchet.deriveHybridMessageKey(classical1, pq1);
		SecretKey mk12 = ratchet.deriveHybridMessageKey(classical1, pq2);
		SecretKey mk21 = ratchet.deriveHybridMessageKey(classical2, pq1);

		assertFalse(Arrays.equals(mk11.getBytes(), mk12.getBytes()));
		assertFalse(Arrays.equals(mk11.getBytes(), mk21.getBytes()));
	}

	@Test
	public void testHybridMessageKeyDeterministic() {
		SecretKey classical = randomKey();
		SecretKey pq = randomKey();

		SecretKey mk1 = ratchet.deriveHybridMessageKey(classical, pq);
		SecretKey mk2 = ratchet.deriveHybridMessageKey(classical, pq);

		assertArrayEquals(mk1.getBytes(), mk2.getBytes());
	}

	@Test(expected = PcsException.class)
	public void testDecapWithWrongCtLengthThrows() throws Exception {
		SecretKey root = randomKey();
		Mode3FullState state = ratchet.createInitialState(root);
		byte[] wrongCt = new byte[10];
		byte[] peerPk = new byte[MLKEM_ENCAPSULATION_KEY_SIZE];
		ratchet.pqDecapsulateRecv(state, null, wrongCt, peerPk);
	}

	@Test(expected = PcsException.class)
	public void testDecapWithWrongPkLengthThrows() throws Exception {
		SecretKey root = randomKey();
		Mode3FullState state = ratchet.createInitialState(root);
		byte[] ct = new byte[MLKEM_CIPHERTEXT_SIZE];
		byte[] wrongPk = new byte[10];
		ratchet.pqDecapsulateRecv(state, null, ct, wrongPk);
	}

	@Test
	public void testSenderAdvanceRotatesActiveKeyPair() {
		SecretKey root = randomKey();
		Mode3FullState state = ratchet.createInitialState(root);
		MlKemKeyPair peer = mlKemProvider.generateKeyPair();
		state = state.withRecvAdvance(state.getCkPq(),
				peer.getEncapsulationKey());
		MlKemKeyPair before = state.getOurActiveKeyPair();

		PqSendResult result = ratchet.pqEncapsulateSend(state);

		// Active KP rotated to new pair; old one moved into LRU.
		assertFalse(Arrays.equals(before.getEncapsulationKey(),
				result.getNewState().getOurActiveKeyPair()
						.getEncapsulationKey()));
		assertEquals(1, result.getNewState().getRecentKeyPairs().size());
	}

	@Test
	public void testLruEvictsBeyondConfiguredSize() {
		SecretKey root = randomKey();
		Mode3FullState state = ratchet.createInitialState(root);
		MlKemKeyPair peer = mlKemProvider.generateKeyPair();
		state = state.withRecvAdvance(state.getCkPq(),
				peer.getEncapsulationKey());

		// Advance LRU_SIZE+2 times.
		for (int i = 0; i < MODE3_FULL_RECV_SK_LRU_SIZE + 2; i++) {
			state = ratchet.pqEncapsulateSend(state).getNewState();
		}

		assertEquals(MODE3_FULL_RECV_SK_LRU_SIZE,
				state.getRecentKeyPairs().size());
	}
}
