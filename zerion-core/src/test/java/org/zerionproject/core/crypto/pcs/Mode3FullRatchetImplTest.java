package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet.PqRecvResult;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet.PqSendResult;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_RECV_SK_LRU_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_SEND_ROTATION_INTERVAL;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Mode3FullRatchetImplTest {

	private CryptoComponent crypto;
	private MlKemProvider mlKemProvider;
	private Mode3FullRatchetImpl ratchet;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
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
	public void testInitialStateHasFreshKeyPairAndNoPeerPk() {
		Mode3FullState s = ratchet.createInitialState();
		assertNull(s.getTheirActivePqPk());
		assertNotNull(s.getOurActiveKeyPair());
		assertEquals(0, s.getRecentKeyPairs().size());
		assertEquals(0L, s.getMessageCounter());
	}

	@Test
	public void testFirstSendEmitsZeroSentinelAndNoSharedSecret() {
		Mode3FullState state = ratchet.createInitialState();

		PqSendResult result = ratchet.pqEncapsulateSend(state);

		assertEquals(MLKEM_CIPHERTEXT_SIZE, result.getCiphertext().length);
		for (byte b : result.getCiphertext()) assertEquals(0, b);
		assertEquals(MLKEM_ENCAPSULATION_KEY_SIZE,
				result.getPkAdvertise().length);
		assertNull(result.getSharedSecret());
		assertNull(result.getKpIdUsed());
	}

	@Test
	public void testReceiverHandlesZeroSentinelWithoutDecap() throws Exception {
		Mode3FullState rState = ratchet.createInitialState();

		byte[] zeroCt = new byte[MLKEM_CIPHERTEXT_SIZE];
		MlKemKeyPair tmp = mlKemProvider.generateKeyPair();
		byte[] peerPk = tmp.getEncapsulationKey();

		PqRecvResult result = ratchet.pqDecapsulateRecv(rState, null,
				zeroCt, peerPk);

		assertNull(result.getSharedSecret());
		assertArrayEquals(peerPk,
				result.getNewState().getTheirActivePqPk());
	}

	@Test
	public void testEncapDecapProducesMatchingSharedSecret() throws Exception {
		MlKemKeyPair bobKp = mlKemProvider.generateKeyPair();

		Mode3FullState aliceState = ratchet.createInitialState()
				.withRecvAdvance(bobKp.getEncapsulationKey());

		PqSendResult aliceSend = ratchet.pqEncapsulateSend(aliceState);

		Mode3FullState bobState = new Mode3FullState(
				null, bobKp, new java.util.LinkedHashMap<>(), 0);

		PqRecvResult bobRecv = ratchet.pqDecapsulateRecv(bobState,
				aliceSend.getKpIdUsed(),
				aliceSend.getCiphertext(), aliceSend.getPkAdvertise());

		assertNotNull(aliceSend.getSharedSecret());
		assertNotNull(bobRecv.getSharedSecret());
		assertArrayEquals(aliceSend.getSharedSecret(),
				bobRecv.getSharedSecret());
	}

	@Test
	public void testHybridMessageKeyDependsOnClassicalAndPq() {
		SecretKey classical1 = randomKey();
		SecretKey classical2 = randomKey();
		byte[] pq1 = randomKey().getBytes();
		byte[] pq2 = randomKey().getBytes();

		SecretKey mk11 = ratchet.deriveHybridMessageKey(classical1, pq1);
		SecretKey mk12 = ratchet.deriveHybridMessageKey(classical1, pq2);
		SecretKey mk21 = ratchet.deriveHybridMessageKey(classical2, pq1);

		assertFalse(Arrays.equals(mk11.getBytes(), mk12.getBytes()));
		assertFalse(Arrays.equals(mk11.getBytes(), mk21.getBytes()));
	}

	@Test
	public void testHybridMessageKeyDeterministic() {
		SecretKey classical = randomKey();
		byte[] pq = randomKey().getBytes();

		SecretKey mk1 = ratchet.deriveHybridMessageKey(classical, pq);
		SecretKey mk2 = ratchet.deriveHybridMessageKey(classical, pq);

		assertArrayEquals(mk1.getBytes(), mk2.getBytes());
	}

	@Test(expected = PcsException.class)
	public void testDecapWithWrongCtLengthThrows() throws Exception {
		Mode3FullState state = ratchet.createInitialState();
		byte[] wrongCt = new byte[10];
		byte[] peerPk = new byte[MLKEM_ENCAPSULATION_KEY_SIZE];
		ratchet.pqDecapsulateRecv(state, null, wrongCt, peerPk);
	}

	@Test(expected = PcsException.class)
	public void testDecapWithWrongPkLengthThrows() throws Exception {
		Mode3FullState state = ratchet.createInitialState();
		byte[] ct = new byte[MLKEM_CIPHERTEXT_SIZE];
		byte[] wrongPk = new byte[10];
		ratchet.pqDecapsulateRecv(state, null, ct, wrongPk);
	}

	@Test
	public void testSenderRotatesActiveKeyPairPeriodically() {
		Mode3FullState state = ratchet.createInitialState();
		MlKemKeyPair peer = mlKemProvider.generateKeyPair();
		state = state.withRecvAdvance(peer.getEncapsulationKey());
		MlKemKeyPair before = state.getOurActiveKeyPair();

		Mode3FullState afterOne = ratchet.pqEncapsulateSend(state).getNewState();
		assertTrue(Arrays.equals(before.getEncapsulationKey(),
				afterOne.getOurActiveKeyPair().getEncapsulationKey()));

		Mode3FullState s = state;
		for (int i = 0; i < MODE3_FULL_SEND_ROTATION_INTERVAL; i++) {
			s = ratchet.pqEncapsulateSend(s).getNewState();
		}
		assertFalse(Arrays.equals(before.getEncapsulationKey(),
				s.getOurActiveKeyPair().getEncapsulationKey()));
	}

	@Test
	public void testLruEvictsBeyondConfiguredSize() {
		Mode3FullState state = ratchet.createInitialState();
		MlKemKeyPair peer = mlKemProvider.generateKeyPair();
		state = state.withRecvAdvance(peer.getEncapsulationKey());

		int sends = (MODE3_FULL_RECV_SK_LRU_SIZE + 2)
				* MODE3_FULL_SEND_ROTATION_INTERVAL;
		for (int i = 0; i < sends; i++) {
			state = ratchet.pqEncapsulateSend(state).getNewState();
		}

		assertEquals(MODE3_FULL_RECV_SK_LRU_SIZE,
				state.getRecentKeyPairs().size());
	}
}
