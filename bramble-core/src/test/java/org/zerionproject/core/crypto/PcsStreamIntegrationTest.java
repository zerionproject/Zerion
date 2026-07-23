package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.AdvanceResult;
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
import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PcsStreamIntegrationTest {

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

	private AuthenticatedCipher createCipher() {
		return new XSalsa20Poly1305AuthenticatedCipher();
	}

	private SecretKey generateKey() {
		byte[] keyBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(keyBytes);
		return new SecretKey(keyBytes);
	}

	@Test
	public void testSingleMessageRoundTrip() throws Exception {

		SecretKey rootKey = generateKey();
		SecretKey streamHeaderKey = generateKey();

		PcsSessionState senderState = ratchet.initializeMode2AsInitiator(rootKey);
		PcsSessionState receiverState = ratchet.initializeMode2AsInitiator(rootKey);

		byte[] streamHeaderNonce = new byte[24];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		byte[] message = "Hello, PCS World!".getBytes();
		ByteArrayOutputStream encryptedStream = new ByteArrayOutputStream();

		StreamEncrypter encrypter = new PcsStreamEncrypterImpl(
				encryptedStream, createCipher(), ratchet, 1L, null,
				streamHeaderNonce, streamHeaderKey, senderState, null);

		encrypter.writeFrame(message, message.length, 0, true);
		encrypter.flush();

		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				encryptedStream.toByteArray());

		byte[] chainId = new byte[5];

		StreamDecrypter decrypter = new PcsStreamDecrypterImpl(
				inputStream, createCipher(), ratchet, skippedKeyStore, chainId,
				1L, streamHeaderKey, null, null);

		byte[] decrypted = new byte[MAX_PAYLOAD_LENGTH];
		int length = decrypter.readFrame(decrypted);

		assertTrue(length > 0);
		byte[] result = Arrays.copyOf(decrypted, length);
		assertArrayEquals(message, result);
	}

	@Test
	public void testMultipleMessagesRoundTrip() throws Exception {

		SecretKey rootKey = generateKey();
		SecretKey streamHeaderKey = generateKey();

		PcsSessionState senderState = ratchet.initializeMode2AsInitiator(rootKey);

		byte[] streamHeaderNonce = new byte[24];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		String[] messages = {"Message 1", "Message 2", "Message 3"};
		ByteArrayOutputStream encryptedStream = new ByteArrayOutputStream();

		StreamEncrypter encrypter = new PcsStreamEncrypterImpl(
				encryptedStream, createCipher(), ratchet, 1L, null,
				streamHeaderNonce, streamHeaderKey, senderState, null);

		for (int i = 0; i < messages.length; i++) {
			byte[] msg = messages[i].getBytes();
			boolean finalFrame = (i == messages.length - 1);
			encrypter.writeFrame(msg, msg.length, 0, finalFrame);
		}
		encrypter.flush();

		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				encryptedStream.toByteArray());

		byte[] chainId = new byte[5];

		StreamDecrypter decrypter = new PcsStreamDecrypterImpl(
				inputStream, createCipher(), ratchet, skippedKeyStore, chainId,
				1L, streamHeaderKey, null, null);

		byte[] buffer = new byte[MAX_PAYLOAD_LENGTH];
		for (String expectedMsg : messages) {
			int length = decrypter.readFrame(buffer);
			assertTrue(length > 0);
			String actual = new String(Arrays.copyOf(buffer, length));
			assertEquals(expectedMsg, actual);
		}

		assertEquals(-1, decrypter.readFrame(buffer));
	}

	@Test
	public void testRatchetProducesUniqueKeysPerMessage() throws Exception {
		SecretKey rootKey = generateKey();
		PcsSessionState state = ratchet.initializeMode2AsInitiator(rootKey);

		SecretKey[] keys = new SecretKey[5];
		for (int i = 0; i < 5; i++) {
			AdvanceResult result = ratchet.advanceSendChain(state);
			keys[i] = result.getMessageKey();
			state = result.getNewState();
		}

		for (int i = 0; i < keys.length; i++) {
			for (int j = i + 1; j < keys.length; j++) {
				if (Arrays.equals(keys[i].getBytes(), keys[j].getBytes())) {
					throw new AssertionError("Keys " + i + " and " + j +
							" are identical - PCS ratchet is not working!");
				}
			}
		}
	}

	@Test
	public void testSenderReceiverRatchetSync() throws Exception {

		SecretKey rootKey = generateKey();

		PcsSessionState senderState = ratchet.initializeMode2AsInitiator(rootKey);
		PcsSessionState receiverState = ratchet.initializeMode2AsInitiator(rootKey);

		AdvanceResult sendResult = ratchet.advanceSendChain(senderState);
		SecretKey senderKey = sendResult.getMessageKey();

		AdvanceResult recvResult = ratchet.advanceReceiveChain(
				receiverState, 0, skippedKeyStore);
		SecretKey receiverKey = recvResult.getMessageKey();

		assertArrayEquals(senderKey.getBytes(), receiverKey.getBytes());
	}

	@Test
	public void testLargePayload() throws Exception {

		SecretKey rootKey = generateKey();
		SecretKey streamHeaderKey = generateKey();
		PcsSessionState senderState = ratchet.initializeMode2AsInitiator(rootKey);

		byte[] streamHeaderNonce = new byte[24];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		int maxPayload = MAX_PAYLOAD_LENGTH - PCS_HEADER_MAX_SIZE;
		byte[] largeMessage = new byte[maxPayload];
		crypto.getSecureRandom().nextBytes(largeMessage);

		ByteArrayOutputStream encryptedStream = new ByteArrayOutputStream();

		StreamEncrypter encrypter = new PcsStreamEncrypterImpl(
				encryptedStream, createCipher(), ratchet, 1L, null,
				streamHeaderNonce, streamHeaderKey, senderState, null);

		encrypter.writeFrame(largeMessage, largeMessage.length, 0, true);
		encrypter.flush();

		ByteArrayInputStream inputStream = new ByteArrayInputStream(
				encryptedStream.toByteArray());
		byte[] chainId = new byte[5];

		StreamDecrypter decrypter = new PcsStreamDecrypterImpl(
				inputStream, createCipher(), ratchet, skippedKeyStore, chainId,
				1L, streamHeaderKey, null, null);

		byte[] buffer = new byte[MAX_PAYLOAD_LENGTH];
		int length = decrypter.readFrame(buffer);

		assertEquals(maxPayload, length);
		assertArrayEquals(largeMessage, Arrays.copyOf(buffer, length));
	}
}
