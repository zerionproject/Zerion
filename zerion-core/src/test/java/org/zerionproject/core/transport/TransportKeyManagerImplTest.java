package org.zerionproject.core.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.transport.IncomingKeys;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.OutgoingKeys;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.TransportKeySet;
import org.zerionproject.core.api.transport.TransportKeys;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.RunAction;
import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.zerionproject.core.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.transport.TransportConstants.REORDERING_WINDOW_SIZE;
import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.zerionproject.core.util.ByteUtils.MAX_32_BIT_UNSIGNED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TransportKeyManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final TransportCrypto transportCrypto =
			context.mock(TransportCrypto.class);
	private final Executor dbExecutor = context.mock(Executor.class);
	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final Clock clock = context.mock(Clock.class);

	private final TransportId transportId = getTransportId();
	private final long maxLatency = 30 * 1000;
	private final long timePeriodLength = maxLatency + MAX_CLOCK_DIFFERENCE;
	private final ContactId contactId = getContactId();
	private final ContactId contactId1 = getContactId();
	private final PendingContactId pendingContactId =
			new PendingContactId(getRandomId());
	private final KeySetId keySetId = new KeySetId(345);
	private final KeySetId keySetId1 = new KeySetId(456);
	private final SecretKey tagKey = getSecretKey();
	private final SecretKey headerKey = getSecretKey();
	private final SecretKey rootKey = getSecretKey();
	private final Random random = new Random();

	private final TransportKeyManager transportKeyManager =
			new TransportKeyManagerImpl(db, transportCrypto, dbExecutor,
					scheduler, clock, transportId, maxLatency);

	@Test
	public void testKeysAreUpdatedAtStartup() throws Exception {
		boolean active = random.nextBoolean();
		TransportKeys shouldUpdate = createTransportKeys(900, 0, active);
		TransportKeys shouldNotUpdate = createTransportKeys(1000, 0, active);
		Collection<TransportKeySet> loaded = asList(
				new TransportKeySet(keySetId, contactId, null, shouldUpdate),
				new TransportKeySet(keySetId1, contactId1, null,
						shouldNotUpdate)
		);
		TransportKeys updated = createTransportKeys(1000, 0, active);
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000 + 1));

			oneOf(db).getTransportKeys(txn, transportId);
			will(returnValue(loaded));

			oneOf(transportCrypto).updateTransportKeys(shouldUpdate, 1000);
			will(returnValue(updated));
			oneOf(transportCrypto).updateTransportKeys(shouldNotUpdate, 1000);
			will(returnValue(shouldNotUpdate));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(6).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction());
			}

			oneOf(db).updateTransportKeys(txn, singletonList(
					new TransportKeySet(keySetId, contactId, null, updated)));

			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(dbExecutor), with(timePeriodLength - 1),
					with(MILLISECONDS));
		}});

		transportKeyManager.start(txn);
		assertEquals(active,
				transportKeyManager.canSendOutgoingStreams(contactId));
	}

	@Test
	public void testRotationKeysForContactAreDerivedAndUpdatedWhenAdded()
			throws Exception {
		boolean alice = random.nextBoolean();
		boolean active = random.nextBoolean();
		TransportKeys transportKeys = createTransportKeys(999, 0, active);
		TransportKeys updated = createTransportKeys(1000, 0, active);
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{
			oneOf(transportCrypto).deriveRotationKeys(transportId, rootKey,
					999, alice, active);
			will(returnValue(transportKeys));

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000 + 1));

			oneOf(transportCrypto).updateTransportKeys(transportKeys, 1000);
			will(returnValue(updated));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction());
			}

			oneOf(db).addTransportKeys(txn, contactId, updated);
			will(returnValue(keySetId));
		}});

		TransportKeyManager transportKeyManager = new TransportKeyManagerImpl(
				db, transportCrypto, dbExecutor, scheduler, clock, transportId,
				maxLatency);

		long timestamp = timePeriodLength * 1000 - 1;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(txn,
				contactId, rootKey, timestamp, alice, active));
		assertEquals(active,
				transportKeyManager.canSendOutgoingStreams(contactId));
	}

	@Test
	public void testHandshakeKeysForContactAreDerivedWhenAdded()
			throws Exception {
		boolean alice = random.nextBoolean();
		TransportKeys transportKeys = createHandshakeKeys(1000, 0, alice);
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000 + 1));

			oneOf(transportCrypto).deriveHandshakeKeys(transportId, rootKey,
					1000, alice);
			will(returnValue(transportKeys));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction());
			}

			oneOf(db).addTransportKeys(txn, contactId, transportKeys);
			will(returnValue(keySetId));
		}});

		assertEquals(keySetId, transportKeyManager.addHandshakeKeys(txn,
				contactId, rootKey, alice));
		assertTrue(transportKeyManager.canSendOutgoingStreams(contactId));
	}

	@Test
	public void testHandshakeKeysForPendingContactAreDerivedWhenAdded()
			throws Exception {
		boolean alice = random.nextBoolean();
		TransportKeys transportKeys = createHandshakeKeys(1000, 0, alice);
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000 + 1));

			oneOf(transportCrypto).deriveHandshakeKeys(transportId, rootKey,
					1000, alice);
			will(returnValue(transportKeys));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction());
			}

			oneOf(db).addTransportKeys(txn, pendingContactId, transportKeys);
			will(returnValue(keySetId));
		}});

		assertEquals(keySetId, transportKeyManager.addHandshakeKeys(txn,
				pendingContactId, rootKey, alice));
		assertTrue(transportKeyManager.canSendOutgoingStreams(
				pendingContactId));
	}

	@Test
	public void testOutgoingStreamContextIsNullIfContactIsNotFound()
			throws Exception {
		Transaction txn = new Transaction(null, false);

		TransportKeyManager transportKeyManager = new TransportKeyManagerImpl(
				db, transportCrypto, dbExecutor, scheduler, clock, transportId,
				maxLatency);
		assertNull(transportKeyManager.getStreamContext(txn, contactId, false));
		assertFalse(transportKeyManager.canSendOutgoingStreams(contactId));
	}

	@Test
	public void testOutgoingStreamContextIsNullIfPendingContactIsNotFound()
			throws Exception {
		Transaction txn = new Transaction(null, false);

		TransportKeyManager transportKeyManager = new TransportKeyManagerImpl(
				db, transportCrypto, dbExecutor, scheduler, clock, transportId,
				maxLatency);
		assertNull(transportKeyManager.getStreamContext(txn, pendingContactId, false));
		assertFalse(transportKeyManager.canSendOutgoingStreams(
				pendingContactId));
	}

	@Test
	public void testOutgoingStreamContextIsNullIfStreamCounterIsExhausted()
			throws Exception {
		boolean alice = random.nextBoolean();

		TransportKeys transportKeys = createTransportKeys(1000,
				MAX_32_BIT_UNSIGNED + 1, true);
		Transaction txn = new Transaction(null, false);

		expectAddContactKeysNotUpdated(alice, true, transportKeys, txn);

		long timestamp = timePeriodLength * 1000;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(
				txn, contactId, rootKey, timestamp, alice, true));
		assertFalse(transportKeyManager.canSendOutgoingStreams(contactId));
		assertNull(transportKeyManager.getStreamContext(txn, contactId, false));
	}

	@Test
	public void testOutgoingStreamCounterIsIncremented() throws Exception {
		boolean alice = random.nextBoolean();

		TransportKeys transportKeys = createTransportKeys(1000,
				MAX_32_BIT_UNSIGNED, true);
		Transaction txn = new Transaction(null, false);

		expectAddContactKeysNotUpdated(alice, true, transportKeys, txn);

		context.checking(new Expectations() {{

			oneOf(db).incrementStreamCounter(txn, transportId, keySetId);
		}});

		long timestamp = timePeriodLength * 1000;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(
				txn, contactId, rootKey, timestamp, alice, true));

		assertTrue(transportKeyManager.canSendOutgoingStreams(contactId));
		StreamContext ctx = transportKeyManager.getStreamContext(txn,
				contactId, false);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertEquals(tagKey, ctx.getTagKey());
		assertEquals(headerKey, ctx.getHeaderKey());
		assertEquals(MAX_32_BIT_UNSIGNED, ctx.getStreamNumber());

		assertFalse(transportKeyManager.canSendOutgoingStreams(contactId));
		assertNull(transportKeyManager.getStreamContext(txn, contactId, false));
	}

	@Test
	public void testIncomingStreamContextIsNullIfTagIsNotFound()
			throws Exception {
		boolean alice = random.nextBoolean();
		boolean active = random.nextBoolean();
		TransportKeys transportKeys = createTransportKeys(1000, 0, active);
		Transaction txn = new Transaction(null, false);

		expectAddContactKeysNotUpdated(alice, active, transportKeys, txn);

		long timestamp = timePeriodLength * 1000;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(
				txn, contactId, rootKey, timestamp, alice, active));
		assertEquals(active,
				transportKeyManager.canSendOutgoingStreams(contactId));

		assertNull(transportKeyManager.getStreamContext(txn,
				new byte[TAG_LENGTH], false));
	}

	@Test
	public void testTagIsNotRecognisedTwice() throws Exception {
		boolean alice = random.nextBoolean();
		TransportKeys transportKeys = createTransportKeys(1000, 0, true);
		Transaction txn = new Transaction(null, false);

		List<byte[]> tags = new ArrayList<>();

		context.checking(new Expectations() {{
			oneOf(transportCrypto).deriveRotationKeys(transportId, rootKey,
					1000, alice, true);
			will(returnValue(transportKeys));

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction(tags));
			}

			oneOf(transportCrypto).updateTransportKeys(transportKeys, 1000);
			will(returnValue(transportKeys));

			oneOf(db).addTransportKeys(txn, contactId, transportKeys);
			will(returnValue(keySetId));

			oneOf(transportCrypto).encodeTag(with(any(byte[].class)),
					with(tagKey), with(PROTOCOL_VERSION),
					with((long) REORDERING_WINDOW_SIZE));
			will(new EncodeTagAction(tags));

			oneOf(db).setReorderingWindow(txn, keySetId, transportId, 999,
					1, new byte[REORDERING_WINDOW_SIZE / 8]);
		}});

		long timestamp = timePeriodLength * 1000;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(
				txn, contactId, rootKey, timestamp, alice, true));
		assertTrue(transportKeyManager.canSendOutgoingStreams(contactId));

		assertEquals(REORDERING_WINDOW_SIZE * 3, tags.size());
		byte[] tag = tags.get(0);

		StreamContext ctx = transportKeyManager.getStreamContext(txn, tag, false);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertEquals(tagKey, ctx.getTagKey());
		assertEquals(headerKey, ctx.getHeaderKey());
		assertEquals(0L, ctx.getStreamNumber());

		assertEquals(REORDERING_WINDOW_SIZE * 3 + 1, tags.size());

		assertNull(transportKeyManager.getStreamContext(txn, tag, false));
	}

	@Test
	public void testGetStreamContextOnlyAndMarkTag() throws Exception {
		boolean alice = random.nextBoolean();
		TransportKeys transportKeys = createTransportKeys(1000, 0, true);
		Transaction txn = new Transaction(null, false);

		List<byte[]> tags = new ArrayList<>();

		context.checking(new Expectations() {{
			oneOf(transportCrypto).deriveRotationKeys(transportId, rootKey,
					1000, alice, true);
			will(returnValue(transportKeys));

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction(tags));
			}

			oneOf(transportCrypto).updateTransportKeys(transportKeys, 1000);
			will(returnValue(transportKeys));

			oneOf(db).addTransportKeys(txn, contactId, transportKeys);
			will(returnValue(keySetId));

			oneOf(transportCrypto).encodeTag(with(any(byte[].class)),
					with(tagKey), with(PROTOCOL_VERSION),
					with((long) REORDERING_WINDOW_SIZE));
			will(new EncodeTagAction(tags));

			oneOf(db).setReorderingWindow(txn, keySetId, transportId, 999,
					1, new byte[REORDERING_WINDOW_SIZE / 8]);
		}});

		long timestamp = timePeriodLength * 1000;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(
				txn, contactId, rootKey, timestamp, alice, true));
		assertTrue(transportKeyManager.canSendOutgoingStreams(contactId));

		assertEquals(REORDERING_WINDOW_SIZE * 3, tags.size());
		byte[] tag = tags.get(0);

		StreamContext ctx = transportKeyManager.getStreamContextOnly(txn, tag, false);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertEquals(tagKey, ctx.getTagKey());
		assertEquals(headerKey, ctx.getHeaderKey());
		assertEquals(0L, ctx.getStreamNumber());
		ctx = transportKeyManager.getStreamContextOnly(txn, tag, false);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertEquals(tagKey, ctx.getTagKey());
		assertEquals(headerKey, ctx.getHeaderKey());
		assertEquals(0L, ctx.getStreamNumber());

		transportKeyManager.markTagAsRecognised(txn, tag);

		assertEquals(REORDERING_WINDOW_SIZE * 3 + 1, tags.size());

		assertNull(transportKeyManager.getStreamContextOnly(txn, tag, false));
	}

	@Test
	public void testKeysAreUpdatedToCurrentPeriod() throws Exception {
		TransportKeys transportKeys = createTransportKeys(1000, 0, true);
		Collection<TransportKeySet> loaded = singletonList(
				new TransportKeySet(keySetId, contactId, null, transportKeys));
		TransportKeys updated = createTransportKeys(1001, 0, true);
		Transaction txn = new Transaction(null, false);
		Transaction txn1 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000));

			oneOf(db).getTransportKeys(txn, transportId);
			will(returnValue(loaded));

			oneOf(transportCrypto).updateTransportKeys(transportKeys, 1000);
			will(returnValue(transportKeys));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction());
			}

			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(dbExecutor), with(timePeriodLength),
					with(MILLISECONDS));
			will(new RunAction());

			oneOf(db).transaction(with(false), withDbRunnable(txn1));

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1001));

			oneOf(transportCrypto).updateTransportKeys(
					with(any(TransportKeys.class)), with(1001L));
			will(returnValue(updated));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction());
			}

			oneOf(db).updateTransportKeys(txn1, singletonList(
					new TransportKeySet(keySetId, contactId, null, updated)));

			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(dbExecutor), with(timePeriodLength),
					with(MILLISECONDS));
		}});

		transportKeyManager.start(txn);
		assertTrue(transportKeyManager.canSendOutgoingStreams(contactId));
	}

	@Test
	public void testActivatingKeys() throws Exception {
		boolean alice = random.nextBoolean();
		TransportKeys transportKeys = createTransportKeys(1000, 0, false);
		Transaction txn = new Transaction(null, false);

		expectAddContactKeysNotUpdated(alice, false, transportKeys, txn);

		context.checking(new Expectations() {{

			oneOf(db).setTransportKeysActive(txn, transportId, keySetId);

			oneOf(db).incrementStreamCounter(txn, transportId, keySetId);
		}});

		long timestamp = timePeriodLength * 1000;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(
				txn, contactId, rootKey, timestamp, alice, false));

		assertFalse(transportKeyManager.canSendOutgoingStreams(contactId));
		assertNull(transportKeyManager.getStreamContext(txn, contactId, false));
		transportKeyManager.activateKeys(txn, keySetId);

		assertTrue(transportKeyManager.canSendOutgoingStreams(contactId));
		StreamContext ctx = transportKeyManager.getStreamContext(txn,
				contactId, false);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertEquals(tagKey, ctx.getTagKey());
		assertEquals(headerKey, ctx.getHeaderKey());
		assertEquals(0L, ctx.getStreamNumber());
	}

	@Test
	public void testRecognisingTagActivatesOutgoingKeys() throws Exception {
		boolean alice = random.nextBoolean();
		TransportKeys transportKeys = createTransportKeys(1000, 0, false);
		Transaction txn = new Transaction(null, false);

		List<byte[]> tags = new ArrayList<>();

		context.checking(new Expectations() {{
			oneOf(transportCrypto).deriveRotationKeys(transportId, rootKey,
					1000, alice, false);
			will(returnValue(transportKeys));

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction(tags));
			}

			oneOf(transportCrypto).updateTransportKeys(transportKeys, 1000);
			will(returnValue(transportKeys));

			oneOf(db).addTransportKeys(txn, contactId, transportKeys);
			will(returnValue(keySetId));

			oneOf(transportCrypto).encodeTag(with(any(byte[].class)),
					with(tagKey), with(PROTOCOL_VERSION),
					with((long) REORDERING_WINDOW_SIZE));
			will(new EncodeTagAction(tags));

			oneOf(db).setReorderingWindow(txn, keySetId, transportId, 999,
					1, new byte[REORDERING_WINDOW_SIZE / 8]);

			oneOf(db).setTransportKeysActive(txn, transportId, keySetId);

			oneOf(db).incrementStreamCounter(txn, transportId, keySetId);
		}});

		long timestamp = timePeriodLength * 1000;
		assertEquals(keySetId, transportKeyManager.addRotationKeys(
				txn, contactId, rootKey, timestamp, alice, false));

		assertFalse(transportKeyManager.canSendOutgoingStreams(contactId));
		assertNull(transportKeyManager.getStreamContext(txn, contactId, false));

		assertEquals(REORDERING_WINDOW_SIZE * 3, tags.size());
		byte[] tag = tags.get(0);
		StreamContext ctx = transportKeyManager.getStreamContext(txn, tag, false);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertEquals(tagKey, ctx.getTagKey());
		assertEquals(headerKey, ctx.getHeaderKey());
		assertEquals(0L, ctx.getStreamNumber());

		assertTrue(transportKeyManager.canSendOutgoingStreams(contactId));
		ctx = transportKeyManager.getStreamContext(txn, contactId, false);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertEquals(tagKey, ctx.getTagKey());
		assertEquals(headerKey, ctx.getHeaderKey());
		assertEquals(0L, ctx.getStreamNumber());
	}

	private void expectAddContactKeysNotUpdated(boolean alice, boolean active,
			TransportKeys transportKeys, Transaction txn) throws Exception {
		context.checking(new Expectations() {{
			oneOf(transportCrypto).deriveRotationKeys(transportId, rootKey,
					1000, alice, active);
			will(returnValue(transportKeys));

			oneOf(clock).currentTimeMillis();
			will(returnValue(timePeriodLength * 1000));

			for (long i = 0; i < REORDERING_WINDOW_SIZE; i++) {
				exactly(3).of(transportCrypto).encodeTag(
						with(any(byte[].class)), with(tagKey),
						with(PROTOCOL_VERSION), with(i));
				will(new EncodeTagAction());
			}

			oneOf(transportCrypto).updateTransportKeys(transportKeys, 1000);
			will(returnValue(transportKeys));

			oneOf(db).addTransportKeys(txn, contactId, transportKeys);
			will(returnValue(keySetId));
		}});
	}

	private TransportKeys createTransportKeys(long timePeriod,
			long streamCounter, boolean active) {
		IncomingKeys inPrev = new IncomingKeys(tagKey, headerKey,
				timePeriod - 1);
		IncomingKeys inCurr = new IncomingKeys(tagKey, headerKey,
				timePeriod);
		IncomingKeys inNext = new IncomingKeys(tagKey, headerKey,
				timePeriod + 1);
		OutgoingKeys outCurr = new OutgoingKeys(tagKey, headerKey,
				timePeriod, streamCounter, active);
		return new TransportKeys(transportId, inPrev, inCurr, inNext, outCurr);
	}

	@SuppressWarnings("SameParameterValue")
	private TransportKeys createHandshakeKeys(long timePeriod,
			long streamCounter, boolean alice) {
		IncomingKeys inPrev = new IncomingKeys(tagKey, headerKey,
				timePeriod - 1);
		IncomingKeys inCurr = new IncomingKeys(tagKey, headerKey,
				timePeriod);
		IncomingKeys inNext = new IncomingKeys(tagKey, headerKey,
				timePeriod + 1);
		OutgoingKeys outCurr = new OutgoingKeys(tagKey, headerKey,
				timePeriod, streamCounter, true);
		return new TransportKeys(transportId, inPrev, inCurr, inNext, outCurr,
				rootKey, alice);
	}

	private class EncodeTagAction implements Action {

		private final Collection<byte[]> tags;

		private EncodeTagAction() {
			tags = null;
		}

		private EncodeTagAction(Collection<byte[]> tags) {
			this.tags = tags;
		}

		@Override
		public Object invoke(Invocation invocation) {
			byte[] tag = (byte[]) invocation.getParameter(0);
			random.nextBytes(tag);
			if (tags != null) tags.add(tag);
			return null;
		}

		@Override
		public void describeTo(Description description) {
			description.appendText("encodes a tag");
		}
	}
}
