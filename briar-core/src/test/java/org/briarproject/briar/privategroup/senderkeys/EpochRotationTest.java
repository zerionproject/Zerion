package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.privategroup.senderkeys.EpochRotationManager;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCryptoState;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyState;
import org.briarproject.bramble.api.identity.AuthorId;
import org.jmock.Expectations;
import org.junit.Test;

import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EpochRotationTest extends BrambleMockTestCase {

	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final Clock clock = context.mock(Clock.class);
	private final SenderKeyManager senderKeyManager =
			context.mock(SenderKeyManager.class);
	private final Transaction txn = context.mock(Transaction.class);

	private final GroupId groupId = new GroupId(getRandomId());
	private final AuthorId authorId = new AuthorId(getRandomId());
	private final SecretKey chainKey = getSecretKey();
	private final long createdAt = System.currentTimeMillis();

	private final EpochRotationManager epochRotationManager =
			new EpochRotationManagerImpl(crypto, clock, senderKeyManager);

	@Test
	public void testCheckRotationNeededReturnsFalseWhenNoKey() throws DbException {
		context.checking(new Expectations() {{
			oneOf(senderKeyManager).getLocalSenderKey(txn, groupId);
			will(returnValue(null));
		}});

		boolean needed = epochRotationManager.checkRotationNeeded(txn, groupId, createdAt);
		assertFalse(needed);
	}

	@Test
	public void testCheckRotationNeededWhenMessageThresholdExceeded() throws DbException {
		SenderKey highCountKey = new SenderKey(
				groupId, authorId, chainKey, 0, 101, createdAt, true, SenderKeyState.ACTIVE);

		context.checking(new Expectations() {{
			oneOf(senderKeyManager).getLocalSenderKey(txn, groupId);
			will(returnValue(highCountKey));
			oneOf(senderKeyManager).shouldRotateEpoch(highCountKey, createdAt);
			will(returnValue(true));
		}});

		boolean needed = epochRotationManager.checkRotationNeeded(txn, groupId, createdAt);
		assertTrue(needed);
	}

	@Test
	public void testDeriveEpochChainKeyWithPqMaterial() {
		byte[] currentChainKey = chainKey.getBytes();
		byte[] pqSharedSecret = getRandomId();
		int newEpoch = 1;

		SecretKey derivedKey = getSecretKey();
		context.checking(new Expectations() {{
			oneOf(crypto).deriveKey(
					with(equal(EpochRotationManager.SENDER_KEY_EPOCH_LABEL)),
					with(any(SecretKey.class)),
					with(any(byte[].class)));
			will(returnValue(derivedKey));
		}});

		byte[] result = epochRotationManager.deriveEpochChainKey(
				currentChainKey, pqSharedSecret, groupId, newEpoch);

		assertNotNull(result);
		assertArrayEquals(derivedKey.getBytes(), result);
	}

	@Test
	public void testDeriveEpochChainKeyWithoutPqMaterial() {
		byte[] currentChainKey = chainKey.getBytes();
		int newEpoch = 1;

		SecretKey derivedKey = getSecretKey();
		context.checking(new Expectations() {{
			oneOf(crypto).deriveKey(
					with(equal(EpochRotationManager.SENDER_KEY_EPOCH_LABEL)),
					with(any(SecretKey.class)),
					with(any(byte[].class)));
			will(returnValue(derivedKey));
		}});

		byte[] result = epochRotationManager.deriveEpochChainKey(
				currentChainKey, null, groupId, newEpoch);

		assertNotNull(result);
		assertArrayEquals(derivedKey.getBytes(), result);
	}

	@Test
	public void testHandleIncomingEpochRotationUpdatesKey() throws DbException {
		SenderKey existingKey = new SenderKey(
				groupId, authorId, chainKey, 0, 50, createdAt, false, SenderKeyState.ACTIVE);
		SenderKey newKey = new SenderKey(
				groupId, authorId, getSecretKey(), 1, 0, System.currentTimeMillis(),
				false, SenderKeyState.ACTIVE);

		context.checking(new Expectations() {{
			oneOf(senderKeyManager).getSenderKey(txn, groupId, newKey.getAuthorId());
			will(returnValue(existingKey));
			oneOf(senderKeyManager).updateSenderKey(txn, with(any(SenderKey.class)));
			oneOf(senderKeyManager).storeSenderKey(txn, newKey);
		}});

		epochRotationManager.handleIncomingEpochRotation(txn, groupId, newKey);
	}

	@Test
	public void testHandleIncomingEpochRotationIgnoresOldEpoch() throws DbException {
		SenderKey existingKey = new SenderKey(
				groupId, authorId, chainKey, 2, 50, createdAt, false, SenderKeyState.ACTIVE);
		SenderKey oldKey = new SenderKey(
				groupId, authorId, getSecretKey(), 1, 0, System.currentTimeMillis(),
				false, SenderKeyState.ACTIVE);

		context.checking(new Expectations() {{
			oneOf(senderKeyManager).getSenderKey(txn, groupId, oldKey.getAuthorId());
			will(returnValue(existingKey));
			// Should not update or store since incoming epoch is older
		}});

		epochRotationManager.handleIncomingEpochRotation(txn, groupId, oldKey);
	}
}
