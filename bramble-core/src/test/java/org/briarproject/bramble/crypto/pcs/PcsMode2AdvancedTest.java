package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.AdvanceResult;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MAX_SKIP;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Advanced tests for PCS Mode 2 covering edge cases:
 * - Mode 1 to Mode 2 upgrade path
 * - Out-of-order message handling in Mode 2
 * - Skipped key storage and retrieval
 * - Chain ID uniqueness with DH ratchet
 */
public class PcsMode2AdvancedTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private SkippedKeyStore skippedKeyStore;

	@Before
	public void setUp() throws Exception {
		// Use reflection to instantiate CryptoComponentImpl (package-private)
		Class<?> cryptoImplClass = Class.forName(
				"org.briarproject.bramble.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName("org.briarproject.bramble.api.system.SecureRandomProvider"),
				Class.forName("org.briarproject.bramble.crypto.PasswordBasedKdf"));
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

	// ==================== Mode 1 → Mode 2 Upgrade Tests ====================

	@Test
	public void testMode1ToMode2UpgradePreservesChainKey() throws Exception {
		// Start with Mode 1 session
		SecretKey rootKey = generateKey();
		PcsSessionState mode1State = PcsSessionState.createInitial(rootKey);

		// Advance Mode 1 a few times
		for (int i = 0; i < 5; i++) {
			AdvanceResult result = ratchet.advanceSendChain(mode1State);
			mode1State = result.getNewState();
		}

		// Upgrade to Mode 2
		PcsSessionState mode2State = ratchet.initializeMode2(mode1State);

		// Verify Mode 2 state
		assertTrue(mode2State.isMode2());
		assertNotNull(mode2State.getDhState());
		assertNotNull(mode2State.getRootKey());
		// Chain key should be preserved during upgrade
		assertEquals(mode1State.getChainKey(), mode2State.getChainKey());
	}

	@Test
	public void testMode1ToMode2UpgradePreservesMessageNumber() throws Exception {
		SecretKey rootKey = generateKey();
		PcsSessionState mode1State = PcsSessionState.createInitial(rootKey);

		// Advance to message number 10
		for (int i = 0; i < 10; i++) {
			AdvanceResult result = ratchet.advanceSendChain(mode1State);
			mode1State = result.getNewState();
		}
		assertEquals(10, mode1State.getMessageNumber());

		// Upgrade to Mode 2
		PcsSessionState mode2State = ratchet.initializeMode2(mode1State);

		// Message number should reset to 0 with new DH chain
		// This is correct behavior for Double Ratchet protocol
		assertEquals(0, mode2State.getMessageNumber());
	}

	// ==================== Out-of-Order Message Tests ====================

	@Test
	public void testMode2OutOfOrderMessageRecovery() throws Exception {
		SecretKey rootKey = generateKey();

		// Alice initializes as initiator
		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();

		// Bob initializes as responder
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);

		// Alice sends messages 0, 1, 2, 3, 4
		SecretKey[] aliceKeys = new SecretKey[5];
		PcsSessionState aliceSendState = aliceState;
		for (int i = 0; i < 5; i++) {
			AdvanceResult result = ratchet.advanceSendChain(aliceSendState);
			aliceKeys[i] = result.getMessageKey();
			aliceSendState = result.getNewState();
		}

		// Bob receives message 3 first (skipping 0, 1, 2)
		AdvanceResult recv3 = ratchet.advanceReceiveChain(
				bobState, 3, skippedKeyStore);
		assertArrayEquals(aliceKeys[3].getBytes(), recv3.getMessageKey().getBytes());
		bobState = recv3.getNewState();

		// Bob should be at message 4 now
		assertEquals(4, bobState.getMessageNumber());

		// Verify skipped keys were stored by using InMemorySkippedKeyStore's total count
		// The chain ID is computed internally by the ratchet using a hash of chain key
		InMemorySkippedKeyStore inMemStore = (InMemorySkippedKeyStore) skippedKeyStore;
		assertEquals("Should have 3 skipped keys stored (messages 0, 1, 2)",
				3, inMemStore.getTotalSkippedKeyCount());
	}

	@Test
	public void testMode2SkippedKeyExpiration() throws Exception {
		// Use a custom clock that we can control
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
		PcsSessionState state = PcsSessionState.createInitial(rootKey);

		// Advance past message 5, storing skipped keys
		AdvanceResult result = customRatchet.advanceReceiveChain(
				state, 5, skippedKeyStore);
		state = result.getNewState();

		// Verify skipped keys 0-4 were stored using total count
		InMemorySkippedKeyStore inMemStore = (InMemorySkippedKeyStore) skippedKeyStore;
		assertEquals("Should have 5 skipped keys stored", 5,
				inMemStore.getTotalSkippedKeyCount());

		// Advance time past the MAX_SKIP_AGE_MS and prune
		// MAX_SKIP_AGE_MS is 7 days = 7 * 24 * 60 * 60 * 1000L
		currentTime[0] += 7 * 24 * 60 * 60 * 1000L + 1; // 7 days + 1 ms
		int pruned = skippedKeyStore.pruneExpiredKeys(currentTime[0]);

		// All 5 keys should be expired and pruned
		assertEquals("All 5 keys should be pruned", 5, pruned);
		assertEquals("No skipped keys should remain", 0,
				inMemStore.getTotalSkippedKeyCount());
	}

	@Test
	public void testMode2MaxSkipEnforcement() throws Exception {
		SecretKey rootKey = generateKey();
		PcsSessionState state = PcsSessionState.createInitial(rootKey);

		// Try to skip more than MAX_SKIP
		try {
			ratchet.advanceReceiveChain(state, MAX_SKIP + 1, skippedKeyStore);
			fail("Should throw PcsException for exceeding MAX_SKIP");
		} catch (PcsException e) {
			assertTrue(e.getMessage().contains("too far ahead"));
		}

		// MAX_SKIP should be allowed
		AdvanceResult result = ratchet.advanceReceiveChain(
				state, MAX_SKIP, skippedKeyStore);
		assertNotNull(result);
		assertEquals(MAX_SKIP + 1, result.getNewState().getMessageNumber());
	}

	// ==================== Chain ID Uniqueness Tests ====================

	@Test
	public void testChainIdChangesWithDhRatchet() throws Exception {
		SecretKey rootKey = generateKey();

		// Initialize Alice and Bob
		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();

		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);
		PublicKey bobInitialKey = bobState.getDhState().getDhPublicKey();

		// Get initial chain ID (based on initial keys)
		byte[] chainId1 = createChainId(aliceInitialKey);

		// Perform DH ratchet on Alice's side
		DhRatchetResult aliceRecv = ratchet.performReceiveDhRatchet(
				aliceState, bobInitialKey);
		aliceState = aliceRecv.getNewState();

		DhRatchetResult aliceSend = ratchet.performSendDhRatchet(aliceState);
		aliceState = aliceSend.getNewState();
		PublicKey aliceNewKey = aliceSend.getDhPublicKey();

		// Chain ID should be different after DH ratchet
		byte[] chainId2 = createChainId(aliceNewKey);
		assertFalse("Chain IDs should differ after DH ratchet",
				Arrays.equals(chainId1, chainId2));
	}

	@Test
	public void testBidirectionalDhRatchetSynchronization() throws Exception {
		SecretKey rootKey = generateKey();

		// Initialize both parties
		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceKey1 = aliceState.getDhState().getDhPublicKey();

		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceKey1);
		PublicKey bobKey1 = bobState.getDhState().getDhPublicKey();

		// Round 1: Alice receives Bob's key, performs DH ratchet
		DhRatchetResult aliceRecv1 = ratchet.performReceiveDhRatchet(
				aliceState, bobKey1);
		aliceState = aliceRecv1.getNewState();

		// Alice sends (generates new key)
		DhRatchetResult aliceSend1 = ratchet.performSendDhRatchet(aliceState);
		aliceState = aliceSend1.getNewState();
		PublicKey aliceKey2 = aliceSend1.getDhPublicKey();

		// Round 2: Bob receives Alice's new key
		DhRatchetResult bobRecv1 = ratchet.performReceiveDhRatchet(
				bobState, aliceKey2);
		bobState = bobRecv1.getNewState();

		// Bob sends (generates new key)
		DhRatchetResult bobSend1 = ratchet.performSendDhRatchet(bobState);
		bobState = bobSend1.getNewState();
		PublicKey bobKey2 = bobSend1.getDhPublicKey();

		// Round 3: Alice receives Bob's new key
		DhRatchetResult aliceRecv2 = ratchet.performReceiveDhRatchet(
				aliceState, bobKey2);
		aliceState = aliceRecv2.getNewState();

		// Verify both parties have evolved their keys
		assertFalse("Alice's key should have changed",
				Arrays.equals(aliceKey1.getEncoded(), aliceKey2.getEncoded()));
		assertFalse("Bob's key should have changed",
				Arrays.equals(bobKey1.getEncoded(), bobKey2.getEncoded()));

		// Both parties should still be able to derive matching message keys
		AdvanceResult aliceMsg = ratchet.advanceSendChain(aliceState);
		SecretKey aliceMsgKey = aliceMsg.getMessageKey();
		aliceState = aliceMsg.getNewState();

		// Bob advances to receive Alice's message
		AdvanceResult bobMsg = ratchet.advanceReceiveChain(
				bobState, 0, skippedKeyStore);
		SecretKey bobMsgKey = bobMsg.getMessageKey();

		// Keys should match
		assertArrayEquals("Message keys should match after DH ratchet sync",
				aliceMsgKey.getBytes(), bobMsgKey.getBytes());
	}

	// ==================== Helper Methods ====================

	/**
	 * Creates a test chain ID based on DH public key.
	 * Used for testing that chain IDs differ when keys change.
	 * Note: This is a simplified version; real chain IDs also include chain key.
	 */
	private byte[] createChainId(PublicKey dhKey) {
		if (dhKey == null) {
			return new byte[32]; // Empty chain ID for Mode 1 testing
		}
		return crypto.hash("test/chain_id", dhKey.getEncoded());
	}
}
