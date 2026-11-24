package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.EventListener;
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
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class ProvokedTrafficPacingTest {

	private static final long NOW = 1_700_000_000_000L;

	@Test
	public void aProvokedReplyDoesNotWakeTheSlotClock() {
		AtomicInteger wakes = new AtomicInteger();
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true);
		scheduler.setWakeListener(wakes::incrementAndGet);
		scheduler.enqueueRecord(new byte[] {0, 1, 2}, false);
		assertEquals(0, wakes.get());
		scheduler.enqueueRecord(new byte[] {0, 1, 2}, true);
		assertEquals(1, wakes.get());
	}

	@Test
	public void aResendOfAMessageThePeerRequestedAgainIsNotLocalActivity()
			throws Exception {
		GroupId g = new GroupId(getRandomId());
		Message fresh = new Message(new MessageId(getRandomId()), g,
				NOW - 1_000, new byte[10]);
		Message old = new Message(new MessageId(getRandomId()), g,
				NOW - 2 * 60 * 60_000L, new byte[10]);
		List<Boolean> flags = Collections.synchronizedList(new ArrayList<>());
		ZppSendScheduler scheduler = new ZppSendScheduler(r -> {
		}, () -> true) {
			@Override
			public void enqueueRecord(byte[] record, boolean userOriginated,
					boolean bulkLane, Runnable onSent) {
				super.enqueueRecord(record, userOriginated, bulkLane, onSent);
				flags.add(userOriginated);
			}
		};
		AtomicInteger batches = new AtomicInteger(0);
		DatabaseComponent db = (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if (name.equals("transactionWithNullableResult")
							|| name.equals("transactionWithResult")) {
						java.lang.reflect.Method call = null;
						for (java.lang.reflect.Method m :
								args[1].getClass().getMethods()) {
							if (m.getName().equals("call")) call = m;
						}
						if (call == null) throw new AssertionError();
						call.setAccessible(true);
						return call.invoke(args[1],
								new Transaction(null, false));
					}
					if (name.equals("generateRequestedBatch")) {
						int n = batches.incrementAndGet();
						if (n == 1) return Arrays.asList(fresh, old);
						if (n == 3) return Collections.singletonList(fresh);
						return null;
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
		LocalMessageLog localMessages = new LocalMessageLog(() -> NOW);
		localMessages.noteUserMessage(fresh.getId());
		localMessages.noteUserMessage(old.getId());
		ZppOutgoingSource source = new ZppOutgoingSource(db, Runnable::run,
				bus, tasks, clock, codec(), c, 60_000, 1_600, scheduler,
				localMessages);
		source.eventOccurred(new MessageRequestedEvent(c));
		while (scheduler.getQueueDepth() > 0) scheduler.tick();
		source.eventOccurred(new MessageRequestedEvent(c));
		assertEquals(Arrays.asList(true, true, false), flags);
	}

	@Test
	public void aRelayedThirdPartyMessageIsNotLocalActivity() {
		GroupId g = new GroupId(getRandomId());
		Message relayed = new Message(new MessageId(getRandomId()), g,
				NOW - 1_000, new byte[10]);
		Message own = new Message(new MessageId(getRandomId()), g,
				NOW - 1_000, new byte[10]);
		LocalMessageLog localMessages = new LocalMessageLog(() -> NOW);
		localMessages.noteUserMessage(own.getId());
		ZppOutgoingSource source = new ZppOutgoingSource(null, Runnable::run,
				null, null, null, null, new ContactId(1), 60_000, 1_600,
				new ZppSendScheduler(r -> {
				}, () -> true), localMessages);
		assertEquals(true, source.isFresh(own, NOW));
		assertEquals(false, source.isFresh(relayed, NOW));
	}

	@Test
	public void aLocalMessageNobodyMarkedAsTheUsersIsNotFresh() {
		GroupId g = new GroupId(getRandomId());
		Message automatic = new Message(new MessageId(getRandomId()), g,
				NOW - 1_000, new byte[10]);
		Message chosen = new Message(new MessageId(getRandomId()), g,
				NOW - 1_000, new byte[10]);
		LocalMessageLog localMessages = new LocalMessageLog(() -> NOW);
		localMessages.noteUserMessage(chosen.getId());
		ZppOutgoingSource source = new ZppOutgoingSource(null, Runnable::run,
				null, null, null, null, new ContactId(1), 60_000, 1_600,
				new ZppSendScheduler(r -> {
				}, () -> true), localMessages);
		assertFalse("an unmarked local message is not fresh",
				source.isFresh(automatic, NOW));
		assertTrue("a marked one is", source.isFresh(chosen, NOW));
	}

	@Test
	public void onlyTheFirstSendOfAMarkedMessageOpensTheWindow() {
		GroupId g = new GroupId(getRandomId());
		Message chosen = new Message(new MessageId(getRandomId()), g,
				NOW - 1_000, new byte[10]);
		Message automatic = new Message(new MessageId(getRandomId()), g,
				NOW - 1_000, new byte[10]);
		LocalMessageLog localMessages = new LocalMessageLog(() -> NOW);
		localMessages.noteUserMessage(chosen.getId());
		ZppOutgoingSource source = new ZppOutgoingSource(null, Runnable::run,
				null, null, null, null, new ContactId(1), 60_000, 1_600,
				new ZppSendScheduler(r -> {
				}, () -> true), localMessages);
		long[] now = {NOW};
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock(() -> now[0], 0L);
		ZppConnectionRunnerImpl.ReceiveActivityGate gate =
				new ZppConnectionRunnerImpl.ReceiveActivityGate(() -> now[0],
						10_000L, 60_000L);

		ZppConnectionRunnerImpl.afterFrame(clock, gate, true,
				source.isFresh(automatic, now[0]), now[0]);
		assertEquals("an unmarked message leaves the window closed", 0L,
				clock.lastRealMs);

		now[0] += 1_000;
		ZppConnectionRunnerImpl.afterFrame(clock, gate, true,
				source.isFresh(chosen, now[0]), now[0]);
		assertEquals("the first send of a marked message opens it", now[0],
				clock.lastRealMs);
		long opened = now[0];

		now[0] += 1_000;
		ZppConnectionRunnerImpl.afterFrame(clock, gate, true,
				source.isFresh(chosen, now[0]), now[0]);
		assertEquals("a resend of the same message does not extend it",
				opened, clock.lastRealMs);
	}

	@Test
	public void theRegistryOfUserMessagesListensToNoEvent() {
		assertFalse("only an explicit mark enters a message",
				EventListener.class.isAssignableFrom(LocalMessageLog.class));
		LocalMessageLog localMessages = new LocalMessageLog(() -> NOW);
		assertFalse(localMessages.isRecentLocal(new MessageId(getRandomId())));
	}

	private static ZmmSyncCodec codec() throws Exception {
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
