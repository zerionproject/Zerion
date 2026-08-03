package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.AdvanceResult;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MAX_SKIP;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PcsMode2AdvancedTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private SkippedKeyStore skippedKeyStore;

	@Before
	public void setUp() throws Exception {

		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName("org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		constructor.setAccessible(true);
		crypto = (CryptoComponent) constructor.newInstance(
				new TestSecureRandomProvider(), null);

		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return System.currentTimeMillis();
			}

			@Override
			public void sleep(long milliseconds) throws InterruptedException {
				Thread.sleep(milliseconds);
			}
		};
		ratchet = new PcsRatchetImpl(crypto, clock);
		skippedKeyStore = new InMemorySkippedKeyStore();
	}

	private SecretKey generateKey() {
		byte[] keyBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(keyBytes);
		return new SecretKey(keyBytes);
	}

	@Test
	public void testMode2OutOfOrderMessageRecovery() throws Exception {
		SecretKey rootKey = generateKey();

		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();

		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);

		SecretKey[] aliceKeys = new SecretKey[5];
		PcsSessionState aliceSendState = aliceState;
		for (int i = 0; i < 5; i++) {
			AdvanceResult result = ratchet.advanceSendChain(aliceSendState);
			aliceKeys[i] = result.getMessageKey();
			aliceSendState = result.getNewState();
		}

		AdvanceResult recv3 = ratchet.advanceReceiveChain(
				bobState, 3, skippedKeyStore);
		assertArrayEquals(aliceKeys[3].getBytes(), recv3.getMessageKey().getBytes());
		bobState = recv3.getNewState();

		assertEquals(4, bobState.getMessageNumber());

		InMemorySkippedKeyStore inMemStore = (InMemorySkippedKeyStore) skippedKeyStore;
		assertEquals("Should have 3 skipped keys stored (messages 0, 1, 2)",
				3, inMemStore.getTotalSkippedKeyCount());
	}

	@Test
	public void testMode2SkippedKeyExpiration() throws Exception {

		final long[] currentTime = {1000000L};
		Clock controllableClock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return currentTime[0];
			}

			@Override
			public void sleep(long milliseconds) throws InterruptedException {
				Thread.sleep(milliseconds);
			}
		};
		PcsRatchetImpl customRatchet = new PcsRatchetImpl(crypto, controllableClock);

		SecretKey rootKey = generateKey();
		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		AdvanceResult result = customRatchet.advanceReceiveChain(
				state, 5, skippedKeyStore);
		state = result.getNewState();

		InMemorySkippedKeyStore inMemStore = (InMemorySkippedKeyStore) skippedKeyStore;
		assertEquals("Should have 5 skipped keys stored", 5,
				inMemStore.getTotalSkippedKeyCount());

		currentTime[0] += 7 * 24 * 60 * 60 * 1000L + 1;
		int pruned = skippedKeyStore.pruneExpiredKeys(currentTime[0]);

		assertEquals("All 5 keys should be pruned", 5, pruned);
		assertEquals("No skipped keys should remain", 0,
				inMemStore.getTotalSkippedKeyCount());
	}

	@Test
	public void testMode2MaxSkipEnforcement() throws Exception {
		SecretKey rootKey = generateKey();
		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		try {
			ratchet.advanceReceiveChain(state, MAX_SKIP + 1, skippedKeyStore);
			fail("Should throw PcsException for exceeding MAX_SKIP");
		} catch (PcsException e) {
			assertTrue(e.getMessage().contains("too far ahead"));
		}

		AdvanceResult result = ratchet.advanceReceiveChain(
				state, MAX_SKIP, skippedKeyStore);
		assertNotNull(result);
		assertEquals(MAX_SKIP + 1, result.getNewState().getMessageNumber());
	}

	@Test
	public void testChainIdChangesWithDhRatchet() throws Exception {
		SecretKey rootKey = generateKey();

		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();

		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);
		PublicKey bobInitialKey = bobState.getDhState().getDhPublicKey();

		byte[] chainId1 = createChainId(aliceInitialKey);

		DhRatchetResult aliceRecv = ratchet.performReceiveDhRatchet(
				aliceState, bobInitialKey);
		aliceState = aliceRecv.getNewState();

		DhRatchetResult aliceSend = ratchet.performSendDhRatchet(aliceState);
		aliceState = aliceSend.getNewState();
		PublicKey aliceNewKey = aliceSend.getDhPublicKey();

		byte[] chainId2 = createChainId(aliceNewKey);
		assertFalse("Chain IDs should differ after DH ratchet",
				Arrays.equals(chainId1, chainId2));
	}

	@Test
	public void testBidirectionalDhRatchetSynchronization() throws Exception {
		SecretKey rootKey = generateKey();

		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceKey1 = aliceState.getDhState().getDhPublicKey();

		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceKey1);
		PublicKey bobKey1 = bobState.getDhState().getDhPublicKey();

		DhRatchetResult aliceRecv1 = ratchet.performReceiveDhRatchet(
				aliceState, bobKey1);
		aliceState = aliceRecv1.getNewState();

		DhRatchetResult aliceSend1 = ratchet.performSendDhRatchet(aliceState);
		aliceState = aliceSend1.getNewState();
		PublicKey aliceKey2 = aliceSend1.getDhPublicKey();

		DhRatchetResult bobRecv1 = ratchet.performReceiveDhRatchet(
				bobState, aliceKey2);
		bobState = bobRecv1.getNewState();

		DhRatchetResult bobSend1 = ratchet.performSendDhRatchet(bobState);
		bobState = bobSend1.getNewState();
		PublicKey bobKey2 = bobSend1.getDhPublicKey();

		DhRatchetResult aliceRecv2 = ratchet.performReceiveDhRatchet(
				aliceState, bobKey2);
		aliceState = aliceRecv2.getNewState();

		assertFalse("Alice's key should have changed",
				Arrays.equals(aliceKey1.getEncoded(), aliceKey2.getEncoded()));
		assertFalse("Bob's key should have changed",
				Arrays.equals(bobKey1.getEncoded(), bobKey2.getEncoded()));

		assertTrue("Alice state should still be Mode 2", aliceState.isMode2());
		assertTrue("Bob state should still be Mode 2", bobState.isMode2());

		assertNotNull("Alice should have chain key", aliceState.getChainKey());
		assertNotNull("Bob should have chain key", bobState.getChainKey());

		assertNotNull("Alice should have DH state", aliceState.getDhState());
		assertNotNull("Bob should have DH state", bobState.getDhState());

		AdvanceResult aliceMsg = ratchet.advanceSendChain(aliceState);
		assertNotNull("Alice should derive message key", aliceMsg.getMessageKey());

		AdvanceResult bobMsg = ratchet.advanceSendChain(bobState);
		assertNotNull("Bob should derive message key", bobMsg.getMessageKey());
	}

	private byte[] createChainId(PublicKey dhKey) {
		if (dhKey == null) {
			return new byte[32];
		}
		return crypto.hash("test/chain_id", dhKey.getEncoded());
	}
}
