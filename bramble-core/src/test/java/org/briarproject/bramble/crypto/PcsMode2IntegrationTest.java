package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.StreamDecrypter;
import org.briarproject.bramble.api.crypto.StreamEncrypter;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.crypto.pcs.InMemorySkippedKeyStore;
import org.briarproject.bramble.crypto.pcs.PcsRatchetImpl;
import org.briarproject.bramble.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.briarproject.bramble.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for PCS Mode 2 (Full Double Ratchet) stream encryption.
 * Tests the DH ratchet functionality with per-message ephemeral keys.
 */
public class PcsMode2IntegrationTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private SkippedKeyStore skippedKeyStore;
	private KeyParser keyParser;

	@Before
	public void setUp() {
		// Directly instantiate CryptoComponentImpl (package-accessible)
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

		// Alice initializes as initiator
		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		assertNotNull(aliceState);
		assertTrue(aliceState.isMode2());
		assertNotNull(aliceState.getDhState());
		assertNotNull(aliceState.getDhState().getDhPublicKey());

		// Bob initializes as responder with Alice's public key
		PublicKey alicePublicKey = aliceState.getDhState().getDhPublicKey();
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(rootKey, alicePublicKey);
		assertNotNull(bobState);
		assertTrue(bobState.isMode2());
		assertNotNull(bobState.getDhState());
	}

	@Test
	public void testDhRatchetProducesUniqueKeys() throws Exception {
		SecretKey rootKey = generateKey();

		// Initialize Alice and Bob
		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey alicePublicKey = aliceState.getDhState().getDhPublicKey();
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(rootKey, alicePublicKey);

		// Collect DH public keys across multiple ratchet steps
		PublicKey[] aliceKeys = new PublicKey[5];
		PcsSessionState currentAliceState = aliceState;

		// First, Alice needs Bob's public key to do DH ratchet
		PublicKey bobPublicKey = bobState.getDhState().getDhPublicKey();
		DhRatchetResult aliceResult = ratchet.performReceiveDhRatchet(
				currentAliceState, bobPublicKey);
		currentAliceState = aliceResult.getNewState();

		for (int i = 0; i < 5; i++) {
			DhRatchetResult result = ratchet.performSendDhRatchet(currentAliceState);
			aliceKeys[i] = result.getDhPublicKey();
			currentAliceState = result.getNewState();
		}

		// Verify all public keys are unique
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
		// Generate shared keys
		SecretKey rootKey = generateKey();
		SecretKey streamHeaderKey = generateKey();

		// Alice (sender) initializes Mode 2
		PcsSessionState aliceSendState = ratchet.initializeMode2AsInitiator(rootKey);

		// Create stream header nonce
		byte[] streamHeaderNonce = new byte[24];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		// Alice encrypts a message
		byte[] message = "Hello, Mode 2 DH Ratchet!".getBytes();
		ByteArrayOutputStream encryptedStream = new ByteArrayOutputStream();

		// Use separate cipher instances for encrypt and decrypt
		AuthenticatedCipher encryptCipher = new XSalsa20Poly1305AuthenticatedCipher();
		StreamEncrypter encrypter = new PcsStreamEncrypterImpl(
				encryptedStream, encryptCipher, ratchet, 1L, null,
				streamHeaderNonce, streamHeaderKey, aliceSendState, null);

		encrypter.writeFrame(message, message.length, 0, true);
		encrypter.flush();

		// Bob (receiver) initializes with Alice's public key
		PublicKey alicePublicKey = aliceSendState.getDhState().getDhPublicKey();
		PcsSessionState bobRecvState = ratchet.initializeMode2AsResponder(
				rootKey, alicePublicKey);

		// Bob decrypts the message
		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				encryptedStream.toByteArray());

		byte[] chainId = new byte[5];

		AuthenticatedCipher decryptCipher = new XSalsa20Poly1305AuthenticatedCipher();
		StreamDecrypter decrypter = new PcsStreamDecrypterImpl(
				inputStream, decryptCipher, ratchet, skippedKeyStore, chainId,
				1L, streamHeaderKey, bobRecvState, null, keyParser);

		byte[] decrypted = new byte[MAX_PAYLOAD_LENGTH];
		int length = decrypter.readFrame(decrypted);

		// Verify
		assertTrue(length > 0);
		byte[] result = Arrays.copyOf(decrypted, length);
		assertArrayEquals(message, result);
	}

	@Test
	public void testMode2MultipleMessagesAlternating() throws Exception {
		// This test simulates a conversation where Alice and Bob alternate
		// sending messages, triggering DH ratchet steps

		SecretKey rootKey = generateKey();

		// Initialize both parties
		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);

		// Simulate Alice receiving Bob's first key (DH ratchet step)
		PublicKey bobInitialKey = bobState.getDhState().getDhPublicKey();
		DhRatchetResult aliceResult = ratchet.performReceiveDhRatchet(
				aliceState, bobInitialKey);
		aliceState = aliceResult.getNewState();

		// Now simulate Alice sending
		DhRatchetResult aliceSend1 = ratchet.performSendDhRatchet(aliceState);
		aliceState = aliceSend1.getNewState();

		// Bob receives Alice's new key
		DhRatchetResult bobRecv1 = ratchet.performReceiveDhRatchet(
				bobState, aliceSend1.getDhPublicKey());
		bobState = bobRecv1.getNewState();

		// Bob sends reply
		DhRatchetResult bobSend1 = ratchet.performSendDhRatchet(bobState);
		bobState = bobSend1.getNewState();

		// Alice receives Bob's reply
		DhRatchetResult aliceRecv1 = ratchet.performReceiveDhRatchet(
				aliceState, bobSend1.getDhPublicKey());
		aliceState = aliceRecv1.getNewState();

		// Verify both parties have progressed through multiple DH ratchet steps
		assertNotNull(aliceState.getDhState());
		assertNotNull(bobState.getDhState());

		// Verify keys have changed from initial
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
		// This test verifies that after a simulated compromise,
		// the next DH ratchet step produces completely new keys

		SecretKey rootKey = generateKey();

		// Initialize Alice
		PcsSessionState aliceState = ratchet.initializeMode2AsInitiator(rootKey);
		PublicKey aliceInitialKey = aliceState.getDhState().getDhPublicKey();

		// Bob initializes
		PcsSessionState bobState = ratchet.initializeMode2AsResponder(
				rootKey, aliceInitialKey);
		PublicKey bobInitialKey = bobState.getDhState().getDhPublicKey();

		// Simulate compromise - attacker captures current state
		SecretKey compromisedRootKey = aliceState.getRootKey();
		SecretKey compromisedChainKey = aliceState.getChainKey();

		// Continue communication - Alice receives Bob's key
		DhRatchetResult aliceRatchet = ratchet.performReceiveDhRatchet(
				aliceState, bobInitialKey);
		aliceState = aliceRatchet.getNewState();

		// After DH ratchet, the new keys should be different from compromised keys
		SecretKey newRootKey = aliceState.getRootKey();
		SecretKey newChainKey = aliceState.getChainKey();

		// Verify keys have changed (post-compromise recovery)
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

		// Generate different DH outputs
		byte[] dhOutput1 = new byte[32];
		byte[] dhOutput2 = new byte[32];
		crypto.getSecureRandom().nextBytes(dhOutput1);
		crypto.getSecureRandom().nextBytes(dhOutput2);

		// Derive keys with same root but different DH outputs
		PcsRatchet.KdfRkResult result1 = ratchet.kdfRk(rootKey, dhOutput1);
		PcsRatchet.KdfRkResult result2 = ratchet.kdfRk(rootKey, dhOutput2);

		// Verify outputs are different
		assertFalse("Different DH outputs should produce different root keys",
				Arrays.equals(result1.getNewRootKey().getBytes(),
						result2.getNewRootKey().getBytes()));
		assertFalse("Different DH outputs should produce different chain keys",
				Arrays.equals(result1.getChainKey().getBytes(),
						result2.getChainKey().getBytes()));

		// Verify root key and chain key from same KDF_RK are different
		assertFalse("Root key and chain key should be different",
				Arrays.equals(result1.getNewRootKey().getBytes(),
						result1.getChainKey().getBytes()));
	}

	@Test
	public void testMode2SymmetricRatchetStillWorks() throws Exception {
		// Verify that symmetric ratchet (KDF_CK) still works correctly in Mode 2
		SecretKey rootKey = generateKey();

		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		// Advance send chain multiple times
		SecretKey[] messageKeys = new SecretKey[5];
		for (int i = 0; i < 5; i++) {
			PcsRatchet.AdvanceResult result = ratchet.advanceSendChain(state);
			messageKeys[i] = result.getMessageKey();
			state = result.getNewState();
		}

		// Verify all message keys are unique
		for (int i = 0; i < messageKeys.length; i++) {
			for (int j = i + 1; j < messageKeys.length; j++) {
				assertFalse("Message keys should be unique",
						Arrays.equals(messageKeys[i].getBytes(),
								messageKeys[j].getBytes()));
			}
		}

		// Verify message number progressed
		assertEquals(5, state.getMessageNumber());
	}
}
