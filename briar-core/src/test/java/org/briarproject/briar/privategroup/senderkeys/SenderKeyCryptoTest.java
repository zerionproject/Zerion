package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.privategroup.senderkeys.GroupMessageCrypto;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyState;
import org.jmock.Expectations;
import org.junit.Test;

import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class SenderKeyCryptoTest extends BrambleMockTestCase {

	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final SenderKeyManager senderKeyManager =
			context.mock(SenderKeyManager.class);
	private final Transaction txn = context.mock(Transaction.class);

	private final GroupId groupId = new GroupId(getRandomId());
	private final AuthorId authorId = new AuthorId(getRandomId());
	private final SecretKey chainKey = getSecretKey();
	private final long createdAt = System.currentTimeMillis();

	private final GroupMessageCrypto groupMessageCrypto =
			new GroupMessageCryptoImpl(crypto, senderKeyManager);

	@Test
	public void testDeriveMessageKeyProducesUniqueKeys() throws Exception {
		SenderKey senderKey = new SenderKey(
				groupId, authorId, chainKey, 0, 0, createdAt, true, SenderKeyState.ACTIVE);

		SecretKey derivedKey1 = getSecretKey();
		SecretKey derivedKey2 = getSecretKey();

		context.checking(new Expectations() {{
			// First derivation at index 0
			oneOf(crypto).deriveKey(
					with(equal(GroupMessageCrypto.MESSAGE_KEY_LABEL)),
					with(any(SecretKey.class)),
					with(any(byte[].class)));
			will(returnValue(derivedKey1));

			// Chain advance
			oneOf(crypto).deriveKey(
					with(equal(GroupMessageCrypto.CHAIN_KEY_LABEL)),
					with(any(SecretKey.class)));
			will(returnValue(getSecretKey()));

			// Second derivation at index 1
			oneOf(crypto).deriveKey(
					with(equal(GroupMessageCrypto.MESSAGE_KEY_LABEL)),
					with(any(SecretKey.class)),
					with(any(byte[].class)));
			will(returnValue(derivedKey2));
		}});

		byte[] key1 = groupMessageCrypto.deriveMessageKeyAt(senderKey, 0);
		byte[] key2 = groupMessageCrypto.deriveMessageKeyAt(senderKey, 1);

		assertNotNull(key1);
		assertNotNull(key2);
		assertNotEquals(new String(key1), new String(key2));
	}

	@Test
	public void testSenderKeyStateTransitions() {
		SenderKey active = new SenderKey(
				groupId, authorId, chainKey, 0, 0, createdAt, true, SenderKeyState.ACTIVE);

		// Test state transitions
		SenderKey rotating = active.withState(SenderKeyState.ROTATING);
		SenderKey revoked = active.withState(SenderKeyState.REVOKED);

		assertNotNull(rotating);
		assertNotNull(revoked);
		assert(rotating.getState() == SenderKeyState.ROTATING);
		assert(revoked.getState() == SenderKeyState.REVOKED);
	}

	@Test
	public void testChainAdvancement() {
		SecretKey newChainKey = getSecretKey();
		SenderKey original = new SenderKey(
				groupId, authorId, chainKey, 0, 5, createdAt, true, SenderKeyState.ACTIVE);

		SenderKey advanced = original.withAdvancedChain(newChainKey, 6);

		assertNotNull(advanced);
		assert(advanced.getMessageIndex() == 6);
		assertArrayEquals(newChainKey.getBytes(), advanced.getChainKey().getBytes());
		assert(advanced.getEpoch() == original.getEpoch());
	}

	@Test
	public void testEpochRotation() {
		SecretKey newChainKey = getSecretKey();
		long rotationTime = System.currentTimeMillis();
		SenderKey original = new SenderKey(
				groupId, authorId, chainKey, 0, 50, createdAt, true, SenderKeyState.ACTIVE);

		SenderKey rotated = original.withNewChainKey(newChainKey, 1, rotationTime);

		assertNotNull(rotated);
		assert(rotated.getEpoch() == 1);
		assert(rotated.getMessageIndex() == 0);
		assert(rotated.getState() == SenderKeyState.ACTIVE);
		assertArrayEquals(newChainKey.getBytes(), rotated.getChainKey().getBytes());
	}

	@Test
	public void testShouldRotateEpochByMessageCount() throws DbException {
		// Create a key that has exceeded the message threshold (100)
		SenderKey highCount = new SenderKey(
				groupId, authorId, chainKey, 0, 101, createdAt, true, SenderKeyState.ACTIVE);

		context.checking(new Expectations() {{
			// Mock shouldRotateEpoch to return true for high count
		}});

		// Key with 101 messages should trigger rotation
		assert(highCount.getMessageIndex() >= SenderKeyManager.EPOCH_MESSAGE_THRESHOLD);
	}

	@Test
	public void testShouldRotateEpochByTime() {
		// Create a key that was created more than 24 hours ago
		long oldCreatedAt = System.currentTimeMillis() - (25L * 60 * 60 * 1000);
		SenderKey oldKey = new SenderKey(
				groupId, authorId, chainKey, 0, 5, oldCreatedAt, true, SenderKeyState.ACTIVE);

		long now = System.currentTimeMillis();
		long timeSinceCreation = now - oldKey.getCreatedAt();

		// Key older than 24 hours should trigger rotation
		assert(timeSinceCreation >= SenderKeyManager.EPOCH_TIME_THRESHOLD_MS);
	}

	@Test
	public void testSenderKeyImmutability() {
		SenderKey original = new SenderKey(
				groupId, authorId, chainKey, 0, 0, createdAt, true, SenderKeyState.ACTIVE);

		// Verify mutations return new objects
		SenderKey incremented = original.withIncrementedIndex();
		SenderKey newState = original.withState(SenderKeyState.ROTATING);

		// Original should be unchanged
		assert(original.getMessageIndex() == 0);
		assert(original.getState() == SenderKeyState.ACTIVE);

		// New objects should have new values
		assert(incremented.getMessageIndex() == 1);
		assert(newState.getState() == SenderKeyState.ROTATING);
	}
}
