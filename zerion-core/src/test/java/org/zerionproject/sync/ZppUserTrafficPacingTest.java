package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.sync.event.MessageToRequestEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.NEVER;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.OPENING_WINDOW_INTERVAL_MS;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.REPLY_WINDOW_MS;

public class ZppUserTrafficPacingTest {

	private static final long NOW = 1_700_000_000_000L;
	private static final long ACTIVE_MS = ZppPacingPolicy.ACTIVE_INTERVAL_MS;
	private static final long IDLE_MS = ZppPacingPolicy.IDLE_INTERVAL_MS;
	private static final long WINDOW_MS = ZppPacingPolicy.IDLE_AFTER_MS;
	private static final long MINUTE = 60_000L;
	private static final int FRAME_BYTES = 1_600;

	@Test
	public void aContactThatKeepsSendingGetsIdleFramesApartFromOneRation()
			throws Exception {
		Link link = new Link(true);
		link.open();
		long end = NOW + 25 * MINUTE;
		long nextMessage = NOW;
		while (link.now[0] < end) {
			while (nextMessage <= link.now[0]) {
				link.peerSendsMessage(nextMessage);
				link.peerOffers(1, nextMessage);
				nextMessage += ACTIVE_MS;
			}
			link.slot();
		}

		assertEquals("one activation per ten minutes, the opening included",
				Arrays.asList(NOW, NOW + OPENING_WINDOW_INTERVAL_MS,
						NOW + 2 * OPENING_WINDOW_INTERVAL_MS),
				link.activations);
		for (Frame f : link.frames) {
			boolean inWindow = false;
			for (long opened : link.activations) {
				if (f.at >= opened && f.at - opened < WINDOW_MS) {
					inWindow = true;
				}
			}
			assertEquals("the gap after the frame at +" + (f.at - NOW),
					inWindow ? ACTIVE_MS : IDLE_MS, f.gapAfter);
		}
		assertTrue("replies were sent in idle frames",
				link.count("ack") > 100 && link.count("request") > 100);
	}

	@Test
	public void noReplyWaitsLongerThanFourIdleSlotsUnderAFlood()
			throws Exception {
		Link link = new Link(true);
		link.open();
		link.runUntil(NOW + WINDOW_MS + IDLE_MS);
		long end = link.now[0] + 10 * MINUTE - WINDOW_MS - 2 * IDLE_MS;
		long nextMessage = link.now[0];
		while (link.now[0] < end) {
			while (nextMessage <= link.now[0]) {
				link.peerSendsMessage(nextMessage);
				link.peerOffers(1, nextMessage);
				nextMessage += ACTIVE_MS;
			}
			link.slot();
		}

		assertTrue(link.ackWaits.size() > 500);
		assertTrue(link.requestWaits.size() > 500);
		long worst = Math.max(Collections.max(link.ackWaits),
				Collections.max(link.requestWaits));
		assertTrue("a reply waited " + worst + " ms", worst <= 4 * IDLE_MS);
		assertTrue("no backlog builds up",
				link.scheduler.getQueueDepth() <= 2);
		for (int ids : link.replySizes) {
			assertTrue("a reply fits one frame", ids <= link.source
					.idsPerFrame());
		}
	}

	@Test
	public void aLoneIncomingMessageIsRequestedAndAcknowledgedInOneSlotEach()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);

		link.peerOffers(1);
		link.slot();
		assertEquals(Collections.singletonList(0L), link.requestWaitsInSlots());
		link.peerSendsMessage();
		link.slot();

		assertEquals(Arrays.asList("request", "ack"), link.kinds());
		assertEquals(Collections.singletonList(0L), link.ackWaitsInSlots());
	}

	@Test
	public void aLargeAttachmentTheUserSentDrainsAtTheFastRateToTheEnd()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message header = link.message(100);
		link.log.noteUserMessage(header.getId());
		List<Message> parts = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			Message chunk = link.message(16_000);
			link.log.noteUserPart(chunk.getId());
			parts.add(chunk);
		}
		List<Message> all = new ArrayList<>(parts);
		all.add(0, header);

		long sentAt = link.now[0];
		link.offer(all.toArray(new Message[0]));
		link.slot();
		assertEquals("the offer opens the window", sentAt,
				link.slots.lastRealMs);
		link.request(all.toArray(new Message[0]));
		int first = link.frames.size();
		while (link.scheduler.getQueueDepth() > 0) link.slot();

		long took = link.now[0] - sentAt;
		assertTrue("the transfer outlasts the window", took > 2 * WINDOW_MS);
		for (Frame f : link.frames.subList(first, link.frames.size() - 1)) {
			assertEquals("a frame of the transfer at +" + (f.at - sentAt),
					ACTIVE_MS, f.gapAfter);
		}
		assertEquals("after the last frame the link is idle again", IDLE_MS,
				link.frames.get(link.frames.size() - 1).gapAfter);
		assertEquals((link.frames.size() - first - 1) * ACTIVE_MS,
				link.frames.get(link.frames.size() - 1).at
						- link.frames.get(first).at);
	}

	@Test
	public void anAttachmentPartThatIsNotMarkedRidesTheIdleFrames()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message relayed = link.message(16_000);

		link.offer(relayed);
		link.slot();
		link.request(relayed);
		while (link.scheduler.getQueueDepth() > 0) link.slot();

		for (Frame f : link.frames) assertEquals(IDLE_MS, f.gapAfter);
		assertEquals(NEVER, link.slots.lastRealMs);
	}

	@Test
	public void aRequestedPartOfTheUsersKeepsTheLinkFastBehindAFullLane()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message relayed = link.message(512 * FRAME_BYTES - 2_000);
		link.request(relayed);
		assertTrue(link.scheduler.getBulkDepth() > 500);
		link.slot();
		assertEquals("automatic frames alone leave the link idle", IDLE_MS,
				link.lastGap());
		Message chunk = link.message(100 * FRAME_BYTES);
		link.log.noteUserPart(chunk.getId());

		link.log.noteRequested(link.contact,
				Collections.singletonList(chunk.getId()));
		link.batches.add(Collections.singletonList(chunk));
		link.sizes.add((long) chunk.getRawLength());
		link.source.eventOccurred(new MessageRequestedEvent(link.contact));
		assertFalse("the part does not fit yet", link.kinds().contains("user"));

		int slots = 0;
		while (!link.labelled("user")) {
			link.slot();
			assertEquals("waiting for room at slot " + slots, ACTIVE_MS,
					link.lastGap());
			if (++slots % 10 == 0) {
				link.source.eventOccurred(
						new MessageRequestedEvent(link.contact));
			}
			assertTrue(slots < 200);
		}
		assertFalse(link.log.hasWanted(link.contact));
		while (link.scheduler.getUrgentDepth() > 0) {
			link.slot();
		}
		assertTrue("the part of the user went ahead of the relayed frames",
				link.scheduler.getBulkDepth() > 300);
		link.slot();
		assertEquals(IDLE_MS, link.lastGap());
	}

	@Test
	public void aMessageTheUserSendsDuringAFloodOpensTheWindowAtOnce()
			throws Exception {
		Link link = new Link(true);
		link.open();
		link.runUntil(NOW + WINDOW_MS + MINUTE);
		for (int i = 0; i < 20; i++) {
			link.peerSendsMessage();
			link.peerOffers(1);
		}
		Message automatic = link.message(10);
		link.request(automatic, link.message(10), link.message(10));
		link.offer(link.message(10));
		assertEquals(NOW, link.slots.lastRealMs);
		int wakes = link.wakes.get();
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());

		link.offer(chosen);
		assertEquals("the offer wakes the paced sender", wakes + 1,
				link.wakes.get());
		int before = link.frames.size();
		link.slot();

		assertEquals("the offer leaves in the next frame", "offer",
				link.frames.get(before).kind);
		assertEquals(link.frames.get(before).at, link.slots.lastRealMs);
		assertEquals(ACTIVE_MS, link.lastGap());
		link.request(chosen);
		link.slot();
		assertEquals("the body of the user goes before the queued replies",
				"user", link.frames.get(before + 1).kind);
	}

	@Test
	public void aBurstOfOffersIsRequestedInRepliesOfOneFrameEach()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		int perFrame = link.source.idsPerFrame();
		assertTrue(perFrame > 20 && perFrame < 60);

		link.peerOffers(2 * perFrame + 5);
		for (int i = 0; i < 3; i++) link.slot();

		assertEquals(Arrays.asList(perFrame, perFrame, 5), link.replySizes);
		assertEquals(Arrays.asList("request", "request", "request"),
				link.kinds());
		assertEquals("a reply never takes the bulk lane", 3,
				link.frames.size());
	}

	@Test
	public void aPartRequestedBeforeTheConnectionWakesThePacedSenderOnce()
			throws Exception {
		Link link = new Link(true);
		Message part = link.message(10);
		link.log.noteUserPart(part.getId());
		link.batches.add(Collections.singletonList(part));
		link.sizes.add(0L);
		int wakes = link.wakes.get();

		link.restart();

		assertEquals(Collections.singletonList("user"), link.queuedKinds);
		assertEquals(wakes + 1, link.wakes.get());
		Message later = link.message(10);
		link.log.noteUserPart(later.getId());
		link.request(later);
		assertEquals("a part requested later waits for its slot", wakes + 1,
				link.wakes.get());
	}

	@Test
	public void onASparseLinkAPartRequestedBeforeTheConnectionWakesAsBefore()
			throws Exception {
		Link link = new Link(false);
		Message part = link.message(10);
		link.log.noteUserPart(part.getId());
		link.batches.add(Collections.singletonList(part));
		link.sizes.add(0L);
		int wakes = link.wakes.get();

		link.restart();

		assertEquals("one wake for the one record queued", wakes + 1,
				link.wakes.get());
	}

	@Test
	public void aSparseLinkKeepsItsOrderAndRepliesToEveryEvent()
			throws Exception {
		Link link = new Link(false);
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());

		link.peerSendsMessage();
		link.peerSendsMessage();
		link.peerOffers(1);
		link.peerOffers(1);
		link.request(link.message(10));
		link.offer(chosen);
		link.request(chosen);

		assertEquals(Arrays.asList("ack", "ack", "request", "request", "body",
				"offer", "user"), link.queuedKinds);
		assertEquals("a reply may carry as many ids as before",
				Arrays.asList(MAX_MESSAGE_IDS, MAX_MESSAGE_IDS,
						MAX_MESSAGE_IDS, MAX_MESSAGE_IDS), link.replyLimits);
		while (link.scheduler.getQueueDepth() > 0) link.slot();
		assertEquals(link.queuedKinds, link.kinds());
	}

	@Test
	public void aMarkOfAPartLastsADayAndThreeSendsOrUntilAcknowledged() {
		long[] now = {NOW};
		LocalMessageLog log = new LocalMessageLog(() -> now[0]);
		ContactId c = new ContactId(1);
		MessageId part = new MessageId(getRandomId());
		log.noteUserPart(part);
		assertFalse("a part opens no window", log.takeFirstOffer(part));
		assertFalse("and is no message of its own on a sparse link",
				log.takeFirstSend(part));
		for (int i = 0; i < LocalMessageLog.MAX_TRAFFIC_SENDS; i++) {
			assertTrue(log.takeUserTraffic(c, part));
		}
		assertFalse(log.takeUserTraffic(c, part));

		MessageId acked = new MessageId(getRandomId());
		log.noteUserPart(acked);
		log.noteRequested(c, Collections.singletonList(acked));
		assertTrue(log.hasWanted(c));
		log.noteAcked(Collections.singletonList(acked));
		assertFalse(log.hasWanted(c));
		assertFalse(log.takeUserTraffic(c, acked));

		MessageId old = new MessageId(getRandomId());
		log.noteUserPart(old);
		log.noteRequested(c, Collections.singletonList(old));
		now[0] += LocalMessageLog.FRESH_MESSAGE_MS + 1;
		assertFalse(log.hasWanted(c));
		assertFalse(log.isUserTraffic(old));

		MessageId unmarked = new MessageId(getRandomId());
		log.noteRequested(c, Collections.singletonList(unmarked));
		assertFalse("only a marked message is waited for", log.hasWanted(c));

		MessageId message = new MessageId(getRandomId());
		log.noteUserMessage(message);
		MessageId first = new MessageId(getRandomId());
		log.noteUserPart(first);
		log.noteRequested(c, Collections.singletonList(first));
		for (int i = 0; i < LocalMessageLog.MAX_TRAFFIC_ENTRIES; i++) {
			log.noteUserPart(new MessageId(getRandomId()));
		}
		assertFalse(log.isUserTraffic(first));
		assertFalse(log.hasWanted(c));
		assertTrue("the parts do not push out the mark of a message",
				log.takeFirstOffer(message));
		assertTrue(log.takeFirstSend(message));
	}

	@Test
	public void offersOfThePhonesOwnMessagesWaitForEachOtherOnADenseLink()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);

		link.offer(link.message(10));
		link.offer(link.message(10));
		link.offer(link.message(10));

		assertEquals("one offer of the phone's own waits at a time", 1,
				link.scheduler.getSmallDepth());
		for (int i = 0; i < 3; i++) {
			link.slot();
			assertEquals(IDLE_MS, link.lastGap());
		}
		assertEquals(3, link.count("offer"));
		assertEquals(3, link.frames.size());
		assertEquals(0, link.scheduler.getQueueDepth());
	}

	@Test
	public void aWaitForAPartThatIsNotSentEndsWhenTheLaneIsEmpty()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message part = link.message(10);
		link.log.noteUserPart(part.getId());
		link.log.noteRequested(link.contact,
				Collections.singletonList(part.getId()));
		assertTrue(link.log.hasWanted(link.contact));

		link.source.eventOccurred(new MessageRequestedEvent(link.contact));

		assertFalse("nothing is queued and the lane is empty",
				link.log.hasWanted(link.contact));
		link.slot();
		assertEquals(IDLE_MS, link.lastGap());
	}

	@Test
	public void aPartAskedForAgainWhileOnItsWayIsNotWaitedForAgain()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message part = link.message(5 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		link.log.noteRequested(link.contact,
				Collections.singletonList(part.getId()));
		link.request(part);
		assertFalse(link.log.hasWanted(link.contact));

		link.log.noteRequested(link.contact,
				Collections.singletonList(part.getId()));
		link.request(part);

		assertFalse("the part is still on its way",
				link.log.hasWanted(link.contact));
		while (link.scheduler.getQueueDepth() > 0) {
			link.slot();
			assertEquals(link.scheduler.getQueueDepth() > 0 ? ACTIVE_MS
					: IDLE_MS, link.lastGap());
		}
		link.slot();
		assertEquals(IDLE_MS, link.lastGap());
	}

	private static final class Frame {
		private final long at;
		private final String kind;
		private long gapAfter;

		private Frame(long at, String kind) {
			this.at = at;
			this.kind = kind;
		}
	}

	private static final class Link {

		private final long[] now = {NOW};
		private final LocalMessageLog log = new LocalMessageLog(() -> now[0]);
		private final ContactId contact = new ContactId(1);
		private final GroupId group = new GroupId(getRandomId());
		private final List<Frame> frames = new ArrayList<>();
		private final List<String> queuedKinds = new ArrayList<>();
		private final List<Long> activations = new ArrayList<>();
		private final List<Long> ackWaits = new ArrayList<>();
		private final List<Long> requestWaits = new ArrayList<>();
		private final List<Integer> replySizes = new ArrayList<>();
		private final List<Integer> replyLimits = new ArrayList<>();
		private final Queue<Offer> offers = new ArrayDeque<>();
		private final Queue<List<Message>> batches = new ArrayDeque<>();
		private final Queue<Long> sizes = new ArrayDeque<>();
		private final Queue<Long> acksDue = new ArrayDeque<>();
		private final Queue<Long> requestsDue = new ArrayDeque<>();
		private final Map<byte[], String> labels = new IdentityHashMap<>();
		private final Map<byte[], List<Long>> carried =
				new IdentityHashMap<>();
		private final AtomicInteger wakes = new AtomicInteger();
		private final ZppSendScheduler scheduler;
		private final DatabaseComponent db;
		private final Clock wall;
		private ZppOutgoingSource source;
		private final ZppConnectionRunnerImpl.SlotClock slots;
		private final ZppConnectionRunnerImpl.ReceiveActivityGate ration;
		private final boolean dense;
		private String label = "body";
		private List<Long> carrying = Collections.emptyList();

		private Link(boolean dense) throws Exception {
			this.dense = dense;
			slots = new ZppConnectionRunnerImpl.SlotClock(() -> now[0],
					NEVER);
			ration = new ZppConnectionRunnerImpl.ReceiveActivityGate(
					() -> now[0], REPLY_WINDOW_MS,
					OPENING_WINDOW_INTERVAL_MS);
			scheduler = new ZppSendScheduler(record -> {
				frames.add(new Frame(now[0], ZppRunnerLabels.kind(labels,
						record)));
				List<Long> due = carried.remove(record);
				if (due == null) return;
				List<Long> waits = "ack".equals(labels.get(record))
						? ackWaits : requestWaits;
				for (long since : due) waits.add(now[0] - since);
			}, () -> true) {
				@Override
				public void enqueueRecord(byte[] record,
						boolean userOriginated, boolean opensWindow, int rank,
						boolean bulkLane, Runnable onSent) {
					String kind = label.equals("body") && rank == RANK_USER
							? "user" : label;
					labels.put(record, kind);
					if (!carrying.isEmpty()) carried.put(record, carrying);
					carrying = Collections.emptyList();
					if (queuedKinds.isEmpty() || !bulkLane
							|| !queuedKinds.get(queuedKinds.size() - 1)
							.equals(kind)) {
						queuedKinds.add(kind);
					}
					super.enqueueRecord(record, userOriginated, opensWindow,
							rank, bulkLane, onSent);
				}
			};
			scheduler.setRanked(dense);
			scheduler.setWakeOnAnyEnqueue(!dense);
			scheduler.setWakeListener(wakes::incrementAndGet);
			db = (DatabaseComponent) Proxy.newProxyInstance(
					DatabaseComponent.class.getClassLoader(),
					new Class<?>[] {DatabaseComponent.class},
					(proxy, method, args) -> answer(method.getName(), args));
			wall = new Clock() {
				@Override
				public long currentTimeMillis() {
					return now[0];
				}

				@Override
				public void sleep(long milliseconds) {
				}
			};
			restart();
		}

		private void restart() throws Exception {
			EventBus bus = (EventBus) Proxy.newProxyInstance(
					EventBus.class.getClassLoader(),
					new Class<?>[] {EventBus.class},
					(proxy, method, args) -> null);
			TaskScheduler tasks = (TaskScheduler) Proxy.newProxyInstance(
					TaskScheduler.class.getClassLoader(),
					new Class<?>[] {TaskScheduler.class},
					(proxy, method, args) -> null);
			source = new ZppOutgoingSource(db, Runnable::run, bus, tasks,
					wall, OversizedRecordTest.codec(), contact, 60_000,
					FRAME_BYTES, scheduler, log);
			source.start();
		}

		private Object answer(String name, Object[] args) throws Exception {
			if (name.equals("transactionWithNullableResult")
					|| name.equals("transactionWithResult")) {
				java.lang.reflect.Method call = null;
				for (java.lang.reflect.Method m :
						args[1].getClass().getMethods()) {
					if (m.getName().equals("call")) call = m;
				}
				if (call == null) throw new AssertionError();
				call.setAccessible(true);
				return call.invoke(args[1], new Transaction(null, false));
			}
			if (name.equals("generateAck")) {
				List<MessageId> ids = take(acksDue, (Integer) args[2], "ack");
				return ids.isEmpty() ? null : new Ack(ids);
			}
			if (name.equals("generateRequest")) {
				List<MessageId> ids =
						take(requestsDue, (Integer) args[2], "request");
				return ids.isEmpty() ? null : new Request(ids);
			}
			if (name.equals("generateOffer")) {
				label = "offer";
				return offers.poll();
			}
			if (name.equals("generateRequestedBatch")) {
				label = "body";
				Long needed = sizes.peek();
				if (needed == null || needed > (Long) args[2]) return null;
				sizes.poll();
				return batches.poll();
			}
			if (name.equals("getNextSendTime")) return Long.MAX_VALUE;
			return null;
		}

		private List<MessageId> take(Queue<Long> due, int limit,
				String kind) {
			List<MessageId> ids = new ArrayList<>();
			List<Long> since = new ArrayList<>();
			while (!due.isEmpty() && ids.size() < limit) {
				since.add(due.poll());
				ids.add(new MessageId(getRandomId()));
			}
			if (ids.isEmpty()) return ids;
			label = kind;
			carrying = since;
			replySizes.add(ids.size());
			replyLimits.add(limit);
			return ids;
		}

		private void open() {
			if (ration.admitOpening(now[0])) {
				slots.lastRealMs = now[0];
				activations.add(now[0]);
			}
		}

		private void received() {
			if (!dense) return;
			if (ration.admitReceipt()) {
				slots.noteActivity();
				if (activations.isEmpty() || now[0] - activations.get(
						activations.size() - 1) >= WINDOW_MS) {
					activations.add(now[0]);
				}
			}
		}

		private void peerSendsMessage() {
			peerSendsMessage(now[0]);
		}

		private void peerSendsMessage(long arrived) {
			received();
			acksDue.add(arrived);
			source.eventOccurred(new MessageToAckEvent(contact));
		}

		private void peerOffers(int count) {
			peerOffers(count, now[0]);
		}

		private void peerOffers(int count, long arrived) {
			received();
			for (int i = 0; i < count; i++) requestsDue.add(arrived);
			source.eventOccurred(new MessageToRequestEvent(contact));
		}

		private Message message(int length) {
			return new Message(new MessageId(getRandomId()), group,
					now[0] - 1_000, new byte[length]);
		}

		private void offer(Message... messages) {
			List<MessageId> ids = new ArrayList<>();
			for (Message m : messages) ids.add(m.getId());
			offers.add(new Offer(ids));
			source.eventOccurred(new MessageSharedEvent(ids.get(0), group,
					Collections.singletonMap(contact, true)));
		}

		private void request(Message... messages) {
			long size = 0;
			for (Message m : messages) size += m.getRawLength() + 64;
			batches.add(Arrays.asList(messages));
			sizes.add(size);
			source.eventOccurred(new MessageRequestedEvent(contact));
		}

		private void slot() throws IOException {
			boolean real = scheduler.tick();
			ZppConnectionRunnerImpl.afterFrame(slots, ration, real,
					scheduler.lastRealFrameOpenedWindow(), now[0]);
			boolean active = ZppConnectionRunnerImpl.isActive(
					scheduler.getUrgentDepth(), slots.lastRealMs, now[0],
					WINDOW_MS);
			long gap = active ? ACTIVE_MS : IDLE_MS;
			frames.get(frames.size() - 1).gapAfter = gap;
			now[0] += gap;
		}

		private void runUntil(long time) throws IOException {
			while (now[0] < time) slot();
			frames.clear();
			queuedKinds.clear();
		}

		private long lastGap() {
			return frames.get(frames.size() - 1).gapAfter;
		}

		private int count(String kind) {
			int n = 0;
			for (Frame f : frames) if (f.kind.equals(kind)) n++;
			return n;
		}

		private boolean labelled(String kind) {
			return labels.containsValue(kind);
		}

		private List<String> kinds() {
			List<String> kinds = new ArrayList<>();
			for (Frame f : frames) {
				if (f.kind.equals("cover")) continue;
				if (kinds.isEmpty() || !kinds.get(kinds.size() - 1)
						.equals(f.kind) || f.kind.equals("ack")
						|| f.kind.equals("request")) {
					kinds.add(f.kind);
				}
			}
			return kinds;
		}

		private List<Long> requestWaitsInSlots() {
			return inSlots(requestWaits);
		}

		private List<Long> ackWaitsInSlots() {
			return inSlots(ackWaits);
		}

		private static List<Long> inSlots(List<Long> waits) {
			List<Long> slots = new ArrayList<>();
			for (long w : waits) slots.add(w / IDLE_MS);
			return slots;
		}
	}

	private static final class ZppRunnerLabels {

		private static String kind(Map<byte[], String> labels,
				byte[] record) {
			String kind = labels.get(record);
			return kind == null ? "cover" : kind;
		}
	}
}
