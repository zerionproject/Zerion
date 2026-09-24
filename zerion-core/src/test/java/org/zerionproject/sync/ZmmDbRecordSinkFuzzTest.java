package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.message.ZmmConstants;
import org.jmock.Expectations;
import org.jmock.api.Invocation;
import org.jmock.lib.action.CustomAction;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Random;

import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getMessage;

/**
 * The record sink drops a malformed sync record and keeps the session:
 * random records, records of other types, sampled bit flips of a valid
 * message record and truncations never escape as an exception, while an
 * intact message record still reaches the database.
 */
public class ZmmDbRecordSinkFuzzTest extends BrambleMockTestCase {

	private static final int RANDOM_RECORDS = 3000;
	private static final int SAMPLED_FLIPS = 300;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final Transaction txn = new Transaction(null, false);
	private final Random random = new Random(41);
	private ZmmSyncCodec codec;
	private ZmmDbRecordSink sink;
	private Message message;

	@Before
	public void setUp() throws Exception {
		CryptoComponent crypto = (CryptoComponent) construct(
				"org.zerionproject.core.crypto.CryptoComponentImpl",
				new TestSecureRandomProvider(), null);
		MessageFactory messageFactory = (MessageFactory) construct(
				"org.zerionproject.core.sync.MessageFactoryImpl", crypto);
		RecordReaderFactory readerFactory = (RecordReaderFactory) construct(
				"org.zerionproject.core.record.RecordReaderFactoryImpl");
		RecordWriterFactory writerFactory = (RecordWriterFactory) construct(
				"org.zerionproject.core.record.RecordWriterFactoryImpl");
		SyncRecordReaderFactory syncReaders = (SyncRecordReaderFactory) construct(
				"org.zerionproject.core.sync.SyncRecordReaderFactoryImpl",
				messageFactory, readerFactory);
		SyncRecordWriterFactory syncWriters = (SyncRecordWriterFactory) construct(
				"org.zerionproject.core.sync.SyncRecordWriterFactoryImpl",
				messageFactory, writerFactory);
		codec = new ZmmSyncCodec(syncWriters, syncReaders);
		sink = new ZmmDbRecordSink(db, codec);
		message = getMessage(getGroup(getClientId(), 1).getId(), 300);
	}

	@Test
	public void intactMessageRecordReachesTheDatabase() throws Exception {
		byte[] record = codec.encodeMessage(message);
		context.checking(new Expectations() {{
			oneOf(db).transaction(with(false), with(any(DbRunnable.class)));
			will(runTask());
			oneOf(db).receiveMessage(with(txn), with(new ContactId(1)),
					with(any(Message.class)));
		}});
		sink.deliver(1, ZmmConstants.TYPE_SYNC, record);
	}

	@Test
	public void randomRecordsAreDroppedWithoutEscaping() throws Exception {
		allowAnyDelivery();
		int[] types = {ZmmConstants.TYPE_SYNC, ZmmConstants.TYPE_SYNC,
				ZmmConstants.TYPE_FRAGMENT, ZmmConstants.TYPE_TEXT, 0x7F};
		for (int i = 0; i < RANDOM_RECORDS; i++) {
			byte[] payload = new byte[random.nextInt(300)];
			random.nextBytes(payload);
			sink.deliver(random.nextInt(3), types[random.nextInt(types.length)],
					payload);
		}
	}

	@Test
	public void damagedMessageRecordsAreDroppedWithoutEscaping()
			throws Exception {
		allowAnyDelivery();
		byte[] record = codec.encodeMessage(message);
		for (int i = 0; i < SAMPLED_FLIPS; i++) {
			byte[] mutated = record.clone();
			mutated[random.nextInt(record.length)] ^= (byte) (1 << random.nextInt(8));
			sink.deliver(1, ZmmConstants.TYPE_SYNC, mutated);
		}
		for (int len = 0; len < record.length; len += 5) {
			byte[] truncated = new byte[len];
			System.arraycopy(record, 0, truncated, 0, len);
			sink.deliver(1, ZmmConstants.TYPE_SYNC, truncated);
		}
		sink.onDisconnected(1);
	}

	private void allowAnyDelivery() throws Exception {
		context.checking(new Expectations() {{
			allowing(db).transaction(with(any(boolean.class)),
					with(any(DbRunnable.class)));
			will(runTask());
			allowing(db).receiveMessage(with(any(Transaction.class)),
					with(any(ContactId.class)), with(any(Message.class)));
			allowing(db).receiveAck(with(any(Transaction.class)),
					with(any(ContactId.class)), with(any(Ack.class)));
			allowing(db).receiveOffer(with(any(Transaction.class)),
					with(any(ContactId.class)), with(any(Offer.class)));
			allowing(db).receiveRequest(with(any(Transaction.class)),
					with(any(ContactId.class)), with(any(Request.class)));
		}});
	}

	private CustomAction runTask() {
		return new CustomAction("run the transaction task") {
			@Override
			public Object invoke(Invocation invocation) throws Throwable {
				((DbRunnable<?>) invocation.getParameter(1)).run(txn);
				return null;
			}
		};
	}

	private static Object construct(String className, Object... args)
			throws Exception {
		Class<?> cls = Class.forName(className);
		for (Constructor<?> c : cls.getDeclaredConstructors()) {
			if (c.getParameterCount() != args.length) continue;
			c.setAccessible(true);
			return c.newInstance(args);
		}
		throw new AssertionError("no constructor with " + args.length
				+ " parameters on " + className);
	}
}
