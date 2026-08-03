package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.AdvanceResult;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.KdfCkResult;
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

public class PcsRatchetImplTest {

	private CryptoComponent crypto;
	private Clock clock;
	private PcsRatchetImpl ratchet;
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

		clock = new Clock() {
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

	private SecretKey getSecretKey() {
		byte[] keyBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(keyBytes);
		return new SecretKey(keyBytes);
	}

	@Test
	public void testDerivePcsRootKeyProducesDeterministicResult() {
		SecretKey contactRootKey = getSecretKey();

		SecretKey pcsRoot1 = ratchet.derivePcsRootKey(contactRootKey);
		SecretKey pcsRoot2 = ratchet.derivePcsRootKey(contactRootKey);

		assertArrayEquals(pcsRoot1.getBytes(), pcsRoot2.getBytes());
	}

	@Test
	public void testDerivePcsRootKeyDifferentInputsProduceDifferentOutputs() {
		SecretKey contactRootKey1 = getSecretKey();
		SecretKey contactRootKey2 = getSecretKey();

		SecretKey pcsRoot1 = ratchet.derivePcsRootKey(contactRootKey1);
		SecretKey pcsRoot2 = ratchet.derivePcsRootKey(contactRootKey2);

		assertFalse(Arrays.equals(pcsRoot1.getBytes(), pcsRoot2.getBytes()));
	}

	@Test
	public void testKdfCkProducesUniqueKeys() {
		SecretKey chainKey = getSecretKey();

		KdfCkResult result = ratchet.kdfCk(chainKey);

		assertNotNull(result.getNewChainKey());
		assertNotNull(result.getMessageKey());

		assertFalse(Arrays.equals(
				result.getNewChainKey().getBytes(),
				result.getMessageKey().getBytes()));

		assertFalse(Arrays.equals(
				chainKey.getBytes(),
				result.getNewChainKey().getBytes()));
	}

	@Test
	public void testKdfCkIsDeterministic() {
		SecretKey chainKey = getSecretKey();

		KdfCkResult result1 = ratchet.kdfCk(chainKey);
		KdfCkResult result2 = ratchet.kdfCk(chainKey);

		assertArrayEquals(
				result1.getNewChainKey().getBytes(),
				result2.getNewChainKey().getBytes());
		assertArrayEquals(
				result1.getMessageKey().getBytes(),
				result2.getMessageKey().getBytes());
	}

	@Test
	public void testAdvanceSendChainIncrementsMessageNumber() {
		SecretKey rootKey = getSecretKey();
		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		assertEquals(0, state.getMessageNumber());

		AdvanceResult result1 = ratchet.advanceSendChain(state);
		assertEquals(1, result1.getNewState().getMessageNumber());

		AdvanceResult result2 = ratchet.advanceSendChain(result1.getNewState());
		assertEquals(2, result2.getNewState().getMessageNumber());

		AdvanceResult result3 = ratchet.advanceSendChain(result2.getNewState());
		assertEquals(3, result3.getNewState().getMessageNumber());
	}

	@Test
	public void testAdvanceSendChainProducesUniqueMessageKeys() {
		SecretKey rootKey = getSecretKey();
		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		AdvanceResult result1 = ratchet.advanceSendChain(state);
		AdvanceResult result2 = ratchet.advanceSendChain(result1.getNewState());
		AdvanceResult result3 = ratchet.advanceSendChain(result2.getNewState());

		assertFalse(Arrays.equals(
				result1.getMessageKey().getBytes(),
				result2.getMessageKey().getBytes()));
		assertFalse(Arrays.equals(
				result2.getMessageKey().getBytes(),
				result3.getMessageKey().getBytes()));
		assertFalse(Arrays.equals(
				result1.getMessageKey().getBytes(),
				result3.getMessageKey().getBytes()));
	}

	@Test
	public void testAdvanceReceiveChainMatchesSendChain() throws PcsException {
		SecretKey rootKey = getSecretKey();

		PcsSessionState sendState = ratchet.initializeMode2AsInitiator(rootKey);
		AdvanceResult sendResult = ratchet.advanceSendChain(sendState);
		SecretKey sentMessageKey = sendResult.getMessageKey();
		int sentMessageNumber = sendState.getMessageNumber();

		PcsSessionState recvState = ratchet.initializeMode2AsInitiator(rootKey);
		AdvanceResult recvResult = ratchet.advanceReceiveChain(
				recvState, sentMessageNumber, skippedKeyStore);
		SecretKey receivedMessageKey = recvResult.getMessageKey();

		assertArrayEquals(sentMessageKey.getBytes(), receivedMessageKey.getBytes());
	}

	@Test
	public void testAdvanceReceiveChainSkipsAndStoresKeys() throws PcsException {
		SecretKey rootKey = getSecretKey();
		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		AdvanceResult result = ratchet.advanceReceiveChain(
				state, 5, skippedKeyStore);

		assertEquals(6, result.getNewState().getMessageNumber());
	}

	@Test
	public void testAdvanceReceiveChainRejectsMessageInPast() {
		SecretKey rootKey = getSecretKey();

		PcsSessionState state = new PcsSessionState(rootKey, 5, 0, null, null);

		try {
			ratchet.advanceReceiveChain(state, 3, skippedKeyStore);
			fail("Expected PcsException for message in past");
		} catch (PcsException e) {

			assertTrue(e.getMessage().contains("in the past"));
		}
	}

	@Test
	public void testAdvanceReceiveChainRejectsTooFarAhead() {
		SecretKey rootKey = getSecretKey();
		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		try {
			ratchet.advanceReceiveChain(state, MAX_SKIP + 1, skippedKeyStore);
			fail("Expected PcsException for message too far ahead");
		} catch (PcsException e) {

			assertTrue(e.getMessage().contains("too far ahead"));
		}
	}

	@Test
	public void testSendReceiveMultipleMessages() throws PcsException {
		SecretKey rootKey = getSecretKey();

		PcsSessionState aliceSend = ratchet.initializeMode2AsInitiator(rootKey);
		PcsSessionState bobRecv = ratchet.initializeMode2AsInitiator(rootKey);

		SecretKey[] aliceKeys = new SecretKey[3];
		for (int i = 0; i < 3; i++) {
			AdvanceResult result = ratchet.advanceSendChain(aliceSend);
			aliceKeys[i] = result.getMessageKey();
			aliceSend = result.getNewState();
		}

		for (int i = 0; i < 3; i++) {
			AdvanceResult result = ratchet.advanceReceiveChain(
					bobRecv, i, skippedKeyStore);
			assertArrayEquals(aliceKeys[i].getBytes(),
					result.getMessageKey().getBytes());
			bobRecv = result.getNewState();
		}
	}
}
