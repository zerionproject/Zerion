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
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
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
					boolean opensWindow, int rank, boolean bulkLane,
					Runnable onSent) {
				super.enqueueRecord(record, userOriginated, opensWindow, rank,
						bulkLane, onSent);
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

	@Test
	public void theOfferOfAMarkedMessageOpensTheWindowAndTheBodyDoesNot()
			throws Exception {
		Exchange x = new Exchange();
		Message chosen = x.message(10);
		x.log.noteUserMessage(chosen.getId());

		x.offer(chosen);
		assertEquals("the offer of a marked message opens the window",
				Collections.singletonList("offer opens"), x.queued);
		assertEquals("the offer wakes the paced sender", 1, x.wakes.get());
		x.sendAll();
		assertEquals(x.now[0], x.slots.lastRealMs);
		long opened = x.slots.lastRealMs;

		x.now[0] += 2_000;
		x.request(chosen);
		assertEquals("the body is marked but opens nothing",
				Arrays.asList("offer opens", "body user"), x.queued);
		assertEquals("the body does not wake the paced sender", 1,
				x.wakes.get());
		x.sendAll();
		assertEquals(opened, x.slots.lastRealMs);
	}

	@Test
	public void aRequestWithheldForHoursOpensNoWindow() throws Exception {
		Exchange x = new Exchange();
		List<Message> chosen = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			Message m = x.message(10);
			x.log.noteUserMessage(m.getId());
			chosen.add(m);
		}
		x.offer(chosen.toArray(new Message[0]));
		x.sendAll();
		long opened = x.slots.lastRealMs;
		assertEquals(x.now[0], opened);
		int wakes = x.wakes.get();

		for (Message m : chosen) {
			x.now[0] += 60 * 60_000L;
			x.request(m);
			x.sendAll();
			assertEquals("a late request opened the window", opened,
					x.slots.lastRealMs);
			boolean first = x.gate.admitReceipt();
			boolean second = x.gate.admitReceipt();
			assertFalse("a late request started a reply window",
					first && second);
		}
		assertEquals("no late body woke the paced sender", wakes,
				x.wakes.get());
		assertEquals(21, x.queued.size());
		assertEquals("offer opens", x.queued.get(0));
		assertFalse(x.queued.subList(1, 21).contains("body user opens"));
	}

	@Test
	public void aSecondOfferOfTheSameMessageDoesNotOpenTheWindowAgain()
			throws Exception {
		Exchange x = new Exchange();
		Message chosen = x.message(10);
		x.log.noteUserMessage(chosen.getId());
		x.offer(chosen);
		x.sendAll();
		long opened = x.slots.lastRealMs;

		x.now[0] += 5 * 60_000L;
		x.offer(chosen);
		x.sendAll();
		Exchange reconnected = x.reconnect();
		reconnected.now[0] += 5 * 60_000L;
		reconnected.offer(chosen);
		reconnected.sendAll();

		assertEquals(Arrays.asList("offer opens", "offer"), x.queued);
		assertEquals(Collections.singletonList("offer"), reconnected.queued);
		assertEquals(opened, x.slots.lastRealMs);
		assertEquals("a new connection is not opened by the offer",
				ZppConnectionRunnerImpl.NEVER, reconnected.slots.lastRealMs);
	}

	@Test
	public void anOfferOfSeveralMessagesOpensTheWindowIfOneIsMarked()
			throws Exception {
		Exchange x = new Exchange();
		Message automatic = x.message(10);
		Message chosen = x.message(10);
		x.log.noteUserMessage(chosen.getId());

		x.offer(automatic);
		x.offer(automatic, chosen);
		x.offer(automatic, chosen);

		assertEquals(Arrays.asList("offer", "offer opens", "offer"),
				x.queued);
	}

	@Test
	public void aBodyRequestedWithoutAnOfferStillOpensTheWindowOnce()
			throws Exception {
		Exchange x = new Exchange();
		Message chosen = x.message(10);
		x.log.noteUserMessage(chosen.getId());

		x.request(chosen);
		x.sendAll();
		x.now[0] += 1_000;
		x.offer(chosen);
		x.request(chosen);

		assertEquals(Arrays.asList("body user opens", "offer", "body"),
				x.queued);
	}

	@Test
	public void theLaterPartOfAMemoIsMarkedButItsOfferOpensNothing()
			throws Exception {
		Exchange x = new Exchange();
		Message part = x.message(10);
		x.log.noteUserMessage(part.getId());
		x.log.noteLaterPart(part.getId());

		x.offer(part);
		x.request(part);

		assertEquals(Arrays.asList("offer", "body user"), x.queued);
		assertEquals(0, x.wakes.get());
	}

	@Test
	public void aMarkThatExpiredOrWasPushedOutAnnouncesNothing() {
		long[] now = {NOW};
		LocalMessageLog log = new LocalMessageLog(() -> now[0]);
		MessageId old = new MessageId(getRandomId());
		log.noteUserMessage(old);
		now[0] += LocalMessageLog.FRESH_MESSAGE_MS + 1;
		assertFalse(log.takeFirstOffer(old));

		MessageId first = new MessageId(getRandomId());
		log.noteUserMessage(first);
		for (int i = 0; i < 1024; i++) {
			log.noteUserMessage(new MessageId(getRandomId()));
		}
		assertFalse(log.takeFirstOffer(first));

		MessageId sent = new MessageId(getRandomId());
		log.noteUserMessage(sent);
		assertTrue(log.takeFirstSend(sent));
		assertFalse("a body that left has nothing more to announce",
				log.takeFirstOffer(sent));

		MessageId twice = new MessageId(getRandomId());
		log.noteUserMessage(twice);
		assertTrue(log.takeFirstOffer(twice));
		assertFalse(log.takeFirstOffer(twice));
		assertTrue("the offer leaves the mark of the body in place",
				log.takeFirstSend(twice));
	}

	private static final class Exchange {

		private final long[] now;
		private final LocalMessageLog log;
		private final GroupId group = new GroupId(getRandomId());
		private final ContactId contact = new ContactId(1);
		private final List<String> queued =
				Collections.synchronizedList(new ArrayList<>());
		private final AtomicInteger wakes = new AtomicInteger();
		private final Queue<Offer> offers = new ArrayDeque<>();
		private final Queue<Collection<Message>> batches = new ArrayDeque<>();
		private final ZppSendScheduler scheduler;
		private final ZppOutgoingSource source;
		private final ZppConnectionRunnerImpl.SlotClock slots;
		private final ZppConnectionRunnerImpl.ReceiveActivityGate gate;
		private boolean offering = false;

		private Exchange() throws Exception {
			this(new long[] {NOW}, null);
		}

		private Exchange(long[] clock, LocalMessageLog shared)
				throws Exception {
			now = clock;
			log = shared != null ? shared : new LocalMessageLog(() -> now[0]);
			slots = new ZppConnectionRunnerImpl.SlotClock(() -> now[0],
					ZppConnectionRunnerImpl.NEVER);
			gate = new ZppConnectionRunnerImpl.ReceiveActivityGate(
					() -> now[0], ZppConnectionRunnerImpl.REPLY_WINDOW_MS,
					ZppConnectionRunnerImpl.RECEIVE_ACTIVATION_INTERVAL_MS);
			scheduler = new ZppSendScheduler(r -> {
			}, () -> true) {
				@Override
				public void enqueueRecord(byte[] record,
						boolean userOriginated, boolean opensWindow, int rank,
						boolean bulkLane, Runnable onSent) {
					super.enqueueRecord(record, userOriginated, opensWindow,
							rank, bulkLane, onSent);
					queued.add((offering ? "offer" : "body")
							+ (userOriginated ? " user" : "")
							+ (opensWindow ? " opens" : ""));
				}
			};
			scheduler.setWakeListener(wakes::incrementAndGet);
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
						if (name.equals("generateOffer")) return offers.poll();
						if (name.equals("generateRequestedBatch")) {
							return batches.poll();
						}
						if (name.equals("getNextSendTime")) {
							return Long.MAX_VALUE;
						}
						return null;
					});
			Clock wall = new Clock() {
				@Override
				public long currentTimeMillis() {
					return now[0];
				}

				@Override
				public void sleep(long milliseconds) {
				}
			};
			EventBus bus = (EventBus) Proxy.newProxyInstance(
					EventBus.class.getClassLoader(),
					new Class<?>[] {EventBus.class},
					(proxy, method, args) -> null);
			TaskScheduler tasks = (TaskScheduler) Proxy.newProxyInstance(
					TaskScheduler.class.getClassLoader(),
					new Class<?>[] {TaskScheduler.class},
					(proxy, method, args) -> null);
			source = new ZppOutgoingSource(db, Runnable::run, bus, tasks,
					wall, codec(), contact, 60_000, 1_600, scheduler, log);
		}

		private Exchange reconnect() throws Exception {
			return new Exchange(now, log);
		}

		private Message message(int length) {
			return new Message(new MessageId(getRandomId()), group,
					now[0] - 1_000, new byte[length]);
		}

		private void offer(Message... messages) {
			List<MessageId> ids = new ArrayList<>();
			for (Message m : messages) ids.add(m.getId());
			offers.add(new Offer(ids));
			offering = true;
			source.eventOccurred(new MessageSharedEvent(ids.get(0), group,
					Collections.singletonMap(contact, true)));
			offering = false;
		}

		private void request(Message... messages) {
			batches.add(Arrays.asList(messages));
			source.eventOccurred(new MessageRequestedEvent(contact));
		}

		private void sendAll() throws Exception {
			while (scheduler.getQueueDepth() > 0) {
				boolean real = scheduler.tick();
				ZppConnectionRunnerImpl.afterFrame(slots, gate, real,
						scheduler.lastRealFrameOpenedWindow(), now[0]);
			}
		}
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
