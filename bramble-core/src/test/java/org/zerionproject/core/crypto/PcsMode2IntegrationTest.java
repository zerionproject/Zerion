package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.pcs.InMemorySkippedKeyStore;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PcsMode2IntegrationTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private SkippedKeyStore skippedKeyStore;
	private KeyParser keyParser;

	@Before
	public void setUp() {

		crypto = new CryptoComponentImpl(new TestSecureRandomProvider(), null);

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
		keyParser = crypto.getAgreementKeyParser();
	}

	private SecretKey generateKey() {
		byte[] keyBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(keyBytes);
		return new SecretKey(keyBytes);
	}

	@Test
	public void testMode2Initialization() throws Exception {
		SecretKey rootKey = generateKey();

		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		assertNotNull(aliceState);
		assertTrue(aliceState.isMode2());
		assertNotNull(aliceState.getDhState());
		assertNotNull(aliceState.getDhState().getDhPublicKey());

		PublicKey alicePublicKey = aliceState.getDhState().getDhPublicKey();
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(rootKey, alicePublicKey);
		assertNotNull(bobState);
		assertTrue(bobState.isMode2());
		assertNotNull(bobState.getDhState());
	}

	@Test
	public void testDhRatchetProducesUniqueKeys() throws Exception {
		SecretKey rootKey = generateKey();

		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey alicePublicKey = aliceState.getDhState().getDhPublicKey();
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(rootKey, alicePublicKey);

		PublicKey[] aliceKeys = new PublicKey[5];
		PcsSessionState currentAliceState = aliceState;

		PublicKey bobPublicKey = bobState.getDhState().getDhPublicKey();
		DhRatchetResult aliceResult = ratchet.performReceiveDhRatchet(
				currentAliceState, bobPublicKey);
		currentAliceState = aliceResult.getNewState();

		for (int i = 0; i < 5; i++) {
			DhRatchetResult result = ratchet.performSendDhRatchet(currentAliceState);
			aliceKeys[i] = result.getDhPublicKey();
			currentAliceState = result.getNewState();
		}

		for (int i = 0; i < aliceKeys.length; i++) {
			for (int j = i + 1; j < aliceKeys.length; j++) {
				byte[] key1 = aliceKeys[i].getEncoded();
				byte[] key2 = aliceKeys[j].getEncoded();
				assertFalse("DH keys " + i + " and " + j + " should be unique",
						Arrays.equals(key1, key2));
			}
		}
	}

	@Test
	public void testMode2SingleMessageRoundTrip() throws Exception {

		SecretKey rootKey = generateKey();
		SecretKey streamHeaderKey = generateKey();

		PcsSessionState aliceSendState = ratchet.initializeMode2AsInitiator(rootKey);

		byte[] streamHeaderNonce = new byte[24];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		byte[] message = "Hello, Mode 2 DH Ratchet!".getBytes();
		ByteArrayOutputStream encryptedStream = new ByteArrayOutputStream();

		AuthenticatedCipher encryptCipher = new XSalsa20Poly1305AuthenticatedCipher();
		StreamEncrypter encrypter = new PcsStreamEncrypterImpl(
				encryptedStream, encryptCipher, ratchet, 1L, null,
				streamHeaderNonce, streamHeaderKey, aliceSendState, null);

		encrypter.writeFrame(message, message.length, 0, true);
		encrypter.flush();

		PublicKey alicePublicKey = aliceSendState.getDhState().getDhPublicKey();
		PcsSessionState bobRecvState = ratchet.initializeMode2AsResponder(
				rootKey, alicePublicKey);

		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				encryptedStream.toByteArray());

		byte[] chainId = new byte[5];

		AuthenticatedCipher decryptCipher = new XSalsa20Poly1305AuthenticatedCipher();
		StreamDecrypter decrypter = new PcsStreamDecrypterImpl(
				inputStream, decryptCipher, ratchet, skippedKeyStore, chainId,
				1L, streamHeaderKey, bobRecvState, null, keyParser);

		byte[] decrypted = new byte[MAX_PAYLOAD_LENGTH];
		int length = decrypter.readFrame(decrypted);

		assertTrue(length > 0);
		byte[] result = Arrays.copyOf(decrypted, length);
		assertArrayEquals(message, result);
	}

	@Test
	public void testMode2MultipleMessagesAlternating() throws Exception {

		SecretKey rootKey = generateKey();

		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);

		PublicKey bobInitialKey = bobState.getDhState().getDhPublicKey();
		DhRatchetResult aliceResult = ratchet.performReceiveDhRatchet(
				aliceState, bobInitialKey);
		aliceState = aliceResult.getNewState();

		DhRatchetResult aliceSend1 = ratchet.performSendDhRatchet(aliceState);
		aliceState = aliceSend1.getNewState();

		DhRatchetResult bobRecv1 = ratchet.performReceiveDhRatchet(
				bobState, aliceSend1.getDhPublicKey());
		bobState = bobRecv1.getNewState();

		DhRatchetResult bobSend1 = ratchet.performSendDhRatchet(bobState);
		bobState = bobSend1.getNewState();

		DhRatchetResult aliceRecv1 = ratchet.performReceiveDhRatchet(
				aliceState, bobSend1.getDhPublicKey());
		aliceState = aliceRecv1.getNewState();

		assertNotNull(aliceState.getDhState());
		assertNotNull(bobState.getDhState());

		assertFalse(Arrays.equals(
				aliceInitialKey.getEncoded(),
				aliceState.getDhState().getDhPublicKey().getEncoded()
		));
		assertFalse(Arrays.equals(
				bobInitialKey.getEncoded(),
				bobState.getDhState().getDhPublicKey().getEncoded()
		));
	}

	@Test
	public void testMode2PostCompromiseRecovery() throws Exception {

		SecretKey rootKey = generateKey();

		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();

		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);
		PublicKey bobInitialKey = bobState.getDhState().getDhPublicKey();

		SecretKey compromisedRootKey = aliceState.getRootKey();
		SecretKey compromisedChainKey = aliceState.getChainKey();

		DhRatchetResult aliceRatchet = ratchet.performReceiveDhRatchet(
				aliceState, bobInitialKey);
		aliceState = aliceRatchet.getNewState();

		SecretKey newRootKey = aliceState.getRootKey();
		SecretKey newChainKey = aliceState.getChainKey();

		assertNotNull(newRootKey);
		assertNotNull(newChainKey);
		assertFalse("Root key should change after DH ratchet",
				Arrays.equals(compromisedRootKey.getBytes(), newRootKey.getBytes()));
		assertFalse("Chain key should change after DH ratchet",
				Arrays.equals(compromisedChainKey.getBytes(), newChainKey.getBytes()));
	}

	@Test
	public void testKdfRkProducesUniqueOutputs() throws Exception {
		SecretKey rootKey = generateKey();

		byte[] dhOutput1 = new byte[32];
		byte[] dhOutput2 = new byte[32];
		crypto.getSecureRandom().nextBytes(dhOutput1);
		crypto.getSecureRandom().nextBytes(dhOutput2);

		PcsRatchet.KdfRkResult result1 = ratchet.kdfRk(rootKey, dhOutput1);
		PcsRatchet.KdfRkResult result2 = ratchet.kdfRk(rootKey, dhOutput2);

		assertFalse("Different DH outputs should produce different root keys",
				Arrays.equals(result1.getNewRootKey().getBytes(),
						result2.getNewRootKey().getBytes()));
		assertFalse("Different DH outputs should produce different chain keys",
				Arrays.equals(result1.getChainKey().getBytes(),
						result2.getChainKey().getBytes()));

		assertFalse("Root key and chain key should be different",
				Arrays.equals(result1.getNewRootKey().getBytes(),
						result1.getChainKey().getBytes()));
	}

	@Test
	public void testMode2SymmetricRatchetStillWorks() throws Exception {

		SecretKey rootKey = generateKey();

		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		SecretKey[] messageKeys = new SecretKey[5];
		for (int i = 0; i < 5; i++) {
			PcsRatchet.AdvanceResult result = ratchet.advanceSendChain(state);
			messageKeys[i] = result.getMessageKey();
			state = result.getNewState();
		}

		for (int i = 0; i < messageKeys.length; i++) {
			for (int j = i + 1; j < messageKeys.length; j++) {
				assertFalse("Message keys should be unique",
						Arrays.equals(messageKeys[i].getBytes(),
								messageKeys[j].getBytes()));
			}
		}

		assertEquals(5, state.getMessageNumber());
	}
}
