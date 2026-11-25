package org.zerionproject.sync;

import org.zerionproject.core.api.Cancellable;
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
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmFragmenter;
import org.zerionproject.message.ZmmRecord;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
	private static final long DAY = 24 * 60 * MINUTE;
	private static final int FRAME_BYTES = 1_600;
	private static final int CHUNK = 512 * 1024;

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
	public void aLargeAttachmentSentOnAnIdleLinkDrainsFastFromItsOfferToItsEnd()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + 5 * MINUTE);
		Message header = link.message(200);
		link.log.noteUserMessage(header.getId());
		List<Message> all = new ArrayList<>();
		all.add(header);
		for (int i = 0; i < 3; i++) {
			Message chunk = link.message(CHUNK);
			link.log.noteUserPart(chunk.getId());
			all.add(chunk);
		}
		Message[] sent = all.toArray(new Message[0]);

		long offeredAt = link.now[0];
		link.offer(sent);
		link.slot();
		assertEquals("offer", link.frames.get(0).kind);
		assertEquals("the offer opens the window", offeredAt,
				link.slots.lastRealMs);
		link.request(sent);
		while (link.busy()) {
			link.slot();
			if (link.count("user") == 400) link.peerAcks(all.get(1));
		}

		int last = link.frames.size() - 1;
		assertTrue("the transfer outlasts the window by far",
				link.frames.get(last).at - offeredAt > 4 * WINDOW_MS);
		assertTrue(link.count("user") > 3 * CHUNK / FRAME_BYTES);
		for (Frame f : link.frames.subList(0, last)) {
			assertEquals("the gap after the frame at +" + (f.at - offeredAt),
					ACTIVE_MS, f.gapAfter);
		}
		assertEquals("after the last frame the link is idle again", IDLE_MS,
				link.frames.get(last).gapAfter);
	}

	@Test
	public void aRequestWithheldPastTheWindowIsAnsweredAtTheIdleRate()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message chosen = link.message(CHUNK);
		link.log.noteUserMessage(chosen.getId());
		link.offer(chosen);
		link.slot();
		long opened = link.slots.lastRealMs;
		assertNotEquals(NEVER, opened);
		link.runUntil(opened + WINDOW_MS + 3 * MINUTE);

		link.request(chosen);
		while (link.busy()) link.slot();

		assertTrue("the body still goes as the user's",
				link.count("user") > CHUNK / FRAME_BYTES);
		for (Frame f : link.frames) assertEquals(IDLE_MS, f.gapAfter);
		assertEquals("the body opens no window", opened,
				link.slots.lastRealMs);
	}

	@Test
	public void olderRequestedMessagesAheadOfAUserPartKeepTheLinkIdle()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message part = link.message(300 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		link.runUntil(link.now[0] + 5 * MINUTE);
		List<Message> older = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			older.add(new Message(new MessageId(getRandomId()), link.group,
					NOW - 5 * DAY + i, new byte[300 * FRAME_BYTES]));
		}
		older.add(part);

		link.request(older.toArray(new Message[0]));
		int slots = 0;
		while (link.busy()) {
			link.slot();
			if (++slots % 13 == 0) {
				link.source.eventOccurred(
						new MessageRequestedEvent(link.contact));
			}
			if (slots == 1000) link.peerAcks(part);
		}

		assertTrue("the contact kept the link busy for over half an hour",
				link.now[0] - NOW > 40 * MINUTE);
		for (Frame f : link.frames) {
			assertEquals("the gap after the frame at +" + (f.at - NOW),
					IDLE_MS, f.gapAfter);
		}
		assertEquals(NEVER, link.slots.lastRealMs);
	}

	@Test
	public void anInterruptedTransferGoesOnIdleAndIsFastInALaterWindow()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message header = link.message(200);
		link.log.noteUserMessage(header.getId());
		Message first = link.message(CHUNK);
		Message second = link.message(CHUNK);
		link.log.noteUserPart(first.getId());
		link.log.noteUserPart(second.getId());
		link.offer(header, first, second);
		link.slot();
		link.request(header, first);
		while (link.busy()) link.slot();
		for (Frame f : link.frames.subList(0, link.frames.size() - 1)) {
			assertEquals(ACTIVE_MS, f.gapAfter);
		}
		assertEquals("the contact held back the rest", IDLE_MS,
				link.lastGap());

		link.runUntil(link.now[0] + MINUTE);
		link.request(second);
		for (int i = 0; i < 20; i++) link.slot();
		for (Frame f : link.frames) {
			assertEquals("user", f.kind);
			assertEquals("a part asked for later rides the idle frames",
					IDLE_MS, f.gapAfter);
		}

		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());
		link.offer(chosen);
		int windowAt = link.frames.size();
		link.slot();
		assertEquals("the user's new message opens a window", "offer",
				link.frames.get(windowAt).kind);
		while (link.busy()) link.slot();
		int last = link.frames.size() - 1;
		assertTrue("the rest outlasts the window",
				link.frames.get(last).at - link.frames.get(windowAt).at
						> WINDOW_MS);
		for (Frame f : link.frames.subList(windowAt, last)) {
			assertEquals("from the window to the end of the part", ACTIVE_MS,
					f.gapAfter);
		}
		assertEquals(IDLE_MS, link.frames.get(last).gapAfter);
	}

	@Test
	public void aPartAskedForAgainAfterItsFirstSendIsNotTheUsersAnyMore()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message part = link.message(5 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		link.request(part);
		while (link.busy()) link.slot();
		assertEquals(Collections.singletonList("user"), link.kinds());

		link.runUntil(link.now[0] + MINUTE);
		link.request(part);
		while (link.busy()) link.slot();

		assertEquals(Collections.singletonList("body"), link.kinds());
		for (Frame f : link.frames) assertEquals(IDLE_MS, f.gapAfter);
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
		while (link.busy()) link.slot();

		for (Frame f : link.frames) assertEquals(IDLE_MS, f.gapAfter);
		assertEquals(NEVER, link.slots.lastRealMs);
	}

	@Test
	public void aPartAlreadyAskedForWhenTheLinkOpensDoesNotWakeIt()
			throws Exception {
		Link link = new Link(true);
		Message part = link.message(10);
		link.log.noteUserPart(part.getId());
		link.requested.add(part);
		int wakes = link.wakes.get();
		link.queuedKinds.clear();

		link.reconnect();

		assertEquals(Collections.singletonList("user"), link.queuedKinds);
		assertEquals(wakes, link.wakes.get());
	}

	@Test
	public void onASparseLinkAPartRequestedBeforeTheConnectionWakesAsBefore()
			throws Exception {
		Link link = new Link(false);
		Message part = link.message(10);
		link.log.noteUserPart(part.getId());
		link.requested.add(part);
		int wakes = link.wakes.get();

		link.reconnect();

		assertEquals("one wake for the one record queued", wakes + 1,
				link.wakes.get());
	}

	@Test
	public void aMarkPendingForAnotherContactLeavesTheOfferLimitInPlace()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		link.log.noteUserMessage(new MessageId(getRandomId()));

		link.offer(link.message(10));
		link.offer(link.message(10));
		link.offer(link.message(10));

		assertEquals(1, link.scheduler.getSmallDepth());
	}

	@Test
	public void anOfferThatNeverLeftGivesItsMarkBack() throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());
		link.offer(chosen);
		assertEquals(1, link.scheduler.getQueueDepth());

		link.reconnect();
		link.offer(chosen);
		link.slot();

		assertEquals("offer", link.lastKind());
		assertEquals("the offer on the new link opens the window",
				link.frames.get(link.frames.size() - 1).at,
				link.slots.lastRealMs);
	}

	@Test
	public void anOfferThatLeftKeepsItsMarkUsed() throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());
		link.offer(chosen);
		link.slot();
		long opened = link.slots.lastRealMs;
		link.runUntil(opened + WINDOW_MS + MINUTE);

		link.reconnect();
		link.offer(chosen);
		link.slot();

		assertEquals("offer", link.lastKind());
		assertEquals(opened, link.slots.lastRealMs);
		assertEquals(IDLE_MS, link.lastGap());
	}

	@Test
	public void aFloodDuringTheUsersTransferAtMostDoublesItAndEndsWithIt()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + 15 * MINUTE);
		Message header = link.message(200);
		link.log.noteUserMessage(header.getId());
		List<Message> all = new ArrayList<>();
		all.add(header);
		for (int i = 0; i < 3; i++) {
			Message chunk = link.message(CHUNK);
			link.log.noteUserPart(chunk.getId());
			all.add(chunk);
		}
		Message[] sent = all.toArray(new Message[0]);
		long offeredAt = link.now[0];
		link.offer(sent);
		link.slot();
		link.request(sent);

		long floodEnd = offeredAt + 60 * MINUTE;
		while (link.now[0] < floodEnd) {
			link.peerSendsMessage();
			link.slot();
		}

		int userFrames = link.count("user");
		assertTrue("the transfer completed", userFrames > 3 * CHUNK
				/ FRAME_BYTES && !link.busyWithUsersData());
		long lastUser = 0;
		for (Frame f : link.frames) {
			if (f.kind.equals("user")) lastUser = f.at;
		}
		long alone = userFrames * ACTIVE_MS;
		assertTrue("stretched to " + (lastUser - offeredAt) + " ms",
				lastUser - offeredAt <= 2 * alone + 4 * ACTIVE_MS);
		int fastAfter = 0;
		for (Frame f : link.frames) {
			if (f.at <= lastUser || f.gapAfter != ACTIVE_MS) continue;
			fastAfter++;
			boolean ration = false;
			for (long opened : link.activations) {
				if (f.at >= opened && f.at - opened < WINDOW_MS) ration = true;
			}
			assertTrue("a fast frame at +" + (f.at - offeredAt)
					+ " after the user's data was gone", ration);
		}
		assertTrue("only the ration of one window per ten minutes",
				fastAfter * ACTIVE_MS <= 4 * WINDOW_MS);
		assertTrue("the flood went on long after the transfer",
				floodEnd - lastUser > 20 * MINUTE);
		assertTrue("acknowledgements still left",
				link.count("ack") > userFrames / 2);
	}

	@Test
	public void besideAnIdleUserTransferARepliesWaitsAtMostEightIdleSlots()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		link.open();
		link.runUntil(link.now[0] + 3 * MINUTE);
		for (int k = 0; k < 4; k++) {
			Message part = link.message(CHUNK);
			link.log.noteUserPart(part.getId());
			link.request(part);
		}
		long end = link.now[0] + 6 * MINUTE;
		long next = link.now[0];
		while (link.now[0] < end) {
			while (next <= link.now[0]) {
				link.peerSendsMessage(next);
				link.peerOffers(1, next);
				next += ACTIVE_MS;
			}
			link.slot();
			assertEquals(IDLE_MS, link.lastGap());
		}

		assertTrue(link.ackWaits.size() > 300);
		assertTrue(link.requestWaits.size() > 300);
		long worst = Math.max(Collections.max(link.ackWaits),
				Collections.max(link.requestWaits));
		assertTrue("a reply waited " + worst + " ms", worst <= 8 * IDLE_MS);
		assertTrue("the user's frames still go every other frame",
				link.count("user") >= link.frames.size() / 2 - 1);
	}

	@Test
	public void aMultiFrameOfferCutAfterItsFirstFrameOpensOneWindowInAll()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());
		Message[] offered = new Message[81];
		offered[0] = chosen;
		for (int i = 1; i < offered.length; i++) {
			offered[i] = link.message(10);
		}
		int windows = 0;
		for (int round = 0; round < 6; round++) {
			link.offer(offered);
			assertTrue(link.scheduler.getBulkDepth() > 1);
			long before = link.slots.lastRealMs;
			link.slot();
			if (link.slots.lastRealMs != before) windows++;
			assertTrue("the rest of the offer is cut off",
					link.scheduler.getBulkDepth() > 0);
			link.reconnect();
			link.runUntil(link.now[0] + 10 * MINUTE);
		}

		assertEquals(1, windows);
		assertFalse(link.log.isUnannounced(chosen.getId()));
	}

	@Test
	public void aPartCutOffByAReconnectGoesFastInAWindowForItsUnusedFrames()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message part = link.message(CHUNK);
		link.log.noteUserPart(part.getId());
		link.request(part);
		for (int i = 0; i < 50; i++) link.slot();
		assertEquals(50, link.count("user"));

		link.reconnect();
		link.request(part);
		for (int i = 0; i < 20; i++) link.slot();
		for (Frame f : link.frames.subList(50, 70)) {
			assertEquals("the resend is still the user's", "user", f.kind);
			assertEquals(IDLE_MS, f.gapAfter);
		}
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());
		link.offer(chosen);
		int windowAt = link.frames.size();
		link.slot();
		while (link.busy()) link.slot();
		int parts = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				OversizedRecordTest.codec().encodeMessage(part), 0,
				FRAME_BYTES).size();
		assertEquals("the user's frames add up to the part once", parts,
				link.count("user"));
		int lastUser = windowAt;
		for (int i = windowAt + 1; i < link.frames.size(); i++) {
			if (link.frames.get(i).kind.equals("user")) lastUser = i;
		}
		assertTrue(lastUser - windowAt > 200);
		for (Frame f : link.frames.subList(windowAt, lastUser)) {
			assertEquals("fast while the budget lasts", ACTIVE_MS,
					f.gapAfter);
		}
		for (Frame f : link.frames.subList(lastUser, link.frames.size())) {
			assertEquals(IDLE_MS, f.gapAfter);
		}
		assertEquals("the frames the first send used go at the cadence",
				50, link.count("body"));
		assertFalse(link.log.isUserTraffic(part.getId()));
	}

	@Test
	public void anAcknowledgedPartIsNotTheUsersAnyMore() throws Exception {
		Link link = new Link(true);
		Message part = link.message(10);
		link.log.noteUserPart(part.getId());

		link.peerAcks(part);

		assertFalse(link.log.isUserTraffic(part.getId()));
	}

	@Test
	public void aMarkClaimedWhileTheLinkStopsIsGivenBack() throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());
		link.stopDuringOffer = true;

		link.offer(chosen);

		assertTrue(link.log.isUnannounced(chosen.getId()));
	}

	@Test
	public void anOfferLeavingAfterTheLinkStoppedTakesItsMarkAgain()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message chosen = link.message(10);
		link.log.noteUserMessage(chosen.getId());
		link.offer(chosen);
		link.source.stop();
		assertTrue(link.log.isUnannounced(chosen.getId()));

		link.slot();

		assertEquals("offer", link.lastKind());
		assertFalse(link.log.isUnannounced(chosen.getId()));
	}

	@Test
	public void cuttingAPartBeforeItsLastFrameAtEveryOpeningUsesUpItsBudget()
			throws Exception {
		Link link = new Link(true);
		Message part = link.message(300 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		long end = NOW + 2 * 60 * MINUTE;
		long fastMs = 0;
		int windows = 0;
		long lastOpen = NOW;
		while (link.now[0] < end) {
			link.reconnect();
			int before = link.activations.size();
			link.open();
			if (link.activations.size() > before) {
				windows++;
				lastOpen = link.now[0];
			}
			link.request(part);
			int guard = 0;
			while (link.scheduler.getQueueDepth() > 1 && guard++ < 5000) {
				link.slot();
				if (link.lastGap() == ACTIVE_MS) fastMs += ACTIVE_MS;
			}
			link.requested.remove(part);
			link.now[0] = Math.max(link.now[0] + 30_000L,
					lastOpen + OPENING_WINDOW_INTERVAL_MS);
		}

		assertTrue(windows >= 10);
		assertTrue("fast for " + fastMs + " ms with " + windows + " windows",
				fastMs <= windows * WINDOW_MS + 2 * WINDOW_MS);
		assertFalse("the cut sends used up the part's budget",
				link.log.isUserTraffic(part.getId()));
	}

	@Test
	public void aControlFrameBesideOneReplyLeavesTheContinuationAlone()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message header = link.message(200);
		link.log.noteUserMessage(header.getId());
		List<Message> all = new ArrayList<>();
		all.add(header);
		for (int i = 0; i < 3; i++) {
			Message chunk = link.message(300 * FRAME_BYTES);
			link.log.noteUserPart(chunk.getId());
			all.add(chunk);
		}
		Message[] sent = all.toArray(new Message[0]);
		long offeredAt = link.now[0];
		link.offer(sent);
		link.slot();
		link.request(sent);
		boolean injected = false;
		while (link.busy()) {
			if (!injected && link.now[0] - offeredAt > 5 * MINUTE) {
				injected = true;
				link.scheduler.enqueueControl(ZmmRecord.encode(
						ZmmConstants.TYPE_ROOT_EVOLUTION, new byte[] {1}));
				link.ackDue();
			}
			link.slot();
		}

		assertTrue(injected);
		int last = link.frames.size() - 1;
		for (Frame f : link.frames.subList(0, last)) {
			assertEquals("the gap after the frame at +" + (f.at - offeredAt),
					ACTIVE_MS, f.gapAfter);
		}
	}

	@Test
	public void aStartedAutomaticRecordAndAFloodDoNotStallTheUsersTransfer()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message old = new Message(new MessageId(getRandomId()), link.group,
				NOW - DAY, new byte[100 * FRAME_BYTES]);
		link.request(old);
		link.slot();
		link.slot();
		Message header = link.message(200);
		link.log.noteUserMessage(header.getId());
		Message part = link.message(300 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		link.offer(header);
		link.request(header, part);

		long start = link.now[0];
		int slots = 0;
		while (link.now[0] - start < 30 * MINUTE) {
			link.ackDue();
			if (++slots % 13 == 0) {
				link.source.eventOccurred(
						new MessageRequestedEvent(link.contact));
			}
			link.slot();
		}

		assertFalse("the user's transfer completed",
				link.busyWithUsersData());
		assertEquals("and so did the started record", 0,
				link.scheduler.getBulkDepth());
	}

	@Test
	public void aMessageLargerThanTheBulkLaneLeavesWhenTheLaneIsEmpty()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message large = link.message(900_000);

		link.request(large);
		int guard = 0;
		while (link.busy() && guard++ < 2_000) link.slot();

		assertTrue(link.requested.isEmpty());
		assertEquals(0, link.scheduler.getQueueDepth());
		assertTrue(link.count("body") > 512);
	}

	@Test
	public void aRecordThatStartedSendingIsFinishedBeforeAnotherOvertakesIt()
			throws Exception {
		List<Long> sent = new ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(record -> {
			if (!ZmmRecord.isCover(record)) {
				sent.add(ByteUtils.readUint32(record, 4));
			}
		}, () -> true);
		scheduler.setRanked(true);
		queue(scheduler, 1, ZppSendScheduler.RANK_AUTOMATIC);
		queue(scheduler, 2, ZppSendScheduler.RANK_AUTOMATIC);
		scheduler.tick();
		scheduler.tick();
		queue(scheduler, 3, ZppSendScheduler.RANK_USER);
		while (scheduler.getQueueDepth() > 0) scheduler.tick();

		List<Long> expected = new ArrayList<>();
		for (long id : new long[] {1, 3, 2}) {
			for (int i = 0; i < 5; i++) expected.add(id);
		}
		assertEquals("the started record ends, then the user's goes first",
				expected, sent);
	}

	@Test
	public void aStartedRecordAheadOfTheUsersDataIsChargedToItsBudget()
			throws Exception {
		ZppSendScheduler scheduler = new ZppSendScheduler(record -> {
		}, () -> true);
		scheduler.setRanked(true);
		scheduler.setFast(true);
		AtomicInteger charged = new AtomicInteger();
		queue(scheduler, 1, ZppSendScheduler.RANK_AUTOMATIC, null);
		scheduler.tick();
		assertFalse(scheduler.lastFrameWasUsers());
		queue(scheduler, 2, ZppSendScheduler.RANK_USER,
				charged::incrementAndGet);
		assertEquals(5, scheduler.getUserDepth());

		for (int i = 0; i < 4; i++) {
			scheduler.tick();
			assertTrue("frame " + i + " of the started record",
					scheduler.lastFrameWasUsers());
			assertEquals(i + 1, charged.get());
			assertEquals(4 - i, scheduler.getUserDepth());
		}
		scheduler.tick();
		assertTrue("the one frame of the user's left", scheduler
				.lastFrameWasUsers());
		assertEquals(5, charged.get());
		for (int i = 0; i < 4; i++) {
			scheduler.tick();
			assertFalse("a frame whose budget went to the started record",
					scheduler.lastFrameWasUsers());
		}
		assertEquals(5, charged.get());
		queue(scheduler, 3, ZppSendScheduler.RANK_AUTOMATIC, null);
		scheduler.tick();
		assertFalse("with nothing of the user's queued it is not",
				scheduler.lastFrameWasUsers());
		scheduler.enqueueControl(ZmmRecord.encode(
				ZmmConstants.TYPE_ROOT_EVOLUTION, new byte[] {1}));
		scheduler.tick();
		assertFalse(scheduler.lastFrameWasUsers());
	}

	@Test
	public void atTheIdleRateAStartedRecordAheadOfTheUsersDataIsNotCharged()
			throws Exception {
		ZppSendScheduler scheduler = new ZppSendScheduler(record -> {
		}, () -> true);
		scheduler.setRanked(true);
		AtomicInteger charged = new AtomicInteger();
		queue(scheduler, 1, ZppSendScheduler.RANK_AUTOMATIC, null);
		scheduler.tick();
		queue(scheduler, 2, ZppSendScheduler.RANK_USER,
				charged::incrementAndGet);

		for (int i = 0; i < 4; i++) {
			scheduler.tick();
			assertFalse(scheduler.lastFrameWasUsers());
		}
		assertEquals("nothing charged at the idle rate", 0, charged.get());
		assertEquals(5, scheduler.getUserDepth());
		for (int i = 0; i < 5; i++) {
			scheduler.tick();
			assertTrue(scheduler.lastFrameWasUsers());
		}
		assertEquals(5, charged.get());
	}

	@Test
	public void anIdleLinkLeavesTheBudgetOfAPartBehindAnOldRecordAlone()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message part = link.message(10 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		Message old = new Message(new MessageId(getRandomId()), link.group,
				NOW - DAY, new byte[100 * FRAME_BYTES]);
		link.request(old);
		link.slot();
		link.request(part);
		int partFrames = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				OversizedRecordTest.codec().encodeMessage(part), 0,
				FRAME_BYTES).size();
		while (link.scheduler.getBulkDepth() > partFrames) link.slot();

		assertEquals("the old record charged nothing", partFrames,
				link.scheduler.getUserDepth());
		while (link.busy()) link.slot();
		for (Frame f : link.frames) assertEquals(IDLE_MS, f.gapAfter);
		assertEquals(0, link.log.userFramesLeft(part.getId(), partFrames));
		assertFalse(link.log.isUserTraffic(part.getId()));
	}

	@Test
	public void insideAWindowAStartedRecordAheadOfThePartIsCharged()
			throws Exception {
		Link link = new Link(true);
		link.runUntil(NOW + MINUTE);
		Message part = link.message(10 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		Message old = new Message(new MessageId(getRandomId()), link.group,
				NOW - DAY, new byte[100 * FRAME_BYTES]);
		link.open();
		link.request(old);
		link.slot();
		link.request(part);
		int partFrames = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				OversizedRecordTest.codec().encodeMessage(part), 0,
				FRAME_BYTES).size();
		while (link.scheduler.getBulkDepth() > partFrames) link.slot();

		assertEquals("the old record used the part's budget", 0,
				link.scheduler.getUserDepth());
		assertEquals(0, link.log.userFramesLeft(part.getId(), partFrames));
	}

	@Test
	public void anOldRecordAheadOfAnUntouchedPartAtEveryOpeningIsBounded()
			throws Exception {
		Link link = new Link(true);
		Message part = link.message(10 * FRAME_BYTES);
		link.log.noteUserPart(part.getId());
		Message old = new Message(new MessageId(getRandomId()), link.group,
				NOW - DAY, new byte[400 * FRAME_BYTES]);
		long end = NOW + 2 * 60 * MINUTE;
		long fastMs = 0;
		int windows = 0;
		long lastOpen = NOW;
		while (link.now[0] < end) {
			link.reconnect();
			int before = link.activations.size();
			link.open();
			if (link.activations.size() > before) {
				windows++;
				lastOpen = link.now[0];
			}
			link.request(old);
			link.slot();
			if (link.lastGap() == ACTIVE_MS) fastMs += ACTIVE_MS;
			link.request(part);
			int guard = 0;
			while (link.scheduler.getBulkDepth() > 0 && guard++ < 5000) {
				if (link.scheduler.getUserDepth() > 0 && link.scheduler
						.getBulkDepth() <= link.scheduler.getUserDepth()) {
					break;
				}
				link.slot();
				if (link.lastGap() == ACTIVE_MS) fastMs += ACTIVE_MS;
			}
			link.requested.remove(part);
			link.requested.remove(old);
			link.now[0] = Math.max(link.now[0] + 30_000L,
					lastOpen + OPENING_WINDOW_INTERVAL_MS);
		}

		assertTrue(windows >= 4);
		int partFrames = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				OversizedRecordTest.codec().encodeMessage(part), 0,
				FRAME_BYTES).size();
		assertTrue("fast for " + fastMs + " ms with " + windows + " windows",
				fastMs <= windows * WINDOW_MS + (partFrames + 2) * ACTIVE_MS);
		assertEquals("the old record used up the part's budget", 0,
				link.log.userFramesLeft(part.getId(), partFrames));
	}

	@Test
	public void twoConnectionsAtOnceShareOneBudget() throws Exception {
		Link first = new Link(true);
		Link second = new Link(true, first.log);
		Message part = first.message(5 * FRAME_BYTES);
		first.log.noteUserPart(part.getId());

		first.request(part);
		second.request(part);

		int frames = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				OversizedRecordTest.codec().encodeMessage(part), 0,
				FRAME_BYTES).size();
		assertEquals(frames, first.scheduler.getUserDepth());
		assertEquals("the budget was handed out once", 0,
				second.scheduler.getUserDepth());

		first.reconnect();
		second.reconnect();
		second.request(part);
		assertEquals("the stopped link gave its budget back", frames,
				second.scheduler.getUserDepth());
	}

	@Test
	public void aReservedBudgetIsGivenBackAndNeverHandedOutTwice() {
		LocalMessageLog log = new LocalMessageLog(() -> NOW);
		MessageId part = new MessageId(getRandomId());
		log.noteUserPart(part);
		assertEquals(10, log.reserveUserFrames(part, 10));
		assertEquals(0, log.reserveUserFrames(part, 10));
		log.noteUserFrameSent(part);
		log.noteUserFrameSent(part);
		log.releaseUserFrames(part, 8);
		assertEquals(8, log.userFramesLeft(part, 10));
		assertEquals(8, log.reserveUserFrames(part, 10));
		log.releaseUserFrames(part, 8);
		log.noteUserFrameSent(part, false);
		assertEquals("a frame that left after its link gave the budget back"
				+ " still counts", 7, log.userFramesLeft(part, 10));
	}

	private static void queue(ZppSendScheduler scheduler, long id, int rank) {
		queue(scheduler, id, rank, null);
	}

	private static void queue(ZppSendScheduler scheduler, long id, int rank,
			Runnable charge) {
		List<byte[]> frames = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				new byte[5 * 1_500], id, FRAME_BYTES);
		assertEquals(5, frames.size());
		for (byte[] frame : frames) {
			scheduler.enqueueRecord(frame, false, false, rank, true,
					charge == null ? null
							: ZppSendScheduler.charged(charge, null));
		}
	}

	@Test
	public void onlyAWindowStartsTheFastRateAndUserProgressCarriesItOn() {
		ZppConnectionRunnerImpl.DensePace pace =
				new ZppConnectionRunnerImpl.DensePace();
		assertFalse(pace.next(false, 0, 0, NEVER, NOW, WINDOW_MS));
		assertFalse("a user record alone starts nothing",
				pace.next(true, 3, 0, NEVER, NOW, WINDOW_MS));
		assertTrue("a control record keeps its treatment",
				pace.next(false, 0, 1, NEVER, NOW, WINDOW_MS));
		assertFalse(pace.next(false, 0, 0, NEVER, NOW, WINDOW_MS));

		assertTrue("inside a window",
				pace.next(false, 0, 0, NOW, NOW, WINDOW_MS));
		assertTrue(pace.next(true, 2, 0, NOW, NOW + WINDOW_MS - 1,
				WINDOW_MS));
		assertTrue("carried on while the user's records move",
				pace.next(true, 2, 0, NOW, NOW + WINDOW_MS, WINDOW_MS));
		assertTrue("one other frame in between is fine",
				pace.next(false, 2, 0, NOW, NOW + WINDOW_MS + 1, WINDOW_MS));
		assertTrue(pace.next(true, 1, 0, NOW, NOW + 3 * WINDOW_MS,
				WINDOW_MS));
		assertFalse("the first frame time without one queued ends it",
				pace.next(true, 0, 0, NOW, NOW + 3 * WINDOW_MS + 1,
						WINDOW_MS));
		assertFalse("and a user record queued later does not restart it",
				pace.next(true, 5, 0, NOW, NOW + 4 * WINDOW_MS, WINDOW_MS));

		long later = NOW + 5 * WINDOW_MS;
		assertTrue("only a new window does",
				pace.next(true, 5, 0, later, later, WINDOW_MS));
		assertTrue(pace.next(false, 5, 0, later, later + WINDOW_MS,
				WINDOW_MS));
		assertFalse("two frame times without the user's records moving end it",
				pace.next(false, 5, 0, later, later + WINDOW_MS + 1,
						WINDOW_MS));
	}

	@Test
	public void aMarkOfAPartLastsForItsFramesUntilAcknowledgedOrADayOld() {
		long[] now = {NOW};
		LocalMessageLog log = new LocalMessageLog(() -> now[0]);
		MessageId part = new MessageId(getRandomId());
		log.noteUserPart(part);
		assertFalse("a part opens no window", log.takeFirstOffer(part));
		assertFalse("and is no message of its own on a sparse link",
				log.takeFirstSend(part));
		assertTrue(log.isUserTraffic(part));
		assertEquals(3, log.userFramesLeft(part, 3));
		assertEquals("asking does not use it up", 3,
				log.userFramesLeft(part, 3));
		log.noteUserFrameSent(part);
		log.noteUserFrameSent(part);
		assertTrue(log.isUserTraffic(part));
		assertEquals(1, log.userFramesLeft(part, 3));
		log.noteUserFrameSent(part);
		assertFalse("a part whose frames all left is not the user's any more",
				log.isUserTraffic(part));
		assertEquals(0, log.userFramesLeft(part, 3));

		MessageId acked = new MessageId(getRandomId());
		log.noteUserPart(acked);
		log.noteAcked(Collections.singletonList(acked));
		assertFalse(log.isUserTraffic(acked));

		MessageId old = new MessageId(getRandomId());
		log.noteUserPart(old);
		now[0] += LocalMessageLog.FRESH_MESSAGE_MS + 1;
		assertFalse(log.isUserTraffic(old));

		MessageId message = new MessageId(getRandomId());
		log.noteUserMessage(message);
		MessageId first = new MessageId(getRandomId());
		log.noteUserPart(first);
		for (int i = 0; i < LocalMessageLog.MAX_TRAFFIC_ENTRIES; i++) {
			log.noteUserPart(new MessageId(getRandomId()));
		}
		assertFalse(log.isUserTraffic(first));
		assertTrue("the parts do not push out the mark of a message",
				log.takeFirstOffer(message));
		log.restoreOffers(Collections.singletonList(message));
		assertTrue("a mark given back can be taken again",
				log.takeFirstOffer(message));
		assertTrue(log.takeFirstSend(message));
		log.restoreOffers(Collections.singletonList(message));
		assertFalse("a message already sent is not given back",
				log.takeFirstOffer(message));
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

	private static final class Frame {
		private final long at;
		private final String kind;
		private long gapAfter;

		private Frame(long at, String kind) {
			this.at = at;
			this.kind = kind;
		}
	}

	private static final class Task {
		private final long due;
		private final Runnable run;

		private Task(long due, Runnable run) {
			this.due = due;
			this.run = run;
		}
	}

	private static final class Link {

		private final long[] now = {NOW};
		private final LocalMessageLog log;
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
		private final List<Message> requested = new ArrayList<>();
		private final Queue<Long> acksDue = new ArrayDeque<>();
		private final Queue<Long> requestsDue = new ArrayDeque<>();
		private final List<Task> tasks = new ArrayList<>();
		private final Map<byte[], String> labels = new IdentityHashMap<>();
		private final Map<byte[], List<Long>> carried =
				new IdentityHashMap<>();
		private final AtomicInteger wakes = new AtomicInteger();
		private final DatabaseComponent db;
		private final Clock wall;
		private final ZppConnectionRunnerImpl.SlotClock slots;
		private final ZppConnectionRunnerImpl.ReceiveActivityGate ration;
		private ZppConnectionRunnerImpl.DensePace pace;
		private final boolean dense;
		private ZppSendScheduler scheduler;
		private ZppOutgoingSource source;
		private long nextRecord = 0;
		private String label = "body";
		private boolean stopDuringOffer = false;
		private List<Long> carrying = Collections.emptyList();

		private Link(boolean dense) throws Exception {
			this(dense, null);
		}

		private Link(boolean dense, LocalMessageLog shared) throws Exception {
			this.dense = dense;
			log = shared != null ? shared : new LocalMessageLog(() -> now[0]);
			slots = new ZppConnectionRunnerImpl.SlotClock(() -> now[0],
					NEVER);
			ration = new ZppConnectionRunnerImpl.ReceiveActivityGate(
					() -> now[0], REPLY_WINDOW_MS,
					OPENING_WINDOW_INTERVAL_MS);
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
			connect();
		}

		private void connect() throws Exception {
			pace = new ZppConnectionRunnerImpl.DensePace();
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
			EventBus bus = (EventBus) Proxy.newProxyInstance(
					EventBus.class.getClassLoader(),
					new Class<?>[] {EventBus.class},
					(proxy, method, args) -> null);
			TaskScheduler timer = (TaskScheduler) Proxy.newProxyInstance(
					TaskScheduler.class.getClassLoader(),
					new Class<?>[] {TaskScheduler.class},
					(proxy, method, args) -> {
						if (!method.getName().equals("schedule")) return null;
						Runnable task = (Runnable) args[0];
						Executor executor = (Executor) args[1];
						long delay = ((TimeUnit) args[3])
								.toMillis((Long) args[2]);
						Task t = new Task(now[0] + delay,
								() -> executor.execute(task));
						tasks.add(t);
						return (Cancellable) () -> tasks.remove(t);
					});
			source = new ZppOutgoingSource(db, Runnable::run, bus, timer,
					wall, OversizedRecordTest.codec(), contact, 60_000,
					FRAME_BYTES, scheduler, log);
			source.start();
		}

		private void reconnect() throws Exception {
			source.stop();
			tasks.clear();
			connect();
		}

		private Object answer(String name, Object[] args) throws Throwable {
			if (name.equals("transaction")
					|| name.equals("transactionWithNullableResult")
					|| name.equals("transactionWithResult")) {
				Method call = null;
				for (Method m : args[1].getClass().getMethods()) {
					if (m.getName().equals("call")
							|| m.getName().equals("run")) {
						call = m;
					}
				}
				if (call == null) throw new AssertionError();
				call.setAccessible(true);
				try {
					return call.invoke(args[1], new Transaction(null, false));
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
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
				if (stopDuringOffer) source.stop();
				label = "offer";
				return offers.poll();
			}
			if (name.equals("generateRequestedBatch")) {
				label = "body";
				long capacity = (Long) args[2];
				List<Message> sorted = new ArrayList<>(requested);
				sorted.sort((a, b) -> Long.compare(a.getTimestamp(),
						b.getTimestamp()));
				List<Message> out = new ArrayList<>();
				for (Message m : sorted) {
					long length = 40 + m.getRawLength();
					if (capacity < length) continue;
					out.add(m);
					capacity -= length;
				}
				requested.removeAll(out);
				return out.isEmpty() ? null : out;
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

		private void ackDue() {
			acksDue.add(now[0]);
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

		private void peerAcks(Message m) throws Exception {
			ZmmSyncCodec codec = OversizedRecordTest.codec();
			ZmmDbRecordSink sink = new ZmmDbRecordSink(db, codec, log);
			byte[] ack = codec.encodeAck(
					new Ack(Collections.singletonList(m.getId())));
			for (byte[] frame : ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
					ack, nextRecord++, FRAME_BYTES)) {
				sink.deliver(contact.getInt(), 1L, ZmmRecord.getType(frame),
						ZmmRecord.getPayload(frame));
			}
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
			requested.addAll(Arrays.asList(messages));
			source.eventOccurred(new MessageRequestedEvent(contact));
		}

		private boolean busyWithUsersData() {
			return scheduler.getUserDepth() > 0 || !requested.isEmpty();
		}

		private boolean busy() {
			return scheduler.getQueueDepth() > 0 || !requested.isEmpty();
		}

		private void slot() throws IOException {
			boolean real = scheduler.tick();
			ZppConnectionRunnerImpl.afterFrame(slots, ration, real,
					scheduler.lastRealFrameOpenedWindow(), now[0]);
			boolean active = pace.next(scheduler.lastFrameWasUsers(),
					scheduler.lastFrameWasControl(),
					scheduler.getUserDepth(),
					scheduler.getControlDepth(), slots.lastRealMs, now[0],
					WINDOW_MS);
			scheduler.setFast(active);
			long gap = active ? ACTIVE_MS : IDLE_MS;
			frames.get(frames.size() - 1).gapAfter = gap;
			now[0] += gap;
			List<Task> due = new ArrayList<>();
			for (Task t : tasks) if (t.due <= now[0]) due.add(t);
			tasks.removeAll(due);
			for (Task t : due) t.run.run();
		}

		private void runUntil(long time) throws IOException {
			while (now[0] < time) slot();
			frames.clear();
			queuedKinds.clear();
		}

		private long lastGap() {
			return frames.get(frames.size() - 1).gapAfter;
		}

		private String lastKind() {
			return frames.get(frames.size() - 1).kind;
		}

		private int count(String kind) {
			int n = 0;
			for (Frame f : frames) if (f.kind.equals(kind)) n++;
			return n;
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
