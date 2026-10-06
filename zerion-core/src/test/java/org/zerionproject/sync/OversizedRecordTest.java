package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.message.ZmmReassembler;
import org.zerionproject.message.ZmmRecord;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class OversizedRecordTest {

	private static final long NOW = 1_700_000_000_000L;
	private static final int FRAME_RECORD_BYTES = 1_600;

	private List<byte[]> queue(Message m) throws Exception {
		List<byte[]> queued = Collections.synchronizedList(new ArrayList<>());
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true) {
			@Override
			public void enqueueRecord(byte[] record, boolean userOriginated,
					boolean bulkLane, Runnable onSent) {
				queued.add(record);
			}
		};
		AtomicBoolean served = new AtomicBoolean(false);
		DatabaseComponent db = (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if (name.equals("transactionWithNullableResult")
							|| name.equals("transactionWithResult")) {
						java.lang.reflect.Method call = null;
						for (java.lang.reflect.Method mm :
								args[1].getClass().getMethods()) {
							if (mm.getName().equals("call")) call = mm;
						}
						if (call == null) throw new AssertionError();
						call.setAccessible(true);
						return call.invoke(args[1],
								new Transaction(null, false));
					}
					if (name.equals("generateRequestedBatch")) {
						if (served.getAndSet(true)) return null;
						return Collections.singletonList(m);
					}
					if (name.equals("getNextSendTime")) return Long.MAX_VALUE;
					return null;
				});
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return NOW;
			}

			@Override
			public void sleep(long milliseconds) {
			}
		};
		EventBus bus = (EventBus) Proxy.newProxyInstance(
				EventBus.class.getClassLoader(), new Class<?>[] {EventBus.class},
				(proxy, method, args) -> null);
		TaskScheduler tasks = (TaskScheduler) Proxy.newProxyInstance(
				TaskScheduler.class.getClassLoader(),
				new Class<?>[] {TaskScheduler.class},
				(proxy, method, args) -> null);
		ContactId c = new ContactId(1);
		ZppOutgoingSource source = new ZppOutgoingSource(db, Runnable::run,
				bus, tasks, clock, codec(), c, 60_000, FRAME_RECORD_BYTES,
				scheduler, new LocalMessageLog(() -> 0L));
		source.eventOccurred(new MessageRequestedEvent(c));
		return queued;
	}

	@Test
	public void aRecordLargerThanThePeerReassemblesIsNeverQueued()
			throws Exception {
		Message huge = new Message(new MessageId(getRandomId()),
				new GroupId(getRandomId()), NOW,
				new byte[ZmmReassembler.MAX_MESSAGE_BYTES]);
		assertEquals(0, queue(huge).size());
	}

	@Test
	public void aRecordWithinTheBoundIsFragmentedAndReassembled()
			throws Exception {
		Message large = new Message(new MessageId(getRandomId()),
				new GroupId(getRandomId()), NOW, new byte[600_000]);
		List<byte[]> queued = queue(large);
		assertTrue(queued.size() > 1);
		ZmmReassembler reassembler = new ZmmReassembler();
		ZmmReassembler.Message joined = null;
		for (byte[] r : queued) {
			ZmmReassembler.Message m = reassembler.receive(1, 1,
					ZmmRecord.getType(r), ZmmRecord.getPayload(r));
			if (m != null) joined = m;
		}
		assertNotNull(joined);
	}

	static ZmmSyncCodec codec() throws Exception {
		CryptoComponent crypto = (CryptoComponent) construct(
				"org.zerionproject.core.crypto.CryptoComponentImpl",
				new TestSecureRandomProvider(), null);
		MessageFactory messageFactory = (MessageFactory) construct(
				"org.zerionproject.core.sync.MessageFactoryImpl", crypto);
		RecordReaderFactory readers = (RecordReaderFactory) construct(
				"org.zerionproject.core.record.RecordReaderFactoryImpl");
		RecordWriterFactory writers = (RecordWriterFactory) construct(
				"org.zerionproject.core.record.RecordWriterFactoryImpl");
		SyncRecordReaderFactory syncReaders = (SyncRecordReaderFactory)
				construct("org.zerionproject.core.sync.SyncRecordReaderFactoryImpl",
						messageFactory, readers);
		SyncRecordWriterFactory syncWriters = (SyncRecordWriterFactory)
				construct("org.zerionproject.core.sync.SyncRecordWriterFactoryImpl",
						messageFactory, writers);
		return new ZmmSyncCodec(syncWriters, syncReaders);
	}

	private static Object construct(String className, Object... args)
			throws Exception {
		Class<?> c = Class.forName(className);
		for (Constructor<?> ctor : c.getDeclaredConstructors()) {
			if (ctor.getParameterCount() != args.length) continue;
			ctor.setAccessible(true);
			try {
				return ctor.newInstance(args);
			} catch (IllegalArgumentException ignored) {
			}
		}
		throw new AssertionError("no constructor for " + className);
	}
}
